"""Check the running decision integration using synthetic records and owner isolation."""
import json
import secrets
from datetime import datetime, timedelta, timezone
from pathlib import Path
import httpx


def main():
    key=Path('data/server-access-key.txt').read_text(encoding='utf-8').strip()
    headers={'Authorization':'Bearer '+key,'X-Sahara-Session':secrets.token_urlsafe(32)}
    now=datetime.now(timezone.utc)
    record={'id':'synthetic-external','source':'synthetic-smoke','kind':'stop','name':'Mavoor Road Junction',
        'lat':11.26,'lng':75.78,'corridor':'mavoor-medical','direction':'outbound',
        'observed_at':now.isoformat(),'expires_at':(now+timedelta(hours=1)).isoformat()}
    candidate=dict(record,id='synthetic-canonical',name='Mavoor Rd Jn')
    with httpx.Client(base_url='http://127.0.0.1:8787',timeout=10,headers=headers) as client:
        health=client.get('/health')
        health.raise_for_status()
        rules=client.post('/v1/intelligence/reconcile',json={'record':record,'candidates':[candidate]})
        rules.raise_for_status()
        assert rules.json()['results'][0]['status']=='verified'
        semantic=client.post('/v1/intelligence/reconcile',json={'record':dict(record,name='Main Mavoor boarding place'),'candidates':[candidate]})
        semantic.raise_for_status()
        decision=semantic.json()['results'][0]
        assert decision['engine']=='jev' and decision['status']!='verified'
        audits=client.get('/v1/intelligence/reconciliations')
        audits.raise_for_status()
        assert len(audits.json()['records'])==2
        other=client.get('/v1/intelligence/reconciliations',headers={'X-Sahara-Session':secrets.token_urlsafe(32)})
        assert other.json()['records']==[]
        diagnostics=client.get('/v1/intelligence/health')
        diagnostics.raise_for_status()
        print(json.dumps({'health':health.json(),'rule_match':rules.json()['results'][0]['status'],
            'semantic_match':decision['status'],'decision_status':decision.get('decision_status'),
            'owner_isolation':True,'diagnostics':diagnostics.json(),
            'notice':'Synthetic records; this check does not establish transport data accuracy.'},indent=2))


if __name__=='__main__': main()
