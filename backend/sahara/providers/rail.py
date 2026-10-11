import asyncio
import re
import time
from datetime import timedelta
from bs4 import BeautifulSoup
from ..domain import Leg, Observation, ProviderResult
from .parsing import money_minor, normalize_availability, observed_time, timed_service, IST, search_days, finish_queries, date_coverage_warning
from .collector import collect_html
from ..places import TERMINALS


def matches_station(label,terminal_id):
    terminal=TERMINALS.get(terminal_id)
    code=terminal.codes.get('rail') if terminal else None
    return bool(code and re.search(r'(?<![A-Za-z])'+re.escape(code)+r'(?![A-Za-z])',label,re.I))


def parse_confirmtkt(html,origin,destination,day,now,url):
    soup=BeautifulSoup(html,'html.parser')
    legs=[]
    cards=soup.select('.train-card, div.rounded-10.border-b')
    for card in cards:
        number=card.select_one('.mr-5')
        if not number or not re.fullmatch(r'\d{5}',number.get_text(strip=True)):
            continue
        service=number.get_text(strip=True)
        name=number.parent.get_text(' ',strip=True)
        texts=[n.get_text(' ',strip=True) for n in card.find_all() if not n.find()]
        times=[t for t in texts if re.match(r'^\d{2}:\d{2}',t)]
        duration=next((t for t in texts if re.search(r'\d+\s*h.*\d+\s*m',t)),None)
        if len(times)<2:
            continue
        if not matches_station(times[0],origin) or not matches_station(times[1],destination):
            continue
        try:
            departure,arrival=timed_service(day,times[0],times[1],duration)
        except ValueError:
            continue
        for box in card.select('.cache-card-wrapper, ._cache-card'):
            lines=list(box.stripped_strings)
            fare_class=next((t for t in lines if t in ('SL','3E','3A','2A','1A','CC','2S','EC','3P','FC')),None)
            if not fare_class:
                continue
            fare=next((money_minor(t) for t in lines if '₹' in t or re.match(r'^Rs',t)),None)
            status=next((t for t in lines if re.search(r'WL|Available|AVL|Regret|RAC|Seats?|CNF',t,re.I) and 'Refund' not in t),'Unknown')
            availability,seats=normalize_availability(status)
            update=next((t for t in lines if 'ago' in t or t.lower()=='now'),'')
            observed=observed_time(update,now)
            legs.append(Leg(id=f'rail:{service}:{origin}:{destination}:{day}:{fare_class}',
                provider='confirmtkt',service_id=f'{service}:{day}',route_geometry_key=f'rail:{service}',name=name,mode='train',
                origin=origin,destination=destination,departure=departure,arrival=arrival,
                fare_minor=fare,availability=availability,seats=seats,fare_class=fare_class,
                booking_url=url,observation=Observation(provider='confirmtkt',evidence='cached' if observed else 'scheduled',
                fetched_at=now,observed_at=observed,expires_at=(observed or now)+timedelta(minutes=15),source_url=url)))
    return list({leg.id:leg for leg in legs}.values())


class RailProvider:
    name='confirmtkt'
    def __init__(self,enabled=True,browser=False,collector=collect_html):
        self.enabled,self.browser,self.collector=enabled,browser,collector

    async def search_progressive(self,request,pairs,now,progress):
        return await self.search(request,pairs,now,progress)

    async def search(self,request,pairs,now,progress=None):
        if not self.enabled or 'train' not in request.modes:
            return ProviderResult(provider=self.name,status='disabled',message='Train collection disabled')
        queries=[(a,b) for a,b in pairs if a.codes.get('rail') and b.codes.get('rail')]
        return await self._search_pairs(request,queries,now,progress)

    async def _search_pairs(self,request,pairs,now,progress=None):
        legs=[]
        errors=date_coverage_warning(request)
        query_limit=asyncio.Semaphore(1 if self.browser else 2)
        started=time.monotonic()

        async def collect_with_budget(url):
            remaining=22-(time.monotonic()-started)
            if remaining<=0:
                raise TimeoutError('Rail source budget exhausted')
            return await asyncio.wait_for(self.collector(url,self.browser),remaining)

        async def query(a,b,day):
            url=f'https://www.confirmtkt.com/rbooking/trains/from/{a.codes["rail"]}/to/{b.codes["rail"]}/{day:%d-%m-%Y}'
            try:
                try:
                    html=await collect_with_budget(url)
                    parsed=parse_confirmtkt(html,a.id,b.id,day,now,url)
                except asyncio.CancelledError:
                    raise
                except Exception:
                    parsed=[]
                if not parsed:
                    fallback=f'https://www.railyatri.in/booking/trains-between-stations?from_code={a.codes["rail"]}&to_code={b.codes["rail"]}&journey_date={day:%d-%m-%Y}'
                    fallback_html=await collect_with_budget(fallback)
                    parsed=parse_railyatri(fallback_html,a.id,b.id,day,now,fallback)
                    if not parsed:
                        errors.append('No verified train cards parsed; inventory unknown')
                legs.extend(parsed)
                if progress and parsed:
                    progress(ProviderResult(provider=self.name,status='partial',legs=list(legs),
                        message='Train observations received; requested corridor prioritized'))
                return True
            except asyncio.CancelledError:
                raise
            except Exception as error:
                errors.append(type(error).__name__)
                return False

        # Preserve the requested rail corridor before spending the source
        # budget on hub combinations.  A direct result is useful immediately;
        # unrelated alternatives can wait for a later explicit search.
        pairs=[(a,b) for a,b in pairs if a.codes.get('rail') and b.codes.get('rail')]
        exact=[(a,b) for a,b in pairs if a.id==request.origin and b.id==request.destination]
        pairs=(exact+[pair for pair in pairs if pair not in exact])[:4]
        days=search_days(request)
        direct=pairs[:1]
        primary_ok=True
        if direct:
            for day in days:
                primary_ok=await query(direct[0][0],direct[0][1],day)
                if not primary_ok or time.monotonic()-started>=20:
                    break

        async def bounded_query(a,b,day):
            async with query_limit:
                return await query(a,b,day)

        if not legs and primary_ok and len(pairs)>1:
            remaining=[(a,b,day) for day in days for a,b in pairs[1:]]
            remaining_budget=max(0.1,22-(time.monotonic()-started))
            await finish_queries((bounded_query(*item) for item in remaining),errors,timeout=remaining_budget)
        return ProviderResult(provider=self.name,status='partial' if legs and errors else 'ok' if legs else 'error' if errors else 'empty',
            legs=legs,message='; '.join(sorted(set(errors))) or 'Parsed train observations; live running status not supplied')


def parse_railyatri(html,origin,destination,day,now,url):
    soup=BeautifulSoup(html,'html.parser')
    legs=[]
    seen=set()
    for link in soup.select('a[href*="/time-table/"]'):
        match=re.search(r'\b\d{5}\b',link.get('href',''))
        if not match or match[0] in seen:
            continue
        seen.add(match[0])
        card=link.find_parent('div',class_=lambda c:c and 'MuiPaper-root' in c)
        if not card:
            continue
        timings=card.select('p.css-1tiw2we')
        if len(timings)<2:
            continue
        if not matches_station(timings[0].get_text(' ',strip=True),origin) or not matches_station(timings[1].get_text(' ',strip=True),destination):
            continue
        clocks=[re.search(r'\d{2}:\d{2}',t.get_text()) for t in timings[:2]]
        duration=card.select_one('.css-rv7cml')
        if not all(clocks) or duration is None:
            continue
        duration_match=re.search(r'\d+\s*h\s*\d+\s*m',duration.get_text())
        if not duration_match:
            continue
        try:
            departure,arrival=timed_service(day,clocks[0][0],clocks[1][0],duration_match[0])
        except ValueError:
            continue
        for box in card.select('.Mui-seat__layout_box'):
            def text(selector):
                element=box.select_one(selector)
                return element.get_text(' ',strip=True) if element else ''
            fare_class=text('.Mui-booking_class__title')
            availability,seats=normalize_availability(text('.Mui-availibility_and__seat_count_title'))
            legs.append(Leg(id=f'rail:{match[0]}:{origin}:{destination}:{day}:{fare_class}',provider='railyatri',
                service_id=f'{match[0]}:{day}',route_geometry_key=f'rail:{match[0]}',name=link.get_text(' ',strip=True),mode='train',origin=origin,destination=destination,
                departure=departure,arrival=arrival,fare_minor=money_minor(text('.Mui-booking_class__price')),
                availability=availability,seats=seats,fare_class=fare_class,booking_url=url,
                observation=Observation(provider='railyatri',evidence='cached',fetched_at=now,
                    expires_at=now+timedelta(minutes=5),source_url=url)))
    return legs
