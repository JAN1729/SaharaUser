import asyncio
import re
import time
from urllib.parse import urlencode
from datetime import datetime,timedelta
from bs4 import BeautifulSoup
from ..domain import Leg, Observation, ProviderResult
from .parsing import money_minor, normalize_availability, duration_minutes, timed_service, IST, search_days, finish_queries, date_coverage_warning
from .collector import collect_html
from ..places import TERMINALS


def parse_ksrtc_json(rows,origin,destination,day,now,url):
    if not isinstance(rows,list):
        raise ValueError('Unrecognized operator inventory response')
    expected_from=TERMINALS[origin].codes['ksrtc'].split('|')[0]
    expected_to=TERMINALS[destination].codes['ksrtc'].split('|')[0]
    def local_time(value):
        parsed=datetime.fromisoformat(value)
        return parsed if parsed.tzinfo else parsed.replace(tzinfo=IST)
    legs=[]
    for row in rows:
        try:
            if str(row['FromCityID'])!=expected_from or str(row['ToCityID'])!=expected_to or row['JourneyDate']!=str(day) or str(row.get('DefaultCurrencyID'))!='1':
                continue
            cancelled=str(row.get('IsCancelled',False)).strip().lower()
            if cancelled not in ('true','false','0','1'):
                continue
            departure,arrival=local_time(row['DepartureTime']),local_time(row['ArrivalTime'])
            if departure.astimezone(IST).date()!=day:
                continue
            seats=row.get('AvailableSeats')
            if seats is not None and (isinstance(seats,bool) or not isinstance(seats,int) or seats<0):
                seats=None
            availability='unknown' if seats is None else 'available' if seats else 'sold_out'
            opens=local_time(row['BookingAllowedDate']) if row.get('BookingAllowedDate') else None
            service=f'{row["CompanyId"]}:{row["ServiceID"]}:{row["TripID"]}:{row["ChartDate"]}'
            human_url='https://onlineksrtcswift.com/search?'+urlencode({'fromCity':TERMINALS[origin].codes['ksrtc'],
                'toCity':TERMINALS[destination].codes['ksrtc'],'departDate':f'{day:%d-%m-%Y}','mode':'oneway','src':'h'})
            legs.append(Leg(id=f'ksrtc:{row["RouteScheduleId"]}',provider='ksrtc',service_id=service,
                name=f'{row["TripCode"]} · {row.get("ServiceType","KSRTC bus")}',mode='bus',origin=origin,destination=destination,
                departure=departure,arrival=arrival,fare_minor=money_minor(row.get('Fare')),availability=availability,seats=seats,
                booking_opens_at=opens,cancelled=cancelled in ('true','1'),booking_url=human_url,
                observation=Observation(provider='ksrtc',evidence='cached',fetched_at=now,expires_at=now+timedelta(minutes=5),
                    source_url=url,parser_version='ksrtc-json-v4-1')))
        except (KeyError,TypeError,ValueError):
            continue
    return list({leg.id:leg for leg in legs}.values())


def parse_ksrtc(html,origin,destination,day,now,url):
    legs=[]
    for index,card in enumerate(BeautifulSoup(html,'html.parser').select('.srch-card')):
        lines=list(card.stripped_strings)
        if len(lines)<6:
            continue
        # Current cards may split clocks and place labels into separate children.
        timings=card.select('.triptimebold')
        times=[node.get_text(' ',strip=True) for node in timings] if len(timings)>=2 else [t for t in lines if re.match(r'^\d{2}:\d{2}',t)]
        duration_node=card.select_one('.duration')
        duration=duration_node.get_text(' ',strip=True) if duration_node else next((t for t in lines if not re.match(r'^\d{2}:\d{2}',t) and duration_minutes(t) is not None),None)
        if len(times)<2:
            continue
        def matches(label,terminal_id):
            terminal=TERMINALS.get(terminal_id)
            if not terminal:
                return False
            display=terminal.codes.get('ksrtc','').split('|')[-1]
            display=re.sub(r'\([^)]*\)','',display).strip()
            terms=[terminal.city,display,*terminal.aliases]
            label=label.lower().replace('bangalore','bengaluru').replace('trivandrum','thiruvananthapuram')
            return any(term and term.lower().replace('bangalore','bengaluru').replace('trivandrum','thiruvananthapuram') in label for term in terms)
        if not matches(times[0],origin) or not matches(times[1],destination):
            continue
        try:
            departure,arrival=timed_service(day,times[0],times[1],duration)
            # A stale/wrong-date page must not be re-labelled with the requested day.
            for label,expected in zip(times[:2],(departure,arrival)):
                shown=re.search(r'\b(\d{1,2})\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)(?:\s+(\d{4}))?\b',label,re.I)
                if shown:
                    month=['jan','feb','mar','apr','may','jun','jul','aug','sep','oct','nov','dec'].index(shown[2].lower())+1
                    if int(shown[1])!=expected.day or month!=expected.month or shown[3] and int(shown[3])!=expected.year:
                        raise ValueError('Displayed service date disagrees with requested date or duration')
        except ValueError:
            continue
        fare=None
        for i,line in enumerate(lines):
            if line=='₹' and i+1<len(lines):
                fare=money_minor('₹'+lines[i+1])
            elif '₹' in line:
                fare=money_minor(line)
        status=next((t for t in lines if re.search(r'Seats?|Berths?|Available|Sold out',t,re.I) and not re.search(r'Select|View',t,re.I)),'Unknown')
        availability,seats=normalize_availability(status)
        view=next((i for i,t in enumerate(lines) if 'View All' in t or t=='₹'),None)
        route_code=card.select_one('.service--route')
        bus_type=card.select_one('.bus--type')
        if route_code:
            name=route_code.get_text(strip=True)
            if bus_type:
                name+=' · '+bus_type.get_text(strip=True)
        else:
            name=lines[view-2] if view is not None and view>3 else 'KSRTC service'
        service=f'{origin}:{destination}:{day}:{departure:%H%M}:{name}'
        booking=re.search(r'Booking for this distance starts from\s+(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})',' '.join(lines),re.I)
        legs.append(Leg(id=f'ksrtc:{service}',provider='ksrtc',service_id=service,name=name,mode='bus',
            route_geometry_key='ksrtc:'+route_code.get_text(strip=True) if route_code else None,
            origin=origin,destination=destination,departure=departure,arrival=arrival,fare_minor=fare,
            availability=availability,seats=seats,booking_url=url,
            booking_opens_at=datetime.fromisoformat(booking[1]).replace(tzinfo=IST) if booking else None,
            observation=Observation(provider='ksrtc',evidence='cached',fetched_at=now,
                expires_at=now+timedelta(minutes=5),source_url=url)))
    return list({leg.id:leg for leg in legs}.values())


class KSRTCProvider:
    name='ksrtc'
    def __init__(self,enabled=True,browser=True,collector=collect_html):
        self.enabled,self.browser,self.collector=enabled,browser,collector

    async def search_progressive(self,request,pairs,now,progress):
        return await self.search(request,pairs,now,progress)

    async def search(self,request,pairs,now,progress=None):
        if not self.enabled or 'bus' not in request.modes:
            return ProviderResult(provider=self.name,status='disabled',message='KSRTC collection disabled')
        legs,errors=[],date_coverage_warning(request)
        started=time.monotonic()
        async def query(a,b,day):
            url='https://onlineksrtcswift.com/search?'+urlencode({'fromCity':a.codes['ksrtc'],'toCity':b.codes['ksrtc'],
                'departDate':f'{day:%d-%m-%Y}','mode':'oneway','src':'h'})
            try:
                remaining=22-(time.monotonic()-started)
                if remaining<=0:
                    raise TimeoutError('KSRTC source budget exhausted')
                html=await asyncio.wait_for(self.collector(url,self.browser),remaining)
                parsed=parse_ksrtc(html,a.id,b.id,day,now,url)
                if not parsed:
                    errors.append('No verified bus cards parsed; inventory unknown')
                legs.extend(parsed)
                if progress and parsed:
                    progress(ProviderResult(provider=self.name,status='partial',legs=list(legs),
                        message='Public webpage inventory received; requested corridor prioritized'))
                return True
            except asyncio.CancelledError:
                raise
            except Exception as error:
                errors.append(type(error).__name__)
                return False

        # Keep the exact requested bus corridor first.  A slow or unreachable
        # public host must not spend the entire provider deadline on unrelated
        # hub combinations before the requested route gets a chance to render.
        queries=[(a,b) for a,b in pairs if a.codes.get('ksrtc') and b.codes.get('ksrtc')]
        exact=[(a,b) for a,b in queries if a.id==request.origin and b.id==request.destination]
        queries=(exact+[pair for pair in queries if pair not in exact])[:4]
        days=search_days(request)
        direct=queries[:1]
        if direct:
            primary_ok=True
            # Check the requested corridor for each covered day before spending
            # the remaining budget on hub alternatives.  Once a direct public
            # card is verified, those alternatives add latency without improving
            # the user's requested direct result.
            for day in days:
                primary_ok=await query(direct[0][0],direct[0][1],day)
                if not primary_ok or time.monotonic()-started>=20:
                    break
            if not legs and primary_ok and len(queries)>1:
                remaining=[(a,b,day) for day in days for a,b in queries[1:]]
                remaining_budget=max(0.1,22-(time.monotonic()-started))
                await finish_queries((query(*item) for item in remaining),errors,timeout=remaining_budget)
        return ProviderResult(provider=self.name,status='partial' if legs and errors else 'ok' if legs else 'error' if errors else 'empty',
            legs=legs,message='; '.join(sorted(set(errors))) or 'Public webpage inventory; upstream update age, exact boarding/drop points and final booking fees unverified')
