import json
import tempfile
import unittest
from pathlib import Path
from fastapi.testclient import TestClient
from sahara.api import create_app
from sahara.settings import Settings


class ContextResetTests(unittest.TestCase):
    def test_new_conversation_does_not_reuse_previous_journey(self):
        with tempfile.TemporaryDirectory() as folder:
            with TestClient(create_app(Settings(database=Path(folder) / 'db'))) as client:
                client.post('/process-query', json={'query': 'Kottayam to Bengaluru tomorrow'})
                result = client.post('/process-query', json={'query': 'hello', 'resetContext': True})
                final = [json.loads(frame) for frame in result.text.split('\n\n') if frame.strip()][-1]['data']
                self.assertEqual('NEEDS_DETAILS', final['intent'])
                self.assertEqual([], final['journeys'])

    def test_mobile_search_polling_preserves_owner_isolation_and_contract(self):
        with tempfile.TemporaryDirectory() as folder:
            with TestClient(create_app(Settings(database=Path(folder) / 'db'))) as client:
                result = client.post('/process-query', json={'query': 'Kottayam to Bengaluru tomorrow'},
                                     headers={'X-Sahara-Session': 'a' * 32})
                final = [json.loads(frame) for frame in result.text.split('\n\n') if frame.strip()][-1]['data']
                path = '/v1/searches/' + final['searchId'] + '/response'
                poll = client.get(path, headers={'X-Sahara-Session': 'a' * 32})
                self.assertEqual(final['journeys'], poll.json()['journeys'])
                self.assertEqual([], poll.json()['toolCalls'])
                self.assertEqual(404, client.get(path, headers={'X-Sahara-Session': 'b' * 32}).status_code)
                journey=next(j for j in final['journeys'] if any(c['kind']=='egress' for c in j['connections']))
                mapped=client.get('/v1/journeys/'+journey['id']+'/map',headers={'X-Sahara-Session':'a'*32}).json()
                self.assertEqual('sample',mapped['data_mode'])
                self.assertFalse(mapped['geometry_complete'])
                self.assertIn('egress',[s['kind'] for s in mapped['segments']])
                self.assertIn('sbc',[p['id'] for p in mapped['pins']])
