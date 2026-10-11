"""Durable opt-in journey revalidation; stores changes, never sends messages."""
import asyncio
import hashlib
import json
from datetime import datetime, timedelta, timezone
from .service import PlanningService
from .settings import Settings


async def check_watches(service):
    # Claim individually so queued watches do not spend their lease waiting for prior requests.
    for _ in range(100):
        now=datetime.now(timezone.utc)
        watch=service.repository.claim_due_watch(now)
        if watch is None:
            break
        owner=watch.pop('_owner')
        token=watch['lease_token']
        watch.pop('notice_retry_after',None)
        event=None
        try:
            outcome=await service.revalidate(watch['journey_id'],owner)
            saved=service.repository.save_tracking(watch['journey_id'],owner,outcome)
            if 'progress_snapshot' in outcome and not saved:
                watch['next_check']=(now+timedelta(seconds=30)).isoformat()
                watch['notice_retry_after']=watch['next_check']
                watch['message']='Travel progress changed during refresh; retrying with current progress.'
                service.repository.finish_watch(watch,owner,token)
                continue
            watch['_notice_checked']=True
            watch['_notice_progress_snapshot']=outcome.get('progress_snapshot')
            if outcome.get('completed'):
                watch['active']=False
            snapshot={'valid':outcome['original_valid'],'requires_progress':outcome.get('requires_progress',False),
                'completed':outcome.get('completed',False),'alternatives':[
                {'services':[(l['provider'],l['service_id'],l.get('fare_class'),l.get('estimated_departure') or l.get('departure'),l.get('estimated_arrival') or l['arrival']) for l in j['legs']],
                 'fare':j['total_fare_minor'],'known_fare':j.get('known_fare_minor'),
                 'departure':j.get('departure'),'arrival':j['arrival'],'warnings':j['warnings']} for j in outcome['alternatives']],
                'coverage':[(p['provider'],p['status']) for p in outcome['providers']]}
            digest=hashlib.sha256(json.dumps(snapshot,sort_keys=True).encode()).hexdigest()
            if digest!=watch.get('last_signature'):
                event=dict(outcome,watch_id=watch['id'])
            watch['last_signature']=digest
            watch['next_check']=(now+timedelta(minutes=5)).isoformat()
        except (LookupError,ValueError):
            watch['active']=False
            watch['message']='Journey finished, invalid or no longer accessible; watch stopped.'
        except Exception:
            watch['next_check']=(now+timedelta(minutes=5)).isoformat()
            if watch.get('_notice_ids'): watch['notice_retry_after']=watch['next_check']
            watch['message']='Refresh unavailable; previous itinerary retained.'
        service.repository.finish_watch(watch,owner,token,event)


async def main():
    service=PlanningService(Settings())
    while True:
        await check_watches(service)
        await asyncio.sleep(30)


if __name__=='__main__':
    asyncio.run(main())
