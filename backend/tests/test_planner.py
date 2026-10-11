import unittest
from datetime import datetime, timedelta, timezone

NOW = datetime(2026, 10, 9, 0, tzinfo=timezone.utc)
DAY = datetime(2026, 10, 10, 0, tzinfo=timezone.utc)


class PlannerTests(unittest.TestCase):
    def setUp(self):
        try:
            from sahara.domain import SearchRequest, Leg, Observation, Terminal, Transfer
            from sahara.planner import plan
        except ImportError:
            self.fail('The normalized connection planner has not been implemented')
        self.Request, self.Leg, self.Observation = SearchRequest, Leg, Observation
        self.Terminal, self.Transfer, self.plan = Terminal, Transfer, plan
        self.terminals = {key: Terminal(id=key, name=key, city=key, lat=10, lng=76)
                          for key in ('a', 'b', 'c', 'b-bus', 'airport')}
        self.transfers = [Transfer(origin='b', destination='b-bus', minutes=40,
                                   fare_minor=5000, evidence='estimated')]

    def request(self, **changes):
        return self.Request(origin='a', destination='c', depart_after=DAY,
                            arrive_by=DAY + timedelta(hours=24), **changes)

    def leg(self, id='one', origin='a', destination='c', depart=6, arrive=9, **changes):
        values = dict(id=id, provider='test', service_id=id, name=id, mode='train',
                      origin=origin, destination=destination,
                      departure=DAY + timedelta(hours=depart), arrival=DAY + timedelta(hours=arrive),
                      fare_minor=20000, availability='available', seats=3,
                      observation=self.Observation(provider='test', evidence='live',
                          fetched_at=NOW, observed_at=NOW, expires_at=NOW + timedelta(minutes=15)))
        values.update(changes)
        return self.Leg(**values)

    def run_plan(self, legs, request=None, transfers=None):
        return self.plan(request or self.request(), legs, self.terminals,
                         self.transfers if transfers is None else transfers, NOW)

    def test_direct_route_has_door_to_door_elapsed_time(self):
        result = self.run_plan([self.leg()])
        self.assertEqual(180, result.journeys[0].duration_minutes)
        self.assertEqual(20000, result.journeys[0].total_fare_minor)

    def test_same_service_with_different_access_origin_cannot_overwrite_another_journey_map(self):
        legs = [self.leg()]
        direct = self.run_plan(legs).journeys[0]
        access = self.run_plan(legs, self.Request(origin='b', destination='c', depart_after=DAY),
            [self.Transfer(origin='b', destination='a', minutes=30, fare_minor=1000)]).journeys[0]
        self.assertEqual('b', access.connections[0].origin)
        self.assertNotEqual(direct.id, access.id)

    def test_group_size_is_part_of_stored_journey_identity(self):
        one = self.run_plan([self.leg()]).journeys[0]
        group = self.run_plan([self.leg()], self.request(passengers=2)).journeys[0]
        self.assertNotEqual(one.total_fare_minor, group.total_fare_minor)
        self.assertNotEqual(one.id, group.id)

    def test_arrival_after_onward_departure_is_rejected(self):
        result = self.run_plan([self.leg(destination='b', arrive=18),
                               self.leg('two', origin='b', depart=17, arrive=21)])
        self.assertEqual([], result.journeys)

    def test_next_day_connection_includes_real_waiting(self):
        result = self.run_plan([self.leg(destination='b', depart=20, arrive=25),
                               self.leg('two', origin='b', depart=27, arrive=29)],
                              self.Request(origin='a', destination='c', depart_after=DAY,
                                           arrive_by=DAY+timedelta(hours=48)))
        self.assertEqual(540, result.journeys[0].duration_minutes)
        self.assertEqual(120, result.journeys[0].connections[0].available_minutes)

    def test_different_terminals_require_transfer_and_buffer(self):
        result = self.run_plan([self.leg(destination='b', arrive=10),
                               self.leg('two', origin='b-bus', mode='bus', depart=10.5, arrive=12)])
        self.assertEqual([], result.journeys)

    def test_transfer_cost_is_part_of_total(self):
        result = self.run_plan([self.leg(destination='b', arrive=10),
                               self.leg('two', origin='b-bus', mode='bus', depart=12, arrive=14)])
        self.assertEqual(45000, result.journeys[0].total_fare_minor)
        self.assertIn('estimated_transfer', result.journeys[0].warnings)

    def test_flight_requires_airport_processing(self):
        result = self.run_plan([self.leg(destination='airport', arrive=10),
                               self.leg('flight', origin='airport', mode='flight', depart=11, arrive=13)])
        self.assertEqual([], result.journeys)

    def test_waitlisted_is_not_available(self):
        self.assertEqual([], self.run_plan([self.leg(availability='waitlisted')]).journeys)

    def test_unknown_seats_not_available(self):
        self.assertEqual([], self.run_plan([self.leg(availability='unknown', seats=None)]).journeys)

    def test_sold_out_is_never_a_candidate_even_when_tentative_options_allowed(self):
        self.assertEqual([],self.run_plan([self.leg(availability='sold_out',seats=None)],self.request(confirmed_only=False)).journeys)

    def test_insufficient_seats_rejected_for_group(self):
        self.assertEqual([], self.run_plan([self.leg(seats=1)], self.request(passengers=2)).journeys)

    def test_zero_seats_overrides_available_label(self):
        self.assertEqual([], self.run_plan([self.leg(seats=0)]).journeys)

    def test_cancelled_service_is_rejected(self):
        self.assertEqual([], self.run_plan([self.leg(cancelled=True)]).journeys)

    def test_expired_offer_is_rejected(self):
        observation = self.Observation(provider='test', evidence='live', fetched_at=NOW,
                                       expires_at=NOW-timedelta(seconds=1))
        self.assertEqual([], self.run_plan([self.leg(observation=observation)]).journeys)

    def test_old_upstream_observation_cannot_be_freshened_by_fetch(self):
        observation = self.Observation(provider='test', evidence='live', fetched_at=NOW,
                                       observed_at=NOW-timedelta(hours=2))
        self.assertEqual([], self.run_plan([self.leg(observation=observation)]).journeys)

    def test_unknown_fare_cannot_win_cheapest(self):
        result = self.run_plan([self.leg('unknown', fare_minor=None), self.leg('known', fare_minor=30000)])
        cheapest = next(j for j in result.journeys if 'lowest_cost' in j.recommendations)
        self.assertEqual(30000, cheapest.total_fare_minor)
        self.assertIsNone(next(j for j in result.journeys if j.legs[0].id=='unknown').total_fare_minor)

    def test_budget_excludes_unknown_total(self):
        self.assertEqual([], self.run_plan([self.leg(fare_minor=None)], self.request(budget_minor=50000)).journeys)

    def test_party_fare_is_not_single_ticket_fare(self):
        result = self.run_plan([self.leg()], self.request(passengers=2))
        self.assertEqual(40000, result.journeys[0].total_fare_minor)

    def test_arrive_by_deadline(self):
        request = self.Request(origin='a', destination='c', depart_after=DAY, arrive_by=DAY+timedelta(hours=8))
        self.assertEqual([], self.run_plan([self.leg()], request).journeys)

    def test_departed_service_is_not_recommended(self):
        leg = self.leg(departure=NOW-timedelta(hours=1), arrival=NOW+timedelta(hours=1))
        self.assertEqual([], self.run_plan([leg], self.request(data_mode='live')).journeys)

    def test_sample_services_do_not_leak_into_live_mode(self):
        leg = self.leg(observation=self.Observation(provider='sample', evidence='fixture', fetched_at=NOW))
        self.assertEqual([], self.run_plan([leg], self.request(data_mode='live')).journeys)

    def test_delay_changes_downstream_feasibility(self):
        first = self.leg(destination='b', arrive=10)
        second = self.leg('two', origin='b', depart=11, arrive=13)
        self.assertEqual(1, len(self.run_plan([first, second]).journeys))
        first = first.model_copy(update={'estimated_arrival': DAY+timedelta(hours=11)})
        self.assertEqual([], self.run_plan([first, second]).journeys)

    def test_direct_flight_origin_needs_predeparture_processing(self):
        request = self.Request(origin='airport', destination='c', depart_after=DAY+timedelta(hours=6),
                               arrive_by=DAY+timedelta(hours=12))
        self.assertEqual([], self.run_plan([self.leg(origin='airport', mode='flight', depart=7)], request).journeys)

    def test_unknown_terminal_does_not_become_a_zero_time_transfer(self):
        self.assertEqual([], self.run_plan([self.leg(destination='missing')]).journeys)

    def test_accessibility_unknown_is_reported(self):
        result = self.run_plan([self.leg()], self.request(wheelchair=True, confirmed_only=False))
        self.assertIn('accessibility_unverified', result.journeys[0].warnings)

    def test_last_mile_transfer_is_included_in_arrival_and_cost(self):
        transfers=[self.Transfer(origin='b',destination='c',minutes=40,fare_minor=5000)]
        result=self.run_plan([self.leg(destination='b',arrive=9)],transfers=transfers)
        self.assertTrue(result.journeys)
        self.assertEqual(220,result.journeys[0].duration_minutes)
        self.assertEqual(25000,result.journeys[0].total_fare_minor)

    def test_last_mile_cannot_break_arrival_deadline(self):
        transfers=[self.Transfer(origin='b',destination='c',minutes=40,fare_minor=5000)]
        request=self.Request(origin='a',destination='c',depart_after=DAY,arrive_by=DAY+timedelta(hours=9.5))
        self.assertEqual([],self.run_plan([self.leg(destination='b',arrive=9)],request,transfers).journeys)

    def test_planner_does_not_recommend_returning_to_start(self):
        transfers=[self.Transfer(origin='a',destination='b',minutes=20,fare_minor=0)]
        result=self.run_plan([self.leg('loop',origin='b',destination='a',depart=1,arrive=2),self.leg()],transfers=transfers)
        self.assertEqual(1,len(result.journeys))
        self.assertEqual(['one'],[leg.id for leg in result.journeys[0].legs])

    def test_cached_inventory_without_upstream_age_is_disclosed(self):
        observation=self.Observation(provider='test',evidence='cached',fetched_at=NOW,expires_at=NOW+timedelta(minutes=5))
        result=self.run_plan([self.leg(observation=observation)])
        self.assertIn('upstream_age_unknown',result.journeys[0].warnings)

    def test_invalid_date_order_and_naive_times_rejected(self):
        from pydantic import ValidationError
        with self.assertRaises(ValidationError):
            self.Request(origin='a', destination='c', depart_after=DAY, arrive_by=DAY-timedelta(hours=1))
        with self.assertRaises(ValidationError):
            self.leg(departure=datetime(2026,10,10,6))


if __name__ == '__main__':
    unittest.main()
