import unittest
from sahara.geometry import GeometryRouter


def graph():
    return {'source_type':'road_graph','source_url':'https://www.openstreetmap.org/copyright','revision':'fixture',
        'nodes':{'n1':{'lat':11,'lng':75},'n2':{'lat':11.001,'lng':75},'n3':{'lat':11.001,'lng':75.001}},
        'terminal_nodes':{'bus-a':'n1','bus-b':'n3'},'terminal_snap_m':{'bus-a':10,'bus-b':20},
        'edges':[{'origin':'n1','destination':'n2','length_m':111,'mode':'road','bidirectional':False,
            'polyline':[{'lat':11,'lng':75},{'lat':11.0005,'lng':75},{'lat':11.001,'lng':75}]},
            {'origin':'n2','destination':'n3','length_m':109,'mode':'road','bidirectional':False,
            'polyline':[{'lat':11.001,'lng':75},{'lat':11.001,'lng':75.0005},{'lat':11.001,'lng':75.001}]}]}


class BusMapTests(unittest.TestCase):
    def test_osm_builder_preserves_curves_and_one_way_bus_restrictions(self):
        import hashlib
        import json
        import tempfile
        from pathlib import Path
        try:
            import osmium
        except ImportError:
            self.skipTest('Optional maps dependency not installed')
        from scripts.build_osm_graph import build
        raw='''<osm version="0.6" generator="test-fixture">
          <node id="1" lat="11" lon="75"/><node id="2" lat="11.001" lon="75"/>
          <node id="3" lat="11.001" lon="75.001"/><node id="4" lat="11.002" lon="75.002"/>
          <way id="10"><nd ref="1"/><nd ref="2"/><nd ref="3"/>
            <tag k="highway" v="primary"/><tag k="oneway" v="yes"/></way>
          <way id="11"><nd ref="3"/><nd ref="4"/><tag k="highway" v="primary"/>
            <tag k="bus" v="no"/></way>
        </osm>'''
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'fixture.osm'
            path.write_text(raw,encoding='utf-8')
            path.with_suffix('.osm.source.json').write_text(json.dumps({
                'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'fixture':True}),encoding='utf-8')
            payload,_=build([path])
        router=GeometryRouter(payload)
        self.assertEqual(1,len(payload['edges']))
        self.assertEqual(3,len(router.route('1','3','bus')['polyline']))
        self.assertEqual([],router.route('3','1','bus')['polyline'])
        self.assertNotIn('4',payload['nodes'])

    def test_road_corridor_is_renderable_but_not_a_verified_bus_service_shape(self):
        router=GeometryRouter(graph())
        mapped=router.bus_guidance('bus-a','bus-b','ksrtc:unknown')
        self.assertEqual('road_guidance',mapped['status'])
        self.assertEqual(5,len(mapped['polyline']))
        self.assertFalse(mapped['service_path_verified'])
        self.assertEqual('calculated_road_corridor',mapped['evidence'])
        self.assertEqual(10,mapped['origin_snap_m'])
        self.assertEqual([],router.route('bus-a','bus-b','bus',required_service='ksrtc:unknown')['polyline'])

    def test_direction_restrictions_and_missing_terminal_are_preserved(self):
        router=GeometryRouter(graph())
        self.assertEqual([],router.bus_guidance('bus-b','bus-a','ksrtc:unknown')['polyline'])
        self.assertEqual([],router.bus_guidance('missing','bus-b','ksrtc:unknown')['polyline'])

    def test_operator_specific_geometry_takes_precedence_over_road_guidance(self):
        payload=graph()
        for edge in payload['edges']: edge['service_routes']=['ksrtc:trip']
        router=GeometryRouter(payload)
        self.assertEqual('mapped',router.bus_guidance('bus-a','bus-b','ksrtc:trip')['status'])

    def test_snap_to_unknown_node_or_outside_bound_is_rejected(self):
        payload=graph()
        payload['terminal_nodes']['bus-a']='made-up'
        with self.assertRaises(ValueError): GeometryRouter(payload)

    def test_map_contract_preserves_source_and_does_not_certify_road_guidance(self):
        import hashlib
        import tempfile
        from pathlib import Path
        from fastapi.testclient import TestClient
        from sahara.api import create_app
        from sahara.settings import Settings
        payload=graph()
        payload['terminal_nodes']={'kottayam-bus':'n1','bengaluru-bus':'n3'}
        payload['terminal_snap_m']={'kottayam-bus':10,'bengaluru-bus':20}
        session='a'*32
        owner=hashlib.sha256(session.encode()).hexdigest()
        observation={'evidence':'fixture','provider':'ksrtc','fetched_at':'2026-10-11T00:00:00+00:00'}
        record={'request':{'origin':'kottayam-bus','destination':'bengaluru-bus','data_mode':'sample'},
            'journey':{'warnings':['sample_data'],'connections':[],'legs':[{'id':'fixture-leg','mode':'bus',
                'provider':'ksrtc','service_id':'fixture-service','name':'Fixture KSRTC service',
                'origin':'kottayam-bus','destination':'bengaluru-bus','observation':observation}]}}
        with tempfile.TemporaryDirectory() as folder:
            app=create_app(Settings(database=Path(folder)/'test.sqlite3',api_key='test-key'))
            app.state.service.geometry=GeometryRouter(payload)
            app.state.service.repository.save('journey','fixture-journey',owner,record)
            with TestClient(app) as client:
                response=client.get('/v1/journeys/fixture-journey/map',headers={'X-Sahara-Session':session})
            self.assertEqual(200,response.status_code)
            body=response.json()
            self.assertFalse(body['geometry_complete'])
            self.assertTrue(body['guidance_complete'])
            segment=body['segments'][0]
            self.assertEqual('road_guidance',segment['status'])
            self.assertEqual(observation,segment['source_observation'])
            self.assertEqual('fixture-service',segment['service_id'])
            self.assertEqual('sample',body['data_mode'])
        payload=graph()
        payload['terminal_snap_m']['bus-a']=501
        with self.assertRaises(ValueError): GeometryRouter(payload)
