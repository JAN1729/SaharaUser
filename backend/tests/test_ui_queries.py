import unittest
from datetime import datetime
from zoneinfo import ZoneInfo
from sahara.interpret import explicit_constraints, interpret


class UiQueryTests(unittest.TestCase):
    def test_trailing_preferences_are_not_parsed_as_part_of_destination(self):
        for suffix,preference in [('comfortable','comfortable'),('cheapest','cheapest'),('fastest','fastest')]:
            parsed=interpret('Kottayam to Bengaluru '+suffix)
            self.assertEqual('sbc',parsed.destination)
            self.assertEqual(preference,parsed.preference)
        self.assertEqual(['bus'],interpret('Kottayam to Bengaluru only buses').modes)
        self.assertEqual('comfortable',interpret('Kottayam to Bengaluru, comfortable').preference)
    def test_comfort_walking_and_connection_preferences_survive_basic_parser(self):
        parsed=interpret('Kottayam to Bengaluru tomorrow, comfortable, at most 1 transfer, walk under 500 m')
        self.assertEqual('comfortable',parsed.preference)
        self.assertEqual(1,parsed.max_transfers)
        self.assertEqual(500,parsed.max_walk_m)
        self.assertIsNone(parsed.budget_minor)
    def test_lowest_fare_suggestion_selects_cheapest_in_demo_and_model_constraints(self):
        query = 'Kottayam to Bengaluru tomorrow, find the lowest fare'
        parsed = interpret(query, now=datetime(2026, 10, 10, tzinfo=ZoneInfo('Asia/Kolkata')))
        self.assertEqual('cheapest', parsed.preference)
        self.assertEqual('cheapest', explicit_constraints(query)['preference'])

    def test_evening_suggestion_preserves_arrival_deadline(self):
        parsed = interpret('Kottayam to Bengaluru tomorrow, arrive before 8 pm',
            now=datetime(2026, 10, 10, tzinfo=ZoneInfo('Asia/Kolkata')))
        self.assertEqual('2026-10-11T20:00:00+05:30', parsed.arrive_by.isoformat())
