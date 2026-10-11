"""Read-only live bus check through the running local Sahara server."""
import json
import argparse
import os
from collections import Counter
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo
import httpx


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--days',type=int,default=1,choices=range(1,8))
    args=parser.parse_args()
    key = os.getenv('SAHARA_API_KEY') or (Path(__file__).resolve().parents[1] / 'data' / 'server-access-key.txt').read_text().strip()
    start = datetime.now(ZoneInfo('Asia/Kolkata')).replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=args.days)
    session = os.urandom(24).hex()
    with httpx.Client(base_url='http://127.0.0.1:8787', timeout=55,
                      headers={'Authorization': 'Bearer ' + key, 'X-Sahara-Session': session}) as client:
        response = client.post('/v1/journeys/search', json={
            'origin': 'kozhikode-bus', 'destination': 'bengaluru-bus', 'depart_after': start.isoformat(),
            'arrive_by': (start + timedelta(hours=23, minutes=59)).isoformat(), 'modes': ['bus'],
            'data_mode': 'live', 'wait_for_complete': True})
        response.raise_for_status()
        record = response.json()
        mobile = client.get('/v1/searches/' + record['id'] + '/response')
        mobile.raise_for_status()
        assert mobile.json()['toolCalls'] == []
        assert mobile.json()['dataMode'] == 'live'
        assert all(leg['observation']['evidence'] != 'fixture' for provider in record['providers'] for leg in provider['legs'])
        print(json.dumps({'status': response.status_code, 'complete': record['collection_complete'],
            'providers': [{'name': p['provider'], 'status': p['status'], 'legs': len(p['legs']), 'message': p['message']} for p in record['providers']],
            'planned_journeys': len(record['plan']['journeys']),
            'rejection_reasons': dict(Counter(r['reason'] for r in record['plan']['rejected'])),
            'mobile_contract_status': mobile.status_code, 'invented_route_geometry': False}, indent=2))


if __name__ == '__main__':
    main()
