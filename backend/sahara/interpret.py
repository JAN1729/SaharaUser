import re
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
from decimal import Decimal
from .domain import SearchRequest, Mode
from .places import resolve, is_kerala


def explicit_constraints(query):
    result={}
    if re.search(r'\b(?:cheapest|lowest\s+(?:fare|price|cost))\b',query,re.I):
        result['preference']='cheapest'
    elif re.search(r'\b(?:comfortable|comfort|convenient|fewest transfers|less walking)\b',query,re.I):
        result['preference']='comfortable'
    for budget in re.finditer(r'(?:under|budget|₹)\s*(\d[\d,]*(?:\.\d{1,2})?)(?![\d.])',query,re.I):
        before=query[max(0,budget.start()-30):budget.start()]
        after=query[budget.end():]
        if re.match(r'\s*(?:more|extra|less)\b',after,re.I): continue
        if re.search(r'\b(?:walk|walking)\s*$',before,re.I) or re.match(r'\s*(?:m|km|metres?|meters?|minutes?|hours?)\b',after,re.I): continue
        result['budget_minor']=int(Decimal(budget[1].replace(',',''))*100)
        break
    transfers=re.search(r'\b(?:at most|maximum|max|up to)\s+(\d+)\s+transfers?\b',query,re.I)
    if transfers: result['max_transfers']=int(transfers[1])
    if re.search(r'\b(?:no transfers|direct only)\b',query,re.I): result['max_transfers']=0
    walking=re.search(r'\bwalk(?:ing)?\s*(?:under|up to|at most)?\s*(\d+(?:\.\d+)?)\s*(km|kilometres?|kilometers?|m|metres?|meters?)\b',query,re.I)
    if walking: result['max_walk_m']=int(Decimal(walking[1])*(1000 if walking[2].lower().startswith('k') else 1))
    if re.search(r'\b(?:no walking|avoid walking)\b',query,re.I): result['max_walk_m']=0
    if re.search(r'\b(?:wheelchair|step.free)\b',query,re.I): result['wheelchair']=True
    people=re.search(r'\bfor\s+(\d+)\s+(?:people|persons|passengers|travellers|travelers)\b',query,re.I)
    if people: result['passengers']=int(people[1])
    names=r'trains?|bus(?:es)?|flights?|local|autos?|metros?|ferries|private'
    only=re.search(r'\bonly\s+((?:'+names+r')(?:\s*(?:,|and|or|/|\+)\s*(?:'+names+r'))*)',query,re.I)
    if only:
        result['modes']=list(dict.fromkeys('bus' if name.lower().startswith('bus') else 'ferry' if name.lower()=='ferries' else name.lower().rstrip('s')
            for name in re.findall(names,only[1],re.I)))
    return result


def relative_budget(query,previous=None):
    match=re.search(r'(?:₹\s*|\b(?:rs\.?|inr)\s*)(\d[\d,]*(?:\.\d{1,2})?)\s*(more|extra|less)\b',query,re.I)
    if not match: return {}
    current=(previous or {}).get('budget_minor')
    if current is None:
        raise ValueError('Give an absolute budget first before increasing or reducing it.')
    delta=int(Decimal(match[1].replace(',',''))*100)
    updated=current+(-delta if match[2].lower()=='less' else delta)
    if updated<0: raise ValueError('The reduced budget cannot be below zero.')
    return {'budget_minor':updated}


def interpret(query,previous=None,now=None):
    now=now or datetime.now(ZoneInfo('Asia/Kolkata'))
    text=query.strip()
    data=dict(previous or {})
    route_patterns=(
        r'\bfrom\s+(.+?)\s+(?:to|→)\s+(.+?)(?=\s+(?:(?:the )?day after tomorrow|tomorrow|today|on|by|before|after|under|budget|with|for|comfortable|comfort|convenient|cheapest|fastest|lowest|fewest|less|only|direct|avoid|no|wheelchair|step.free|max|maximum|at most|up to|walking|walk|train|bus|flight)\b|$)',
        r'(?:from\s+)?(.+?)\s+(?:to|→)\s+(.+?)(?=\s+(?:(?:the )?day after tomorrow|tomorrow|today|on|by|before|after|under|budget|with|for|comfortable|comfort|convenient|cheapest|fastest|lowest|fewest|less|only|direct|avoid|no|wheelchair|step.free|max|maximum|at most|up to|walking|walk|train|bus|flight)\b|$)',
        r'(.+?)\s+முதல்\s+(.+?)\s+വരെ(?=\s|$)',
        r'(.+?)\s+سے\s+(.+?)\s+تک(?=\s|$)',
        r'(.+?)\s+से\s+(.+?)\s+तक(?=\s|$)',
        r'(.+?)\s+ನಿಂದ\s+(.+?)\s+ಗೆ(?=\s|$)',
        r'(.+?)\s+இருந்து\s+(.+?)\s+വരെ(?=\s|$)',
        r'(.+?)\s+నుండి\s+(.+?)\s+వరకు(?=\s|$)',
        r'(.+?)\s+മുതൽ\s+(.+?)\s+വരെ(?=\s|$)',
    )
    match=None
    for pattern in route_patterns:
        match=re.search(pattern,text,re.I)
        if match:
            break
    if match:
        raw_origin = re.sub(
            r'^(?:please\s+)?(?:take me|travel|go|how (?:do|can) i get|how to get|how to go|can i get|plan (?:a\s+)?(?:journey|trip|route)?|find (?:a\s+)?(?:journey|trip|route|bus|train)?|show (?:me\s+)?(?:a\s+)?(?:journey|trip|route|bus|train)?|search (?:for\s+)?(?:a\s+)?(?:journey|trip|route)?|i want to (?:go|travel|reach)|get me|book)\s+(?:from\s+)?',
            '',
            match[1],
            flags=re.I
        ).strip()
        raw_origin = re.sub(r'^(?:train|bus|flight|trains|buses|flights)\s+(?:from\s+)?', '', raw_origin, flags=re.I).strip()
        raw_origin = re.sub(r'^from\s+', '', raw_origin, flags=re.I).strip()
        raw_dest = re.sub(r'\s+(?:by\s+)?(?:train|bus|flight|trains|buses|flights|tomorrow|today|day after tomorrow).*$', '', match[2], flags=re.I).strip(' ,;?!.')
        raw_dest = re.sub(r'\s+(?:train|bus|flight|trains|buses|flights)$', '', raw_dest, flags=re.I).strip(' ,;?!.')
        data['origin']=resolve(raw_origin).id
        data['destination']=resolve(raw_dest).id
    if not data.get('origin') or not data.get('destination'):
        raise ValueError('Please specify an origin and destination, for example: Kottayam to Bengaluru tomorrow.')
    day=now.astimezone(ZoneInfo('Asia/Kolkata')).replace(hour=0,minute=0,second=0,microsecond=0)
    if 'tomorrow' in text.lower() or 'നാളെ' in text:
        day+=timedelta(days=2 if re.search(r'\bday\s+after\s+tomorrow\b',text,re.I) else 1)
        data['depart_after']=day
        data.pop('arrive_by',None)
    elif 'today' in text.lower() or 'ഇന്ന്' in text:
        data['depart_after']=now
        data.pop('arrive_by',None)
    date_match=re.search(r'\b(\d{4}-\d{2}-\d{2})\b',text)
    if date_match:
        day=datetime.fromisoformat(date_match[1]).replace(tzinfo=ZoneInfo('Asia/Kolkata'))
        data['depart_after']=day
        data.pop('arrive_by',None)
    if not data.get('depart_after'):
        data['depart_after']=day+timedelta(days=1)
    for word,key in [('after','depart_after'),('by','arrive_by'),('before','arrive_by')]:
        clock=re.search(r'\b'+word+r'\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b',text,re.I)
        if clock:
            hour=int(clock[1])
            if clock[3]:
                hour=hour%12+(12 if clock[3].lower()=='pm' else 0)
            start=data['depart_after']
            if isinstance(start,str):
                start=datetime.fromisoformat(start)
            data[key]=start.astimezone(ZoneInfo('Asia/Kolkata')).replace(hour=hour,minute=int(clock[2] or 0))
    if 'cheapest' in text.lower(): data['preference']='cheapest'
    if 'fastest' in text.lower(): data['preference']='fastest'
    if 'robust' in text.lower() or 'reliable' in text.lower(): data['preference']='robust'
    constraints = explicit_constraints(text)
    data.update(constraints)
    data.update(relative_budget(text,previous))
    if 'modes' not in constraints:
        if re.search(r'\b(?:trains?|rail|railway)\b', text, re.I):
            data['modes'] = [Mode.TRAIN]
        elif re.search(r'\b(?:bus(?:es)?|ksrtc)\b', text, re.I):
            data['modes'] = [Mode.BUS]
        else:
            origin_id = data.get('origin')
            dest_id = data.get('destination')
            if origin_id and dest_id and not (is_kerala(origin_id) and is_kerala(dest_id)):
                default_modes = SearchRequest.model_fields['modes'].default
                data['modes'] = [m for m in default_modes if m != Mode.BUS]
    return SearchRequest.model_validate(data)
