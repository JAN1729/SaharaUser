import unittest
from datetime import datetime, date, timezone, timedelta

NOW = datetime(2026,10,9,tzinfo=timezone.utc)


class ProviderParsingTests(unittest.TestCase):
    def setUp(self):
        try:
            from sahara.providers.parsing import normalize_availability, money_minor, timed_service
            from sahara.providers.rail import parse_confirmtkt
            from sahara.providers.ksrtc import parse_ksrtc
            from sahara.providers.flights import parse_duffel
        except ImportError:
            self.fail('Provider normalization and isolated collectors are not implemented')
        self.status, self.money, self.times = normalize_availability, money_minor, timed_service
        self.rail, self.bus, self.flights = parse_confirmtkt, parse_ksrtc, parse_duffel

    def test_availability_normalization_rejects_false_positive_text(self):
        for text, expected in [('Unknown','unknown'),('0 Seats','sold_out'),('Not Available','sold_out'),
                               ('WL 15','waitlisted'),('RAC 2','rac'),('Available 12','available'),
                               ('5 Seats Left','available')]:
            with self.subTest(text=text):
                self.assertEqual(expected, self.status(text)[0])

    def test_money_unknown_and_currency_are_not_zero(self):
        self.assertIsNone(self.money('Unknown'))
        self.assertEqual(128050, self.money('₹1,280.50'))
        self.assertIsNone(self.money('$40'))

    def test_overnight_times_use_duration_to_find_arrival_day(self):
        dep, arr = self.times(date(2026,10,10),'23:00','02:00','27h 0m')
        self.assertEqual(12, arr.day)
        self.assertEqual(timedelta(hours=27), arr-dep)

    def test_inconsistent_duration_is_rejected(self):
        with self.assertRaises(ValueError):
            self.times(date(2026,10,10),'23:00','02:00','6h 0m')

    def test_rail_html_retains_classes_and_upstream_age(self):
        html = '''<div class="train-card"><div><span class="mr-5">12677</span> Express</div>
        <p>23:00 ERS</p><p>02:00 SBC</p><p>3h 0m</p>
        <div class="cache-card-wrapper"><p>SL</p><p>₹450</p><p>Available 4</p><p>2 mins ago</p></div>
        <div class="cache-card-wrapper"><p>3A</p><p>₹1,200</p><p>WL 10</p><p>1 hr ago</p></div></div>'''
        legs = self.rail(html,'ers','sbc',date(2026,10,10),NOW,'https://www.confirmtkt.com/test')
        self.assertEqual(2,len(legs))
        self.assertEqual(45000,legs[0].fare_minor)
        self.assertEqual(NOW-timedelta(minutes=2),legs[0].observation.observed_at)
        self.assertEqual('waitlisted',legs[1].availability.value)
        self.assertEqual(11,legs[0].arrival.day)

    def test_bus_unknown_seats_are_not_confirmed(self):
        html = '''<div class="srch-card"><p>23:00, Sat, Ernakulam</p><p>8h 0m</p>
        <p>07:00, Sun, Bangalore</p><p>SUPER EXPRESS</p><p>Non AC</p><p>View All</p>
        <p>₹</p><p>550</p><p>Unknown</p></div>'''
        legs = self.bus(html,'ernakulam-bus','bengaluru-bus',date(2026,10,10),NOW,'https://onlineksrtcswift.com/test')
        self.assertEqual(1,len(legs))
        self.assertEqual('unknown',legs[0].availability.value)
        self.assertEqual(55000,legs[0].fare_minor)

    def flight_offer(self, **changes):
        offer = {'id':'off_test','live_mode':True,'total_currency':'INR','total_amount':'5000.00',
                 'expires_at':'2026-10-09T00:15:00Z','slices':[{'segments':[{
                 'departing_at':'2026-10-10T12:00:00','arriving_at':'2026-10-10T13:30:00',
                 'origin':{'iata_code':'COK','iata_country_code':'IN','time_zone':'Asia/Kolkata'},
                 'destination':{'iata_code':'BLR','iata_country_code':'IN','time_zone':'Asia/Kolkata'},
                 'operating_carrier':{'name':'Example'},'operating_carrier_flight_number':'123'}]}]}
        offer.update(changes)
        return offer

    def test_flight_offer_fare_is_for_party_and_timezone_is_preserved(self):
        legs = self.flights([self.flight_offer()],NOW)
        self.assertEqual(500000,legs[0].fare_minor)
        self.assertEqual('party',legs[0].fare_basis)
        self.assertEqual(timedelta(hours=5,minutes=30),legs[0].departure.utcoffset())

    def test_test_mode_and_expired_flights_do_not_become_live_offers(self):
        self.assertEqual([],self.flights([self.flight_offer(live_mode=False)],NOW))
        self.assertEqual([],self.flights([self.flight_offer(expires_at='2026-10-08T00:00:00Z')],NOW))

    def test_refreshed_offer_id_keeps_service_identity(self):
        first=self.flights([self.flight_offer(id='off_1')],NOW)[0]
        second=self.flights([self.flight_offer(id='off_2')],NOW)[0]
        self.assertNotEqual(first.id,second.id)
        self.assertEqual(first.service_id,second.service_id)

    def test_flight_segments_cannot_have_a_broken_connection(self):
        offer=self.flight_offer()
        segment=offer['slices'][0]['segments'][0]
        second=dict(segment,departing_at='2026-10-10T13:00:00',arriving_at='2026-10-10T14:00:00')
        offer['slices'][0]['segments'].append(second)
        self.assertEqual([],self.flights([offer],NOW))


class BrokerTests(unittest.IsolatedAsyncioTestCase):
    async def test_external_flight_api_is_disabled_even_when_a_key_exists(self):
        import httpx
        from sahara.providers.flights import FlightProvider
        from sahara.places import TERMINALS
        from sahara.domain import SearchRequest
        offer=ProviderParsingTests().flight_offer()
        calls=[]
        def respond(request):
            calls.append(request)
            if len(calls)>1:
                return httpx.Response(503,json={'error':'temporarily unavailable'})
            return httpx.Response(200,json={'data':{'offers':[offer]}})
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            result=await FlightProvider('test-key',client).search(SearchRequest(origin='cok',destination='blr',depart_after=NOW),
                [(TERMINALS['cok'],TERMINALS['blr']),(TERMINALS['trv'],TERMINALS['blr'])],NOW)
        self.assertEqual('disabled',result.status)
        self.assertEqual([],calls)
        self.assertEqual([],result.legs)

    async def test_duplicate_requests_share_collection(self):
        try:
            from sahara.broker import Broker
        except ImportError:
            self.fail('Provider broker is not implemented')
        from sahara.domain import SearchRequest, ProviderResult
        import asyncio
        class CountingProvider:
            name = 'test'
            calls = 0
            async def search(self, request, pairs, now):
                self.calls += 1
                await asyncio.sleep(.02)
                return ProviderResult(provider=self.name,status='empty')
        provider=CountingProvider()
        broker=Broker([provider],timeout=.5)
        request=SearchRequest(origin='ers',destination='sbc',depart_after=NOW)
        first,second=await asyncio.gather(broker.collect(request,[],NOW),broker.collect(request,[],NOW))
        self.assertEqual(1,provider.calls)
        self.assertEqual('empty',first[0].status)

    async def test_timeout_is_not_empty_inventory(self):
        try:
            from sahara.broker import Broker
        except ImportError:
            self.fail('Provider broker is not implemented')
        from sahara.domain import SearchRequest
        import asyncio
        class SlowProvider:
            name='slow'
            async def search(self,*args):
                await asyncio.sleep(1)
        broker=Broker([SlowProvider()],timeout=.01)
        result=await broker.collect(SearchRequest(origin='ers',destination='sbc',depart_after=NOW),[],NOW)
        self.assertEqual('timeout',result[0].status)
