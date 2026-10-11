import json
import asyncio
from pathlib import Path
from ..domain import Leg, ProviderResult
from ..intelligence import TransportRecord
from ..places import TERMINALS
from ..fleet import _distance


class LocalProvider:
    name='local-feed'
    def __init__(self,path='',intelligence=None):
        self.path=path
        self.intelligence=intelligence

    async def search(self,request,pairs,now):
        return await self._search(request,pairs,now)

    async def search_progressive(self,request,pairs,now,update):
        return await self._search(request,pairs,now,update)

    async def _search(self,request,pairs,now,update=None):
        if not self.path:
            return ProviderResult(provider=self.name,status='disabled',message='No local timetable/vehicle feed configured; road transfers are estimates, not live city buses')
        # Configured server-owned normalized feed only; no client-supplied URL/path.
        def load():
            with Path(self.path).open('rb') as stream:
                raw=stream.read(4_000_001)
            if len(raw)>4_000_000: raise ValueError('Local feed exceeds four megabytes')
            payload=json.loads(raw.decode('utf-8'))
            if not isinstance(payload,dict) or not isinstance(payload.get('legs'),list) or len(payload['legs'])>1000:
                raise ValueError('Local feed must contain at most one thousand legs')
            return payload
        payload=await asyncio.to_thread(load)
        bindings={}
        unresolved=0
        needed={row[field] for row in payload['legs'] for field in ('origin','destination') if row[field] not in TERMINALS}
        if update and needed:
            known=[Leg.model_validate(row) for row in payload['legs'] if row['origin'] in TERMINALS and row['destination'] in TERMINALS]
            update(ProviderResult(provider=self.name,status='partial',legs=known,
                message='Known local inventory ready; external stop aliases still under review'))
        if self.intelligence and payload.get('stop_records'):
            if len(payload['stop_records'])>100 or len(payload.get('canonical_stops',[]))>100:
                raise ValueError('Local stop registry exceeds bounded size')
            records=[TransportRecord.model_validate(row) for row in payload['stop_records']]
            references=[TransportRecord.model_validate(row) for row in payload.get('canonical_stops',[])]
            # References must resolve to the existing map/planner catalogue and agree spatially.
            references=[r for r in references if r.kind=='stop' and r.id in TERMINALS and r.lat is not None
                and _distance(r.lat,r.lng,TERMINALS[r.id].lat,TERMINALS[r.id].lng)<=100]
            if len({r.id for r in records})!=len(records):
                raise ValueError('Feed stop identifiers must be unique')
            slots=asyncio.Semaphore(4)
            async def bind(record):
                if record.kind!='stop' or record.id not in needed: return record.id,None,0
                candidates=[r for r in references if record.lat is not None and _distance(record.lat,record.lng,r.lat,r.lng)<=40][:17]
                if not candidates or len(candidates)>16:
                    return record.id,None,1
                async with slots:
                    results=await self.intelligence.reconcile(record,candidates,'server-local-feed',now)
                matched=[r for r in results if r['status']=='verified']
                return record.id,matched[0]['candidate_id'] if len(matched)==1 else None,int(len(matched)!=1)
            for external,canonical,missing in await asyncio.gather(*(bind(r) for r in records)):
                if canonical: bindings[external]=canonical
                unresolved+=missing
        legs=[]
        for row in payload['legs']:
            row=dict(row)
            for field in ('origin','destination'):
                row[field]=bindings.get(row[field],row[field])
            if row['origin'] not in TERMINALS or row['destination'] not in TERMINALS:
                unresolved+=1
                continue
            # Name matching does not create vehicle_id, GPS, fare or timetable evidence.
            legs.append(Leg.model_validate(row))
        return ProviderResult(provider=self.name,status='partial' if unresolved else 'ok' if legs else 'empty',legs=legs,
            message='Configured local observations; source timestamps preserved'+
                (f'; {unresolved} unresolved stop/leg records retained for review and excluded from planning' if unresolved else ''))
