import unittest
from datetime import date, datetime, timezone, timedelta


class RailFallbackTests(unittest.TestCase):
    def test_confirmtkt_original_scraper_card_selector(self):
        from sahara.providers.rail import parse_confirmtkt
        html='''<div class="rounded-10 border-b"><div><span class="mr-5">12677</span>Express</div>
        <p>23:00 ERS</p><p>02:00 SBC</p><p>3h 0m</p><div class="cache-card-wrapper">
        <p>SL</p><p>₹450</p><p>Available 4</p><p>now</p></div></div>'''
        legs=parse_confirmtkt(html,'ers','sbc',date(2026,10,10),datetime(2026,10,9,tzinfo=timezone.utc),'https://www.confirmtkt.com/test')
        self.assertEqual(1,len(legs))

    def test_railyatri_fallback_normalizes_a_real_card_shape(self):
        try:
            from sahara.providers.rail import parse_railyatri
        except ImportError:
            self.fail('Isolated RailYatri fallback is not implemented')
        html='''<div class="MuiPaper-root"><a href="/time-table/12677">12677 Express</a>
        <p class="css-1tiw2we">ERS, 23:00 Ernakulam</p><p class="css-1tiw2we">02:00, SBC Bengaluru</p>
        <div class="css-rv7cml">3h 0m</div><div class="Mui-seat__layout_box">
        <span class="Mui-booking_class__title">SL</span><span class="Mui-booking_class__price">₹450</span>
        <span class="Mui-availibility_and__seat_count_title">Available 3</span></div></div>'''
        legs=parse_railyatri(html,'ers','sbc',date(2026,10,10),datetime(2026,10,9,tzinfo=timezone.utc),'https://www.railyatri.in/test')
        self.assertEqual(1,len(legs))
        self.assertEqual('railyatri',legs[0].provider)
        self.assertEqual(45000,legs[0].fare_minor)
        self.assertEqual(11,legs[0].arrival.day)

    def test_malformed_bus_card_cannot_borrow_unrelated_route(self):
        from sahara.providers.ksrtc import parse_ksrtc
        html='''<div class="srch-card"><p>23:00, Sat, Trivandrum</p><p>8h 0m</p>
        <p>07:00, Sun, Kozhikode</p><p>EXPRESS</p><p>Non AC</p><p>View All</p><p>₹550</p><p>10 Seats Left</p></div>'''
        legs=parse_ksrtc(html,'ernakulam-bus','bengaluru-bus',date(2026,10,10),datetime(2026,10,9,tzinfo=timezone.utc),'https://onlineksrtcswift.com/test')
        self.assertEqual([],legs)

    def test_train_card_cannot_borrow_an_unrelated_route(self):
        from sahara.providers.rail import parse_confirmtkt, parse_railyatri
        now=datetime(2026,10,9,tzinfo=timezone.utc)
        html='''<div class="train-card"><div><span class="mr-5">12677</span>Express</div>
        <p>23:00 TVC</p><p>02:00 CBE</p><p>3h 0m</p><div class="cache-card-wrapper">
        <p>SL</p><p>₹450</p><p>Available 4</p><p>now</p></div></div>'''
        self.assertEqual([],parse_confirmtkt(html,'ers','sbc',date(2026,10,10),now,'https://www.confirmtkt.com/test'))
        html='''<div class="MuiPaper-root"><a href="/time-table/12677">12677 Express</a>
        <p class="css-1tiw2we">TVC, 23:00</p><p class="css-1tiw2we">02:00, CBE</p>
        <div class="css-rv7cml">3h 0m</div><div class="Mui-seat__layout_box">
        <span class="Mui-booking_class__title">SL</span><span class="Mui-booking_class__price">₹450</span>
        <span class="Mui-availibility_and__seat_count_title">Available 3</span></div></div>'''
        self.assertEqual([],parse_railyatri(html,'ers','sbc',date(2026,10,10),now,'https://www.railyatri.in/test'))


class RailRequestTests(unittest.IsolatedAsyncioTestCase):
    async def test_verified_direct_train_stops_before_hub_fanout(self):
        from sahara.providers.rail import RailProvider
        from sahara.domain import SearchRequest
        from sahara.places import TERMINALS
        calls=[]
        html='''<div class="train-card"><div><span class="mr-5">12677</span>Express</div>
        <p>23:00 ERS</p><p>02:00 SBC</p><p>3h 0m</p><div class="cache-card-wrapper">
        <p>SL</p><p>₹450</p><p>Available 4</p><p>now</p></div></div>'''
        async def collector(url,*args):
            calls.append(url)
            return html
        now=datetime(2026,10,9,tzinfo=timezone.utc)
        request=SearchRequest(origin='ers',destination='sbc',depart_after=now,
                              arrive_by=now+timedelta(hours=17))
        pairs=[(TERMINALS['ers'],TERMINALS['sbc']),
               (TERMINALS['cbe'],TERMINALS['sbc'])]
        result=await RailProvider(collector=collector).search(request,pairs,now)
        self.assertEqual('ok',result.status)
        self.assertEqual(1,len(result.legs))
        self.assertEqual(1,len(calls))


class BusRequestTests(unittest.IsolatedAsyncioTestCase):
    async def test_bus_url_uses_source_city_code_and_date_format(self):
        from unittest.mock import patch
        from urllib.parse import urlparse,parse_qs
        from sahara.providers.ksrtc import KSRTCProvider
        from sahara.places import TERMINALS
        from sahara.domain import SearchRequest
        urls=[]
        async def collector(url,*args):
            urls.append(url)
            return '<html></html>'
        now=datetime(2026,10,9,tzinfo=timezone.utc)
        request=SearchRequest(origin='coimbatore-bus',destination='bengaluru-bus',depart_after=now,
                              arrive_by=now+timedelta(hours=17))
        with patch('sahara.providers.ksrtc.collect_html',collector):
            await KSRTCProvider(collector=collector).search(request,[(TERMINALS['coimbatore-bus'],TERMINALS['bengaluru-bus'])],now)
        params=parse_qs(urlparse(urls[0]).query)
        self.assertEqual(['386|Coimbatore'],params['fromCity'])
        self.assertEqual(['09-10-2026'],params['departDate'])

    async def test_verified_direct_bus_stops_before_hub_fanout(self):
        from sahara.providers.ksrtc import KSRTCProvider
        from sahara.domain import SearchRequest
        from sahara.places import TERMINALS
        calls=[]
        html='''<div class="srch-card"><div class="triptimebold">07:00, 09 Oct, Coimbatore</div>
        <div class="duration">5:00 HRS</div><div class="triptimebold">12:00, 09 Oct, Bangalore</div>
        <span class="service--route">0700CBEBNG</span><span class="bus--type">SUPER FAST</span>
        <div>View All</div><div>₹400</div><div>10 Seats Left</div></div>'''
        async def collector(url,*args):
            calls.append(url)
            return html
        now=datetime(2026,10,9,tzinfo=timezone.utc)
        request=SearchRequest(origin='coimbatore-bus',destination='bengaluru-bus',depart_after=now,
                              arrive_by=now+timedelta(hours=17))
        # Discovery can place a hub pair before the exact city pair.  The
        # provider must reorder the requested corridor before opening pages.
        pairs=[(TERMINALS['ernakulam-bus'],TERMINALS['bengaluru-bus']),
               (TERMINALS['coimbatore-bus'],TERMINALS['bengaluru-bus'])]
        result=await KSRTCProvider(collector=collector).search(request,pairs,now)
        self.assertEqual('ok',result.status)
        self.assertEqual(1,len(result.legs))
        self.assertEqual(1,len(calls))

    async def test_primary_transport_failure_does_not_fan_out_unrelated_routes(self):
        from sahara.providers.ksrtc import KSRTCProvider
        from sahara.domain import SearchRequest
        from sahara.places import TERMINALS
        calls=[]
        async def collector(url,*args):
            calls.append(url)
            raise TimeoutError('public host unavailable')
        now=datetime(2026,10,9,tzinfo=timezone.utc)
        request=SearchRequest(origin='coimbatore-bus',destination='bengaluru-bus',depart_after=now)
        pairs=[(TERMINALS['coimbatore-bus'],TERMINALS['bengaluru-bus']),
               (TERMINALS['ernakulam-bus'],TERMINALS['bengaluru-bus'])]
        result=await KSRTCProvider(collector=collector).search(request,pairs,now)
        self.assertEqual('error',result.status)
        self.assertEqual(1,len(calls))
