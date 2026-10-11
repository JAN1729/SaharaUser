import unittest
import json
from pathlib import Path
from datetime import datetime,date,timezone,timedelta

NOW=datetime(2026,10,10,6,17,tzinfo=timezone.utc)
DAY=date(2026,10,15)


class OperatorInventoryTests(unittest.TestCase):
    def test_actual_public_webpage_rows_preserve_route_code_duration_and_booking_window(self):
        from sahara.providers.ksrtc import parse_ksrtc
        html=(Path(__file__).parent/'fixtures/ksrtc-public-dom-2026-10-15.html').read_text(encoding='utf8')
        legs=parse_ksrtc(html,'kozhikode-bus','bengaluru-bus',DAY,NOW,'https://onlineksrtcswift.com/search')
        self.assertEqual(3,len(legs))
        direct=next(l for l in legs if '0700KKDBNG' in l.name)
        self.assertEqual(45200,direct.fare_minor)
        self.assertEqual(timedelta(hours=8,minutes=45),direct.arrival-direct.departure)
        overnight=next(l for l in legs if '2200KKDBNG' in l.name)
        self.assertEqual(16,overnight.arrival.day)
        restricted=next(l for l in legs if '1805EKMBNG' in l.name)
        self.assertEqual(14,restricted.booking_opens_at.day)

    def test_explicit_string_boolean_and_currency_are_normalized(self):
        row=dict(self.rows()[0],IsCancelled='false',DefaultCurrencyID='1')
        self.assertFalse(self.parse([row])[0].cancelled)
        self.assertTrue(self.parse([dict(row,IsCancelled='true')])[0].cancelled)
        self.assertEqual([],self.parse([dict(row,IsCancelled='unrecognized')]))

    def rows(self):
        return json.loads((Path(__file__).parent/'fixtures/ksrtc-operator-2026-10-15.json').read_text(encoding='utf-8'))['rows']

    def parse(self,rows=None):
        try:
            from sahara.providers.ksrtc import parse_ksrtc_json
        except ImportError:
            self.fail('The observed public operator JSON is not normalized yet')
        return parse_ksrtc_json(self.rows() if rows is None else rows,'kozhikode-bus','bengaluru-bus',DAY,NOW,'https://assets.onlineksrtcswift.com/api/resource/searchRoutesV4')

    def test_operator_snapshot_keeps_explicit_dates_fare_seats_and_booking_window(self):
        legs=self.parse()
        self.assertEqual(3,len(legs))
        direct=next(l for l in legs if '2200KKDBNG' in l.name)
        self.assertEqual((55300,36,15,16),(direct.fare_minor,direct.seats,direct.departure.day,direct.arrival.day))
        restricted=next(l for l in legs if '1805EKMBNG' in l.name)
        self.assertEqual(15,restricted.departure.day)
        self.assertEqual(14,restricted.booking_opens_at.day)
        self.assertEqual('cached',restricted.observation.evidence)

    def test_wrong_route_day_currency_and_unknown_seats_are_not_fabricated(self):
        row=dict(self.rows()[0])
        for update in [{'FromCityID':443},{'JourneyDate':'2026-10-16'},{'DefaultCurrencyID':2}]:
            self.assertEqual([],self.parse([dict(row,**update)]))
        unknown=self.parse([dict(row,AvailableSeats=None)])[0]
        self.assertEqual('unknown',unknown.availability)
        self.assertIsNone(unknown.seats)

    def test_future_booking_window_excludes_visible_seats_from_bookable_results(self):
        from sahara.domain import SearchRequest
        from sahara.places import TERMINALS,transfer_catalog
        from sahara.planner import plan
        request=SearchRequest(origin='kozhikode-bus',destination='bengaluru-bus',depart_after=datetime(2026,10,15,tzinfo=timezone(timedelta(hours=5,minutes=30))),data_mode='live')
        result=plan(request,self.parse(),TERMINALS,transfer_catalog(),NOW)
        self.assertEqual(1,len(result.journeys))
        self.assertEqual(2,len([r for r in result.rejected if r.reason=='booking_not_open']))

    def test_current_html_duration_and_berth_count_are_understood(self):
        from sahara.providers.parsing import duration_minutes,normalize_availability
        self.assertEqual(465,duration_minutes('7:45 HRS'))
        self.assertEqual(480,duration_minutes('8:0 HRS'))
        self.assertEqual(('available',47),normalize_availability('47 Berths Left'))
        self.assertEqual(('sold_out',0),normalize_availability('0 Berths Left'))
