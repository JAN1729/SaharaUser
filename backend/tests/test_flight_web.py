import asyncio
import unittest
from datetime import date, datetime, timedelta, timezone
from pathlib import Path


ROOT = Path(__file__).parent
NOW = datetime(2026, 10, 9, 12, tzinfo=timezone.utc)


def fixture(name='google_flights_rendered.html'):
    return (ROOT / 'fixtures' / name).read_text(encoding='utf-8')


class GoogleFlightHtmlTests(unittest.TestCase):
    def test_rendered_result_cards_keep_date_route_fare_and_provenance(self):
        from sahara.providers.flights import parse_google_flights

        legs = parse_google_flights(
            fixture(), 'cok', 'blr', date(2026, 10, 11), NOW,
            'https://www.google.com/travel/flights?hl=en&gl=IN&curr=INR#flt=COK.BLR.2026-10-11',
        )

        self.assertEqual(1, len(legs))
        leg = legs[0]
        self.assertEqual(('cok', 'blr'), (leg.origin, leg.destination))
        self.assertEqual(datetime(2026, 10, 11, 10, 30, tzinfo=timezone(timedelta(hours=5, minutes=30))), leg.departure)
        self.assertEqual(datetime(2026, 10, 11, 11, 50, tzinfo=timezone(timedelta(hours=5, minutes=30))), leg.arrival)
        self.assertEqual(581000, leg.fare_minor)
        self.assertEqual('INR', leg.currency)
        self.assertEqual('per_person', leg.fare_basis)
        self.assertEqual('available', leg.availability.value)
        self.assertEqual('cached', leg.observation.evidence)
        self.assertEqual(NOW, leg.observation.fetched_at)
        self.assertEqual('https://www.google.com/travel/flights?hl=en&gl=IN&curr=INR#flt=COK.BLR.2026-10-11', leg.observation.source_url)

    def test_from_price_without_dated_times_is_not_itinerary_inventory(self):
        from sahara.providers.flights import parse_google_flights

        html = '<li class="pIav2d" aria-label="Flights from COK to BLR from ₹3,499"><span>From ₹3,499</span></li>'
        self.assertEqual([], parse_google_flights(html, 'cok', 'blr', date(2026, 10, 11), NOW, 'https://www.google.com/travel/flights'))

    def test_undated_card_cannot_borrow_date_from_query_url(self):
        from sahara.providers.flights import parse_google_flights

        html = '''<li class="pIav2d" aria-label="IndiGo COK to BLR, ₹5,810, 1 hr 20 min, nonstop">
          <div class="route" data-origin="COK" data-destination="BLR">COK → BLR</div>
          <span>10:30 AM</span><span>11:50 AM</span><span>₹5,810</span>
        </li>'''
        url = 'https://www.google.com/travel/flights?hl=en#flt=COK.BLR.2026-10-11'
        self.assertEqual([], parse_google_flights(html, 'cok', 'blr', date(2026, 10, 11), NOW, url))

    def test_page_visible_selected_date_proves_undated_card_for_exact_route(self):
        from sahara.providers.flights import parse_google_flights

        html = '''<div data-selected-date="2026-10-11" data-selected-origin="COK"
                    data-selected-destination="BLR">Sun, Oct 11, 2026</div>
        <li class="pIav2d" aria-label="IndiGo COK to BLR, ₹5,810, 1 hr 20 min, nonstop">
          <div class="route" data-origin="COK" data-destination="BLR">COK → BLR</div>
          <span>10:30 AM</span><span>11:50 AM</span><span>₹5,810</span>
        </li>'''
        legs = parse_google_flights(html, 'cok', 'blr', date(2026, 10, 11), NOW, 'https://www.google.com/travel/flights')
        self.assertEqual(1, len(legs))

    def test_wrong_visible_selected_date_does_not_prove_undated_card(self):
        from sahara.providers.flights import parse_google_flights

        html = '''<div data-selected-date="2026-10-12" data-selected-origin="COK"
                    data-selected-destination="BLR">Mon, Oct 12, 2026</div>
        <li class="pIav2d" aria-label="IndiGo COK to BLR, ₹5,810, 1 hr 20 min, nonstop">
          <div class="route" data-origin="COK" data-destination="BLR">COK → BLR</div>
          <span>10:30 AM</span><span>11:50 AM</span><span>₹5,810</span>
        </li>'''
        self.assertEqual([], parse_google_flights(html, 'cok', 'blr', date(2026, 10, 11), NOW, 'https://www.google.com/travel/flights'))

    def test_explicit_time_datetime_attributes_prove_card_date(self):
        from sahara.providers.flights import parse_google_flights

        html = '''<li class="pIav2d" aria-label="IndiGo COK to BLR, ₹5,810, nonstop">
          <div class="route" data-origin="COK" data-destination="BLR">COK → BLR</div>
          <div class="mv1WYe"><time datetime="2026-10-11T10:30:00+05:30">10:30 AM</time>
          <time datetime="2026-10-11T11:50:00+05:30">11:50 AM</time></div>
          <span>₹5,810</span>
        </li>'''
        legs = parse_google_flights(html, 'cok', 'blr', date(2026, 10, 11), NOW, 'https://www.google.com/travel/flights')
        self.assertEqual(1, len(legs))

    def test_google_rendered_accessible_card_proves_date_from_flight_label(self):
        from sahara.providers.flights import parse_google_flights

        html = '''<ul><li class="pIav2d">
          <div role="link" aria-label="From 4408 Indian rupees. Nonstop flight with Air India Express. Leaves Cochin International Airport at 10:25 AM on Monday, October 12 and arrives at Kempegowda International Airport Bengaluru at 11:35 AM on Monday, October 12. Total duration 1 hr 10 min."></div>
          <span class="mv1WYe" aria-label="Leaves Cochin International Airport at 10:25 AM on Monday, October 12 and arrives at Kempegowda International Airport Bengaluru at 11:35 AM on Monday, October 12.">10:25 AM – 11:35 AM</span>
          <span aria-label="Departure time: 10:25 AM.">10:25 AM</span>
          <span aria-label="Arrival time: 11:35 AM.">11:35 AM</span>
          <span>Air India Express</span><span>COK–BLR</span><span>1 hr 10 min</span><span>₹4,408</span>
        </li></ul>'''
        legs = parse_google_flights(html, 'cok', 'blr', date(2026, 10, 12), NOW, 'https://www.google.com/travel/flights/search')
        self.assertEqual(1, len(legs))
        self.assertEqual(datetime(2026, 10, 12, 10, 30, tzinfo=timezone(timedelta(hours=5, minutes=30))).date(), legs[0].departure.date())

    def test_round_trip_party_total_is_rejected_for_one_way_inventory(self):
        from sahara.providers.flights import parse_google_flights

        html = '''<li class="pIav2d" aria-label="IndiGo COK to BLR, ₹12,000 round trip total for 2 travelers">
          <div class="route" data-origin="COK" data-destination="BLR">COK → BLR</div>
          <span aria-label="Departure time: Sunday, October 11, 2026, 10:30 AM">10:30 AM</span>
          <span aria-label="Arrival time: Sunday, October 11, 2026, 11:50 AM">11:50 AM</span>
          <span>₹12,000 round trip total for 2 travelers</span>
        </li>'''
        self.assertEqual([], parse_google_flights(html, 'cok', 'blr', date(2026, 10, 11), NOW, 'https://www.google.com/travel/flights', passengers=2))

    def test_party_total_requires_matching_passenger_count_when_not_round_trip(self):
        from sahara.providers.flights import parse_google_flights

        html = '''<li class="pIav2d" aria-label="IndiGo COK to BLR, ₹12,000 total for 2 travelers">
          <div class="route" data-origin="COK" data-destination="BLR">COK → BLR</div>
          <span aria-label="Departure time: Sunday, October 11, 2026, 10:30 AM">10:30 AM</span>
          <span aria-label="Arrival time: Sunday, October 11, 2026, 11:50 AM">11:50 AM</span>
          <span>₹12,000 total for 2 travelers</span>
        </li>'''
        url = 'https://www.google.com/travel/flights'
        self.assertEqual([], parse_google_flights(html, 'cok', 'blr', date(2026, 10, 11), NOW, url, passengers=1))
        legs = parse_google_flights(html, 'cok', 'blr', date(2026, 10, 11), NOW, url, passengers=2)
        self.assertEqual(1, len(legs))
        self.assertEqual('party', legs[0].fare_basis)


class CollectorChallengeTests(unittest.TestCase):
    def test_dormant_script_marker_is_not_reported_as_visible_challenge(self):
        from sahara.providers.collector import has_visible_challenge

        html = '<html><head><script>var captchaConfig = true;</script></head><body><div>Flights</div></body></html>'
        self.assertFalse(has_visible_challenge(html))

    def test_hidden_accessibility_marker_is_not_reported_as_visible_challenge(self):
        from sahara.providers.collector import has_visible_challenge

        html = '<html><body><div aria-hidden="true">Captcha required</div><div>Flights</div></body></html>'
        self.assertFalse(has_visible_challenge(html))

    def test_visible_challenge_text_is_reported(self):
        from sahara.providers.collector import has_visible_challenge

        html = '<html><body><h1>Verify you are human</h1><p>Captcha required</p></body></html>'
        self.assertTrue(has_visible_challenge(html))

    def test_explicit_next_day_arrival_preserves_chronology(self):
        from sahara.providers.flights import parse_google_flights

        html = '''<li class="pIav2d" aria-label="Air India COK to BLR, ₹6,200, 1 hr 30 min, nonstop">
          <div class="route" data-origin="COK" data-destination="BLR">COK → BLR</div>
          <span aria-label="Departure time: Sunday, October 11, 2026, 11:50 PM">11:50 PM</span>
          <span aria-label="Arrival time: Monday, October 12, 2026, 1:20 AM +1">1:20 AM +1</span>
          <span class="FpEdX">₹6,200</span>
        </li>'''
        legs = parse_google_flights(html, 'cok', 'blr', date(2026, 10, 11), NOW, 'https://www.google.com/travel/flights')
        self.assertEqual(1, len(legs))
        self.assertEqual(12, legs[0].arrival.day)
        self.assertEqual(timedelta(hours=1, minutes=30), legs[0].arrival - legs[0].departure)

    def test_missing_inr_fare_and_wrong_route_are_skipped(self):
        from sahara.providers.flights import parse_google_flights

        html = '''
        <li class="pIav2d" aria-label="Example CCJ to BLR, $40, 1 hr, nonstop">
          <div class="route" data-origin="CCJ" data-destination="BLR">CCJ → BLR</div>
          <span aria-label="Departure time: Sunday, October 11, 2026, 10:00 AM">10:00 AM</span>
          <span aria-label="Arrival time: Sunday, October 11, 2026, 11:00 AM">11:00 AM</span>
          <span>$40</span>
        </li>
        <li class="pIav2d" aria-label="Example COK to CCJ, ₹2,000, 1 hr, nonstop">
          <div class="route" data-origin="COK" data-destination="CCJ">COK → CCJ</div>
          <span aria-label="Departure time: Sunday, October 11, 2026, 10:00 AM">10:00 AM</span>
          <span aria-label="Arrival time: Sunday, October 11, 2026, 11:00 AM">11:00 AM</span>
          <span>₹2,000</span>
        </li>'''
        self.assertEqual([], parse_google_flights(html, 'cok', 'blr', date(2026, 10, 11), NOW, 'https://www.google.com/travel/flights'))


class FlightProviderWebTests(unittest.IsolatedAsyncioTestCase):
    def test_google_url_uses_public_tfs_results_link(self):
        from sahara.providers.flights import FlightProvider

        url = FlightProvider.url('COK', 'BLR', date(2026, 10, 11), passengers=2)
        self.assertIn('https://www.google.com/travel/flights/search?', url)
        self.assertIn('tfs=', url)
        self.assertIn('#flt=COK.BLR.2026-10-11', url)

    async def test_provider_uses_bounded_dated_google_queries_and_shared_collector(self):
        from sahara.domain import SearchRequest
        from sahara.places import TERMINALS
        from sahara.providers.flights import FlightProvider

        urls = []

        async def collector(url, browser=False):
            urls.append((url, browser))
            return fixture()

        request = SearchRequest(
            origin='cok', destination='blr',
            depart_after=datetime(2026, 10, 11, 8, tzinfo=timezone(timedelta(hours=5, minutes=30))),
            arrive_by=datetime(2026, 10, 13, 8, tzinfo=timezone(timedelta(hours=5, minutes=30))),
            data_mode='live',
        )
        result = await FlightProvider(enabled=True, browser=True, collector=collector).search(
            request, [(TERMINALS['cok'], TERMINALS['blr'])], NOW,
        )

        self.assertEqual('partial', result.status)
        self.assertEqual(2, len(urls))
        self.assertTrue(all(browser for _, browser in urls))
        self.assertTrue(any('COK' in url and 'BLR' in url and '2026-10-11' in url for url, _ in urls))
        self.assertTrue(any('COK' in url and 'BLR' in url and '2026-10-12' in url for url, _ in urls))
        self.assertTrue(all(leg.observation.source_url.startswith('https://www.google.com/') for leg in result.legs))

    async def test_source_challenge_is_reported_as_error_without_invented_legs(self):
        from sahara.domain import SearchRequest
        from sahara.places import TERMINALS
        from sahara.providers.flights import FlightProvider

        async def blocked(url, browser=False):
            raise ValueError('Source challenge: use authorized provider access')

        request = SearchRequest(origin='cok', destination='blr', depart_after=NOW, data_mode='live')
        result = await FlightProvider(enabled=True, collector=blocked).search(
            request, [(TERMINALS['cok'], TERMINALS['blr'])], NOW,
        )
        self.assertEqual('error', result.status)
        self.assertEqual([], result.legs)
        self.assertIn('challenge', result.message.lower())

    async def test_disabled_provider_never_calls_collector(self):
        from sahara.domain import SearchRequest
        from sahara.places import TERMINALS
        from sahara.providers.flights import FlightProvider

        calls = []

        async def collector(*args):
            calls.append(args)
            return fixture()

        request = SearchRequest(origin='cok', destination='blr', depart_after=NOW, data_mode='live')
        result = await FlightProvider(collector=collector).search(
            request, [(TERMINALS['cok'], TERMINALS['blr'])], NOW,
        )
        self.assertEqual('disabled', result.status)
        self.assertEqual([], calls)

