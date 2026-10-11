"""Actual HTTP-contract test with live collectors. No source mocks or sample services."""
import argparse
import json
import time
import asyncio
from pathlib import Path
from datetime import datetime,timezone
from fastapi.testclient import TestClient
from sahara.api import create_app
from sahara.settings import Settings


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--origin',required=True)
    parser.add_argument('--destination',required=True)
    parser.add_argument('--depart-after',required=True)
    parser.add_argument('--arrive-by')
    parser.add_argument('--browser',action='store_true')
    parser.add_argument('--output',required=True)
    args=parser.parse_args()
    output=Path(args.output)
    output.parent.mkdir(parents=True,exist_ok=True)
    runtime=Settings()
    settings=Settings(database=output.parent/'live-test.sqlite3',api_key='isolated-local-test-gateway',
        scraping=True,browser=args.browser,duffel_key=runtime.duffel_key,local_feed=runtime.local_feed)
    request={'origin':args.origin,'destination':args.destination,'depart_after':args.depart_after,
        'arrive_by':args.arrive_by,'passengers':1,'data_mode':'live','preference':'balanced'}
    started=time.perf_counter()
    with TestClient(create_app(settings)) as client:
        call_started=time.perf_counter()
        response=client.post('/v1/journeys/search',json=request,headers={'Authorization':'Bearer '+settings.api_key})
        first_seconds=round(time.perf_counter()-call_started,3)
        payload=response.json()
        print(json.dumps({'phase':'first_response','seconds':first_seconds,'http_status':response.status_code,
            'journeys':len(payload.get('plan',{}).get('journeys',[])),'collection_complete':payload.get('collection_complete'),
            'providers':[{'provider':p['provider'],'status':p['status'],'legs':len(p['legs'])} for p in payload.get('providers',[])]}),flush=True)
        (output.parent/'first-response.json').write_text(json.dumps(payload,indent=2,ensure_ascii=False),encoding='utf-8')
        replay_started=time.perf_counter()
        replay=client.post('/v1/journeys/search',json=request,headers={'Authorization':'Bearer '+settings.api_key})
        replay_seconds=round(time.perf_counter()-replay_started,3)
        print(json.dumps({'phase':'replay','seconds':replay_seconds,'http_status':replay.status_code,
            'journeys':len(replay.json().get('plan',{}).get('journeys',[]))}),flush=True)
        final_payload=payload
        deadline=time.perf_counter()+30
        while response.status_code==200 and not final_payload.get('collection_complete',True) and time.perf_counter()<deadline:
            time.sleep(.25)
            refreshed=client.get('/v1/searches/'+payload['id'])
            refreshed.raise_for_status()
            final_payload=refreshed.json()
        (output.parent/'final-response.json').write_text(json.dumps(final_payload,indent=2,ensure_ascii=False),encoding='utf-8')
        warmed=[]
        for _ in range(5):
            tick=time.perf_counter()
            repeated=client.post('/v1/journeys/search',json=request,headers={'Authorization':'Bearer '+settings.api_key})
            repeated.raise_for_status()
            warmed.append(round(time.perf_counter()-tick,3))
        print(json.dumps({'phase':'completed_cache_replay','seconds':warmed,
            'replayed':repeated.json().get('replayed'),'journeys':len(repeated.json().get('plan',{}).get('journeys',[]))}),flush=True)
    report={'test_kind':'actual-public-source-collection-through-HTTP-contract','checked_at':datetime.now(timezone.utc).isoformat(),
        'request':request,'http_status':response.status_code,'elapsed_seconds':round(time.perf_counter()-started,3),
        'first_response_seconds':first_seconds,'replay_seconds':replay_seconds,
        'completed_replay_seconds':warmed,
        'configuration':{'browser_rendering':settings.browser,'flight_access_configured':bool(settings.duffel_key),
                         'local_feed_configured':bool(settings.local_feed),'gateway':'isolated local test app; no production credentials changed'},
        'location_assumption':'City input resolves to listed transport terminals; this is not a GPS-to-door test.',
        'response':payload,'final_response':final_payload}
    if response.status_code==200:
        legs=[l for p in final_payload['providers'] for l in p['legs']]
        assert not any(l['observation']['evidence']=='fixture' for l in legs),'Live test mixed fixture data'
        journeys=final_payload['plan']['journeys']
        assert not any('sample_data' in j['warnings'] for j in journeys),'Sample itinerary entered live test'
        report['outcome']='verified_source_journeys' if journeys else 'no_verified_itinerary_in_checked_sources'
        summary={'outcome':report['outcome'],'http_status':response.status_code,'request':request,'elapsed_seconds':report['elapsed_seconds'],
            'normalized_real_legs':len(legs),'journeys':len(journeys),
            'first_response_seconds':first_seconds,'replay_seconds':replay_seconds,
            'completed_replay_seconds':warmed,
            'collection_complete':final_payload.get('collection_complete'),
            'providers':[{'provider':p['provider'],'status':p['status'],'legs':len(p['legs']),'message':p['message']} for p in final_payload['providers']],
            'rejections':final_payload['plan']['rejected'][:10],
            'first_journeys':[{'departure':j['departure'],'arrival':j['arrival'],'known_fare_minor':j['known_fare_minor'],
                'total_fare_minor':j['total_fare_minor'],'warnings':j['warnings'],
                'legs':[{'name':l['name'],'mode':l['mode'],'provider':l['provider'],'departure':l['departure'],'arrival':l['arrival'],
                    'fare_minor':l['fare_minor'],'availability':l['availability'],'seats':l['seats']} for l in j['legs']]} for j in journeys[:3]]}
    else:
        report['outcome']='request_failed'
        summary={'outcome':report['outcome'],'http_status':response.status_code,'detail':payload}
    output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(summary,ensure_ascii=True,indent=2))


if __name__=='__main__': main()
