"""Pure passenger guidance for a stored Sahara journey.

The planning service owns journey persistence and revalidation.  This module
only turns one stored journey snapshot into a small, safe guidance payload.
It deliberately has no provider, database, clock, or network dependencies:
``now`` and optional driver observations are supplied by the caller.

Timetables are useful for planning, but they are not vehicle positions.  A
vehicle location is returned only when its observation has a usable coordinate,
an accuracy of at most 50 metres, and a timezone-aware observation timestamp
within the small freshness window below.  ETA and estimated-arrival claims are
accepted only with an explicit source verification marker.  An optional
verification marker also controls whether exact boarding instructions may be
surfaced; a fresh coordinate alone is only a reported location.
"""

from __future__ import annotations

import math
from datetime import datetime, timedelta, timezone
from typing import Any, Mapping

from .places import TERMINALS


_MAX_OBSERVATION_AGE = timedelta(seconds=90)
_MAX_FUTURE_SKEW = timedelta(seconds=15)
_MAX_GPS_ACCURACY_M = 50.0
_WAITING_WINDOW = timedelta(minutes=30)
_READY_WINDOW = timedelta(hours=2)


def build_guidance(
    record: dict,
    now: datetime,
    fleet_observations: dict[str, dict] | None = None,
) -> dict:
    """Build a passenger guidance payload from a stored journey snapshot.

    ``record`` is normally the journey row produced by ``Repository``::

        {
            "request": {"data_mode": "sample" | "live", ...},
            "journey": {"id": "...", "legs": [...], "connections": [...]},
            "providers": [...],
            "progress": {"completed_leg_ids": [], "onboard_leg_id": None},
        }

    The function does not mutate ``record`` or ``fleet_observations``.  It
    reports an invalid progress prefix as ``needs_progress`` rather than
    raising, so an API caller can still show the user what needs confirmation.
    """

    current_time = _aware(now)
    request = record.get("request") if isinstance(record, Mapping) else {}
    request = request if isinstance(request, Mapping) else {}
    data_mode = str(request.get("data_mode") or "sample")

    journey = record.get("journey") if isinstance(record, Mapping) else {}
    journey = journey if isinstance(journey, Mapping) else {}
    journey_id = (
        journey.get("id")
        or record.get("journey_id")
        or record.get("id")
        or ""
    )
    journey_id = str(journey_id)

    legs = [leg for leg in journey.get("legs", []) if isinstance(leg, Mapping)]
    alerts: list[str] = []
    vehicles: list[dict[str, Any]] = []
    valid_fleet: dict[str, dict[str, Any]] = {}

    if fleet_observations:
        valid_fleet, vehicles = _validated_fleet(
            fleet_observations, legs, current_time, alerts
        )

    if data_mode == "sample":
        alerts.append(
            "This is sample journey data; schedules and fares are illustrative, and no live vehicle position is implied."
        )

    if not legs:
        alerts.append("This journey has no usable service legs yet.")
        return {
            "journey_id": journey_id,
            "data_mode": data_mode,
            "status": "unverified",
            "updated_at": _iso(current_time),
            "current_leg_index": 0,
            "boarding": None,
            "next_connection": None,
            "alerts": _unique(alerts),
            "vehicles": vehicles,
            "requires_progress": False,
        }

    leg_ids = [str(leg.get("id") or f"leg-{index}") for index, leg in enumerate(legs)]
    completed, onboard, progress_valid, progress_alerts = _progress_state(
        record.get("progress") if isinstance(record, Mapping) else None,
        leg_ids,
    )
    alerts.extend(progress_alerts)

    arrival_info = _arrival_information(legs, current_time, valid_fleet)

    # ``current_leg_index`` is the passenger-confirmed leg.  Time alone never
    # advances this index; an overdue unconfirmed leg instead requests input.
    if onboard is not None and onboard in leg_ids:
        current_index = leg_ids.index(onboard)
    else:
        current_index = min(len(completed), len(legs))

    if len(completed) == len(legs) and onboard is None:
        # There is no future boarding action after the passenger has explicitly
        # completed every leg.  Returning the last origin as "upcoming" would
        # invite the client to show stale boarding instructions.
        boarding = None
    else:
        boarding_index = min(current_index, len(legs) - 1)
        boarding_leg = legs[boarding_index]
        boarding = _boarding_payload(
            boarding_leg,
            current_index=boarding_index,
            onboard=onboard == leg_ids[boarding_index],
            fleet=valid_fleet.get(leg_ids[boarding_index]),
            alerts=alerts,
        )

    # Identify the next meaningful access/transfer connection.  For a journey
    # already in progress, the connection is anchored to the confirmed leg;
    # before departure, the access connection explains getting to leg zero.
    connection = None
    connection_anchor = None
    if current_index < len(legs):
        if onboard is not None and onboard in leg_ids:
            connection_anchor = current_index
        elif completed:
            connection_anchor = len(completed) - 1
        else:
            connection_anchor = -1
        connection = _next_connection(
            journey,
            legs,
            connection_anchor,
            current_time,
            arrival_info,
            request_origin=str(request.get("origin") or ""),
        )

    connection_missed = bool(connection and connection.get("status") == "missed")
    if connection_missed:
        alerts.append(str(connection.get("message") or "The next connection cannot be made in the available time."))
    if connection and connection.get("timing_basis") == "missing_transfer_record":
        alerts.append(str(connection.get("message") or "The transfer record is missing, so connection feasibility cannot be confirmed."))

    first_start = _datetime(legs[0].get("departure"))
    if first_start is None:
        alerts.append("The first service has no valid scheduled departure.")

    status = _status_for(
        legs=legs,
        leg_ids=leg_ids,
        completed=completed,
        onboard=onboard,
        progress_valid=progress_valid,
        current_index=current_index,
        now=current_time,
        arrival_info=arrival_info,
        connection_missed=connection_missed,
    )

    requires_progress = not progress_valid
    if status in {"needs_progress", "missed"}:
        requires_progress = True
    if status == "needs_progress":
        alerts.append(
            "Tell Sahara which legs you completed and which service you boarded; the clock alone does not confirm boarding."
        )

    # A live timetable can be perfectly useful while still lacking a verified
    # vehicle feed.  Keep that distinction visible beside the schedule.
    if data_mode == "live" and not vehicles:
        alerts.append(
            "No fresh verified vehicle position is available; scheduled times are not live vehicle tracking."
        )

    if connection and not connection.get("verified_arrival", False):
        alerts.append(
            "The next connection is checked against the scheduled arrival; no fresh verified arrival estimate is available."
        )

    # Provider messages are source context, never substitutes for a vehicle
    # observation.  Include only useful failure/partial notices and keep them
    # as plain human sentences.
    providers = record.get("providers", []) if isinstance(record, Mapping) else []
    if not isinstance(providers, list):
        providers = []
    for provider in providers:
        if not isinstance(provider, Mapping):
            continue
        provider_status = str(provider.get("status") or "")
        if provider_status and provider_status not in {"ok", "empty"}:
            name = str(provider.get("provider") or "a source")
            message = str(provider.get("message") or "").strip()
            alerts.append(
                f"{name} source coverage is {provider_status.replace('_', ' ')}{': ' + message if message else '.'}"
            )

    return {
        "journey_id": journey_id,
        "data_mode": data_mode,
        "status": status,
        "updated_at": _iso(current_time),
        "current_leg_index": current_index,
        "boarding": boarding,
        "next_connection": connection,
        "alerts": _unique(alerts),
        "vehicles": vehicles,
        "requires_progress": requires_progress,
    }


def _aware(value: datetime) -> datetime:
    """Return an aware datetime without making a naive caller fail silently."""

    if value.tzinfo is None or value.utcoffset() is None:
        return value.replace(tzinfo=timezone.utc)
    return value


def _datetime(value: Any) -> datetime | None:
    if isinstance(value, datetime):
        return _aware(value)
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        return _aware(datetime.fromisoformat(value.replace("Z", "+00:00")))
    except (TypeError, ValueError, OverflowError):
        return None


def _driver_datetime(value: Any) -> datetime | None:
    """Parse driver timestamps strictly; naive values have no safe timezone."""

    if isinstance(value, datetime):
        if value.tzinfo is None or value.utcoffset() is None:
            return None
        return value
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (TypeError, ValueError, OverflowError):
        return None
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        return None
    return parsed


def _iso(value: datetime | None) -> str | None:
    return _aware(value).isoformat() if value is not None else None


def _number(value: Any) -> float | None:
    if isinstance(value, bool):
        return None
    try:
        result = float(value)
    except (TypeError, ValueError, OverflowError):
        return None
    return result if math.isfinite(result) else None


def _minutes(delta: timedelta) -> int:
    # Floor makes a small negative margin visible instead of rounding it to a
    # falsely feasible zero-minute spare.
    return math.floor(delta.total_seconds() / 60)


def _unique(messages: list[str]) -> list[str]:
    return list(dict.fromkeys(message for message in messages if message))


def _is_verified(value: Mapping[str, Any]) -> bool:
    """Require an explicit verification marker for live vehicle evidence."""

    for key in ("verified", "source_verified", "verified_source", "location_verified"):
        if value.get(key) is True:
            return True
    source = value.get("source")
    if isinstance(source, Mapping):
        for key in ("verified", "source_verified", "verified_source"):
            if source.get(key) is True:
                return True
    return value.get("verification") == "verified"


def _fresh(timestamp: datetime | None, now: datetime) -> bool:
    if timestamp is None:
        return False
    age = now - timestamp
    return age <= _MAX_OBSERVATION_AGE and age >= -_MAX_FUTURE_SKEW


def _validated_fleet(
    observations: Mapping[str, Any],
    legs: list[Mapping[str, Any]],
    now: datetime,
    alerts: list[str],
) -> tuple[dict[str, dict[str, Any]], list[dict[str, Any]]]:
    leg_ids = {str(leg.get("id")) for leg in legs}
    valid: dict[str, dict[str, Any]] = {}
    vehicles: list[dict[str, Any]] = []

    for raw_leg_id, raw in observations.items():
        leg_id = str(raw_leg_id)
        if leg_id not in leg_ids:
            alerts.append("A vehicle update for an unknown journey leg was ignored.")
            continue
        if not isinstance(raw, Mapping):
            alerts.append(f"The vehicle update for leg {leg_id} was ignored because it was malformed.")
            continue
        observed_at = _driver_datetime(raw.get("observed_at"))
        if not _fresh(observed_at, now):
            alerts.append(f"The vehicle update for leg {leg_id} was ignored because it was stale or from the future.")
            continue

        lat = _number(raw.get("lat"))
        lng = _number(raw.get("lng"))
        accuracy = _number(raw.get("accuracy_m"))
        if (
            lat is None
            or lng is None
            or not -90 <= lat <= 90
            or not -180 <= lng <= 180
        ):
            alerts.append(f"The vehicle update for leg {leg_id} was ignored because its coordinates were invalid.")
            continue
        if accuracy is None or accuracy < 0 or accuracy > _MAX_GPS_ACCURACY_M:
            alerts.append(f"The vehicle update for leg {leg_id} was ignored because its GPS accuracy exceeded 50 metres or was missing.")
            continue

        vehicle_id = str(raw.get("vehicle_id") or "").strip()
        if not vehicle_id:
            alerts.append(f"The vehicle update for leg {leg_id} was ignored because it had no vehicle identifier.")
            continue

        source_verified = _is_verified(raw)
        vehicle: dict[str, Any] = {
            "leg_id": leg_id,
            "vehicle_id": vehicle_id,
            "lat": lat,
            "lng": lng,
            "accuracy_m": accuracy,
            "observed_at": _iso(observed_at),
            "source_verified": source_verified,
            "evidence": "verified_fresh" if source_verified else "fresh_fleet_observation",
        }
        eta = _number(raw.get("eta_to_boarding_minutes"))
        if eta is not None and eta >= 0 and source_verified:
            vehicle["eta_to_boarding_minutes"] = eta if not eta.is_integer() else int(eta)
            vehicle["approaching"] = True
        elif eta is not None and eta >= 0:
            alerts.append(f"The approach ETA for leg {leg_id} was withheld because the source was not verified.")
        estimated_arrival = _driver_datetime(raw.get("estimated_arrival")) if source_verified else None
        chronology_rejected = False
        if (
            estimated_arrival is not None
            and (estimated_arrival <= now or estimated_arrival <= observed_at)
        ):
            alerts.append(f"The arrival estimate for leg {leg_id} was ignored because its chronology had already elapsed.")
            estimated_arrival = None
            chronology_rejected = True
        if estimated_arrival is not None:
            vehicle["estimated_arrival"] = _iso(estimated_arrival)
        elif raw.get("estimated_arrival") is not None and not chronology_rejected:
            if not source_verified:
                alerts.append(f"The arrival estimate for leg {leg_id} was withheld because the source was not verified.")
            else:
                alerts.append(f"The arrival estimate for leg {leg_id} was ignored because its timestamp was missing a timezone or invalid.")
        if raw.get("provider"):
            vehicle["provider"] = str(raw["provider"])

        # These fields can be used for precise boarding help only when the
        # source explicitly vouches for them.  A valid GPS fix alone is not a
        # platform or pickup-point verification.
        if source_verified:
            if raw.get("boarding_precision") is not None:
                vehicle["boarding_precision"] = str(raw["boarding_precision"])
            if raw.get("boarding_instructions") is not None:
                vehicle["boarding_instructions"] = str(raw["boarding_instructions"])
        elif raw.get("boarding_precision") is not None or raw.get("boarding_instructions") is not None:
            alerts.append(f"Exact boarding details for leg {leg_id} were withheld because the source was not verified.")

        valid[leg_id] = {"raw": raw, "vehicle": vehicle, "estimated_arrival": estimated_arrival}
        vehicles.append(vehicle)

    return valid, vehicles


def _progress_state(
    progress: Any,
    leg_ids: list[str],
) -> tuple[list[str], str | None, bool, list[str]]:
    progress = progress if isinstance(progress, Mapping) else {}
    raw_completed = progress.get("completed_leg_ids", [])
    completed = [str(item) for item in raw_completed] if isinstance(raw_completed, list) else []
    onboard_value = progress.get("onboard_leg_id")
    onboard = str(onboard_value) if onboard_value is not None else None
    alerts: list[str] = []
    valid = isinstance(raw_completed, list)

    expected = leg_ids[: len(completed)]
    if completed != expected:
        valid = False
        alerts.append("Saved travel progress is not an ordered prefix of this journey.")
    if onboard is not None:
        if len(completed) >= len(leg_ids) or leg_ids[len(completed)] != onboard:
            valid = False
            alerts.append("Saved onboard progress does not follow the completed legs.")
    if len(set(completed)) != len(completed):
        valid = False
        alerts.append("Saved travel progress contains a repeated leg.")
    if not valid:
        # Retain only the safe prefix for display.  Never let malformed input
        # move the current leg beyond the first unconfirmed service.
        safe_count = 0
        for expected_id, actual_id in zip(leg_ids, completed):
            if expected_id != actual_id:
                break
            safe_count += 1
        completed = leg_ids[:safe_count]
        onboard = None
    return completed, onboard, valid, alerts


def _arrival_information(
    legs: list[Mapping[str, Any]],
    now: datetime,
    fleet: Mapping[str, Mapping[str, Any]],
) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for index, leg in enumerate(legs):
        leg_id = str(leg.get("id") or f"leg-{index}")
        scheduled = _datetime(leg.get("arrival"))
        effective = scheduled
        source = "scheduled"
        verified = False

        fleet_entry = fleet.get(leg_id)
        fleet_estimate = fleet_entry.get("estimated_arrival") if fleet_entry else None
        if fleet_estimate is not None:
            effective = fleet_estimate
            source = "verified_fleet_estimate"
            verified = True
        else:
            estimated = _datetime(leg.get("estimated_arrival"))
            observation = leg.get("observation")
            observation = observation if isinstance(observation, Mapping) else {}
            observed_at = _datetime(observation.get("observed_at"))
            evidence = str(observation.get("evidence") or "")
            if (
                estimated is not None
                and estimated > now
                and observed_at is not None
                and estimated > observed_at
                and _fresh(observed_at, now)
                and evidence in {"live", "cached"}
            ):
                effective = estimated
                source = "verified_source_estimate"
                verified = True

        result[leg_id] = {
            "scheduled": scheduled,
            "effective": effective,
            "source": source,
            "verified": verified,
        }
    return result


def _terminal_payload(place_id: Any) -> dict[str, Any]:
    place_id = str(place_id or "")
    terminal = TERMINALS.get(place_id)
    result: dict[str, Any] = {
        "place_id": place_id,
        "name": terminal.name if terminal else place_id,
        "precision": "approximate",
        "approximate": True,
        "evidence": "approximate_seed_terminal",
        "instructions": "Use this approximate terminal area and verify the exact boarding point with the operator.",
    }
    if terminal:
        result.update({"lat": terminal.lat, "lng": terminal.lng})
    return result


def _boarding_payload(
    leg: Mapping[str, Any],
    current_index: int,
    onboard: bool,
    fleet: Mapping[str, Any] | None,
    alerts: list[str],
) -> dict[str, Any]:
    payload = _terminal_payload(leg.get("origin"))
    payload["leg_id"] = str(leg.get("id") or f"leg-{current_index}")
    payload["mode"] = str(leg.get("mode") or "unknown")
    payload["status"] = "boarded" if onboard else "upcoming"

    source_verified = False
    exact = leg.get("boarding")
    if isinstance(exact, Mapping) and _is_verified(exact):
        source_verified = True
        if exact.get("precision") is not None:
            payload["precision"] = str(exact["precision"])
        if exact.get("instructions") is not None:
            payload["instructions"] = str(exact["instructions"])
        payload["evidence"] = "verified_source"
        payload["approximate"] = payload["precision"] != "exact"
    elif leg.get("boarding_verified") is True:
        source_verified = True
        if leg.get("boarding_precision") is not None:
            payload["precision"] = str(leg["boarding_precision"])
        if leg.get("boarding_instructions") is not None:
            payload["instructions"] = str(leg["boarding_instructions"])
        payload["evidence"] = "verified_source"
        payload["approximate"] = payload["precision"] != "exact"
    else:
        observation = leg.get("observation")
        if isinstance(observation, Mapping) and _is_verified(observation):
            if observation.get("boarding_precision") is not None:
                payload["precision"] = str(observation["boarding_precision"])
                payload["evidence"] = "verified_source"
                payload["approximate"] = payload["precision"] != "exact"
                source_verified = True
            if observation.get("boarding_instructions") is not None:
                payload["instructions"] = str(observation["boarding_instructions"])
                payload["evidence"] = "verified_source"
                source_verified = True

    if fleet:
        vehicle = fleet.get("vehicle") or {}
        payload["vehicle_id"] = vehicle.get("vehicle_id")
        eta = vehicle.get("eta_to_boarding_minutes")
        if eta is not None:
            payload["approach_eta_minutes"] = eta
            alerts.append(
                f"Vehicle {vehicle.get('vehicle_id')} has a fresh verified position and is estimated to reach boarding in {eta} minutes."
            )
        if vehicle.get("boarding_precision") is not None:
            payload["precision"] = str(vehicle["boarding_precision"])
            payload["evidence"] = "verified_source"
            payload["approximate"] = payload["precision"] != "exact"
            source_verified = True
        if vehicle.get("boarding_instructions") is not None:
            payload["instructions"] = str(vehicle["boarding_instructions"])
            payload["evidence"] = "verified_source"
            source_verified = True

    if not source_verified:
        alerts.append(
            "Boarding coordinates are an approximate terminal seed; the exact boarding point and operator instructions are not verified."
        )
    return payload


def _connection_record(
    journey: Mapping[str, Any],
    kind: str,
    origin: str,
    destination: str,
) -> Mapping[str, Any] | None:
    for connection in journey.get("connections", []) if isinstance(journey, Mapping) else []:
        if not isinstance(connection, Mapping):
            continue
        if str(connection.get("kind") or "transfer") != kind:
            continue
        if str(connection.get("origin") or "") == origin and str(connection.get("destination") or "") == destination:
            return connection
    # Access connections are unique in a Journey; if a hand-built record did
    # not include IDs, the first one is still safer than inventing a route.
    if kind == "access":
        for connection in journey.get("connections", []) if isinstance(journey, Mapping) else []:
            if isinstance(connection, Mapping) and str(connection.get("kind") or "") == "access":
                return connection
    return None


def _connection_minutes(connection: Mapping[str, Any] | None, key: str, nested_key: str) -> int:
    value = connection.get(key) if connection else None
    if value is None and connection:
        transfer = connection.get("transfer")
        value = transfer.get(nested_key) if isinstance(transfer, Mapping) else None
    number = _number(value)
    return max(0, math.floor(number)) if number is not None else 0


def _next_connection(
    journey: Mapping[str, Any],
    legs: list[Mapping[str, Any]],
    anchor: int,
    now: datetime,
    arrival_info: Mapping[str, Mapping[str, Any]],
    request_origin: str = "",
) -> dict[str, Any] | None:
    if anchor < 0:
        next_index = 0
        next_leg = legs[next_index]
        next_departure = _datetime(next_leg.get("departure"))
        if next_departure is None:
            return None
        # The request carries the door-to-door origin; the Journey JSON only
        # carries the service legs.  A missing request origin falls back to the
        # service origin without inventing a transfer.
        origin = request_origin or str(next_leg.get("origin") or "")
        destination = str(next_leg.get("origin") or "")
        raw = _connection_record(journey, "access", origin, destination)
        if raw is None:
            raw = _connection_record(journey, "access", "", destination)
        if raw is None:
            if origin != destination:
                return {
                    "kind": "access",
                    "from_leg_id": None,
                    "to_leg_id": str(next_leg.get("id") or "leg-0"),
                    "origin": origin,
                    "destination": destination,
                    "available_minutes": _minutes(next_departure - now),
                    "travel_minutes": None,
                    "transfer_minutes": None,
                    "processing_minutes": None,
                    "buffer_minutes": None,
                    "required_minutes": None,
                    "spare_minutes": None,
                    "feasible": False,
                    "can_make": False,
                    "verified_arrival": False,
                    "timing_basis": "missing_transfer_record",
                    "status": "unverified",
                    "message": "No verified access transfer record exists, so reaching the boarding point cannot be confirmed.",
                }
            # Direct boarding has no transfer record.  Returning a synthetic
            # zero-minute access leg would look like verified route guidance.
            return None
        available = _minutes(next_departure - now)
        travel = _connection_minutes(raw, "transfer_minutes", "minutes")
        processing = _connection_minutes(raw, "processing_minutes", "minutes")
        buffer = _connection_minutes(raw, "buffer_minutes", "minutes")
        required = travel + processing + buffer
        spare = available - required
        feasible = spare >= 0
        return {
            "kind": "access",
            "from_leg_id": None,
            "to_leg_id": str(next_leg.get("id") or "leg-0"),
            "origin": str(raw.get("origin") if raw else origin),
            "destination": destination,
            "available_minutes": available,
            "travel_minutes": travel,
            "transfer_minutes": travel,
            "processing_minutes": processing,
            "buffer_minutes": buffer,
            "required_minutes": required,
            "spare_minutes": spare,
            "feasible": feasible,
            "can_make": feasible,
            "verified_arrival": False,
            "timing_basis": "scheduled",
            "status": "unverified" if feasible else "missed",
            "message": "The boarding access window is based on the scheduled departure; terminal access time is approximate.",
        }

    next_index = anchor + 1
    if next_index >= len(legs):
        return None
    previous = legs[anchor]
    next_leg = legs[next_index]
    previous_id = str(previous.get("id") or f"leg-{anchor}")
    next_id = str(next_leg.get("id") or f"leg-{next_index}")
    previous_destination = str(previous.get("destination") or "")
    next_origin = str(next_leg.get("origin") or "")
    raw = _connection_record(journey, "transfer", previous_destination, next_origin)
    arrival = arrival_info.get(previous_id, {}).get("effective")
    scheduled_arrival = arrival_info.get(previous_id, {}).get("scheduled")
    departure = _datetime(next_leg.get("departure"))
    if departure is None or arrival is None:
        return None
    if raw is None and previous_destination != next_origin:
        # A cross-terminal connection without a stored transfer is unknown.
        # Do not turn missing data into a zero-minute, feasible transfer.
        verified_arrival = bool(arrival_info.get(previous_id, {}).get("verified"))
        return {
            "kind": "transfer",
            "from_leg_id": previous_id,
            "to_leg_id": next_id,
            "origin": previous_destination,
            "destination": next_origin,
            "available_minutes": _minutes(departure - arrival),
            "travel_minutes": None,
            "transfer_minutes": None,
            "processing_minutes": None,
            "buffer_minutes": None,
            "required_minutes": None,
            "spare_minutes": None,
            "feasible": False,
            "can_make": False,
            "verified_arrival": verified_arrival,
            "timing_basis": "missing_transfer_record",
            "scheduled_available_minutes": _minutes(departure - scheduled_arrival) if scheduled_arrival else None,
            "estimated_arrival": _iso(arrival) if verified_arrival else None,
            "status": "unverified",
            "message": "No verified transfer record exists between these terminals, so connection feasibility cannot be confirmed.",
        }
    travel = _connection_minutes(raw, "transfer_minutes", "minutes")
    processing = _connection_minutes(raw, "processing_minutes", "minutes")
    buffer = _connection_minutes(raw, "buffer_minutes", "minutes")
    available = _minutes(departure - arrival)
    required = travel + processing + buffer
    spare = available - required
    feasible = spare >= 0
    verified_arrival = bool(arrival_info.get(previous_id, {}).get("verified"))
    status = "feasible" if feasible else "missed"
    if not verified_arrival:
        status = "unverified" if feasible else "missed"
    message = (
        "The next connection remains feasible with the current arrival estimate."
        if feasible
        else "The latest arrival estimate leaves no time for the next connection."
    )
    return {
        "kind": "transfer",
        "from_leg_id": previous_id,
        "to_leg_id": next_id,
        "origin": previous_destination,
        "destination": next_origin,
        "available_minutes": available,
        "travel_minutes": travel,
        "transfer_minutes": travel,
        "processing_minutes": processing,
        "buffer_minutes": buffer,
        "required_minutes": required,
        "spare_minutes": spare,
        "feasible": feasible,
        "can_make": feasible,
        "verified_arrival": verified_arrival,
        "timing_basis": "verified_estimate" if verified_arrival else "scheduled",
        "scheduled_available_minutes": _minutes(departure - scheduled_arrival) if scheduled_arrival else None,
        "estimated_arrival": _iso(arrival) if verified_arrival else None,
        "status": status,
        "message": message,
    }


def _status_for(
    *,
    legs: list[Mapping[str, Any]],
    leg_ids: list[str],
    completed: list[str],
    onboard: str | None,
    progress_valid: bool,
    current_index: int,
    now: datetime,
    arrival_info: Mapping[str, Mapping[str, Any]],
    connection_missed: bool,
) -> str:
    if not progress_valid:
        return "needs_progress"
    if len(completed) == len(legs):
        return "complete"
    if connection_missed and (onboard is not None or completed):
        return "missed"
    if onboard is not None:
        index = leg_ids.index(onboard)
        start = _datetime(legs[index].get("departure"))
        arrival = arrival_info.get(onboard, {}).get("effective")
        if start is None or arrival is None or start > now or arrival <= now:
            return "needs_progress"
        return "onboard"

    current = legs[current_index]
    start = _datetime(current.get("departure"))
    if start is None:
        return "unverified"
    if start <= now:
        return "needs_progress"
    if completed:
        return "waiting"
    remaining = start - now
    if remaining <= _WAITING_WINDOW:
        return "waiting"
    if remaining <= _READY_WINDOW:
        return "ready"
    return "planning"

