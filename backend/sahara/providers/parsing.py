import re
from datetime import datetime, timedelta
from decimal import Decimal, InvalidOperation
from zoneinfo import ZoneInfo
from ..domain import Availability

IST=ZoneInfo('Asia/Kolkata')


def money_minor(value):
    text=str(value or '').strip()
    if not text or re.search(r'unknown|n/a|\$|€|£',text,re.I):
        return None
    match=re.fullmatch(r'(?:₹|Rs\.?|INR)?\s*([\d,]+(?:\.\d{1,2})?)',text,re.I)
    if not match:
        return None
    try:
        return int(Decimal(match[1].replace(',',''))*100)
    except InvalidOperation:
        return None


def normalize_availability(value):
    text=str(value or '').upper().strip()
    if re.search(r'NOT AVAILABLE|NO SEATS|NO BERTHS|SOLD OUT|REGRET|\b0\s*(?:SEATS?|BERTHS?)\b',text):
        return Availability.SOLD_OUT,0
    if re.search(r'\b(?:GNWL|RLWL|PQWL|TQWL|WL|WAITLIST)',text):
        return Availability.WAITLISTED,None
    if re.search(r'\bRAC\b',text):
        return Availability.RAC,None
    if re.search(r'\bCNF|CONFIRMED',text):
        return Availability.CONFIRMED,None
    if re.search(r'\bAVL\b|AVAILABLE|\d+\s*(?:SEATS?|BERTHS?)',text):
        match=re.search(r'\d+',text)
        seats=int(match[0]) if match else None
        return (Availability.SOLD_OUT if seats==0 else Availability.AVAILABLE),seats
    return Availability.UNKNOWN,None


def duration_minutes(value):
    text=str(value or '')
    colon=re.fullmatch(r'\s*(\d{1,3}):([0-5]?\d)\s*(?:HRS?|HOURS?)?\s*',text,re.I)
    if colon:
        return int(colon[1])*60+int(colon[2])
    if ':' in text:
        return None
    match=re.search(r'(\d+)\s*h(?:rs?)?\s*(?:(\d+)\s*m)?',text,re.I)
    if match:
        return int(match[1])*60+int(match[2] or 0)
    return None


def timed_service(day,departure,arrival,duration):
    def parse(value):
        match=re.search(r'\b(\d{1,2}):(\d{2})\b',str(value))
        if not match:
            raise ValueError('Missing service clock time')
        return datetime(day.year,day.month,day.day,int(match[1]),int(match[2]),tzinfo=IST)
    dep,arr=parse(departure),parse(arrival)
    minutes=duration_minutes(duration)
    if minutes is None or minutes<=0:
        raise ValueError('Duration needed to verify arrival date')
    expected=dep+timedelta(minutes=minutes)
    arr=arr.replace(year=expected.year,month=expected.month,day=expected.day)
    if abs((arr-expected).total_seconds())>120:
        raise ValueError('Arrival clock and service duration disagree')
    return dep,arr


def observed_time(text,now):
    match=re.search(r'(\d+)\s*(min|hr|hour|day)',text,re.I)
    if match:
        return now-timedelta(minutes=int(match[1])*{'min':1,'hr':60,'hour':60,'day':1440}[match[2].lower()])
    return now if re.search(r'\bnow\b|just updated',text,re.I) else None


def search_days(request):
    start=request.depart_after.astimezone(IST).date()
    end=(request.arrive_by or request.depart_after+timedelta(days=2)).astimezone(IST).date()
    # Bound provider requests; farther dates are disclosed as incomplete coverage.
    return [start+timedelta(days=i) for i in range(min((end-start).days+1,2))]


def date_coverage_warning(request,limit=2):
    start=request.depart_after.astimezone(IST).date()
    end=(request.arrive_by or request.depart_after+timedelta(days=2)).astimezone(IST).date()
    return ['Service date coverage incomplete: only the first '+str(limit)+' dates checked'] if (end-start).days+1>limit else []


async def finish_queries(coroutines,errors,timeout=22):
    import asyncio
    tasks=[asyncio.create_task(c) for c in coroutines]
    if not tasks:
        return
    try:
        done,pending=await asyncio.wait(tasks,timeout=timeout)
        if pending:
            errors.append('Some searches timed out; coverage incomplete')
            for task in pending: task.cancel()
        await asyncio.gather(*tasks,return_exceptions=True)
    finally:
        for task in tasks:
            if not task.done(): task.cancel()
        await asyncio.gather(*tasks,return_exceptions=True)
