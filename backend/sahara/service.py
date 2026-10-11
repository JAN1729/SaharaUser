import uuid
import asyncio
from datetime import datetime, timedelta, timezone
from .domain import SearchRequest, Journey, ProviderResult, Leg, Mode
from .planner import plan
from .places import TERMINALS, resolve, transfer_catalog, discovery_pairs, is_kerala
from .broker import Broker
from .providers.sample import SampleProvider
from .providers.rail import RailProvider
from .providers.ksrtc import KSRTCProvider
from .providers.flights import FlightProvider
from .providers.local import LocalProvider
from .providers.collector import PageCollector
from .storage import Repository
from .geometry import GeometryRouter
from .fleet import ExistingDriverFeed
from .jev_decisions import JevDecisions
from .intelligence import MobilityIntelligence


class PlanningService:
    def __init__(self,settings):
        self.enrichments=set()
        self.replays={}
        self.repository=Repository(settings.database)
        self.decisions=JevDecisions(settings.openrouter_key if settings.jev_enabled else '',settings.jev_model,timeout=settings.jev_timeout)
        trusted_sources=[]
        for item in settings.notice_sources.split(';'):
            if not item.strip(): continue
            if '@' not in item: raise ValueError('Notice source must use source@hostname')
            source,host=item.strip().rsplit('@',1)
            if not source or not host or '/' in host or ':' in host: raise ValueError('Invalid notice source hostname')
            trusted_sources.append((source,host.lower()))
        self.intelligence=MobilityIntelligence(self.repository,self.decisions,settings.jev_accept_matches,trusted_sources)
        self.geometry=GeometryRouter.from_path(settings.route_graph)
        self.sample=Broker([SampleProvider()])
        self.collector=PageCollector()
        self.fleet=ExistingDriverFeed(settings.driver_url,settings.driver_key)
        self.live=Broker([RailProvider(settings.scraping,settings.browser,self.collector),
            KSRTCProvider(settings.scraping,settings.browser,self.collector),
            FlightProvider(enabled=settings.scraping,browser=settings.browser,collector=self.collector),LocalProvider(settings.local_feed,self.intelligence)])

    async def search(self,request,owner,now=None):
        now=now or datetime.now(timezone.utc)
        request=request.model_copy(update={'origin':resolve(request.origin).id,'destination':resolve(request.destination).id})
        if request.data_mode == 'live' and not (is_kerala(request.origin) and is_kerala(request.destination)):
            filtered = [m for m in request.modes if str(m) not in ('bus',) and m != Mode.BUS]
            if Mode.TRAIN not in filtered and 'train' not in filtered:
                filtered.append(Mode.TRAIN)
            request = request.model_copy(update={'modes': filtered})
        if request.origin==request.destination:
            raise ValueError('Origin and destination must be different')
        if request.depart_after < now-timedelta(minutes=5):
            raise ValueError('Choose a current or future departure window')
        if request.depart_after > now+timedelta(days=365):
            raise ValueError('Search window exceeds one year')
        replay_key=(owner,request.model_dump_json(exclude={'wait_for_complete'}))
        cached=self.replays.get(replay_key)
        generation=getattr(self.live,'generation',0)
        if request.data_mode=='live' and cached and cached[1]>now and cached[2]==generation and cached[3] is self.live:
            record=self.repository.get('search',cached[0],owner)
            if record and (not request.wait_for_complete or record.get('collection_complete')):
                return dict(record,replayed=True)
        broker=self.sample if request.data_mode=='sample' else self.live
        pairs=discovery_pairs(request.origin,request.destination)
        if request.data_mode=='live' and not request.wait_for_complete and hasattr(broker,'collect_fast'):
            results,pending=await broker.collect_fast(request,pairs,now)
            record=self._persist(request,results,owner,now,collection_complete=pending is None,
                pending_providers=broker.pending_names_key(pending.get_name()) if pending else [])
            self._remember(request,owner,record,now,generation)
            if pending:
                task=asyncio.create_task(self._enrich(record['id'],request,pairs,owner,pending,generation))
                self.enrichments.add(task)
                task.add_done_callback(self.enrichments.discard)
            return record
        results=await broker.collect(request,pairs,now)
        record=self._persist(request,results,owner,now)
        self._remember(request,owner,record,now,generation)
        return record

    def _remember(self,request,owner,record,now,generation=None):
        generation=getattr(self.live,'generation',0) if generation is None else generation
        if request.data_mode!='live' or not record['plan']['journeys'] or generation!=getattr(self.live,'generation',0):
            return
        key=(owner,request.model_dump_json(exclude={'wait_for_complete'}))
        current=self.replays.get(key)
        if current and current[1]>now and current[2]==generation and current[3] is self.live and current[0]!=record['id']:
            return
        expires=[datetime.fromisoformat(l['observation']['expires_at']) for j in record['plan']['journeys'] for l in j['legs'] if l['observation'].get('expires_at')]
        starts=[datetime.fromisoformat(j['departure']) for j in record['plan']['journeys']]
        until=min([now+timedelta(minutes=2),*expires,*starts])
        if len(self.replays)>200: self.replays.clear()
        self.replays[key]=(record['id'],until,generation,self.live)

    def _persist(self,request,results,owner,now,locked_legs=(),locked_connections=(),record_id=None,collection_complete=True,pending_providers=None):
        legs=[leg for result in results for leg in result.legs]
        if request.data_mode == 'live' and not (is_kerala(request.origin) and is_kerala(request.destination)):
            legs=[l for l in legs if str(l.mode) != 'bus' and l.mode != Mode.BUS]
        planned=plan(request,legs,TERMINALS,transfer_catalog(),now,locked_legs,locked_connections)
        if locked_legs:
            for journey in planned.journeys:
                journey.warnings=sorted(set(journey.warnings+['travel_progress_user_reported','onboard_status_unverified']))
        previous=self.repository.get('search',record_id,owner) if record_id else None
        record={'id':record_id or uuid.uuid4().hex,'request':request.model_dump(mode='json'),
            'plan':planned.model_dump(mode='json'),'providers':[r.model_dump(mode='json') for r in results],
            'created_at':previous['created_at'] if previous else now.isoformat(),'updated_at':now.isoformat(),
            'collection_complete':collection_complete,'pending_providers':pending_providers or [],
            'coverage':'Bounded seed terminals; provider failures and missing modes are reported explicitly.'}
        snapshots=[]
        for journey in planned.journeys:
            # IDs must identify this search instance so another search cannot overwrite a chosen itinerary.
            journey_id=record['id'][:12]+'-'+journey.id
            journey.id=journey_id
            chosen={leg.id for leg in journey.legs}
            providers=record['providers'] if request.data_mode=='sample' else [dict(p,legs=[l for l in p['legs'] if l['id'] in chosen]) for p in record['providers']]
            snapshots.append({'request':request.model_dump(mode='json'),'journey':journey.model_dump(mode='json'),
                'providers':providers,'search_id':record['id']})
        record['plan']=planned.model_dump(mode='json')
        self.repository.save_search_bundle(record,snapshots,owner)
        return record

    async def _enrich(self,id,request,pairs,owner,pending,generation):
        signature=None
        try:
            while True:
                await asyncio.wait({pending},timeout=.25)
                now=datetime.now(timezone.utc)
                done=pending.done()
                names=[] if done else self.live.pending_names_key(pending.get_name())
                if not done and not names:
                    # Provider callbacks can finish one event-loop tick before
                    # the aggregate task. Resolve it before persisting final flags.
                    await asyncio.shield(pending)
                    done=True
                results=pending.result() if done else self.live.snapshot_key(pending.get_name(),now)
                current=tuple((p.provider,p.status,p.message,tuple((l.id,l.provider,l.service_id,l.origin,l.destination,
                    l.fare_minor,l.currency,l.availability,l.seats,l.start,l.end,l.cancelled,l.wheelchair,l.booking_opens_at,
                    l.observation.evidence,l.observation.expires_at) for l in p.legs)) for p in results)
                if current!=signature or done:
                    record=self._persist(request,results,owner,now,record_id=id,collection_complete=done,
                        pending_providers=[] if done else names)
                    self._remember(request,owner,record,now,generation)
                    signature=current
                if done: break
        except asyncio.CancelledError:
            record=self.repository.get('search',id,owner)
            if record:
                record.update(collection_complete=True,pending_providers=[],collection_error='Enrichment interrupted by server shutdown')
                self.repository.save('search',id,owner,record)
            raise
        except Exception as error:
            record=self.repository.get('search',id,owner)
            if record:
                record.update(collection_complete=True,pending_providers=[],collection_error=type(error).__name__+': enrichment unavailable')
                self.repository.save('search',id,owner,record)

    async def aclose(self):
        tasks=list(self.enrichments)
        for task in tasks: task.cancel()
        await asyncio.gather(*tasks,return_exceptions=True)
        for broker in (self.live,self.sample):
            if hasattr(broker,'aclose'): await broker.aclose()
        await self.collector.aclose()
        await self.decisions.aclose()

    async def revalidate(self,id,owner,sample_delay_minutes=0,completed_leg_ids=None,onboard_leg_id=None,repair_progress=False):
        record=self.repository.get('journey',id,owner)
        if not record:
            raise LookupError('Journey not found')
        request=SearchRequest.model_validate(record['request'])
        if sample_delay_minutes and request.data_mode!='sample':
            raise ValueError('Sample disruption controls cannot change live journeys')
        chosen=Journey.model_validate(record['journey'])
        now=datetime.now(timezone.utc)
        progress=record.get('progress',{'completed_leg_ids':[],'onboard_leg_id':None})
        expected_progress=progress
        leg_ids=[l.id for l in chosen.legs]
        old_completed=progress.get('completed_leg_ids',[]) if isinstance(progress,dict) else None
        old_onboard=progress.get('onboard_leg_id') if isinstance(progress,dict) else None
        valid_saved=(isinstance(old_completed,list) and old_completed==leg_ids[:len(old_completed)]
            and (old_onboard is None or (len(old_completed)<len(leg_ids) and old_onboard==leg_ids[len(old_completed)])))
        if repair_progress:
            if valid_saved: raise ValueError('Saved progress is valid; it cannot be reset')
            progress={'completed_leg_ids':[],'onboard_leg_id':None}
            old_completed=[]
            old_onboard=None
        elif not valid_saved:
            raise ValueError('Saved travel status needs explicit reconfirmation')
        if completed_leg_ids is not None or onboard_leg_id is not None:
            progress={'completed_leg_ids':completed_leg_ids if completed_leg_ids is not None else progress['completed_leg_ids'],
                      'onboard_leg_id':onboard_leg_id}
        completed=progress['completed_leg_ids']
        if completed!=[l.id for l in chosen.legs[:len(completed)]]:
            raise ValueError('Completed legs must be the ordered initial portion of the chosen journey')
        aboard=progress.get('onboard_leg_id')
        if aboard and (len(completed)>=len(chosen.legs) or chosen.legs[len(completed)].id!=aboard):
            raise ValueError('Onboard leg must immediately follow completed legs')
        if len(completed)<len(old_completed) or (old_onboard and len(completed)==len(old_completed) and aboard!=old_onboard):
            raise ValueError('Travel status has already advanced. Refresh before confirming the next leg')
        prefix_count=len(completed)+bool(aboard)
        if any(l.start>now for l in chosen.legs[:prefix_count]):
            raise ValueError('Travel progress cannot include a service that has not departed')
        record=self.repository.update_progress(id,owner,expected_progress,progress)
        if len(completed)==len(chosen.legs):
            return self._progress_outcome(id,owner,request,record,now,'Journey marked completed.',completed=True)
        if not prefix_count and chosen.legs[0].start<now:
            return self._progress_outcome(id,owner,request,record,now,'Departure has passed. Tell us completed legs and the leg you boarded before replanning.',requires_progress=True)
        if request.arrive_by and request.arrive_by<=now:
            return self._progress_outcome(id,owner,request,record,now,
                'The explicit arrival deadline has elapsed. Start a new search if you want to change that deadline.')
        recovery_request=request.model_copy(update={'depart_after':max(request.depart_after,now)})
        if request.data_mode=='sample':
            providers=[ProviderResult.model_validate(p) for p in record['providers']]
            target=chosen.legs[0].id
            for provider in providers:
                provider.legs=[leg.model_copy(update={'estimated_arrival':leg.arrival+timedelta(minutes=sample_delay_minutes)})
                               if leg.id==target else leg for leg in provider.legs]
        else:
            self.live.invalidate()
            self.replays={k:v for k,v in self.replays.items() if k[0]!=owner}
            # Revalidation may start after departure; new searches still require a future window.
            recovery_origin=chosen.legs[prefix_count-1].destination if prefix_count else request.origin
            pairs=discovery_pairs(recovery_origin,request.destination)
            if aboard:
                active=chosen.legs[len(completed)]
                pairs=[(TERMINALS[active.origin],TERMINALS[active.destination]),*pairs][:18]
            providers=await self.live.collect(recovery_request,pairs,now)
        refreshed=[l for p in providers for l in p.legs]
        prefix=[]
        for index,old in enumerate(chosen.legs[:prefix_count]):
            current=next((l for l in refreshed if self._identity(l)==self._identity(old)),old)
            if old.id==aboard and (current.start>now or current.end<=now or current.cancelled):
                return self._progress_outcome(id,owner,request,record,now,
                    'Onboard status conflicts with the latest timetable. Update arrival/completed legs before selecting an onward connection.',requires_progress=True)
            current=current.model_copy(update={'id':old.id,'fare_minor':old.fare_minor,
                'fare_basis':old.fare_basis,'ticket_group':old.ticket_group})
            if index<len(completed):
                # User-reported completion is progress, not freshly verified seat inventory.
                current=old.model_copy(update={'estimated_arrival':min(now,old.end)})
            prefix.append(current)
        locked_connections=[]
        for connection in chosen.connections:
            if connection.kind=='access' and prefix:
                locked_connections.append(connection)
            elif connection.kind=='transfer' and any(connection.origin==a.destination and connection.destination==b.origin for a,b in zip(prefix,prefix[1:])):
                locked_connections.append(connection)
        updated=self._persist(recovery_request,providers,owner,now,prefix,locked_connections)
        original_services=[self._identity(leg) for leg in chosen.legs]
        same=next((j for j in updated['plan']['journeys'] if [self._identity(Leg.model_validate(l)) for l in j['legs']]==original_services),None)
        # Completed/departed original journeys are not silently reconstructed with new sample services.
        outcome={'journey_id':id,'original_valid':same is not None,'current_journey':same,
            'progress_snapshot':progress,
            'alternatives':updated['plan']['journeys'],'providers':updated['providers'],
            'data_mode':request.data_mode,'checked_at':now.isoformat(),
            'requires_progress':False,'completed':False,
            'recovery_window':{'depart_after':recovery_request.depart_after.isoformat(),'arrive_by':recovery_request.arrive_by.isoformat() if recovery_request.arrive_by else None},
            'message':'Chosen connection remains feasible in checked data.' if same else 'Chosen journey is no longer verified; review refreshed alternatives.'}
        self.repository.save('event',uuid.uuid4().hex,owner,outcome)
        return outcome

    @staticmethod
    def _identity(leg):
        return leg.provider,leg.service_id,leg.fare_class,leg.origin,leg.destination

    def _progress_outcome(self,id,owner,request,record,now,message,requires_progress=False,completed=False):
        outcome={'journey_id':id,'original_valid':False,'current_journey':None,'alternatives':[],
                 'progress_snapshot':record.get('progress',{'completed_leg_ids':[],'onboard_leg_id':None}),
                 'providers':record['providers'],'data_mode':request.data_mode,'checked_at':now.isoformat(),
                 'requires_progress':requires_progress,'completed':completed,'message':message}
        self.repository.save('event',uuid.uuid4().hex,owner,outcome)
        return outcome
