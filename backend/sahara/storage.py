import json
import sqlite3
import uuid
from contextlib import contextmanager
from pathlib import Path
from datetime import datetime, timezone, timedelta


class Repository:
    def __init__(self,path):
        self.path=Path(path)
        self.path.parent.mkdir(parents=True,exist_ok=True)
        with self.connect() as db:
            db.execute('PRAGMA journal_mode=WAL')
            db.executescript('''CREATE TABLE IF NOT EXISTS records (
                kind TEXT NOT NULL,id TEXT NOT NULL,owner TEXT NOT NULL,payload TEXT NOT NULL,
                updated_at TEXT NOT NULL,PRIMARY KEY(kind,id,owner));
                CREATE INDEX IF NOT EXISTS owner_records ON records(owner,kind,updated_at);''')

    @contextmanager
    def connect(self):
        connection=sqlite3.connect(self.path,timeout=10)
        connection.row_factory=sqlite3.Row
        try:
            with connection:
                yield connection
        finally:
            connection.close()

    def save(self,kind,id,owner,payload):
        with self.connect() as db:
            db.execute('INSERT INTO records VALUES(?,?,?,?,?) ON CONFLICT(kind,id,owner) DO UPDATE SET payload=excluded.payload,updated_at=excluded.updated_at',
                       (kind,id,owner,json.dumps(payload),datetime.now(timezone.utc).isoformat()))

    def recover_pending_searches(self):
        # The central API has one process. In-memory enrichments cannot survive a crash.
        with self.connect() as db:
            rows=db.execute("SELECT id,owner,payload FROM records WHERE kind='search' AND json_extract(payload,'$.collection_complete')=0").fetchall()
            for row in rows:
                record=json.loads(row['payload'])
                pending=set(record.get('pending_providers',[]))
                record.update(collection_complete=True,pending_providers=[],
                    collection_error='Collection interrupted by server restart. Run a new search to refresh sources.')
                for provider in record.get('providers',[]):
                    if provider['provider'] in pending:
                        provider.update(status='partial' if provider.get('legs') else 'error',
                            message='Source collection interrupted by server restart')
                db.execute("UPDATE records SET payload=?,updated_at=? WHERE kind='search' AND id=? AND owner=?",
                    (json.dumps(record),datetime.now(timezone.utc).isoformat(),row['id'],row['owner']))

    def update_progress(self,id,owner,expected,progress):
        # Compare/write under the SQLite writer lock, including other server/worker processes.
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row=db.execute("SELECT payload FROM records WHERE kind='journey' AND id=? AND owner=?",(id,owner)).fetchone()
            if row is None: raise LookupError('Journey not found')
            current=json.loads(row['payload'])
            saved=current.get('progress',{'completed_leg_ids':[],'onboard_leg_id':None})
            if saved!=expected:
                raise ValueError('Travel status changed during this request. Refresh before confirming the next leg')
            current['progress']=progress
            db.execute("UPDATE records SET payload=?,updated_at=? WHERE kind='journey' AND id=? AND owner=?",
                (json.dumps(current),datetime.now(timezone.utc).isoformat(),id,owner))
            return current

    def save_tracking(self,id,owner,outcome):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            journey=db.execute("SELECT payload FROM records WHERE kind='journey' AND id=? AND owner=?",(id,owner)).fetchone()
            if journey is None: return False
            progress=json.loads(journey['payload']).get('progress',{'completed_leg_ids':[],'onboard_leg_id':None})
            if outcome.get('progress_snapshot')!=progress: return False
            old=db.execute("SELECT payload FROM records WHERE kind='tracking' AND id=? AND owner=?",(id,owner)).fetchone()
            if old:
                checked=json.loads(old['payload']).get('checked_at')
                if checked and datetime.fromisoformat(checked)>datetime.fromisoformat(outcome['checked_at']): return False
            db.execute('INSERT INTO records VALUES(?,?,?,?,?) ON CONFLICT(kind,id,owner) DO UPDATE SET payload=excluded.payload,updated_at=excluded.updated_at',
                ('tracking',id,owner,json.dumps(outcome),datetime.now(timezone.utc).isoformat()))
            return True

    def save_search_bundle(self,record,journeys,owner):
        stamp=datetime.now(timezone.utc).isoformat()
        with self.connect() as db:
            db.execute('INSERT INTO records VALUES(?,?,?,?,?) ON CONFLICT(kind,id,owner) DO UPDATE SET payload=excluded.payload,updated_at=excluded.updated_at',
                       ('search',record['id'],owner,json.dumps(record),stamp))
            db.executemany('INSERT OR IGNORE INTO records VALUES(?,?,?,?,?)',
                [('journey',j['journey']['id'],owner,json.dumps(j),stamp) for j in journeys])

    def get(self,kind,id,owner):
        with self.connect() as db:
            row=db.execute('SELECT payload FROM records WHERE kind=? AND id=? AND owner=?',(kind,id,owner)).fetchone()
            return json.loads(row['payload']) if row else None

    def list_records(self,kind,owner=None):
        with self.connect() as db:
            if owner is None:
                rows=db.execute('SELECT id,owner,payload FROM records WHERE kind=? ORDER BY updated_at DESC LIMIT 200',(kind,)).fetchall()
                return [dict(json.loads(row['payload']),_owner=row['owner']) for row in rows]
            rows=db.execute('SELECT payload FROM records WHERE kind=? AND owner=? ORDER BY updated_at DESC LIMIT 200',(kind,owner)).fetchall()
            return [json.loads(row['payload']) for row in rows]

    def close(self):
        pass  # connections are per-operation; no application-wide connection held

    def watch_events(self,id,owner):
        with self.connect() as db:
            rows=db.execute("SELECT payload FROM records WHERE kind='watch_event' AND owner=? AND json_extract(payload,'$.watch_id')=? ORDER BY updated_at DESC LIMIT 200",(owner,id)).fetchall()
            return [json.loads(row['payload']) for row in rows]

    def claim_due_watch(self,now):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            stamp=now.isoformat()
            row=db.execute('''SELECT id,owner,payload FROM records WHERE kind='watch'
                AND json_extract(payload,'$.active')=1
                AND (json_extract(payload,'$.next_check') IS NULL OR json_extract(payload,'$.next_check')<=?
                    OR EXISTS (SELECT 1 FROM records AS n WHERE n.kind='notice' AND n.owner=records.owner
                        AND json_extract(n.payload,'$.journey_id')=json_extract(records.payload,'$.journey_id')
                        AND json_extract(n.payload,'$.processing')='pending'
                        AND (json_extract(n.payload,'$.notice.expires_at') IS NULL OR julianday(json_extract(n.payload,'$.notice.expires_at'))>julianday(?))))
                AND (json_extract(payload,'$.lease_until') IS NULL OR json_extract(payload,'$.lease_until')<=?)
                AND (json_extract(payload,'$.notice_retry_after') IS NULL OR json_extract(payload,'$.notice_retry_after')<=?)
                ORDER BY updated_at ASC LIMIT 1''',(stamp,stamp,stamp,stamp)).fetchone()
            if not row:
                return None
            watch=json.loads(row['payload'])
            notices=db.execute("""SELECT id FROM records WHERE kind='notice' AND owner=?
                AND json_extract(payload,'$.journey_id')=? AND json_extract(payload,'$.processing')='pending'
                AND (json_extract(payload,'$.notice.expires_at') IS NULL OR julianday(json_extract(payload,'$.notice.expires_at'))>julianday(?))""",
                (row['owner'],watch['journey_id'],stamp)).fetchall()
            watch['_notice_ids']=[n['id'] for n in notices]
            watch['lease_token']=uuid.uuid4().hex
            watch['lease_until']=(now+timedelta(minutes=10)).isoformat()
            db.execute("UPDATE records SET payload=? WHERE kind='watch' AND id=? AND owner=?",
                       (json.dumps(watch),row['id'],row['owner']))
            return dict(watch,_owner=row['owner'])

    def finish_watch(self,watch,owner,token,event=None):
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            row=db.execute("SELECT payload FROM records WHERE kind='watch' AND id=? AND owner=?",(watch['id'],owner)).fetchone()
            if not row:
                return False
            current=json.loads(row['payload'])
            if current.get('lease_token')!=token:
                return False
            payload=dict(watch)
            payload.pop('_owner',None)
            notice_ids=payload.pop('_notice_ids',[])
            payload.pop('_notice_checked',None)
            progress_snapshot=payload.pop('_notice_progress_snapshot',None)
            notice_checked=watch.get('_notice_checked',False)
            payload.pop('lease_token',None)
            payload.pop('lease_until',None)
            # A concurrent stop request wins over the worker's earlier snapshot.
            if not current.get('active'):
                payload['active']=False
                event=None
            if notice_checked and progress_snapshot is not None:
                journey=db.execute("SELECT payload FROM records WHERE kind='journey' AND id=? AND owner=?",
                    (watch['journey_id'],owner)).fetchone()
                progress=json.loads(journey['payload']).get('progress',{'completed_leg_ids':[],'onboard_leg_id':None}) if journey else None
                if progress!=progress_snapshot:
                    payload['active']=bool(current.get('active'))
                    notice_checked=False
                    event=None
                    payload['last_signature']=current.get('last_signature')
                    payload['next_check']=(datetime.now(timezone.utc)+timedelta(seconds=30)).isoformat()
                    payload['notice_retry_after']=payload['next_check']
                    payload['message']='Travel progress changed during refresh; retrying with current progress.'
            stamp=datetime.now(timezone.utc).isoformat()
            db.execute("UPDATE records SET payload=?,updated_at=? WHERE kind='watch' AND id=? AND owner=?",
                       (json.dumps(payload),stamp,watch['id'],owner))
            if event is not None:
                db.execute('INSERT INTO records VALUES(?,?,?,?,?)',('watch_event',uuid.uuid4().hex,owner,json.dumps(event),stamp))
            if current.get('active') and notice_checked:
                for notice_id in notice_ids:
                    db.execute("""UPDATE records SET payload=json_set(payload,'$.processing','checked','$.verified_at',?),updated_at=?
                        WHERE kind='notice' AND id=? AND owner=? AND json_extract(payload,'$.processing')='pending'""",
                        (stamp,stamp,notice_id,owner))
            return True
