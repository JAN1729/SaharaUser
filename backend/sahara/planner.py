"""Bounded timetable search; no LLM or database side effects."""
import hashlib
import json
import math
from collections import deque
from datetime import timedelta
from .domain import Availability, Connection, Journey, Mode, PlanResult, Rejection


def plan(request, legs, terminals, transfers, now, locked_legs=(), locked_connections=()):
    rejected = []
    eligible = []
    deadline = request.arrive_by or request.depart_after + timedelta(days=2)
    for leg in legs:
        reason = None
        age = now - (leg.observation.observed_at or leg.observation.fetched_at)
        if leg.origin not in terminals or leg.destination not in terminals:
            reason = 'unknown_terminal'
        elif leg.mode not in request.modes:
            reason = 'mode_excluded'
        elif leg.cancelled:
            reason = 'cancelled'
        elif leg.booking_opens_at and now < leg.booking_opens_at:
            reason = 'booking_not_open'
        elif leg.observation.expires_at and leg.observation.expires_at <= now:
            reason = 'expired_offer'
        elif request.data_mode == 'live' and leg.observation.evidence == 'fixture':
            reason = 'sample_data_in_live_search'
        elif leg.observation.evidence in ('live', 'cached') and age > timedelta(minutes=15):
            reason = 'stale_availability'
        elif leg.availability == Availability.SOLD_OUT:
            reason = 'sold_out'
        elif request.confirmed_only and leg.availability not in (Availability.AVAILABLE, Availability.CONFIRMED):
            reason = 'availability_unverified'
        elif leg.seats is not None and leg.seats < request.passengers:
            reason = 'insufficient_seats'
        elif leg.start < max(now, request.depart_after) or leg.end > deadline:
            reason = 'outside_travel_window'
        elif leg.currency != request.currency:
            reason = 'currency_conversion_unavailable'
        elif request.wheelchair and leg.wheelchair is False:
            reason = 'inaccessible_service'
        if reason:
            rejected.append(Rejection(leg_id=leg.id, reason=reason))
        else:
            eligible.append(leg)
    eligible.sort(key=lambda leg: (leg.start, leg.id))
    transfer_map = {(t.origin, t.destination): t for t in transfers}
    complete = []
    prefix=list(locked_legs)
    queue = deque([(prefix, list(locked_connections), prefix[-1].destination if prefix else request.origin,
                    max(now,prefix[-1].end) if prefix else request.depart_after,
                    {(l.provider,l.service_id) for l in prefix})])
    if prefix:
        finished=_finish(prefix,list(locked_connections),request,transfer_map,deadline)
        if finished:
            if request.budget_minor is None or finished.total_fare_minor is not None and finished.total_fare_minor<=request.budget_minor:
                complete.append(finished)
            else:
                rejected.append(Rejection(leg_id=prefix[-1].id,reason='budget_unverified_or_exceeded'))
            queue.clear()
    labels = 0
    bounded = False
    while queue and labels < 4000:
        path, connections, position, available_at, used = queue.popleft()
        labels += 1
        if len(path) >= request.max_transfers + 1:
            continue
        for leg in eligible:
            if (leg.provider, leg.service_id) in used:
                continue
            visited={request.origin,*[l.origin for l in path],*[l.destination for l in path]}
            if leg.destination in visited:
                continue
            transfer = None if position == leg.origin else transfer_map.get((position, leg.origin))
            if position != leg.origin and transfer is None:
                continue
            if transfer and (transfer.currency != request.currency or
                             transfer.distance_m > request.max_walk_m and transfer.mode == 'walk' or
                             request.wheelchair and transfer.wheelchair is False):
                continue
            transfer_minutes = transfer.minutes if transfer else 0
            processing = request.airport_processing_minutes if leg.mode == Mode.FLIGHT else 0
            if path and path[-1].mode == Mode.FLIGHT:
                processing += 45  # conservative airport exit allowance; explicit policy, not live data
            buffer = request.connection_buffer_minutes if path else 0
            required = transfer_minutes + processing + buffer
            if leg.start < available_at + timedelta(minutes=required):
                if path:
                    rejected.append(Rejection(leg_id=leg.id, reason='connection_too_short'))
                continue
            new_path = path + [leg]
            new_connections = list(connections)
            if path or transfer:
                available_minutes = math.floor((leg.start-available_at).total_seconds()/60)
                new_connections.append(Connection(origin=position, destination=leg.origin,
                    kind='transfer' if path else 'access',
                    available_minutes=available_minutes, transfer_minutes=transfer_minutes,
                    processing_minutes=processing, buffer_minutes=buffer,
                    spare_minutes=available_minutes-required, transfer=transfer,
                    protection='same_ticket_group' if path and path[-1].ticket_group and path[-1].ticket_group == leg.ticket_group else 'unknown'))
            journey=_finish(new_path,new_connections,request,transfer_map,deadline)
            if journey:
                if request.budget_minor is not None and (journey.total_fare_minor is None or journey.total_fare_minor > request.budget_minor):
                    rejected.append(Rejection(leg_id=leg.id, reason='budget_unverified_or_exceeded'))
                    continue
                complete.append(journey)
            else:
                if len(queue)<8000:
                    queue.append((new_path, new_connections, leg.destination, leg.end,
                                  used | {(leg.provider, leg.service_id)}))
                else:
                    bounded=True
    unique = {j.id: j for j in complete}
    journeys = list(unique.values())
    if journeys:
        min(journeys, key=lambda j: (j.arrival, j.duration_minutes)).recommendations.append('earliest_arrival')
        priced = [j for j in journeys if j.total_fare_minor is not None]
        if priced:
            min(priced, key=lambda j: (j.total_fare_minor, j.arrival)).recommendations.append('lowest_cost')
        min(journeys, key=lambda j: (len(j.warnings), j.transfers,
            -(j.minimum_spare_minutes or 0), j.arrival)).recommendations.append('robust_option')
        if request.preference == 'cheapest':
            journeys.sort(key=lambda j: (j.total_fare_minor is None, j.total_fare_minor or 0, j.arrival))
        elif request.preference == 'fastest':
            journeys.sort(key=lambda j: j.arrival)
        elif request.preference == 'robust':
            journeys.sort(key=lambda j: (len(j.warnings), j.transfers, -(j.minimum_spare_minutes or 0)))
        elif request.preference == 'comfortable':
            journeys.sort(key=lambda j: (j.transfers,
                sum(c.transfer.distance_m for c in j.connections if c.transfer and c.transfer.mode=='walk'),
                len(j.warnings),j.duration_minutes,j.total_fare_minor is None,j.known_fare_minor))
        else:
            journeys.sort(key=lambda j: (not bool(j.recommendations), j.arrival, j.transfers))
    result_warnings = ['search_limit_reached'] if bounded or queue and labels >= 4000 else []
    seen = set()
    reasons = []
    for r in rejected:
        if (r.leg_id, r.reason) not in seen:
            reasons.append(r)
            seen.add((r.leg_id, r.reason))
    return PlanResult(journeys=journeys[:12], rejected=reasons[:100], warnings=result_warnings, searched_labels=labels)


def _finish(path,connections,request,transfer_map,deadline):
    leg=path[-1]
    result=list(connections)
    if leg.end>deadline:
        return None
    if leg.destination!=request.destination:
        egress=transfer_map.get((leg.destination,request.destination))
        if egress is None or egress.currency!=request.currency or request.wheelchair and egress.wheelchair is False:
            return None
        if egress.mode=='walk' and egress.distance_m>request.max_walk_m:
            return None
        exit_minutes=45 if leg.mode==Mode.FLIGHT else 0
        if leg.end+timedelta(minutes=egress.minutes+exit_minutes)>deadline:
            return None
        result.append(Connection(kind='egress',origin=leg.destination,destination=request.destination,
            available_minutes=egress.minutes+exit_minutes,transfer_minutes=egress.minutes,
            processing_minutes=exit_minutes,buffer_minutes=0,spare_minutes=0,transfer=egress))
    return _journey(path,result,request)


def _journey(legs, connections, request):
    known = 0
    complete = True
    warnings = set()
    for leg in legs:
        if leg.fare_minor is None:
            complete = False
            warnings.add('fare_incomplete')
        else:
            known += leg.fare_minor * (request.passengers if leg.fare_basis == 'per_person' else 1)
        if leg.observation.evidence == 'fixture':
            warnings.add('sample_data')
        elif leg.observation.evidence != 'live':
            warnings.add('live_status_unverified')
        if leg.observation.evidence != 'fixture' and leg.observation.observed_at is None:
            warnings.add('upstream_age_unknown')
        if leg.availability not in (Availability.AVAILABLE, Availability.CONFIRMED):
            warnings.add('availability_unverified')
        if request.wheelchair and leg.wheelchair is None:
            warnings.add('accessibility_unverified')
    for connection in connections:
        if connection.protection == 'unknown' and len(legs) > 1:
            warnings.add('separate_ticket_protection_unknown')
        if connection.kind == 'transfer' and connection.spare_minutes < 30:
            warnings.add('tight_connection')
        if connection.transfer:
            transfer = connection.transfer
            if transfer.fare_minor is None:
                complete = False
                warnings.add('fare_incomplete')
            else:
                known += transfer.fare_minor * request.passengers
            if transfer.evidence == 'estimated':
                warnings.add('estimated_transfer')
            if request.wheelchair and transfer.wheelchair is None:
                warnings.add('accessibility_unverified')
    initial = connections[0].transfer_minutes if connections and connections[0].kind == 'access' else 0
    departure = legs[0].start - timedelta(minutes=initial + (request.airport_processing_minutes if legs[0].mode == Mode.FLIGHT else 0))
    final = connections[-1] if connections and connections[-1].kind == 'egress' else None
    arrival = legs[-1].end + timedelta(minutes=final.transfer_minutes+final.processing_minutes if final else 0)
    # Keep identity stable across inventory refreshes, but distinct for door-to-door
    # requests/groups. Stored snapshots also carry a search-specific ID prefix.
    identity=json.dumps([request.origin,request.destination,request.passengers,request.currency,
        request.data_mode,[l.id for l in legs]],separators=(',',':'))
    return Journey(id=hashlib.sha256(identity.encode()).hexdigest()[:20],
        legs=legs, connections=connections, departure=departure, arrival=arrival,
        duration_minutes=math.ceil((arrival-departure).total_seconds()/60),
        total_fare_minor=known if complete else None, known_fare_minor=known,
        currency=request.currency, transfers=len(legs)-1, warnings=sorted(warnings),
        minimum_spare_minutes=min((c.spare_minutes for c in connections if c.kind=='transfer'), default=None))
