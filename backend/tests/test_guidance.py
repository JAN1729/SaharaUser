import unittest
from datetime import datetime, timedelta, timezone

from sahara.guidance import build_guidance


IST = timezone(timedelta(hours=5, minutes=30))
NOW = datetime(2026, 10, 10, 8, 0, tzinfo=IST)


def _leg(leg_id, origin, destination, departure, arrival, **extra):
    value = {
        "id": leg_id,
        "provider": "test",
        "service_id": leg_id,
        "name": leg_id,
        "mode": "train",
        "origin": origin,
        "destination": destination,
        "departure": departure.isoformat(),
        "arrival": arrival.isoformat(),
        "observation": {
            "provider": "test",
            "evidence": "scheduled",
            "fetched_at": NOW.isoformat(),
        },
    }
    value.update(extra)
    return value


def _record(*legs, data_mode="live", completed=None, onboard=None, connections=None):
    return {
        "id": "search-row",
        "request": {
            "origin": "kty",
            "destination": "sbc",
            "data_mode": data_mode,
        },
        "journey": {
            "id": "journey-1",
            "legs": list(legs),
            "connections": list(connections or []),
        },
        "providers": [],
        "progress": {
            "completed_leg_ids": list(completed or []),
            "onboard_leg_id": onboard,
        },
    }


class GuidanceTests(unittest.TestCase):
    def setUp(self):
        self.first = _leg(
            "l1",
            "kty",
            "ers",
            NOW + timedelta(hours=1),
            NOW + timedelta(hours=3),
        )
        self.second = _leg(
            "l2",
            "ers",
            "sbc",
            NOW + timedelta(hours=3, minutes=45),
            NOW + timedelta(hours=6),
        )
        self.transfer = {
            "kind": "transfer",
            "origin": "ers",
            "destination": "ers",
            "available_minutes": 45,
            "transfer_minutes": 10,
            "processing_minutes": 0,
            "buffer_minutes": 15,
            "spare_minutes": 20,
        }

    def test_future_journey_has_approximate_boarding_without_exact_claims(self):
        result = build_guidance(
            _record(self.first, self.second, connections=[self.transfer]),
            NOW,
        )

        self.assertEqual("journey-1", result["journey_id"])
        self.assertEqual("ready", result["status"])
        self.assertEqual(0, result["current_leg_index"])
        self.assertEqual("approximate", result["boarding"]["precision"])
        self.assertTrue(result["boarding"]["approximate"])
        self.assertEqual("approximate_seed_terminal", result["boarding"]["evidence"])
        self.assertIn("exact boarding point", result["boarding"]["instructions"])
        self.assertNotIn("platform", result["boarding"].keys())
        self.assertFalse(result["requires_progress"])

    def test_clock_does_not_confirm_boarding_but_explicit_progress_advances(self):
        after_departure = NOW + timedelta(hours=1, minutes=5)
        no_progress = build_guidance(
            _record(self.first, self.second, connections=[self.transfer]),
            after_departure,
        )
        self.assertEqual("needs_progress", no_progress["status"])
        self.assertTrue(no_progress["requires_progress"])
        self.assertEqual(0, no_progress["current_leg_index"])
        self.assertTrue(any("clock alone" in alert for alert in no_progress["alerts"]))

        explicit = build_guidance(
            _record(
                self.first,
                self.second,
                completed=["l1"],
                connections=[self.transfer],
            ),
            after_departure,
        )
        self.assertEqual(1, explicit["current_leg_index"])
        self.assertEqual("waiting", explicit["status"])
        self.assertFalse(explicit["requires_progress"])

    def test_completed_journey_has_no_upcoming_boarding(self):
        result = build_guidance(
            _record(
                self.first,
                self.second,
                completed=["l1", "l2"],
                connections=[self.transfer],
            ),
            NOW + timedelta(hours=7),
        )

        self.assertEqual("complete", result["status"])
        self.assertEqual(2, result["current_leg_index"])
        self.assertIsNone(result["boarding"])

    def test_fresh_verified_arrival_detects_a_missed_connection(self):
        now = NOW + timedelta(hours=1, minutes=30)
        result = build_guidance(
            _record(
                self.first,
                self.second,
                onboard="l1",
                connections=[self.transfer],
            ),
            now,
            fleet_observations={
                "l1": {
                    "vehicle_id": "train-1",
                    "lat": 9.7,
                    "lng": 76.4,
                    "observed_at": now.isoformat(),
                    "accuracy_m": 20,
                    "verified": True,
                    "estimated_arrival": (NOW + timedelta(hours=4)).isoformat(),
                }
            },
        )

        self.assertEqual("missed", result["status"])
        self.assertTrue(result["requires_progress"])
        self.assertEqual("verified_estimate", result["next_connection"]["timing_basis"])
        self.assertFalse(result["next_connection"]["feasible"])
        self.assertLess(result["next_connection"]["spare_minutes"], 0)
        self.assertEqual("train-1", result["vehicles"][0]["vehicle_id"])

    def test_stale_or_unusable_gps_never_creates_a_vehicle_or_approach_eta(self):
        stale = NOW - timedelta(seconds=91)
        for observation in (
            {
                "vehicle_id": "stale",
                "lat": 9.7,
                "lng": 76.4,
                "observed_at": stale.isoformat(),
                "accuracy_m": 10,
                "verified": True,
                "eta_to_boarding_minutes": 4,
            },
            {
                "vehicle_id": "future",
                "lat": 9.7,
                "lng": 76.4,
                "observed_at": (NOW + timedelta(seconds=16)).isoformat(),
                "accuracy_m": 10,
                "verified": True,
                "eta_to_boarding_minutes": 4,
            },
            {
                "vehicle_id": "bad-accuracy",
                "lat": 9.7,
                "lng": 76.4,
                "observed_at": NOW.isoformat(),
                "accuracy_m": 51,
                "verified": True,
                "eta_to_boarding_minutes": 4,
            },
            {
                "vehicle_id": "bad-coordinates",
                "lat": 91,
                "lng": 76.4,
                "observed_at": NOW.isoformat(),
                "accuracy_m": 10,
                "verified": True,
                "eta_to_boarding_minutes": 4,
            },
        ):
            with self.subTest(vehicle=observation["vehicle_id"]):
                result = build_guidance(
                    _record(self.first, self.second, connections=[self.transfer]),
                    NOW,
                    fleet_observations={"l1": observation},
                )
                self.assertEqual([], result["vehicles"])
                self.assertNotIn("approach_eta_minutes", result["boarding"])
                self.assertFalse(any("fresh verified position" in alert for alert in result["alerts"]))

    def test_scheduled_connection_is_explicitly_unverified_without_live_arrival(self):
        result = build_guidance(
            _record(
                self.first,
                self.second,
                onboard="l1",
                connections=[self.transfer],
            ),
            NOW + timedelta(hours=1, minutes=30),
        )

        connection = result["next_connection"]
        self.assertEqual("unverified", connection["status"])
        self.assertFalse(connection["verified_arrival"])
        self.assertEqual("scheduled", connection["timing_basis"])
        self.assertEqual(20, connection["spare_minutes"])
        self.assertTrue(any("scheduled arrival" in alert for alert in result["alerts"]))

    def test_missing_cross_terminal_transfer_cannot_become_zero_minute_feasible(self):
        cross_terminal_leg = _leg(
            "l2",
            "kty",
            "sbc",
            NOW + timedelta(hours=3, minutes=45),
            NOW + timedelta(hours=6),
        )
        result = build_guidance(
            _record(
                self.first,
                cross_terminal_leg,
                onboard="l1",
                connections=[],
            ),
            NOW + timedelta(hours=1, minutes=30),
        )

        connection = result["next_connection"]
        self.assertEqual("unverified", connection["status"])
        self.assertFalse(connection["feasible"])
        self.assertFalse(connection["can_make"])
        self.assertIsNone(connection["travel_minutes"])
        self.assertIsNone(connection["spare_minutes"])
        self.assertEqual("missing_transfer_record", connection["timing_basis"])
        self.assertTrue(any("No verified transfer record" in alert for alert in result["alerts"]))

    def test_missing_cross_terminal_access_is_unverified_and_not_feasible(self):
        first_from_other_terminal = _leg(
            "l1",
            "ers",
            "sbc",
            NOW + timedelta(hours=1),
            NOW + timedelta(hours=3),
        )
        result = build_guidance(
            _record(first_from_other_terminal, connections=[]),
            NOW,
        )

        connection = result["next_connection"]
        self.assertEqual("access", connection["kind"])
        self.assertFalse(connection["can_make"])
        self.assertEqual("unverified", connection["status"])
        self.assertEqual("missing_transfer_record", connection["timing_basis"])

    def test_elapsed_verified_arrival_is_rejected_and_schedule_remains_unverified(self):
        now = NOW + timedelta(hours=1, minutes=30)
        result = build_guidance(
            _record(
                self.first,
                self.second,
                onboard="l1",
                connections=[self.transfer],
            ),
            now,
            fleet_observations={
                "l1": {
                    "vehicle_id": "train-1",
                    "lat": 9.7,
                    "lng": 76.4,
                    "observed_at": now.isoformat(),
                    "accuracy_m": 20,
                    "verified": True,
                    "estimated_arrival": (NOW - timedelta(minutes=1)).isoformat(),
                }
            },
        )

        self.assertNotIn("estimated_arrival", result["vehicles"][0])
        self.assertFalse(result["next_connection"]["verified_arrival"])
        self.assertEqual("scheduled", result["next_connection"]["timing_basis"])
        self.assertEqual("unverified", result["next_connection"]["status"])
        self.assertTrue(any("chronology had already elapsed" in alert for alert in result["alerts"]))

    def test_verified_driver_position_without_eta_does_not_invent_approach(self):
        result = build_guidance(
            _record(self.first, self.second, connections=[self.transfer]),
            NOW,
            fleet_observations={
                "l1": {
                    "vehicle_id": "train-1",
                    "lat": 9.7,
                    "lng": 76.4,
                    "observed_at": NOW.isoformat(),
                    "accuracy_m": 20,
                    "verified": True,
                }
            },
        )

        self.assertTrue(result["vehicles"][0]["source_verified"])
        self.assertNotIn("eta_to_boarding_minutes", result["vehicles"][0])
        self.assertNotIn("approaching", result["vehicles"][0])
        self.assertNotIn("approach_eta_minutes", result["boarding"])

    def test_fresh_fleet_fix_shows_location_but_withholds_unverified_eta_and_boarding(self):
        result = build_guidance(
            _record(self.first, self.second, connections=[self.transfer]),
            NOW,
            fleet_observations={
                "l1": {
                    "vehicle_id": "train-1",
                    "lat": 9.7,
                    "lng": 76.4,
                    "observed_at": NOW.isoformat(),
                    "accuracy_m": 20,
                    "eta_to_boarding_minutes": 8,
                    "boarding_precision": "exact",
                    "boarding_instructions": "Platform 2",
                }
            },
        )

        self.assertEqual("train-1", result["vehicles"][0]["vehicle_id"])
        self.assertEqual(9.7, result["vehicles"][0]["lat"])
        self.assertNotIn("approach_eta_minutes", result["boarding"])
        self.assertNotIn("eta_to_boarding_minutes", result["vehicles"][0])
        self.assertNotIn("boarding_precision", result["vehicles"][0])
        self.assertEqual("approximate", result["boarding"]["precision"])
        self.assertTrue(any("withheld" in alert for alert in result["alerts"]))

    def test_naive_driver_timestamp_is_rejected_without_inventing_utc(self):
        result = build_guidance(
            _record(self.first, self.second, connections=[self.transfer]),
            NOW,
            fleet_observations={
                "l1": {
                    "vehicle_id": "naive-time",
                    "lat": 9.7,
                    "lng": 76.4,
                    "observed_at": NOW.replace(tzinfo=None).isoformat(),
                    "accuracy_m": 20,
                    "eta_to_boarding_minutes": 4,
                    "estimated_arrival": (NOW + timedelta(hours=4)).isoformat(),
                    "verified": True,
                }
            },
        )

        self.assertEqual([], result["vehicles"])
        self.assertNotIn("approach_eta_minutes", result["boarding"])
        self.assertTrue(any("stale or from the future" in alert for alert in result["alerts"]))

    def test_sample_label_is_preserved_and_no_vehicle_is_invented(self):
        result = build_guidance(
            _record(self.first, data_mode="sample"),
            NOW,
        )

        self.assertEqual("sample", result["data_mode"])
        self.assertEqual([], result["vehicles"])
        self.assertTrue(any("sample journey data" in alert for alert in result["alerts"]))


if __name__ == "__main__":
    unittest.main()
