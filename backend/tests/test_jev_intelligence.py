import asyncio
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
import httpx


NOW = datetime(2026, 10, 11, tzinfo=timezone.utc)


def answer(questions, selections=None):
    values = {}
    for key, question in questions.items():
        if question['type'] == 'noul':
            values[key] = {'type': 'noul', 'noul': (selections or {}).get(key, .99)}
        else:
            choice = (selections or {}).get(key, next(iter(question['criteria'])))
            options = list(question['criteria'])
            values[key] = {'type': 'choice', 'choice': choice, 'confidence': .98,
                'probabilities': {k: .99 if k == choice else .01 / (len(options)-1) for k in options}}
    return {'model': 'typesafe/jev-1.13-20260917', 'answers': values,
        'usage': {'input_tokens': 100, 'output_tokens': 10, 'cost': .0000042}}


def stop(id='external', name='Mavoor Road Junction', **updates):
    return dict(id=id, source='operator-a', kind='stop', name=name,
        lat=11.26, lng=75.78, corridor='mavoor-medical', direction='outbound',
        observed_at=NOW.isoformat(), expires_at=(NOW+timedelta(days=2)).isoformat(), **updates)


class JevClientTests(unittest.IsolatedAsyncioTestCase):
    async def test_model_family_pin_and_response_version_are_enforced(self):
        from sahara.jev_decisions import JevDecisions
        with self.assertRaises(ValueError): JevDecisions('dummy',model='typesafe/jev-latest')
        question={'a':{'type':'noul','instructions':'A?'}}
        body=answer(question)
        body['model']='typesafe/jev-2.0'
        engine=JevDecisions('dummy',transport=httpx.MockTransport(lambda _:httpx.Response(200,json=body)))
        self.assertEqual('unavailable',(await engine.evaluate({},question))['status'])
        await engine.aclose()

    async def test_openrouter_contract_cache_and_cleanup(self):
        from sahara.jev_decisions import JevDecisions
        calls=[]
        def handle(request):
            body=json.loads(request.content)
            calls.append(body)
            self.assertEqual('https://openrouter.ai/api/alpha/decisions', str(request.url))
            self.assertEqual('Bearer dummy', request.headers['Authorization'])
            self.assertEqual('typesafe/jev-1.13', body['model'])
            return httpx.Response(200, json=answer(body['questions']))
        engine=JevDecisions('dummy', transport=httpx.MockTransport(handle))
        questions={'alias': {'type':'noul','instructions':'Names are aliases?'}}
        first=await engine.evaluate({'name':'Mavoor'}, questions)
        self.assertEqual('ok', first['status'])
        first['answers']['alias']['noul']=0
        second=await engine.evaluate({'name':'Mavoor'}, questions)
        self.assertTrue(second['cached'])
        self.assertEqual(.99, second['answers']['alias']['noul'])
        self.assertEqual(1,len(calls))
        await engine.aclose()
        self.assertTrue(engine.client.is_closed)

    async def test_no_key_is_free_disabled_path(self):
        from sahara.jev_decisions import JevDecisions
        engine=JevDecisions(transport=httpx.MockTransport(lambda _: self.fail('network without key')))
        self.assertEqual('disabled',(await engine.evaluate({},{}))['status'])
        await engine.aclose()

    async def test_malformed_probabilities_and_unknown_choice_never_reach_policy(self):
        from sahara.jev_decisions import JevDecisions
        question={'kind': {'type':'choice','instructions':'Pick','criteria':{'same':'Same','different':'Different'}}}
        for invalid in [float('nan'),2,True]:
            def handle(request):
                body=answer(question)
                body['answers']['kind']['confidence']=invalid
                return httpx.Response(200,content=json.dumps(body))
            engine=JevDecisions('dummy',transport=httpx.MockTransport(handle))
            self.assertEqual('unavailable',(await engine.evaluate({},question))['status'])
            await engine.aclose()
        def handle(request):
            body=answer(question)
            body['answers']['kind']['choice']='invented'
            return httpx.Response(200,json=body)
        engine=JevDecisions('dummy',transport=httpx.MockTransport(handle))
        self.assertEqual('unavailable',(await engine.evaluate({},question))['status'])
        await engine.aclose()

    async def test_rate_limit_cooldown_no_busy_retry_or_secret_error(self):
        from sahara.jev_decisions import JevDecisions
        calls=[]
        def handle(request):
            calls.append(1)
            return httpx.Response(429,json={'error':{'message':'dummy private diagnostic'}})
        engine=JevDecisions('dummy',transport=httpx.MockTransport(handle))
        result=await engine.evaluate({}, {'a':{'type':'noul','instructions':'A?'}})
        self.assertEqual('unavailable',result['status'])
        self.assertEqual('http_status',result['failure_kind'])
        self.assertEqual(429,engine.diagnostics()['last_failure']['http_status'])
        self.assertNotIn('dummy',json.dumps(result))
        self.assertEqual('cooldown',(await engine.evaluate({}, {'a':{'type':'noul','instructions':'A?'}}))['status'])
        self.assertEqual(1,len(calls))
        await engine.aclose()

    async def test_timeout_includes_queue_and_cancellation_is_propagated(self):
        from sahara.jev_decisions import JevDecisions
        async def handle(request):
            await asyncio.sleep(1)
            return httpx.Response(200,json=answer(json.loads(request.content)['questions']))
        engine=JevDecisions('dummy',transport=httpx.MockTransport(handle),timeout=.02)
        result=await engine.evaluate({}, {'a':{'type':'noul','instructions':'A?'}})
        self.assertEqual('unavailable',result['status'])
        self.assertEqual('deadline',result['failure_kind'])
        self.assertEqual('deadline',engine.diagnostics()['last_failure']['category'])
        engine.cooldown_until=0
        task=asyncio.create_task(engine.evaluate({}, {'a':{'type':'noul','instructions':'A?'}}))
        await asyncio.sleep(0)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError): await task
        await engine.aclose()

    async def test_queue_deadline_and_concurrent_singleflight(self):
        from sahara.jev_decisions import JevDecisions
        calls=[]
        async def handle(request):
            calls.append(1)
            await asyncio.sleep(.01)
            return httpx.Response(200,json=answer(json.loads(request.content)['questions']))
        engine=JevDecisions('dummy',transport=httpx.MockTransport(handle),timeout=.1)
        question={'a':{'type':'noul','instructions':'A?'}}
        first,second=await asyncio.gather(engine.evaluate({},question),engine.evaluate({},question))
        self.assertEqual('ok',first['status'])
        self.assertEqual(first,second)
        self.assertEqual(1,len(calls))
        for _ in range(4): await engine.capacity.acquire()
        self.assertEqual('unavailable',(await engine.evaluate({'new':True},question))['status'])
        self.assertEqual(1,len(calls))
        for _ in range(4): engine.capacity.release()
        await engine.aclose()

    async def test_score_is_validated_and_bad_usage_is_unavailable(self):
        from sahara.jev_decisions import JevDecisions
        question={'rubric':{'type':'score','instructions':'Rate','criteria':['Low','High']}}
        body={'model':'typesafe/jev-1.13','answers':{'rubric':{'type':'score','score':.9,
            'confidence':.8,'probabilities':{'0':.1,'1':.9},'legend':{'0':'Low','1':'High'}}},'usage':{'cost':0,'input_tokens':3}}
        engine=JevDecisions('dummy',transport=httpx.MockTransport(lambda _: httpx.Response(200,json=body)))
        self.assertEqual(.9,(await engine.evaluate({},question))['answers']['rubric']['score'])
        await engine.aclose()
        body['usage']=[]
        engine=JevDecisions('dummy',transport=httpx.MockTransport(lambda _: httpx.Response(200,json=body)))
        self.assertEqual('unavailable',(await engine.evaluate({},question))['status'])
        await engine.aclose()


class ReconciliationTests(unittest.IsolatedAsyncioTestCase):
    async def reconcile(self,a,b,semantics=None,accept=False):
        from sahara.jev_decisions import JevDecisions
        from sahara.intelligence import TransportRecord, reconcile_records
        async def handle(request):
            body=json.loads(request.content)
            selected={k+'_0':v for k,v in (semantics or {}).items()}
            return httpx.Response(200,json=answer(body['questions'],selected))
        engine=JevDecisions('dummy',transport=httpx.MockTransport(handle))
        try:
            return await reconcile_records(TransportRecord(**a),[TransportRecord(**b)],engine,NOW,accept_semantic=accept)
        finally: await engine.aclose()

    async def test_normalized_alias_requires_independent_checks(self):
        results=await self.reconcile(stop(),stop('canonical','Mavoor Rd Jn'))
        self.assertEqual('verified', results[0]['status'])
        self.assertEqual('rules', results[0]['engine'])

    async def test_close_opposite_direction_and_far_names_cannot_merge(self):
        for change in [{'direction':'inbound'},{'lat':12.0},{'corridor':'another'}]:
            other=stop('canonical')
            other.update(change)
            result=await self.reconcile(stop(),other)
            self.assertEqual('different',result[0]['status'])
            self.assertEqual('rules',result[0]['engine'])

    async def test_missing_operational_identity_and_stale_observation_need_review(self):
        for change in [{'direction':None},{'expires_at':NOW.isoformat()},{'observed_at':(NOW+timedelta(hours=1)).isoformat()}]:
            other=stop('canonical','Mavoor Rd Jn')
            other['observed_at']=(NOW-timedelta(hours=1)).isoformat()
            other.update(change)
            self.assertEqual('review',(await self.reconcile(stop(),other))[0]['status'])

    async def test_conflicting_trip_identity_or_order_is_never_overridden(self):
        a=stop(identity_namespace='operator',identity_id='123',stop_sequence=['a','b'],kind_unused='x')
        a.pop('kind_unused')
        b=stop('canonical',identity_namespace='operator',identity_id='124',stop_sequence=['b','a'])
        self.assertEqual('different',(await self.reconcile(a,b))[0]['status'])

    async def test_semantic_match_is_review_by_default_and_optional_accept_is_gated(self):
        a=stop(name='Main Mavoor boarding point')
        b=stop('canonical')
        self.assertEqual('review',(await self.reconcile(a,b,{'relationship':'same','name_alias':.99}))[0]['status'])
        self.assertEqual('verified',(await self.reconcile(a,b,{'relationship':'same','name_alias':.99},accept=True))[0]['status'])
        self.assertEqual('review',(await self.reconcile(a,b,{'relationship':'same','name_alias':.5},accept=True))[0]['status'])

    async def test_multiple_verified_candidates_are_ambiguous_not_first_match_wins(self):
        from sahara.intelligence import TransportRecord, reconcile_records
        from sahara.jev_decisions import JevDecisions
        engine=JevDecisions()
        rows=await reconcile_records(TransportRecord(**stop()),[TransportRecord(**stop('a')),TransportRecord(**stop('b'))],engine,NOW)
        self.assertEqual(['review','review'],[r['status'] for r in rows])
        await engine.aclose()

    async def test_normalized_match_competing_with_unresolved_alias_is_review(self):
        from sahara.intelligence import TransportRecord, reconcile_records
        from sahara.jev_decisions import JevDecisions
        engine=JevDecisions()
        rows=await reconcile_records(TransportRecord(**stop()),[TransportRecord(**stop('a')),
            TransportRecord(**stop('b','Mavoor main boarding place'))],engine,NOW)
        self.assertEqual('review',rows[0]['status'])
        self.assertEqual('competing_unresolved_candidate',rows[0]['reason'])
        await engine.aclose()


class TriageTests(unittest.IsolatedAsyncioTestCase):
    async def test_watch_commit_rechecks_progress_atomically(self):
        from sahara.storage import Repository
        with tempfile.TemporaryDirectory() as folder:
            repo=Repository(Path(folder)/'db')
            old={'completed_leg_ids':[],'onboard_leg_id':None}
            repo.save('journey','j','alice',{'progress':old})
            repo.save('notice','n','alice',{'id':'n','journey_id':'j','action':'revalidate','processing':'pending'})
            repo.save('watch','w','alice',{'id':'w','journey_id':'j','active':True,'last_signature':'original'})
            claimed=repo.claim_due_watch(datetime.now(timezone.utc))
            claimed.update(_notice_checked=True,_notice_progress_snapshot=old,last_signature='stale')
            repo.save('journey','j','alice',{'progress':{'completed_leg_ids':['l'],'onboard_leg_id':None}})
            repo.finish_watch(claimed,'alice',claimed['lease_token'],{'watch_id':'w','original_valid':False})
            self.assertEqual([],repo.watch_events('w','alice'))
            self.assertEqual('pending',repo.get('notice','n','alice')['processing'])
            self.assertEqual('original',repo.get('watch','w','alice')['last_signature'])
            self.assertIsNone(repo.claim_due_watch(datetime.now(timezone.utc)))

    async def test_untrusted_notice_needs_review_without_network(self):
        from sahara.intelligence import triage_notice, Notice
        from sahara.jev_decisions import JevDecisions
        engine=JevDecisions('dummy',transport=httpx.MockTransport(lambda _:self.fail('Untrusted notice reached model')))
        notice=Notice(text='Bus diverted',source='unknown',source_url='https://example.com',
            observed_at=NOW,expires_at=NOW+timedelta(hours=1),terminal_ids=['a'])
        result=await triage_notice(notice,{'legs':[{'origin':'a','destination':'b','service_id':'s','name':'Bus'}]},engine,NOW)
        self.assertEqual(('review','untrusted_notice_source'),(result['action'],result['reason']))
        await engine.aclose()

    async def test_progress_change_rejects_stale_notice_event_and_acknowledgement(self):
        from sahara.storage import Repository
        from sahara.worker import check_watches
        with tempfile.TemporaryDirectory() as folder:
            repo=Repository(Path(folder)/'db')
            old={'completed_leg_ids':[],'onboard_leg_id':None}
            repo.save('journey','j','alice',{'progress':old})
            repo.save('notice','n','alice',{'id':'n','journey_id':'j','action':'revalidate','processing':'pending'})
            repo.save('watch','w','alice',{'id':'w','journey_id':'j','active':True})
            class Service:
                calls=0
                async def revalidate(self,*args):
                    self.calls+=1
                    repo.save('journey','j','alice',{'progress':{'completed_leg_ids':['l'],'onboard_leg_id':None}})
                    return {'original_valid':False,'alternatives':[],'providers':[],'progress_snapshot':old,'checked_at':datetime.now(timezone.utc).isoformat()}
            service=Service()
            service.repository=repo
            await check_watches(service)
            self.assertEqual(1,service.calls)
            self.assertEqual([],repo.watch_events('w','alice'))
            self.assertEqual('pending',repo.get('notice','n','alice')['processing'])
            self.assertIsNone(repo.get('tracking','j','alice'))

    async def test_notice_decision_never_changes_journey_or_claims_reliability(self):
        from sahara.intelligence import MobilityIntelligence, Notice
        from sahara.jev_decisions import JevDecisions
        from sahara.storage import Repository
        def handle(request):
            body=json.loads(request.content)
            self.assertNotIn('owner',body['state'])
            return httpx.Response(200,json=answer(body['questions'],{'kind':'diversion','ambiguous':.01,'relevant':.99}))
        engine=JevDecisions('dummy',transport=httpx.MockTransport(handle))
        with tempfile.TemporaryDirectory() as folder:
            repo=Repository(Path(folder)/'db')
            repo.save('journey','j','alice',{'journey':{'legs':[{'origin':'a','destination':'b','service_id':'s','name':'Mavoor bus'}]}})
            intelligence=MobilityIntelligence(repo,engine,trusted_sources=[('operator-a','example.com')])
            notice=Notice(text='Mavoor bus services diverted this evening.',source='operator-a',
                source_url='https://example.com/notice',observed_at=NOW,expires_at=NOW+timedelta(hours=1),terminal_ids=['a'])
            result=await intelligence.triage('j','alice',notice,NOW)
            self.assertEqual('revalidate', result['action'])
            self.assertIsNone(repo.get('notice',result['id'],'bob'))
            self.assertNotIn('original_valid',result)
            self.assertEqual('s',repo.get('journey','j','alice')['journey']['legs'][0]['service_id'])
            self.assertEqual('pending', repo.get('notice',result['id'],'alice')['processing'])
        await engine.aclose()

    async def test_expired_or_explicitly_unrelated_notice_never_calls_model(self):
        from sahara.intelligence import triage_notice, Notice
        from sahara.jev_decisions import JevDecisions
        engine=JevDecisions('dummy',transport=httpx.MockTransport(lambda _: self.fail('Unnecessary request')))
        journey={'legs':[{'origin':'a','destination':'b','service_id':'s','name':'Bus'}]}
        base=dict(text='Bus diverted.',source='operator',source_url='https://example.com',observed_at=NOW-timedelta(hours=1),expires_at=NOW+timedelta(hours=1))
        self.assertEqual('ignore',(await triage_notice(Notice(**base,terminal_ids=['z']),journey,engine,NOW))['action'])
        base['expires_at']=NOW
        self.assertEqual('review',(await triage_notice(Notice(**base,terminal_ids=['a']),journey,engine,NOW))['action'])
        await engine.aclose()

    async def test_worker_revalidates_notice_without_model_claim_becoming_alert(self):
        from sahara.intelligence import MobilityIntelligence
        from sahara.jev_decisions import JevDecisions
        from sahara.storage import Repository
        from sahara.worker import check_watches
        with tempfile.TemporaryDirectory() as folder:
            repo=Repository(Path(folder)/'db')
            repo.save('notice','n','alice',{'id':'n','journey_id':'j','action':'revalidate','processing':'pending'})
            repo.save('watch','w','alice',{'id':'w','journey_id':'j','active':True,'next_check':(datetime.now(timezone.utc)+timedelta(hours=1)).isoformat()})
            class Service:
                calls=0
                async def revalidate(self,*args):
                    self.calls+=1
                    return {'original_valid':True,'alternatives':[],'providers':[]}
            service=Service()
            service.repository=repo
            service.intelligence=MobilityIntelligence(repo,JevDecisions())
            await check_watches(service)
            self.assertEqual(1,service.calls)
            self.assertEqual('checked',repo.get('notice','n','alice')['processing'])
            self.assertTrue(repo.watch_events('w','alice')[0]['original_valid'])

    async def test_stopped_watch_wins_over_inflight_notice_verification(self):
        from sahara.storage import Repository
        from sahara.worker import check_watches
        with tempfile.TemporaryDirectory() as folder:
            repo=Repository(Path(folder)/'db')
            repo.save('notice','n','alice',{'id':'n','journey_id':'j','action':'revalidate','processing':'pending'})
            repo.save('watch','w','alice',{'id':'w','journey_id':'j','active':True})
            class Service:
                async def revalidate(self,*args):
                    current=repo.get('watch','w','alice')
                    current['active']=False
                    repo.save('watch','w','alice',current)
                    return {'original_valid':False,'alternatives':[],'providers':[]}
            service=Service()
            service.repository=repo
            await check_watches(service)
            self.assertEqual([],repo.watch_events('w','alice'))
            self.assertEqual('pending',repo.get('notice','n','alice')['processing'])

    async def test_expired_notice_does_not_force_future_watch(self):
        from sahara.storage import Repository
        with tempfile.TemporaryDirectory() as folder:
            repo=Repository(Path(folder)/'db')
            now=datetime.now(timezone.utc)
            repo.save('notice','n','alice',{'id':'n','journey_id':'j','action':'revalidate','processing':'pending',
                'notice':{'expires_at':(now-timedelta(minutes=1)).isoformat()}})
            repo.save('watch','w','alice',{'id':'w','journey_id':'j','active':True,'next_check':(now+timedelta(hours=1)).isoformat()})
            self.assertIsNone(repo.claim_due_watch(now))


class LocalBindingTests(unittest.IsolatedAsyncioTestCase):
    async def test_local_feed_bytes_and_leg_rows_are_bounded(self):
        from sahara.providers.local import LocalProvider
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'feed.json'
            path.write_bytes(b'x'*4_000_001)
            with self.assertRaises(ValueError): await LocalProvider(str(path)).search(None,[],NOW)
            path.write_text(json.dumps({'legs':[{}]*1001}),encoding='utf-8')
            with self.assertRaises(ValueError): await LocalProvider(str(path)).search(None,[],NOW)

    async def test_alias_timeout_does_not_hide_known_feed_inventory(self):
        from sahara.providers.local import LocalProvider
        from sahara.domain import SearchRequest
        from sahara.providers.sample import SampleProvider
        from sahara.broker import Broker
        from sahara.places import TERMINALS
        request=SearchRequest(origin='kty',destination='sbc',depart_after=NOW+timedelta(days=1))
        sample=await SampleProvider().search(request,[],NOW)
        known=sample.legs[0].model_dump(mode='json')
        unknown=dict(known,id='external-leg',origin='external')
        canonical=TERMINALS[known['origin']]
        external=stop(name='An ambiguous boarding place')
        reference=stop(known['origin'],canonical.name)
        for row in (external,reference): row.update(lat=canonical.lat,lng=canonical.lng)
        class SlowIntelligence:
            async def reconcile(self,*args):
                raise TimeoutError('Controlled alias source timeout after the known inventory callback')
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'feed.json'
            path.write_text(json.dumps({'legs':[known,unknown],'stop_records':[external],
                'canonical_stops':[reference]}),encoding='utf-8')
            broker=Broker([LocalProvider(str(path),SlowIntelligence())],timeout=2)
            result=(await broker.collect(request,[],NOW))[0]
            self.assertEqual('partial',result.status)
            self.assertEqual([known['id']],[leg.id for leg in result.legs])
            await broker.aclose()

    async def test_verified_stop_alias_normalizes_feed_endpoint_without_vehicle_link(self):
        from sahara.providers.local import LocalProvider
        from sahara.intelligence import MobilityIntelligence
        from sahara.jev_decisions import JevDecisions
        from sahara.storage import Repository
        from sahara.domain import SearchRequest
        from sahara.providers.sample import SampleProvider
        request=SearchRequest(origin='kty',destination='sbc',depart_after=NOW+timedelta(days=1))
        sample=await SampleProvider().search(request,[],NOW)
        leg=sample.legs[0].model_dump(mode='json')
        known=leg['origin']
        leg['origin']='external'
        leg['vehicle_id']=None
        with tempfile.TemporaryDirectory() as folder:
            repo=Repository(Path(folder)/'db')
            engine=JevDecisions()
            intelligence=MobilityIntelligence(repo,engine)
            path=Path(folder)/'feed.json'
            from sahara.places import TERMINALS
            canonical=TERMINALS[known]
            external=stop(name=canonical.name)
            reference=stop(known,canonical.name)
            for row in (external,reference): row.update(lat=canonical.lat,lng=canonical.lng)
            path.write_text(json.dumps({'legs':[leg],'stop_records':[external],
                'canonical_stops':[reference]}),encoding='utf-8')
            result=await LocalProvider(str(path),intelligence).search(request,[],NOW)
            self.assertEqual(known,result.legs[0].origin)
            self.assertIsNone(result.legs[0].vehicle_id)
            await engine.aclose()
