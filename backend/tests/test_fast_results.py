import asyncio
import unittest
import tempfile
import json
from pathlib import Path
from datetime import datetime,timezone,timedelta
from sahara.domain import Leg,Observation,ProviderResult,SearchRequest
from sahara.broker import Broker
from sahara.settings import Settings
from sahara.service import PlanningService


class QuickProvider:
    name='quick'
    calls=0
    async def search_progressive(self,request,pairs,now,progress):
        self.calls+=1
        leg=Leg(id='quick-leg',provider='quick',service_id='quick-service',name='Early option',mode='train',origin='kty',destination='sbc',
            departure=request.depart_after+timedelta(hours=1),arrival=request.depart_after+timedelta(hours=5),fare_minor=100000,
            availability='available',seats=3,observation=Observation(provider='quick',evidence='cached',fetched_at=now,expires_at=now+timedelta(minutes=5)))
        await asyncio.sleep(.01)
        progress(ProviderResult(provider=self.name,status='partial',legs=[leg],message='More inventory still loading'))
        await asyncio.sleep(.15)
        return ProviderResult(provider=self.name,status='ok',legs=[leg])
    async def search(self,*args): return await self.search_progressive(*args,lambda _:None)


class SlowProvider:
    name='slow'
    async def search(self,*args):
        await asyncio.sleep(.25)
        return ProviderResult(provider=self.name,status='empty')


class FastResultTests(unittest.IsolatedAsyncioTestCase):
    async def test_initial_and_enriched_search_flags_are_saved_atomically(self):
        with tempfile.TemporaryDirectory() as folder:
            service=PlanningService(Settings(database=Path(folder)/'db'))
            service.live=Broker([QuickProvider(),SlowProvider()])
            original=service.repository.save_search_bundle
            observed=[]
            def capture(record,snapshots,owner):
                observed.append(record['collection_complete'])
                if not record['collection_complete']:
                    self.assertTrue(record['pending_providers'])
                original(record,snapshots,owner)
            service.repository.save_search_bundle=capture
            now=datetime.now(timezone.utc)
            request=SearchRequest(origin='kty',destination='sbc',depart_after=now+timedelta(days=1),data_mode='live')
            await service.search(request,'owner')
            await asyncio.gather(*list(service.enrichments))
            self.assertFalse(observed[0])
            self.assertTrue(observed[-1])
            await service.aclose()

    async def test_invalidation_starts_new_inventory_even_while_old_fetch_is_running(self):
        now=datetime.now(timezone.utc)
        request=SearchRequest(origin='kty',destination='sbc',depart_after=now+timedelta(days=1),data_mode='live')
        provider=QuickProvider()
        broker=Broker([provider])
        _,old=await broker.collect_fast(request,[],now,.1)
        broker.invalidate()
        _,fresh=await broker.collect_fast(request,[],now,.1)
        self.assertIsNot(old,fresh)
        self.assertEqual(2,provider.calls)
        await asyncio.gather(old,fresh)
        self.assertEqual(1,len(broker.cache))
        broker.invalidate()
        self.assertFalse(broker.progress)
        self.assertFalse(broker.ready)
        self.assertFalse(broker.finished)

    async def test_first_inventory_returns_while_slow_provider_is_running(self):
        now=datetime.now(timezone.utc)
        request=SearchRequest(origin='kty',destination='sbc',depart_after=now+timedelta(days=1),data_mode='live')
        broker=Broker([QuickProvider(),SlowProvider()])
        try:
            results,pending=await broker.collect_fast(request,[],now,.1)
        except AttributeError:
            self.fail('Broker cannot return useful early inventory without waiting for slow sources')
        self.assertFalse(pending.done())
        self.assertTrue(results[0].legs)
        self.assertEqual('partial',results[1].status)
        await pending

    async def test_fast_replay_and_budget_refinement_share_the_same_collection(self):
        now=datetime.now(timezone.utc)
        request=SearchRequest(origin='kty',destination='sbc',depart_after=now+timedelta(days=1),data_mode='live')
        provider=QuickProvider()
        broker=Broker([provider,SlowProvider()])
        try:
            first,task=await broker.collect_fast(request,[],now,.1)
            second,other=await broker.collect_fast(request.model_copy(update={'preference':'cheapest','budget_minor':150000}),[],now,.1)
        except AttributeError:
            self.fail('Fast replay is not implemented')
        self.assertIs(task,other)
        self.assertEqual(1,provider.calls)
        self.assertTrue(second[0].legs)
        await task

    async def test_stored_search_enriches_without_overwriting_selected_snapshot(self):
        with tempfile.TemporaryDirectory() as folder:
            service=PlanningService(Settings(database=Path(folder)/'db'))
            service.live=Broker([QuickProvider(),SlowProvider()])
            now=datetime.now(timezone.utc)
            request=SearchRequest(origin='kty',destination='sbc',depart_after=now+timedelta(days=1),data_mode='live')
            result=await service.search(request,'owner')
            self.assertFalse(result.get('collection_complete',True))
            self.assertTrue(result['plan']['journeys'])
            selected_id=result['plan']['journeys'][0]['id']
            await asyncio.gather(*list(service.enrichments))
            final=service.repository.get('search',result['id'],'owner')
            self.assertTrue(final['collection_complete'])
            self.assertEqual([],final['pending_providers'])
            selected=service.repository.get('journey',selected_id,'owner')
            self.assertEqual('partial',selected['providers'][0]['status'])
            await service.aclose()


class ChatFastResultTests(unittest.IsolatedAsyncioTestCase):
    def test_chat_waits_for_first_inventory_instead_of_finalizing_empty_pending_search(self):
        from fastapi.testclient import TestClient
        from sahara.api import create_app
        class TinyFirstBudget(Broker):
            async def collect_fast(self,request,pairs,now,timeout=4):
                return await super().collect_fast(request,pairs,now,.001)
        with tempfile.TemporaryDirectory() as folder:
            app=create_app(Settings(database=Path(folder)/'db',api_key='test-key'))
            app.state.service.live=TinyFirstBudget([QuickProvider(),SlowProvider()])
            with TestClient(app) as client:
                response=client.post('/process-query',json={'query':'Kottayam to Bengaluru tomorrow','dataMode':'live'},
                    headers={'Authorization':'Bearer test-key'})
                frames=[json.loads(frame) for frame in response.text.strip().split('\n\n')]
                final=next(frame['data'] for frame in frames if frame['type']=='final_response')
                self.assertTrue(final['journeys'])
                self.assertTrue(final['success'])
                self.assertIn('collectionComplete',final)

    async def test_exact_replay_reuses_owner_search_and_keeps_other_owners_separate(self):
        with tempfile.TemporaryDirectory() as folder:
            service=PlanningService(Settings(database=Path(folder)/'db'))
            provider=QuickProvider()
            service.live=Broker([provider,SlowProvider()])
            now=datetime.now(timezone.utc)
            request=SearchRequest(origin='kty',destination='sbc',depart_after=now+timedelta(days=1),data_mode='live')
            first=await service.search(request,'alice')
            replay=await service.search(request,'alice')
            other=await service.search(request,'bob')
            self.assertEqual(first['id'],replay['id'])
            self.assertTrue(replay['replayed'])
            self.assertNotEqual(first['id'],other['id'])
            self.assertEqual(1,provider.calls)
            await service.aclose()
