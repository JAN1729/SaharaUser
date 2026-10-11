import hashlib
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import AsyncMock
import httpx


class IntentTests(unittest.IsolatedAsyncioTestCase):
    async def test_tracking_guidance_accepts_strong_judgment_but_keeps_uncertain_fallback(self):
        from sahara.language import RequestInterpreter
        from sahara.jev_decisions import JevDecisions
        confidence=[.92]
        def handle(request):
            criteria=json.loads(request.content)['questions']['intent']['criteria']
            return httpx.Response(200,json={'model':'typesafe/jev-1.13','answers':{'intent':{
                'type':'choice','choice':'tracking','confidence':confidence[0],
                'probabilities':{key:.93 if key=='tracking' else .07 if key=='unclear' else 0 for key in criteria}}},
                'usage':{'input_tokens':1,'cost':0}})
        engine=JevDecisions('dummy',transport=httpx.MockTransport(handle))
        parser=RequestInterpreter('dummy',decisions=engine)
        parser._extract=AsyncMock(return_value='fallback')
        try:
            with self.assertRaisesRegex(ValueError,'Follow journey'):
                await parser.parse('Check whether our vehicle is approaching the station')
            parser._extract.assert_not_awaited()
            confidence[0]=.8
            self.assertEqual('fallback',await parser.parse('Check whether my vehicle is approaching the station'))
        finally:
            await engine.aclose()

    async def test_explicit_tracking_question_uses_selected_journey_guidance_without_ai(self):
        from sahara.language import RequestInterpreter
        from sahara.jev_decisions import JevDecisions
        decisions=JevDecisions('dummy',transport=httpx.MockTransport(lambda _:self.fail('Clear tracking intent should not consume a model')))
        parser=RequestInterpreter('dummy',decisions=decisions)
        with self.assertRaisesRegex(ValueError,'Follow journey'):
            await parser.parse('Where is my bus?')
        await decisions.aclose()

    async def test_tracking_paraphrase_does_not_fall_into_generic_extraction(self):
        from sahara.language import RequestInterpreter
        from sahara.jev_decisions import JevDecisions
        decisions=JevDecisions('dummy',transport=httpx.MockTransport(lambda _:self.fail('Tracking paraphrase should not consume a model')))
        parser=RequestInterpreter('dummy',decisions=decisions)
        with self.assertRaisesRegex(ValueError,'Follow journey'):
            await parser.parse('Could you locate my bus right now?')
        await decisions.aclose()

    async def test_strong_followup_judgment_cannot_hijack_explicit_new_route(self):
        from sahara.language import RequestInterpreter
        from sahara.jev_decisions import JevDecisions
        def handle(request):
            body=json.loads(request.content)
            criteria=body['questions']['intent']['criteria']
            return httpx.Response(200,json={'model':'typesafe/jev-1.13-20260917','answers':{'intent':{
                'type':'choice','choice':'tracking','confidence':1,
                'probabilities':{key:int(key=='tracking') for key in criteria}}},
                'usage':{'input_tokens':100,'cost':.000001}})
        decisions=JevDecisions('dummy',transport=httpx.MockTransport(handle))
        parser=RequestInterpreter('dummy',decisions=decisions)
        parser._extract=AsyncMock(return_value='route extraction')
        self.assertEqual('route extraction',await parser.parse('Find a bus from Kottayam to Bengaluru tomorrow'))
        parser._extract.assert_awaited_once()
        await decisions.aclose()

    async def test_intent_state_contains_no_arbitrary_identity_or_coordinates(self):
        from sahara.language import RequestInterpreter
        from sahara.jev_decisions import JevDecisions
        sent=[]
        def handle(request):
            body=json.loads(request.content)
            sent.append(body['state'])
            criteria=body['questions']['intent']['criteria']
            return httpx.Response(200,json={'model':'typesafe/jev-1.13-20260917','answers':{'intent':{
                'type':'choice','choice':'unclear','confidence':1,'probabilities':{k:int(k=='unclear') for k in criteria}}},
                'usage':{'input_tokens':100,'cost':.000001}})
        decisions=JevDecisions('dummy',transport=httpx.MockTransport(handle))
        parser=RequestInterpreter('dummy',decisions=decisions)
        parser._extract=AsyncMock(return_value='fallback')
        query='My name is Alice. Phone +91 98765 43210, email alice@example.com. GPS 11.261,75.781. Help me find my bus.'
        self.assertEqual('fallback',await parser.parse(query))
        raw=json.dumps(sent)
        for private in ('Alice','alice','98765','43210','example','11','261','75','781'):
            self.assertNotIn(private,raw)
        self.assertIn('bus',raw)
        await decisions.aclose()

    async def test_clear_route_and_constraint_requests_skip_both_models(self):
        from sahara.language import RequestInterpreter
        from sahara.jev_decisions import JevDecisions
        decisions=JevDecisions('dummy',transport=httpx.MockTransport(lambda _: self.fail('Unnecessary decision')))
        parser=RequestInterpreter('dummy',transport=httpx.MockTransport(lambda _: self.fail('Unnecessary extraction')),decisions=decisions)
        parsed=await parser.parse('Kottayam to Bengaluru tomorrow')
        updated=await parser.parse('budget 900',parsed.model_dump(mode='json'))
        self.assertEqual(90000,updated.budget_minor)
        self.assertEqual(parsed.origin,updated.origin)
        await decisions.aclose()

    async def test_uncertain_intent_retains_existing_extraction(self):
        from sahara.language import RequestInterpreter
        from sahara.jev_decisions import JevDecisions
        decisions=JevDecisions()
        parser=RequestInterpreter('dummy',decisions=decisions)
        parser._extract=AsyncMock(return_value='fallback')
        self.assertEqual('fallback',await parser.parse('We have luggage and want an easier journey'))
        parser._extract.assert_awaited_once()
        await decisions.aclose()

    async def test_unextractable_amount_is_not_guessed_by_classifier(self):
        from sahara.language import RequestInterpreter
        from sahara.jev_decisions import JevDecisions
        decisions=JevDecisions()
        parser=RequestInterpreter('dummy',decisions=decisions)
        parser._extract=AsyncMock(return_value='fallback')
        with self.assertRaisesRegex(ValueError,'absolute budget'):
            await parser.parse('I can spend ₹200 more',{'origin':'kty','destination':'sbc'})
        parser._extract.assert_not_awaited()
        await decisions.aclose()

    async def test_rich_route_constraints_are_not_discarded_by_fast_grammar(self):
        from sahara.language import RequestInterpreter
        from sahara.jev_decisions import JevDecisions
        decisions=JevDecisions()
        parser=RequestInterpreter('dummy',decisions=decisions)
        parser._extract=AsyncMock(return_value='fallback')
        self.assertEqual('fallback',await parser.parse('Kottayam to Bengaluru tomorrow with my grandmother and heavy luggage'))
        await decisions.aclose()


class IntelligenceAPITests(unittest.TestCase):
    def setUp(self):
        from sahara.api import create_app
        from sahara.settings import Settings
        from fastapi.testclient import TestClient
        self.folder=tempfile.TemporaryDirectory()
        self.client=TestClient(create_app(Settings(database=Path(self.folder.name)/'db',api_key='local-key',openrouter_key='')))
        self.client.__enter__()
        self.headers={'Authorization':'Bearer local-key','X-Sahara-Session':'a'*32}

    def tearDown(self):
        self.client.__exit__(None,None,None)
        self.folder.cleanup()

    def test_reconciliation_authorization_and_owner_scoped_audit(self):
        now=datetime.now(timezone.utc)
        record={'id':'a','source':'operator','kind':'stop','name':'Mavoor Road Junction',
            'lat':11.26,'lng':75.78,'corridor':'mavoor','direction':'outbound',
            'observed_at':now.isoformat(),'expires_at':(now+timedelta(days=1)).isoformat()}
        body={'record':record,'candidates':[dict(record,id='canonical',name='Mavoor Rd Jn')]}
        self.assertEqual(401,self.client.post('/v1/intelligence/reconcile',json=body).status_code)
        result=self.client.post('/v1/intelligence/reconcile',json=body,headers=self.headers)
        self.assertEqual(200,result.status_code)
        self.assertEqual('verified',result.json()['results'][0]['status'])
        self.assertEqual(1,len(self.client.get('/v1/intelligence/reconciliations',headers=self.headers).json()['records']))
        headers=dict(self.headers,**{'X-Sahara-Session':'b'*32})
        self.assertEqual([],self.client.get('/v1/intelligence/reconciliations',headers=headers).json()['records'])
        self.assertEqual(401,self.client.get('/v1/intelligence/health').status_code)

    def test_notice_cannot_access_another_owners_journey(self):
        owner=hashlib.sha256(('b'*32).encode()).hexdigest()
        self.client.app.state.service.repository.save('journey','j',owner,{'journey':{'legs':[]}})
        now=datetime.now(timezone.utc)
        notice={'text':'Bus diverted','source':'operator','source_url':'https://example.com',
            'observed_at':now.isoformat(),'expires_at':(now+timedelta(hours=1)).isoformat()}
        self.assertEqual(404,self.client.post('/v1/journeys/j/notices',headers=self.headers,json=notice).status_code)
