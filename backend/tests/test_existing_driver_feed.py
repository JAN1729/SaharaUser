import unittest
from datetime import datetime, timedelta, timezone
import httpx

from sahara.fleet import ExistingDriverFeed


class ExistingDriverFeedTests(unittest.IsolatedAsyncioTestCase):
    async def test_bounded_fleet_limit_reports_partial_instead_of_false_empty(self):
        def handle(request):
            if request.url.path.endswith('/vehicles'):
                return httpx.Response(200,json=[{'id':f'bus{i}','name':'Bus','is_active':True} for i in range(101)])
            return httpx.Response(200,json=[])
        result=await ExistingDriverFeed('https://example.supabase.co','key',httpx.MockTransport(handle)).nearby(9.59,76.53)
        self.assertEqual('partial',result['status'])
        self.assertIn('incomplete',result['notice'])
        self.assertEqual([],result['vehicles'])
    async def test_disabled_feed_makes_no_requests(self):
        def forbidden(request):
            self.fail('No driver request should run without a configured connection')
        result=await ExistingDriverFeed('', '', httpx.MockTransport(forbidden)).nearby(9.59,76.53)
        self.assertEqual('unconfigured',result['status'])
        self.assertEqual([],result['vehicles'])

    async def test_empty_registered_fleet_never_reads_unrelated_location_records(self):
        calls=[]
        def handle(request):
            calls.append(request.url.path)
            return httpx.Response(200,json=[])
        result=await ExistingDriverFeed('https://example.supabase.co','test-public-key',httpx.MockTransport(handle)).nearby(9.59,76.53)
        self.assertEqual('empty',result['status'])
        self.assertEqual(['/rest/v1/vehicles'],calls)

    async def test_fresh_registered_trip_is_read_only_and_stale_or_wrong_vehicle_is_omitted(self):
        now=datetime.now(timezone.utc)
        calls=[]
        def handle(request):
            calls.append(request)
            self.assertEqual('GET',request.method)
            self.assertNotIn('driver_id',request.url.params.get('select',''))
            if request.url.path.endswith('/vehicles'):
                return httpx.Response(200,json=[{'id':'bus1','name':'Local bus','is_active':True}, {'id':'old','name':'Old','is_active':True}])
            if request.url.path.endswith('/route_stops'):
                return httpx.Response(200,json=[{'id':'s1','vehicle_id':'bus1','name':'Board here','latitude':9.59,'longitude':76.53,'stop_order':1}])
            return httpx.Response(200,json=[
                {'bus_id':'bus1','latitude':9.59,'longitude':76.53,'accuracy':10,'timestamp_iso':now.isoformat(),'trip_status':'started'},
                {'bus_id':'old','latitude':9.59,'longitude':76.53,'accuracy':10,'timestamp_iso':(now-timedelta(minutes=5)).isoformat(),'trip_status':'started'},
                {'bus_id':'unregistered','latitude':9.59,'longitude':76.53,'accuracy':10,'timestamp_iso':now.isoformat(),'trip_status':'started'}])
        feed=ExistingDriverFeed('https://example.supabase.co','test-public-key',httpx.MockTransport(handle))
        result=await feed.nearby(9.59,76.53,now=now)
        self.assertEqual(['bus1'],[v['vehicle_id'] for v in result['vehicles']])
        self.assertEqual('Board here',result['vehicles'][0]['stops'][0]['name'])
        self.assertIsNone(result['vehicles'][0].get('eta_minutes'))
        self.assertEqual(3,len(calls))

    async def test_future_invalid_inaccurate_and_stopped_updates_are_not_live(self):
        now=datetime.now(timezone.utc)
        for change in [{'latitude':100}, {'accuracy':100}, {'timestamp_iso':(now+timedelta(minutes=1)).isoformat()}, {'trip_status':'stopped'}]:
            def handle(request):
                if request.url.path.endswith('/vehicles'):
                    return httpx.Response(200,json=[{'id':'bus1','name':'Bus','is_active':True}])
                if request.url.path.endswith('/route_stops'): return httpx.Response(200,json=[])
                return httpx.Response(200,json=[dict(bus_id='bus1',latitude=9.59,longitude=76.53,accuracy=10,
                    timestamp_iso=now.isoformat(),trip_status='started')|change])
            result=await ExistingDriverFeed('https://example.supabase.co','key',httpx.MockTransport(handle)).nearby(9.59,76.53,now=now)
            self.assertEqual([],result['vehicles'])
