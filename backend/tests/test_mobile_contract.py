import unittest
from datetime import date, datetime, timezone


class MobileContractTests(unittest.TestCase):
    def test_bus_page_cannot_relabel_an_explicit_source_date(self):
        from pathlib import Path
        from sahara.providers.ksrtc import parse_ksrtc
        html = (Path(__file__).parent / 'fixtures' / 'ksrtc-public-dom-2026-10-15.html').read_text(encoding='utf-8')
        result = parse_ksrtc(html, 'kozhikode-bus', 'bengaluru-bus', date(2026, 10, 14),
                             datetime(2026, 10, 10, tzinfo=timezone.utc), 'https://onlineksrtcswift.com/search')
        self.assertEqual([], result)

    def test_bus_arrival_label_must_agree_with_duration(self):
        from sahara.providers.ksrtc import parse_ksrtc
        html = '''<div class="srch-card"><div class="triptimebold">23:00, 15 Oct, Kozhikode</div>
        <div class="duration">7:00 HRS</div><div class="triptimebold">06:00, 17 Oct, Bangalore</div>
        <div>EXPRESS</div><div>Non AC</div><div>View All</div><div>₹500</div><div>10 Seats Left</div></div>'''
        self.assertEqual([], parse_ksrtc(html, 'kozhikode-bus', 'bengaluru-bus', date(2026, 10, 15),
                                         datetime(2026, 10, 10, tzinfo=timezone.utc), 'https://onlineksrtcswift.com/search'))

    def test_multimodal_response_does_not_invent_route_geometry(self):
        from sahara.compat import android_response
        record = {'id': 'search', 'request': {'origin': 'ers', 'destination': 'sbc', 'data_mode': 'live'},
                  'plan': {'journeys': [{'legs': [{'mode': 'train', 'origin': 'ers', 'destination': 'sbc',
                      'departure': '2026-10-15T08:00:00+05:30', 'arrival': '2026-10-15T18:00:00+05:30'}],
                      'connections': [], 'duration_minutes': 600, 'total_fare_minor': None, 'known_fare_minor': 0}]},
                  'providers': [{'provider': 'rail', 'status': 'partial', 'message': 'One source unavailable', 'legs': []}]}
        result = android_response(record)
        self.assertEqual([], result['toolCalls'])
        self.assertEqual('partial', result['providers'][0]['status'])
        self.assertEqual('Ernakulam Junction', result['placeNames']['ers'])

    def test_split_bus_clock_and_city_markup_is_matched_without_borrowing_other_routes(self):
        from sahara.providers.ksrtc import parse_ksrtc
        html = '''<div class="srch-card"><div class="triptimebold"><span>07:00</span><span>Kozhikode (11)</span></div>
        <div class="duration">8:45 HRS</div><div class="triptimebold"><span>15:45</span><span>Bangalore</span></div>
        <span class="service--route">0700KKDBNG</span><span class="bus--type">SUPER FAST</span>
        <div class="pricetag">₹452</div><div class="seatcount">49 Seats Left</div></div>'''
        args = (date(2026, 10, 15), datetime(2026, 10, 10, tzinfo=timezone.utc), 'https://onlineksrtcswift.com/search')
        self.assertEqual(1, len(parse_ksrtc(html, 'kozhikode-bus', 'bengaluru-bus', *args)))
        self.assertEqual([], parse_ksrtc(html, 'ernakulam-bus', 'bengaluru-bus', *args))


class BrowserContractTests(unittest.IsolatedAsyncioTestCase):
    async def test_primary_train_page_uses_configured_browser(self):
        from sahara.providers.rail import RailProvider
        from sahara.domain import SearchRequest
        from sahara.places import TERMINALS
        calls = []
        async def collector(url, browser):
            calls.append((url, browser))
            return '<html></html>'
        now = datetime(2026, 10, 10, tzinfo=timezone.utc)
        await RailProvider(browser=True, collector=collector).search(
            SearchRequest(origin='ers', destination='sbc', depart_after=now), [(TERMINALS['ers'], TERMINALS['sbc'])], now)
        self.assertTrue(calls[0][1])
