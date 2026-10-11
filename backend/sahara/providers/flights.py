"""Public webpage flight collection.

The flight adapter intentionally reads only the public Google Flights page.  It
does not call the site's internal request endpoints or an inventory API.  A
normal HTML request may therefore return an empty shell; callers can opt into a
regular rendered browser through ``browser=True`` and must surface a blocked
or incomplete source to the user.
"""

import asyncio
import base64
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
from decimal import Decimal, InvalidOperation
import hashlib
import json
import re
from urllib.parse import quote

from bs4 import BeautifulSoup

from ..domain import Leg, Observation, ProviderResult
from .collector import collect_html
from .parsing import (
    IST,
    date_coverage_warning,
    duration_minutes,
    finish_queries,
    money_minor,
    search_days,
)


GOOGLE_FLIGHTS_URL = 'https://www.google.com/travel/flights'
_PARSER_VERSION = 'google-flights-dom-v1'
_TIME_RE = re.compile(r'(?<!\d)(\d{1,2}):(\d{2})\s*([ap]\.? ?m\.?)?', re.I)
_DATE_RE = re.compile(
    r'\b(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)?\s*,?\s*'
    r'(January|February|March|April|May|June|July|August|September|October|November|December|'
    r'Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\s+(\d{1,2})'
    r'(?:\s*,?\s*(\d{4}))?\b',
    re.I,
)
_MONTHS = {
    'jan': 1, 'january': 1, 'feb': 2, 'february': 2, 'mar': 3, 'march': 3,
    'apr': 4, 'april': 4, 'may': 5, 'june': 6, 'jun': 6, 'july': 7, 'jul': 7,
    'aug': 8, 'august': 8, 'sep': 9, 'sept': 9, 'september': 9, 'oct': 10,
    'october': 10, 'nov': 11, 'november': 11, 'dec': 12, 'december': 12,
}
_FARE_TOKEN_RE = re.compile(
    r'(?:(?:₹|INR|Rs\.?)[ \u00a0\u202f]*[\d,]+(?:\.\d{1,2})?)', re.I,
)
_IATA_RE = re.compile(r'(?<![A-Z])([A-Z]{3})(?![A-Z])')


def _varint(value):
    """Encode a non-negative protobuf varint for Google's public search link."""
    value = int(value)
    if value < 0:
        raise ValueError('varint values must be non-negative')
    encoded = bytearray()
    while value >= 0x80:
        encoded.append((value & 0x7f) | 0x80)
        value >>= 7
    encoded.append(value)
    return bytes(encoded)


def _field_varint(field, value):
    return _varint((int(field) << 3) | 0) + _varint(value)


def _field_bytes(field, value):
    value = bytes(value)
    return _varint((int(field) << 3) | 2) + _varint(len(value)) + value


def _field_string(field, value):
    return _field_bytes(field, str(value).encode('utf-8'))


def _google_airport(code):
    # Search-link wire format: airport type is field 1 and IATA code is field 2.
    return _field_varint(1, 1) + _field_string(2, str(code).upper())


def _google_flight_leg(origin, destination, day):
    # The public tfs search link carries an explicit date and two airport
    # messages.  This is link encoding only; it is not an inventory request.
    return (
        _field_string(2, day.strftime('%Y-%m-%d'))
        + _field_bytes(13, _google_airport(origin))
        + _field_bytes(14, _google_airport(destination))
    )


def _google_tfs(origin, destination, day, passengers=1):
    """Build Google's public, shareable one-way search-link token.

    The token is the same compact protobuf-shaped value embedded in ordinary
    Google Flights result URLs.  It is used only as a navigation URL; response
    collection remains a public rendered-HTML operation in ``PageCollector``.
    """
    payload = (
        _field_varint(2, 0)
        + _field_bytes(3, _google_flight_leg(origin, destination, day))
        + _field_varint(8, max(1, int(passengers)))
        + _field_varint(9, 1)    # economy
        + _field_varint(19, 2)   # one-way
    )
    return base64.urlsafe_b64encode(payload).decode('ascii').rstrip('=')


def _clean(value):
    return re.sub(r'\s+', ' ', str(value or '').replace('\u202f', ' ')).strip()


def _element_text(element):
    if element is None:
        return ''
    aria = _clean(element.get('aria-label', ''))
    visible = _clean(element.get_text(' ', strip=True))
    return ' '.join(part for part in (aria, visible) if part)


def _clock(text):
    """Return the last explicit clock in a public result label."""
    matches = list(_TIME_RE.finditer(_clean(text)))
    if not matches:
        return None
    match = matches[-1]
    hour, minute = int(match[1]), int(match[2])
    meridiem = (match[3] or '').replace('.', '').lower()
    if meridiem:
        if not 1 <= hour <= 12:
            return None
        if meridiem == 'pm' and hour != 12:
            hour += 12
        elif meridiem == 'am' and hour == 12:
            hour = 0
    elif not 0 <= hour <= 23:
        return None
    return hour, minute


def _calendar_date(text, fallback):
    iso = re.search(r'\b(\d{4})-(\d{2})-(\d{2})\b', _clean(text))
    if iso:
        try:
            return date(int(iso[1]), int(iso[2]), int(iso[3]))
        except ValueError:
            return None
    matches = list(_DATE_RE.finditer(_clean(text)))
    if not matches:
        return None
    match = matches[-1]
    month = _MONTHS[match[1].casefold()]
    year = int(match[3]) if match[3] else fallback.year
    try:
        return date(year, month, int(match[2]))
    except ValueError:
        return None


def _time_info(element, fallback_day=None):
    text = _element_text(element)
    raw_datetime = str(element.get('datetime', '')).strip() if element is not None else ''
    explicit = None
    if raw_datetime:
        try:
            explicit = datetime.fromisoformat(raw_datetime.replace('Z', '+00:00'))
        except ValueError:
            explicit = None
    return {
        'clock': (explicit.hour, explicit.minute) if explicit else _clock(text),
        'date': explicit.date() if explicit else _calendar_date(text, fallback_day or date.today()),
        'next_day': bool(re.search(r'\+\s*(?:1|one)|\bnext\s+day\b|\bnext\s+day\b', text, re.I)),
        'text': text,
    }


def _card_flight_date(card, kind, fallback_day):
    """Extract the explicit date in Google's accessible flight description."""
    labels = [
        _clean(element.get('aria-label', ''))
        for element in card.find_all(attrs={'aria-label': True})
    ]
    wanted = kind.casefold()
    for label in labels:
        if wanted == 'departure':
            if not re.search(r'\b(?:leaves|departs?|departing|departure)\b', label, re.I):
                continue
            # A single accessible label often describes both legs.  Restrict a
            # departure date lookup to its text before the arrival clause.
            segment = re.split(r'\b(?:arrives?|arrival|arriving)\b', label, maxsplit=1, flags=re.I)[0]
        else:
            if not re.search(r'\b(?:arrives?|arrival|arriving)\b', label, re.I):
                continue
            segment = re.split(r'\b(?:arrives?|arrival|arriving)\b', label, maxsplit=1, flags=re.I)[-1]
        selected = _calendar_date(segment, fallback_day or date.today())
        if selected:
            return selected
    return None


def _find_time(card, kind, fallback_day=None):
    """Find an explicit departure/arrival node in a rendered result card."""
    wanted = kind.casefold()
    for element in card.find_all(attrs={'aria-label': True}):
        label = _clean(element.get('aria-label', ''))
        if wanted in label.casefold() and 'time' in label.casefold():
            info = _time_info(element, fallback_day)
            if info['clock']:
                info['date'] = info['date'] or _card_flight_date(card, kind, fallback_day)
                return info

    group = card.select_one('.mv1WYe, [data-testid="flight-times"]')
    if group:
        nodes = group.find_all(['span', 'div', 'time'], recursive=True)
        infos = [
            _time_info(node, fallback_day)
            for node in nodes
            if _time_info(node, fallback_day)['clock']
        ]
        if infos:
            info = infos[0] if wanted == 'departure' else infos[-1]
            info['date'] = info['date'] or _card_flight_date(card, kind, fallback_day)
            return info

    # A few rendered snapshots retain only the two visible clock spans.  Use
    # their order, while still refusing cards that expose no times at all.
    candidates = []
    for element in card.find_all(['span', 'div', 'time']):
        info = _time_info(element, fallback_day)
        if info['clock']:
            candidates.append(info)
    if candidates:
        info = candidates[0] if wanted == 'departure' else candidates[-1]
        info['date'] = info['date'] or _card_flight_date(card, kind, fallback_day)
        return info
    return None


def _route(card, origin, destination):
    expected = (str(origin).strip().upper(), str(destination).strip().upper())
    for element in card.find_all(attrs={'data-origin': True}):
        source = str(element.get('data-origin', '')).strip().upper()
        target = str(element.get('data-destination', '')).strip().upper()
        if (source, target) == expected:
            return True
        if source == expected[0] and target and target != expected[1]:
            return False

    text = _clean(' '.join([
        str(card.get('aria-label', '')),
        card.get_text(' ', strip=True),
    ])).upper()
    if re.search(r'\b' + re.escape(expected[0]) + r'\b\s*(?:→|->|TO|–|-)\s*\b' + re.escape(expected[1]) + r'\b', text):
        return True
    tokens = list(_IATA_RE.finditer(text))
    positions = {token.group(1): token.start() for token in tokens}
    return expected[0] in positions and expected[1] in positions and positions[expected[0]] < positions[expected[1]]


def _fare(card):
    texts = []
    for element in card.find_all(attrs={'aria-label': True}):
        texts.append(_clean(element.get('aria-label', '')))
    texts.extend(_clean(element.get_text(' ', strip=True)) for element in card.find_all(['span', 'div']))
    texts.append(_clean(card.get('aria-label', '')))
    round_trip = bool(re.search(r'\bround[\s-]*trip\b', ' '.join(texts), re.I))
    candidates = []
    for text in texts:
        if not re.search(r'(?:₹|\bINR\b|\bRs\.?)', text, re.I):
            continue
        # "from ₹..." is a route/date price hint, not a sellable itinerary.
        if re.search(r'\bfrom\s*(?:₹|\bINR\b|\bRs\.?)', text, re.I):
            continue
        token = _FARE_TOKEN_RE.search(text)
        if not token:
            continue
        minor = money_minor(token[0])
        if minor is None:
            continue
        lower = text.casefold()
        party = re.search(
            r'\b(?:total\s+for|for)?\s*(\d+)\s*'
            r'(?:travell?ers?|passengers?|adults?|people)\b',
            lower,
        )
        party_count = int(party[1]) if party else None
        per_person_label = re.search(r'\bper\s+(?:travell?er|passenger|adult)\b', lower)
        basis = 'per_person' if per_person_label else (
            'party' if party_count is not None or re.search(r'\bparty\b|\btotal\b', lower) else 'per_person'
        )
        fare_class = None
        for candidate in ('Premium Economy', 'Business', 'First', 'Economy'):
            if re.search(r'\b' + re.escape(candidate) + r'\b', text, re.I):
                fare_class = candidate
                break
        candidates.append((minor, basis, fare_class, party_count))
    if not candidates:
        return None, 'per_person', None, None, round_trip
    # Prefer a fare label that states the passenger basis over a surrounding
    # card label that only repeats the amount.
    selected = next((candidate for candidate in candidates if candidate[1] == 'party'), candidates[0])
    return (*selected, round_trip)


def _visible_marker_text(element):
    if element is None:
        return ''
    value = element.get('value', '')
    return _clean(' '.join([
        element.get_text(' ', strip=True),
        element.get('aria-label', ''),
        value,
    ]))


def _marker_route(element, origin, destination):
    source = str(element.get('data-selected-origin') or element.get('data-origin') or '').strip().upper()
    target = str(element.get('data-selected-destination') or element.get('data-destination') or '').strip().upper()
    expected = (str(origin).strip().upper(), str(destination).strip().upper())
    if source or target:
        return (source, target) == expected
    text = _visible_marker_text(element)
    parent = element.parent if element.parent else None
    text = _clean(' '.join([text, _visible_marker_text(parent)]))
    if not text:
        return False
    return bool(re.search(
        r'\b' + re.escape(expected[0]) + r'\b\s*(?:→|->|TO|–|-)\s*\b' + re.escape(expected[1]) + r'\b',
        text.upper(),
    ))


def _page_selected_date(soup, origin, destination, requested_day):
    """Return a visible, route-bound selected date from page controls.

    The query URL is deliberately excluded.  A result card may inherit the
    selected date from a visible date control, but only when that control has
    an exact date and the same route.
    """
    markers = []
    for element in soup.find_all(attrs={'data-selected-date': True}):
        markers.append((element, element.get('data-selected-date')))
    for element in soup.find_all(attrs={'data-departure-date': True}):
        markers.append((element, element.get('data-departure-date')))
    for element in soup.find_all(attrs={'aria-label': True}):
        label = _clean(element.get('aria-label', '')).casefold()
        if 'date' in label and ('departure' in label or 'selected' in label):
            markers.append((element, _visible_marker_text(element)))
    for element, raw in markers:
        if not _visible_marker_text(element):
            continue
        selected = _calendar_date(str(raw), requested_day)
        if selected != requested_day or not _marker_route(element, origin, destination):
            continue
        return selected
    return None


def _duration(card):
    for element in card.find_all(['span', 'div']):
        text = _clean(element.get_text(' ', strip=True))
        minutes = duration_minutes(text)
        if minutes:
            return minutes
    return None


def _flight_number(card):
    text = _clean(' '.join([str(card.get('aria-label', '')), card.get_text(' ', strip=True)]))
    match = re.search(r'\b([A-Z]{2})\s*[- ]?\s*(\d{2,4})\b', text, re.I)
    return (match[1].upper() + match[2]) if match else None


def _airline(card):
    element = card.select_one('.sSHqwe, [data-testid="airline-name"]')
    if element:
        return _clean(element.get_text(' ', strip=True))
    label = _clean(card.get('aria-label', ''))
    label = re.split(r'\b(?:COK|CCJ|COK|BLR|INR|₹)\b', label, maxsplit=1, flags=re.I)[0]
    return label.strip(' ,') or 'Flight'


def _service_hash(origin, destination, departure, airline, flight_number):
    identity = [origin, destination, departure.isoformat(), airline, flight_number]
    return 'flight:' + hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()[:24]


def parse_google_flights(html, origin, destination, day, now, url, passengers=1):
    """Parse dated Google Flights result cards from rendered public HTML.

    The parser intentionally requires an exact route, explicit departure and
    arrival clocks, and an INR fare on the same card.  A page-level "from"
    price, a different route, a foreign-currency fare, or a card with no dated
    clocks and no visible route-bound selected-date control is not treated as
    inventory.  The query URL alone never supplies date proof.
    """
    requested_day = day.date() if isinstance(day, datetime) else day
    soup = BeautifulSoup(html, 'html.parser')
    page_date = _page_selected_date(soup, origin, destination, requested_day)
    cards = soup.select(
        'li.pIav2d, div.pIav2d, [class~="pIav2d"], '
        'li[aria-label*="₹"], li[aria-label*="INR"]'
    )
    if not cards:
        cards = [element for element in soup.find_all(attrs={'data-origin': True}) if element.name in ('li', 'div')]
    legs = []
    seen = set()
    for card in cards:
        try:
            if not _route(card, origin, destination):
                continue
            departure_info = _find_time(card, 'departure', requested_day)
            arrival_info = _find_time(card, 'arrival', requested_day)
            if not departure_info or not arrival_info:
                continue
            departure_day = departure_info['date'] or page_date
            if departure_day is None:
                continue
            if departure_day != requested_day:
                continue
            arrival_day = arrival_info['date'] or departure_day
            if arrival_info['next_day']:
                arrival_day = requested_day + timedelta(days=1)
            if departure_info['clock'] is None or arrival_info['clock'] is None:
                continue
            departure = datetime(*departure_day.timetuple()[:3], *departure_info['clock'], tzinfo=IST)
            arrival = datetime(*arrival_day.timetuple()[:3], *arrival_info['clock'], tzinfo=IST)
            if arrival <= departure:
                minutes = _duration(card)
                if arrival_info['date'] is None and minutes and minutes > 0:
                    candidate = departure + timedelta(minutes=minutes)
                    if candidate.date() not in (requested_day, requested_day + timedelta(days=1)):
                        continue
                    if (candidate.hour, candidate.minute) != (arrival.hour, arrival.minute):
                        continue
                    arrival = candidate
                else:
                    continue
            fare, fare_basis, fare_class, party_count, round_trip = _fare(card)
            if fare is None:
                continue
            if round_trip or (fare_basis == 'party' and party_count != passengers):
                continue
            airline = _airline(card)
            flight_number = _flight_number(card)
            service_id = _service_hash(str(origin).lower(), str(destination).lower(), departure, airline, flight_number)
            if service_id in seen:
                continue
            seen.add(service_id)
            summary = _clean(card.get('aria-label', ''))
            lower = summary.casefold() + ' ' + _clean(card.get_text(' ', strip=True)).casefold()
            if re.search(r'\b(?:sold\s*out|unavailable|no\s+seats?)\b', lower):
                availability = 'sold_out'
            else:
                availability = 'available'
            name = airline + (f' {flight_number}' if flight_number else '')
            legs.append(Leg(
                id='google:' + service_id.removeprefix('flight:'),
                provider='google-flights',
                service_id=service_id,
                name=name,
                mode='flight',
                origin=str(origin).lower(),
                destination=str(destination).lower(),
                departure=departure,
                arrival=arrival,
                fare_minor=fare,
                fare_basis=fare_basis,
                currency='INR',
                availability=availability,
                fare_class=fare_class,
                booking_url=url,
                ticket_group=flight_number,
                observation=Observation(
                    provider='google-flights',
                    evidence='cached',
                    fetched_at=now,
                    expires_at=now + timedelta(minutes=5),
                    source_url=url,
                    parser_version=_PARSER_VERSION,
                ),
            ))
        except (TypeError, ValueError, KeyError, OverflowError):
            continue
    return legs


# Keep descriptive aliases for callers that pass a saved rendered snapshot.
parse_google_flights_html = parse_google_flights
parse_flight_html = parse_google_flights


def parse_duffel(offers,now):
    legs=[]
    for offer in offers:
        try:
            expiry=datetime.fromisoformat(offer['expires_at'].replace('Z','+00:00'))
            if not offer.get('live_mode') or expiry<=now or offer['total_currency']!='INR':
                continue
            if len(offer['slices'])!=1:
                continue
            segments=offer['slices'][0]['segments']
            if not segments or any(s[side].get('iata_country_code')!='IN' for s in segments for side in ('origin','destination')):
                continue
            first,last=segments[0],segments[-1]
            def time(text,place):
                value=datetime.fromisoformat(text)
                return value if value.tzinfo else value.replace(tzinfo=ZoneInfo(place['time_zone']))
            clocks=[(time(s['departing_at'],s['origin']),time(s['arriving_at'],s['destination'])) for s in segments]
            if any(arr<=dep for dep,arr in clocks):
                continue
            if any(segments[i-1]['destination']['iata_code']!=s['origin']['iata_code'] or clocks[i][0]<clocks[i-1][1]
                   for i,s in enumerate(segments) if i):
                continue
            identity=[(s['origin']['iata_code'],s['destination']['iata_code'],clocks[i][0].isoformat(),
                       s.get('operating_carrier',{}).get('id') or s.get('operating_carrier',{}).get('name'),
                       s.get('operating_carrier_flight_number'),
                       [(p.get('cabin_class'),p.get('fare_basis_code')) for p in s.get('passengers',[])])
                      for i,s in enumerate(segments)]
            service_id='flight:'+hashlib.sha256(json.dumps(identity,sort_keys=True).encode()).hexdigest()[:24]
            name=' / '.join(f'{s.get("operating_carrier",{}).get("name","Flight")} {s.get("operating_carrier_flight_number","")}' for s in segments)
            legs.append(Leg(id='duffel:'+offer['id'],provider='duffel',service_id=service_id,name=name,mode='flight',
                origin=first['origin']['iata_code'].lower(),destination=last['destination']['iata_code'].lower(),
                departure=time(first['departing_at'],first['origin']),arrival=time(last['arriving_at'],last['destination']),
                fare_minor=int(Decimal(offer['total_amount'])*100),fare_basis='party',availability='available',
                ticket_group=offer['id'],observation=Observation(provider='duffel',evidence='live',fetched_at=now,
                    observed_at=now,expires_at=expiry,source_url='https://duffel.com/docs/api/offers')))
        except (KeyError,ValueError,TypeError,InvalidOperation,ZoneInfoNotFoundError):
            continue
    return legs


class FlightProvider:
    name='flight-web'
    def __init__(self, enabled=False, browser=False, collector=None):
        """Create a bounded public-page flight provider.

        ``enabled`` is deliberately opt-in because rendered public pages can be
        slow or unavailable.  ``collector`` is dependency-injected so tests and
        the central server can share one ``PageCollector`` instance.  A legacy
        positional key is accepted as a disabled value by treating any
        non-boolean first argument as the old, prohibited API configuration.
        """
        self.enabled = enabled if isinstance(enabled, bool) else False
        self.browser = bool(browser) if isinstance(enabled, bool) else False
        self.collector = collector or collect_html

    @staticmethod
    def url(origin, destination, day, passengers=1):
        # This is the public link a person can open.  The ``tfs`` value is the
        # ordinary shareable search-link token; we never reconstruct or call
        # Google's internal batchexecute/network requests.
        token = _google_tfs(origin, destination, day, passengers)
        return (
            f'{GOOGLE_FLIGHTS_URL}/search?tfs={token}&hl=en&gl=IN&curr=INR'
            f'#flt={quote(str(origin).upper())}.{quote(str(destination).upper())}.{day:%Y-%m-%d}'
            ';c:INR;e:1;sd:1;t:f'
        )

    async def search_progressive(self, request, pairs, now, progress):
        return await self.search(request, pairs, now, progress)

    async def search(self, request, pairs, now, progress=None):
        modes = {getattr(mode, 'value', mode) for mode in request.modes}
        if not self.enabled or 'flight' not in modes:
            return ProviderResult(
                provider=self.name,
                status='disabled',
                message='Public webpage flight collection disabled; no external flight API is used',
            )

        queries = []
        seen = set()
        for origin, destination in pairs:
            source = origin.codes.get('iata')
            target = destination.codes.get('iata')
            if not source or not target or (source, target) in seen:
                continue
            seen.add((source, target))
            queries.append((source, target))
            # Browser-rendered flight pages are expensive; keep the same
            # two-airport-pair bound used by the previous flight adapter.
            if len(queries) >= 2:
                break
        if not queries:
            return ProviderResult(
                provider=self.name,
                status='empty',
                message='No bounded airport pair was available for public flight search',
            )

        legs = []
        errors = date_coverage_warning(request)
        query_limit = asyncio.Semaphore(1 if self.browser else 2)

        async def query(source, target, day):
            url = self.url(source, target, day, request.passengers)
            try:
                async with query_limit:
                    html = await self.collector(url, self.browser)
                parsed = parse_google_flights(html, source, target, day, now, url, passengers=request.passengers)
                if not parsed:
                    errors.append(
                        f'No dated INR flight cards parsed for {source}-{target} on {day}; availability unknown'
                    )
                legs.extend(parsed)
                if progress and parsed:
                    progress(ProviderResult(
                        provider=self.name,
                        status='partial',
                        legs=list(legs),
                        message='Public flight cards received; remaining bounded date checks are still running',
                    ))
            except Exception as error:
                detail = str(error).strip()
                if detail and len(detail) <= 240:
                    errors.append(detail)
                else:
                    errors.append(type(error).__name__ + ': public flight page unavailable')

        work = (query(source, target, day) for day in search_days(request) for source, target in queries)
        await finish_queries(work, errors)
        unique_legs = list({leg.id: leg for leg in legs}.values())
        if unique_legs and errors:
            status = 'partial'
        elif unique_legs:
            status = 'ok'
        elif errors:
            status = 'error'
        else:
            status = 'empty'
        return ProviderResult(
            provider=self.name,
            status=status,
            legs=unique_legs,
            message='; '.join(sorted(set(errors))) or (
                'Parsed dated public flight cards; final seat inventory and booking fees may change'
                if unique_legs else 'No dated public flight inventory found'
            ),
        )
