import unittest
import tempfile
from pathlib import Path
from datetime import datetime,timezone,timedelta


class MonitoringTests(unittest.IsolatedAsyncioTestCase):
    async def test_watch_detects_partial_fare_and_departure_changes(self):
        from sahara.storage import Repository
        from sahara.worker import check_watches
        with tempfile.TemporaryDirectory() as folder:
            repository=Repository(Path(folder)/'db')
            repository.save('watch','w','owner',{'id':'w','journey_id':'journey','active':True})
            class Service:
                calls=0
                async def revalidate(self,*args):
                    self.calls+=1
                    return {'original_valid':True,'providers':[],'alternatives':[{
                        'legs':[{'provider':'test','service_id':'s','fare_class':None,'departure':'2026-10-10T06:00:00Z' if self.calls<3 else '2026-10-10T07:00:00Z','arrival':'2026-10-10T10:00:00Z'}],
                        'total_fare_minor':None,'known_fare_minor':10000 if self.calls==1 else 20000,
                        'departure':'2026-10-10T06:00:00Z' if self.calls<3 else '2026-10-10T07:00:00Z',
                        'arrival':'2026-10-10T10:00:00Z','warnings':['fare_incomplete']}]}
            service=Service()
            service.repository=repository
            for _ in range(3):
                await check_watches(service)
                watch=repository.get('watch','w','owner')
                watch['next_check']=(datetime.now(timezone.utc)-timedelta(minutes=1)).isoformat()
                repository.save('watch','w','owner',watch)
            self.assertEqual(3,len(repository.watch_events('w','owner')))

    async def test_due_watch_claim_prevents_duplicate_worker_and_avoids_starvation(self):
        from sahara.storage import Repository
        from sahara.worker import check_watches
        with tempfile.TemporaryDirectory() as folder:
            repository=Repository(Path(folder)/'db')
            for i in range(205):
                repository.save('watch',str(i),'owner',{'id':str(i),'journey_id':'journey','active':True})
            now=datetime.now(timezone.utc)
            claim=repository.claim_due_watch(now)
            other=repository.claim_due_watch(now)
            self.assertNotEqual(claim['id'],other['id'])
            repository.finish_watch(claim,'owner',claim['lease_token'])
            repository.finish_watch(other,'owner',other['lease_token'])
            class Service:
                calls=0
                async def revalidate(self,*args):
                    self.calls+=1
                    return {'original_valid':True,'alternatives':[],'providers':[]}
            service=Service()
            service.repository=repository
            await check_watches(service)
            await check_watches(service)
            await check_watches(service)
            self.assertEqual(205,service.calls)
            with repository.connect() as db:
                self.assertEqual(205,db.execute("SELECT count(*) FROM records WHERE kind='watch_event'").fetchone()[0])

    async def test_watch_worker_records_only_meaningful_change(self):
        try:
            from sahara.worker import check_watches
        except ImportError:
            self.fail('Durable journey monitoring is not implemented')
        from sahara.settings import Settings
        from sahara.service import PlanningService
        from sahara.domain import SearchRequest
        with tempfile.TemporaryDirectory() as folder:
            service=PlanningService(Settings(database=Path(folder)/'db.sqlite3'))
            request=SearchRequest(origin='kty',destination='sbc',depart_after=datetime.now(timezone.utc)+timedelta(days=1))
            result=await service.search(request,'owner')
            journey=result['plan']['journeys'][0]
            service.repository.save('watch','w1','owner',{'id':'w1','journey_id':journey['id'],'active':True})
            await check_watches(service)
            first=service.repository.list_records('watch_event','owner')
            await check_watches(service)
            second=service.repository.list_records('watch_event','owner')
            self.assertEqual(1,len(first))
            self.assertEqual(1,len(second))


class CollectionDateTests(unittest.IsolatedAsyncioTestCase):
    async def test_rail_adapter_fetches_next_service_day_for_overnight_search(self):
        from sahara.providers.rail import RailProvider
        from sahara.domain import SearchRequest
        from sahara.places import TERMINALS
        html='''<div class="train-card"><div><span class="mr-5">12677</span>Express</div>
        <p>23:00 ERS</p><p>02:00 SBC</p><p>3h 0m</p><div class="cache-card-wrapper">
        <p>SL</p><p>₹450</p><p>Available 4</p><p>now</p></div></div>'''
        async def collector(*args): return html
        try:
            provider=RailProvider(collector=collector)
        except TypeError:
            self.fail('Rail collection cannot yet verify next-day services using an isolated collector')
        now=datetime(2026,10,9,tzinfo=timezone.utc)
        request=SearchRequest(origin='ers',destination='sbc',depart_after=datetime(2026,10,10,tzinfo=timezone.utc))
        result=await provider.search(request,[(TERMINALS['ers'],TERMINALS['sbc'])],now)
        self.assertEqual({10,11},set(l.departure.day for l in result.legs))
