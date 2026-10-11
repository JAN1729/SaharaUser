"""The existing Android response contract, backed by validated journeys."""
from zoneinfo import ZoneInfo
from .places import TERMINALS

IST=ZoneInfo('Asia/Kolkata')


def android_response(record):
    journeys=record['plan']['journeys']
    sample=record['request']['data_mode']=='sample'
    lines=['Sample journey data — schedules, prices and seats are illustrative.' if sample else 'Journeys from checked sources. Availability can change before booking.']
    for index,journey in enumerate(journeys[:3],1):
        cost=f'₹{journey["total_fare_minor"]/100:,.0f}' if journey['total_fare_minor'] is not None else f'₹{journey["known_fare_minor"]/100:,.0f} known; total incomplete'
        lines.append(f'\n{index}. {" + ".join(l["mode"] for l in journey["legs"])} · {cost} · {journey["duration_minutes"]//60}h {journey["duration_minutes"]%60}m')
        for leg in journey['legs']:
            from datetime import datetime
            start=datetime.fromisoformat(leg.get('estimated_departure') or leg['departure']).astimezone(IST)
            end=datetime.fromisoformat(leg.get('estimated_arrival') or leg['arrival']).astimezone(IST)
            lines.append(f'{TERMINALS[leg["origin"]].name} {start:%d %b %H:%M} → {TERMINALS[leg["destination"]].name} {end:%d %b %H:%M}')
        for connection in journey['connections']:
            if connection['kind']=='transfer':
                lines.append(f'Transfer: {connection["transfer_minutes"]} min travel, {connection["processing_minutes"]} min processing, {connection["spare_minutes"]} min spare after buffer.')
    complete=record.get('collection_complete',True)
    if not complete:
        lines.append('Additional sources are still loading. Check this search for updated options.')
    if not journeys and complete:
        lines.append('No verified journey found in the checked coverage. Try a wider window or another listed terminal.')
    unavailable=[p['provider'] for p in record['providers'] if p['status'] not in ('ok','empty')]
    if unavailable:
        lines.append('Incomplete coverage: '+', '.join(unavailable)+'.')
    tools=[]
    # Sources provide timetable evidence, not verified route geometry or vehicle GPS.
    return {'success':bool(journeys),'response':'\n'.join(lines),'intent':'MULTIMODAL_JOURNEY',
        'journeys':journeys,'searchId':record['id'],'dataMode':record['request']['data_mode'],
        'collectionComplete':complete,'pendingProviders':record.get('pending_providers',[]),
        'providers':[{k:v for k,v in p.items() if k!='legs'} for p in record['providers']],
        'placeNames':{id:t.name for id,t in TERMINALS.items()},
        'coverage':record.get('coverage','Bounded verified terminal mappings'),
        'collectionError':record.get('collection_error'),
        'toolCalls':tools,'agentTrace':[{'agent':'ConnectionPlanner','action':'validate_timetable','success':True}],
        'executionTimeMs':0}
