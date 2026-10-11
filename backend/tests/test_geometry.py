import unittest


class GeometryTests(unittest.TestCase):
    def graph(self):
        return {'source_type':'road_graph','source_url':'https://example.org/published-route','revision':'test',
            'nodes':{'a':{'lat':12,'lng':77},'b':{'lat':12.001,'lng':77},'c':{'lat':12.001,'lng':77.001}},
            'edges':[{'origin':'a','destination':'b','length_m':112,'mode':'road',
                      'polyline':[{'lat':12,'lng':77},{'lat':12.0005,'lng':77},{'lat':12.001,'lng':77}]},
                     {'origin':'b','destination':'c','length_m':109,'mode':'road',
                      'polyline':[{'lat':12.001,'lng':77},{'lat':12.001,'lng':77.0005},{'lat':12.001,'lng':77.001}]}]}
    def test_route_follows_graph_edges_instead_of_endpoint_straight_line(self):
        from sahara.geometry import GeometryRouter
        router=GeometryRouter(self.graph())
        route=router.route('a','c','auto')
        self.assertEqual('mapped',route['status'])
        self.assertEqual(220,route['distance_m'])
        self.assertEqual(5,len(route['polyline']))
        self.assertEqual([],router.route('c','a','auto')['polyline'])
        self.assertEqual([],router.route('a','c','train')['polyline'])
        self.assertEqual([],router.route('a','c','auto',required_service='rail:12677')['polyline'])
    def test_missing_graph_and_invalid_geometry_never_invent_path(self):
        from sahara.geometry import GeometryRouter
        self.assertEqual([],GeometryRouter().route('a','c','train')['polyline'])
        invalid=self.graph()
        invalid['edges'][0]['polyline'][0]['lat']=11
        with self.assertRaises(ValueError): GeometryRouter(invalid)
