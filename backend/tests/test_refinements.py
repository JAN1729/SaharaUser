import unittest
from datetime import datetime, timezone, timedelta


class RefinementTests(unittest.TestCase):
    def test_budget_refinement_preserves_route_and_date(self):
        from sahara.interpret import interpret
        now=datetime(2026,10,9,12,tzinfo=timezone.utc)
        first=interpret('Kottayam to Bengaluru tomorrow',now=now)
        second=interpret('same journey under 1500',first.model_dump(mode='json'),now=now)
        self.assertEqual(first.origin,second.origin)
        self.assertEqual(first.depart_after,second.depart_after)
        self.assertEqual(150000,second.budget_minor)

    def test_route_map_orders_transfer_terminals_chronologically(self):
        from sahara.compat import android_response
        record={'request':{'origin':'kty','destination':'sbc','data_mode':'sample'},'id':'s1','providers':[],
          'plan':{'journeys':[{'id':'j1','total_fare_minor':90000,'known_fare_minor':90000,'duration_minutes':600,
            'connections':[],'legs':[{'origin':'kty','destination':'ers','mode':'train','departure':'2026-10-10T06:00:00+05:30','arrival':'2026-10-10T08:00:00+05:30'},
            {'origin':'ernakulam-bus','destination':'bengaluru-bus','mode':'bus','departure':'2026-10-10T09:00:00+05:30','arrival':'2026-10-10T16:00:00+05:30'}]}]}}
        res = android_response(record)
        self.assertEqual([], res['toolCalls'])
        self.assertEqual('MULTIMODAL_JOURNEY', res['intent'])

    def test_flight_discovery_includes_accessible_airport_for_kottayam(self):
        from sahara.places import discovery_pairs
        pairs=discovery_pairs('kty','sbc')
        self.assertIn(('cok','blr'),[(a.id,b.id) for a,b in pairs])

    def test_arrive_by_today_does_not_default_to_tomorrow(self):
        from sahara.interpret import interpret
        now=datetime(2026,10,9,2,tzinfo=timezone.utc)
        result=interpret('Kottayam to Bengaluru today by 11pm',now=now)
        self.assertEqual(9,result.arrive_by.day)
        self.assertEqual(23,result.arrive_by.hour)
