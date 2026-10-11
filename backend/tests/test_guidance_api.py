import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from fastapi.testclient import TestClient
from sahara.api import create_app
from sahara.settings import Settings


class GuidanceApiTests(unittest.TestCase):
    def test_map_uses_the_same_refreshed_snapshot_as_guidance(self):
        import copy,hashlib
        service=self.client.app.state.service
        owner=hashlib.sha256(self.headers['X-Sahara-Session'].encode()).hexdigest()
        refreshed=copy.deepcopy(self.journey)
        refreshed['warnings']=['refreshed_source_warning']
        service.repository.save('tracking',self.journey['id'],owner,{'original_valid':True,'current_journey':refreshed,'progress_snapshot':{'completed_leg_ids':[],'onboard_leg_id':None}})
        response=self.client.get('/v1/journeys/'+self.journey['id']+'/map',headers=self.headers)
        self.assertEqual(200,response.status_code)
        self.assertEqual(['refreshed_source_warning'],response.json()['warnings'])

    def test_revalidation_forwards_explicit_invalid_status_repair(self):
        import hashlib
        service=self.client.app.state.service
        owner=hashlib.sha256(self.headers['X-Sahara-Session'].encode()).hexdigest()
        record=service.repository.get('journey',self.journey['id'],owner)
        record['progress']={'completed_leg_ids':['bad'],'onboard_leg_id':None}
        service.repository.save('journey',self.journey['id'],owner,record)
        response=self.client.post('/v1/journeys/'+self.journey['id']+'/revalidate',headers=self.headers,json={'repair_progress':True})
        self.assertEqual(200,response.status_code,response.text)
        self.assertEqual([],service.repository.get('journey',self.journey['id'],owner)['progress']['completed_leg_ids'])

    def setUp(self):
        self.folder=tempfile.TemporaryDirectory()
        self.client=TestClient(create_app(Settings(database=Path(self.folder.name)/'test.db',api_key='test-key')))
        self.headers={'X-Sahara-Session':'a'*32}
        result=self.client.post('/v1/journeys/search',headers=self.headers,json={'origin':'kty','destination':'sbc',
            'depart_after':(datetime.now(timezone.utc)+timedelta(days=1)).isoformat(),'data_mode':'sample'}).json()
        self.journey=result['plan']['journeys'][0]

    def tearDown(self):
        self.client.close()
        self.folder.cleanup()

    def test_guidance_is_owner_bound_and_never_calls_driver_data_for_sample(self):
        response=self.client.get('/v1/journeys/'+self.journey['id']+'/guidance',headers=self.headers)
        self.assertEqual(200,response.status_code)
        self.assertEqual('sample',response.json()['data_mode'])
        self.assertEqual('approximate',response.json()['boarding']['precision'])
        self.assertEqual([],response.json()['vehicles'])
        other=self.client.get('/v1/journeys/'+self.journey['id']+'/guidance',headers={'X-Sahara-Session':'b'*32})
        self.assertEqual(404,other.status_code)

    def test_future_boarding_cannot_be_silently_confirmed(self):
        response=self.client.post('/v1/journeys/'+self.journey['id']+'/progress',headers=self.headers,
            json={'completed_leg_ids':[],'onboard_leg_id':self.journey['legs'][0]['id']})
        self.assertEqual(400,response.status_code)

    def test_live_fleet_endpoint_is_key_protected_without_driver_writes(self):
        response=self.client.get('/v1/fleet/nearby?terminal_id=kty',headers=self.headers)
        self.assertEqual(401,response.status_code)
        response=self.client.get('/v1/fleet/nearby?terminal_id=kty',headers=self.headers|{'Authorization':'Bearer test-key'})
        self.assertEqual(200,response.status_code)
        self.assertEqual('unconfigured',response.json()['status'])

    def test_explicit_repair_only_resets_invalid_saved_progress(self):
        service=self.client.app.state.service
        id=self.journey['id']
        # HTTP exposes the owner binding; use the stored record's owner without changing it.
        import hashlib
        owner=hashlib.sha256(self.headers['X-Sahara-Session'].encode()).hexdigest()
        record=service.repository.get('journey',id,owner)
        self.assertIsNotNone(record)
        record['progress']={'completed_leg_ids':['invalid-leg'],'onboard_leg_id':None}
        service.repository.save('journey',id,owner,record)
        endpoint='/v1/journeys/'+id+'/progress'
        response=self.client.post(endpoint,headers=self.headers,json={'repair_progress':True})
        self.assertEqual(200,response.status_code,response.text)
        self.assertEqual([],response.json()['progress']['completed_leg_ids'])
        response=self.client.post(endpoint,headers=self.headers,json={'repair_progress':True})
        self.assertEqual(400,response.status_code)
