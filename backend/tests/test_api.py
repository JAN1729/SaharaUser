import tempfile
import unittest
from pathlib import Path
from datetime import datetime, timezone, timedelta


class StorageTests(unittest.TestCase):
    def test_owner_bound_records_survive_reopen(self):
        try:
            from sahara.storage import Repository
        except ImportError:
            self.fail('Durable owner-bound storage is not implemented')
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'test.sqlite3'
            repository=Repository(path)
            repository.save('journey','j1','alice',{'answer':42})
            reopened=Repository(path)
            self.assertEqual({'answer':42},reopened.get('journey','j1','alice'))
            self.assertIsNone(reopened.get('journey','j1','bob'))


class APIJourneyTests(unittest.TestCase):
    def setUp(self):
        try:
            from sahara.api import create_app
            from sahara.settings import Settings
        except ImportError:
            self.fail('Runnable search and revalidation application is not implemented')
        from fastapi.testclient import TestClient
        self.folder=tempfile.TemporaryDirectory()
        self.client=TestClient(create_app(Settings(database=Path(self.folder.name)/'db.sqlite3',api_key='test-key')))
        self.request={'origin':'kty','destination':'sbc','depart_after':(datetime.now(timezone.utc)+timedelta(days=1)).isoformat(),'data_mode':'sample'}

    def tearDown(self):
        if hasattr(self,'folder'):
            self.client.close()
            self.folder.cleanup()

    def test_sample_search_exposes_provenance_and_alternatives(self):
        response=self.client.post('/v1/journeys/search',json=self.request)
        self.assertEqual(200,response.status_code)
        body=response.json()
        self.assertGreaterEqual(len(body['plan']['journeys']),2)
        self.assertEqual('sample',body['request']['data_mode'])
        self.assertTrue(all('sample_data' in j['warnings'] for j in body['plan']['journeys']))

    def test_live_search_requires_access_key_before_provider_calls(self):
        response=self.client.post('/v1/journeys/search',json={**self.request,'data_mode':'live'})
        self.assertEqual(401,response.status_code)

    def test_https_cookie_is_secure_and_invalid_data_mode_is_rejected(self):
        from fastapi.testclient import TestClient
        with TestClient(self.client.app,base_url='https://testserver') as secure:
            response=secure.post('/v1/journeys/search',json=self.request)
            self.assertIn('Secure',response.headers['set-cookie'])
        response=self.client.post('/process-query',json={'query':'Kottayam to Bengaluru tomorrow','dataMode':'unknown'})
        self.assertEqual(422,response.status_code)

    def test_watch_events_are_filtered_before_recent_record_limit(self):
        import hashlib,json
        repository=self.client.app.state.service.repository
        session='a'*32
        owner=hashlib.sha256(session.encode()).hexdigest()
        repository.save('watch','wanted',owner,{'id':'wanted','active':True})
        repository.save('watch_event','wanted-event',owner,{'watch_id':'wanted','message':'earlier change'})
        with repository.connect() as db:
            db.executemany('INSERT INTO records VALUES(?,?,?,?,?)',[
                ('watch_event',str(i),owner,json.dumps({'watch_id':'other','message':'other change'}),'2999-01-01T00:00:00+00:00') for i in range(201)])
        response=self.client.get('/v1/watches/wanted/events',headers={'X-Sahara-Session':session})
        self.assertEqual([{'watch_id':'wanted','message':'earlier change'}],response.json()['events'])

    def test_owner_session_cannot_read_another_journey(self):
        first=self.client.post('/v1/journeys/search',json=self.request,headers={'X-Sahara-Session':'a'*32}).json()
        response=self.client.get('/v1/searches/'+first['id'],headers={'X-Sahara-Session':'b'*32})
        self.assertEqual(404,response.status_code)

    def test_later_shared_service_search_preserves_original_journey_map(self):
        headers={'X-Sahara-Session':'a'*32}
        first=self.client.post('/v1/journeys/search',json=self.request,headers=headers).json()
        original=next(j for j in first['plan']['journeys'] if j['legs'][0]['mode']=='flight')
        second=self.client.post('/v1/journeys/search',json={**self.request,'origin':'ers'},headers=headers).json()
        same_service=next(j for j in second['plan']['journeys']
            if [l['id'] for l in j['legs']]==[l['id'] for l in original['legs']])
        self.assertNotEqual(original['id'],same_service['id'])
        original_map=self.client.get('/v1/journeys/'+original['id']+'/map',headers=headers)
        self.assertEqual(200,original_map.status_code)
        self.assertEqual('kty',original_map.json()['pins'][0]['id'])
        self.assertEqual('kty',self.client.app.state.service.repository.get('journey',original['id'],
            __import__('hashlib').sha256(('a'*32).encode()).hexdigest())['request']['origin'])

    def test_revalidation_records_disruption_and_new_plan(self):
        first=self.client.post('/v1/journeys/search',json=self.request).json()
        journey=next(j for j in first['plan']['journeys'] if len(j['legs'])>1)
        response=self.client.post('/v1/journeys/'+journey['id']+'/revalidate',json={'sample_delay_minutes':180})
        self.assertEqual(200,response.status_code)
        self.assertFalse(response.json()['original_valid'])
        self.assertTrue(response.json()['alternatives'])

    def test_sample_delay_injection_is_rejected_for_live_record(self):
        app=self.client.app
        repository=app.state.service.repository
        request={**self.request,'data_mode':'live'}
        import hashlib
        repository.save('journey','live-id',hashlib.sha256(('a'*32).encode()).hexdigest(),{'request':request,'journey':{},'providers':[]})
        response=self.client.post('/v1/journeys/live-id/revalidate',json={'sample_delay_minutes':15},headers={'X-Sahara-Session':'a'*32})
        self.assertEqual(400,response.status_code)

    def test_compatibility_stream_has_parseable_final_response(self):
        response=self.client.post('/process-query',json={'userId':'USR-TEMPUSER','query':'Kottayam to Bengaluru tomorrow'})
        self.assertEqual(200,response.status_code)
        import json
        events=[json.loads(s) for s in response.text.split('\n\n') if s.strip()]
        final=next(e['data'] for e in events if e['type']=='final_response')
        self.assertTrue(final['journeys'])
        self.assertIn('Sample',final['response'])
        self.assertEqual([],final['toolCalls'])

    def test_bus_chat_does_not_invoke_unrelated_visa_services(self):
        import types
        from unittest.mock import AsyncMock, patch
        engine=types.SimpleNamespace(process_sahara_chat_query=AsyncMock(return_value={}))
        adapter=types.SimpleNamespace(sahara_mcp_engine=engine)
        with patch.dict('sys.modules',{'visaguy_mcp.sahara_adapter':adapter}):
            response=self.client.post('/process-query',json={
                'query':'Kottayam to Bengaluru tomorrow by bus','dataMode':'sample'})
        self.assertEqual(200,response.status_code)
        self.assertIn('final_response',response.text)
        engine.process_sahara_chat_query.assert_not_awaited()

    def test_compatibility_missing_places_asks_for_origin_and_destination(self):
        response=self.client.post('/process-query',json={'query':'hello'})
        self.assertIn('origin',response.text.lower())

    def test_web_application_and_health_are_available(self):
        self.assertEqual(200,self.client.get('/').status_code)
        self.assertEqual('ok',self.client.get('/health').json()['status'])

    def test_unknown_place_is_validation_error(self):
        response=self.client.post('/v1/journeys/search',json={**self.request,'origin':'Atlantis'})
        self.assertEqual(422,response.status_code)


if __name__=='__main__':
    unittest.main()
