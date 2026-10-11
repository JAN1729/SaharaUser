"""Exercise the running local API without live providers or external effects."""
import json
import time
from datetime import datetime,timedelta,timezone
import httpx


def main():
    with httpx.Client(base_url='http://127.0.0.1:8787',timeout=30) as client:
        started=time.perf_counter()
        diagnostic=client.get('/')
        diagnostic.raise_for_status()
        assert 'Sahara backend diagnostics' in diagnostic.text
        request={'origin':'kty','destination':'sbc','depart_after':(datetime.now(timezone.utc)+timedelta(days=1)).isoformat(),
                 'data_mode':'sample','passengers':2}
        response=client.post('/v1/journeys/search',json=request)
        response.raise_for_status()
        body=response.json()
        assert body['plan']['journeys']
        assert all('sample_data' in j['warnings'] for j in body['plan']['journeys'])
        chosen=next(j for j in body['plan']['journeys'] if len(j['legs'])>1)
        changed=client.post('/v1/journeys/'+chosen['id']+'/revalidate',json={'sample_delay_minutes':180})
        changed.raise_for_status()
        assert not changed.json()['original_valid']
        assert changed.json()['alternatives']
        watch=client.post('/v1/journeys/'+chosen['id']+'/watch')
        watch.raise_for_status()
        events=client.get('/v1/watches/'+watch.json()['id']+'/events')
        events.raise_for_status()
        stopped=client.delete('/v1/watches/'+watch.json()['id'])
        stopped.raise_for_status()
        assert not stopped.json()['active']
        stream=client.post('/process-query',json={'query':'Kottayam to Bengaluru tomorrow','dataMode':'sample'})
        stream.raise_for_status()
        frames=[json.loads(f) for f in stream.text.split('\n\n') if f.strip()]
        assert frames[-1]['type']=='final_response' and frames[-1]['data']['success']
        blocked=client.post('/v1/journeys/search',json=dict(request,data_mode='live'))
        assert blocked.status_code==401
        print(json.dumps({'search_status':response.status_code,'journeys':len(body['plan']['journeys']),
            'disrupted_original_valid':changed.json()['original_valid'],
            'recovery_alternatives':len(changed.json()['alternatives']),
            'chat_frames':[f['type'] for f in frames],'unkeyed_live_status':blocked.status_code,
            'watch_stopped':not stopped.json()['active'],'diagnostic_status':diagnostic.status_code,
            'elapsed_seconds':round(time.perf_counter()-started,3)},indent=2))


if __name__=='__main__':
    main()
