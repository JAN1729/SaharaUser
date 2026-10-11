import hashlib
import asyncio
import json
import re
import secrets
from collections import defaultdict, deque
from datetime import datetime, timezone
from pathlib import Path
import time
import uuid
from contextlib import asynccontextmanager
from fastapi import FastAPI, Request, Response, HTTPException
from fastapi.responses import FileResponse, StreamingResponse
from pydantic import BaseModel, Field
from typing import Literal
import logging
from .domain import SearchRequest, Mode
from .settings import Settings
from .service import PlanningService
from .places import TERMINALS, is_kerala
from .compat import android_response
from .interpret import interpret, explicit_constraints
from .language import RequestInterpreter
from .guidance import build_guidance
from .intelligence import TransportRecord, Notice
import copy

logger = logging.getLogger(__name__)


class RevalidateRequest(BaseModel):
    repair_progress:bool=False
    sample_delay_minutes:int=Field(default=0,ge=0,le=360)
    completed_leg_ids:list[str]|None=Field(default=None,max_length=4)
    onboard_leg_id:str|None=Field(default=None,max_length=200)


class ChatRequest(BaseModel):
    query:str=Field(min_length=1,max_length=2000)
    userId:str=''
    userLocation:dict|None=None
    viewportState:dict|None=None
    dataMode:Literal['sample','live']='sample'
    resetContext:bool=False


class ReconcileRequest(BaseModel):
    record:TransportRecord
    candidates:list[TransportRecord]=Field(min_length=1,max_length=16)


def create_app(settings=None):
    settings=settings or Settings()
    service=PlanningService(settings)
    interpreter=RequestInterpreter(settings.openrouter_key,settings.openrouter_model,decisions=service.decisions)
    @asynccontextmanager
    async def lifespan(_):
        service.repository.recover_pending_searches()
        try:
            yield
        finally:
            await service.aclose()
    app=FastAPI(title='Sahara commute backend',version='0.1.0',lifespan=lifespan)
    app.state.service=service
    usage=defaultdict(deque)
    def principal(request,response=None):
        session=request.headers.get('X-Sahara-Session') or request.cookies.get('sahara_session')
        if not session:
            session=secrets.token_urlsafe(32)
            if response is not None:
                response.set_cookie('sahara_session',session,httponly=True,samesite='strict',secure=request.url.scheme=='https',max_age=30*86400)
        if not re.fullmatch(r'[A-Za-z0-9_-]{24,100}',session):
            raise HTTPException(400,'Use a randomly generated session identifier')
        return hashlib.sha256(session.encode()).hexdigest()

    def authorize(request,live):
        if live:
            auth_header = request.headers.get('Authorization','')
            if settings.api_key and secrets.compare_digest(auth_header,'Bearer '+settings.api_key):
                return
            client_host = request.client.host if request.client else ''
            if client_host in ('127.0.0.1','localhost','::1'):
                return
            raise HTTPException(401,'Live mode requires the configured Sahara API key')

    def limit(owner):
        stamp=time.monotonic()
        bucket=usage[owner]
        while bucket and bucket[0]<stamp-60:
            bucket.popleft()
        if len(bucket)>=20:
            raise HTTPException(429,'Please wait before another search')
        bucket.append(stamp)
        if len(usage)>2000:
            for key in list(usage):
                if not usage[key] or usage[key][-1]<stamp-60:
                    del usage[key]

    @app.get('/')
    def index():
        return FileResponse(Path(__file__).parent/'web'/'index.html')

    @app.get('/health')
    def health():
        return {'status':'ok','storage':'sqlite','live_enabled':bool(settings.api_key),
            'http_collector':service.collector.http_engine,'browser_collector':'playwright',
            'decision_model':service.decisions.model if service.decisions.configured else 'disabled',
            'semantic_matches':'enabled' if settings.jev_accept_matches else 'review-only',
            'language_interpreter':settings.openrouter_model if settings.openrouter_key else 'basic-parser'}

    @app.get('/v1/places')
    def places():
        return {'terminals':[t.model_dump() for t in TERMINALS.values()],
                'notice':'Seed terminal coordinates and transfers are approximate; verify actual boarding points.'}

    @app.post('/v1/access/check')
    def check_access(request:Request):
        authorize(request,True)
        return {'authorized':True}

    @app.get('/v1/intelligence/health')
    def intelligence_health(request:Request):
        authorize(request,True)
        return service.decisions.diagnostics()

    @app.post('/v1/intelligence/reconcile')
    async def reconcile_records(body:ReconcileRequest,request:Request,response:Response):
        authorize(request,True)
        owner=principal(request,response)
        limit(owner)
        try:
            results=await service.intelligence.reconcile(body.record,body.candidates,owner)
        except ValueError as error:
            raise HTTPException(422,str(error))
        return {'results':results,'semantic_matches':'enabled' if settings.jev_accept_matches else 'review-only',
            'notice':'Matching does not verify fares, timetable availability, vehicle assignment or arrival reliability.'}

    @app.get('/v1/intelligence/reconciliations')
    def reconciliation_history(request:Request):
        authorize(request,True)
        return {'records':service.repository.list_records('reconciliation',principal(request))}

    @app.post('/v1/journeys/{id}/notices')
    async def journey_notice(id:str,body:Notice,request:Request):
        authorize(request,True)
        owner=principal(request)
        limit(owner)
        try:
            result=await service.intelligence.triage(id,owner,body)
        except LookupError:
            raise HTTPException(404,'Journey not found')
        return result

    @app.get('/v1/providers/health')
    def providers():
        return {'providers':[{'name':'confirmtkt','configured':settings.scraping},
            {'name':'ksrtc','configured':settings.scraping},{'name':'flight-web','configured':settings.scraping and settings.browser},
            {'name':'existing-driver','configured':service.fleet.configured},
            {'name':'local-feed','configured':bool(settings.local_feed)}],
            'notice':'Configuration is not a live health or inventory check.'}

    @app.get('/v1/fleet/nearby')
    async def nearby_fleet(request:Request,terminal_id:str):
        authorize(request,True)
        if terminal_id not in TERMINALS: raise HTTPException(422,'Choose a covered terminal')
        terminal=TERMINALS[terminal_id]
        return await service.fleet.nearby(terminal.lat,terminal.lng)

    def current_tracking(id,record,owner):
        tracking=service.repository.get('tracking',id,owner)
        progress=record.get('progress',{'completed_leg_ids':[],'onboard_leg_id':None})
        return tracking if tracking and tracking.get('progress_snapshot')==progress else None

    def effective_journey_record(id,record,owner):
        effective=copy.deepcopy(record)
        tracking=current_tracking(id,record,owner)
        if tracking and tracking.get('original_valid') and tracking.get('current_journey'):
            updated=tracking['current_journey']
            # Preserve confirmation IDs across equivalent refreshed source offers.
            if len(updated['legs'])==len(record['journey']['legs']):
                effective['journey']=copy.deepcopy(updated)
                effective['journey']['id']=id
                for new,old in zip(effective['journey']['legs'],record['journey']['legs']): new['id']=old['id']
                effective['providers']=tracking.get('providers',record['providers'])
        return effective

    async def guidance_payload(id,record,owner):
        effective=effective_journey_record(id,record,owner)
        tracking=current_tracking(id,record,owner)
        now=datetime.now(timezone.utc)
        result=build_guidance(effective,now)
        fleet={'status':'sample','vehicles':[],'notice':'No live fleet is implied by demo journeys.'}
        if record['request']['data_mode']=='live' and result.get('boarding'):
            board=result['boarding']
            if 'lat' in board and 'lng' in board: fleet=await service.fleet.nearby(board['lat'],board['lng'],now=now)
            registered={v['vehicle_id']:v for v in fleet['vehicles']}
            observations={l['id']:registered[l['vehicle_id']] for l in effective['journey']['legs'] if l.get('vehicle_id') in registered}
            result=build_guidance(effective,now,observations)
        result['nearby_fleet']=fleet
        result['progress']=record.get('progress',{'completed_leg_ids':[],'onboard_leg_id':None})
        result['alternatives']=tracking.get('alternatives',[]) if tracking and not tracking.get('original_valid') else []
        result['revalidation']=({k:tracking.get(k) for k in ('original_valid','message','checked_at','requires_progress','completed')} if tracking else None)
        index=result['current_leg_index']
        if result.get('boarding') and index<len(effective['journey']['legs']):
            leg=effective['journey']['legs'][index]
            result['boarding']['departure']=leg.get('estimated_departure') or leg['departure']
        return result

    @app.get('/v1/journeys/{id}/guidance')
    async def guidance(id:str,request:Request):
        owner=principal(request)
        record=service.repository.get('journey',id,owner)
        if record is None: raise HTTPException(404,'Journey not found')
        authorize(request,record['request']['data_mode']=='live')
        return await guidance_payload(id,record,owner)

    @app.post('/v1/journeys/{id}/progress')
    async def progress(id:str,body:RevalidateRequest,request:Request):
        owner=principal(request)
        record=service.repository.get('journey',id,owner)
        if record is None: raise HTTPException(404,'Journey not found')
        authorize(request,record['request']['data_mode']=='live')
        if body.sample_delay_minutes: raise HTTPException(400,'Progress cannot inject delays')
        limit(owner)
        try:
            result=await service.revalidate(id,owner,completed_leg_ids=body.completed_leg_ids,onboard_leg_id=body.onboard_leg_id,repair_progress=body.repair_progress)
        except ValueError as error: raise HTTPException(400,str(error))
        service.repository.save_tracking(id,owner,result)
        record=service.repository.get('journey',id,owner)
        return await guidance_payload(id,record,owner)

    @app.post('/v1/journeys/search')
    async def search(body:SearchRequest,request:Request,response:Response):
        authorize(request,body.data_mode=='live')
        owner=principal(request,response)
        limit(owner)
        try:
            return await service.search(body,owner)
        except ValueError as error:
            raise HTTPException(422,str(error))

    @app.get('/v1/searches/{id}')
    def get_search(id:str,request:Request):
        result=service.repository.get('search',id,principal(request))
        if result is None: raise HTTPException(404,'Search not found')
        return result

    @app.get('/v1/searches/{id}/response')
    def get_mobile_search(id:str,request:Request):
        record=service.repository.get('search',id,principal(request))
        if record is None: raise HTTPException(404,'Search not found')
        return android_response(record)

    @app.post('/v1/journeys/{id}/revalidate')
    async def revalidate(id:str,body:RevalidateRequest,request:Request):
        owner=principal(request)
        record=service.repository.get('journey',id,owner)
        if record is None: raise HTTPException(404,'Journey not found')
        if body.sample_delay_minutes and record['request']['data_mode']!='sample':
            raise HTTPException(400,'Sample disruptions cannot mutate live journeys')
        authorize(request,record['request']['data_mode']=='live')
        limit(owner)
        try:
            result=await service.revalidate(id,owner,body.sample_delay_minutes,body.completed_leg_ids,body.onboard_leg_id,body.repair_progress)
            service.repository.save_tracking(id,owner,result)
            return result
        except ValueError as error: raise HTTPException(400,str(error))

    @app.get('/v1/journeys/{id}/map')
    def journey_map(id:str,request:Request):
        owner=principal(request)
        record=service.repository.get('journey',id,owner)
        if not record: raise HTTPException(404,'Journey not found')
        record=effective_journey_record(id,record,owner)
        legs=record['journey']['legs']
        connections=record['journey']['connections']
        segments=[]
        def connection_segment(connection):
            mode=(connection.get('transfer') or {}).get('mode','walk')
            route=service.geometry.route(connection['origin'],connection['destination'],mode)
            if connection['origin']==connection['destination']:
                route={'status':'same_terminal','polyline':[],'distance_m':0,
                    'notice':'Same terminal; platform and exit guidance are not verified.'}
            return dict(route,origin=connection['origin'],destination=connection['destination'],mode=mode,
                kind=connection['kind'],transfer_minutes=connection['transfer_minutes'])
        for connection in connections:
            if connection['kind']=='access': segments.append(connection_segment(connection))
        for index,leg in enumerate(legs):
            key=leg.get('route_geometry_key')
            route=service.geometry.route(leg['origin'],leg['destination'],leg['mode'],required_service=key or '__unmapped_service__')
            if leg['mode']=='bus' and leg['provider']=='ksrtc':
                route=service.geometry.bus_guidance(leg['origin'],leg['destination'],key)
            segments.append(dict(route,origin=leg['origin'],destination=leg['destination'],mode=leg['mode'],
                kind='service',provider=leg['provider'],service_id=leg['service_id'],service_name=leg['name'],source_observation=leg['observation']))
            if index+1<len(legs):
                for connection in connections:
                    if connection['kind']=='transfer' and connection['origin']==leg['destination'] and connection['destination']==legs[index+1]['origin']:
                        segments.append(connection_segment(connection))
        for connection in connections:
            if connection['kind']=='egress': segments.append(connection_segment(connection))
        ids=list(dict.fromkeys([record['request']['origin'],*[place for s in segments for place in (s['origin'],s['destination'])],record['request']['destination']]))
        return {'journey_id':id,'data_mode':record['request']['data_mode'],'warnings':record['journey']['warnings'],'segments':segments,
            'pins':[dict(TERMINALS[id].model_dump(),evidence='approximate_seed_terminal') for id in ids],
            'vehicles':[],
            'geometry_complete':all(s['status'] in ('mapped','same_terminal') for s in segments),
            'guidance_complete':all(s['status'] in ('mapped','road_guidance','same_terminal') for s in segments)}

    @app.post('/v1/journeys/{id}/watch')
    def watch(id:str,request:Request):
        owner=principal(request)
        record=service.repository.get('journey',id,owner)
        if not record: raise HTTPException(404,'Journey not found')
        authorize(request,record['request']['data_mode']=='live')
        watch_id=uuid.uuid4().hex
        service.repository.save('watch',watch_id,owner,{'id':watch_id,'journey_id':id,'active':True,
            'notice':'Watch saved; run the watch worker to refresh. No external notification channel is enabled.'})
        return {'id':watch_id,'active':True}

    @app.get('/v1/watches/{id}/events')
    def watch_events(id:str,request:Request):
        owner=principal(request)
        if not service.repository.get('watch',id,owner): raise HTTPException(404,'Watch not found')
        return {'events':service.repository.watch_events(id,owner)}

    @app.delete('/v1/watches/{id}')
    def stop_watch(id:str,request:Request):
        owner=principal(request)
        record=service.repository.get('watch',id,owner)
        if not record: raise HTTPException(404,'Watch not found')
        record['active']=False
        service.repository.save('watch',id,owner,record)
        return {'active':False}

    @app.post('/process-query')
    async def process_query(body:ChatRequest,request:Request):
        authorize(request,body.dataMode=='live')
        session_response=Response()
        owner=principal(request,session_response)
        limit(owner)
        previous=None if body.resetContext else service.repository.get('session','current',owner)
        if body.resetContext:
            service.repository.save('session','current',owner,{})
        async def events():
            def event(type,data): return json.dumps({'type':type,'data':data},ensure_ascii=False)+'\n\n'
            yield event('thinking','Checking complete journeys and connection times…')
            try:
                logger.info(f"Process query received: query='{body.query}', mode={body.dataMode}")
                # Unkeyed demo requests must not consume a configured paid model.
                parsed=await interpreter.parse(body.query,previous) if body.dataMode=='live' else interpret(body.query,previous)
                parsed=parsed.model_copy(update={'data_mode':'live' if body.dataMode=='live' else 'sample'})
                if parsed.data_mode == 'live' and not (is_kerala(parsed.origin) and is_kerala(parsed.destination)):
                    if 'modes' not in explicit_constraints(body.query):
                        parsed = parsed.model_copy(update={'modes': [Mode.TRAIN]})
                service.repository.save('session','current',owner,parsed.model_dump(mode='json'))
                yield event('reasoning','Checking public transport sources in configured coverage…')
                record=await service.search(parsed,owner)
                if not record['plan']['journeys'] and not record.get('collection_complete',True):
                    yield event('reasoning','Sources are still loading; waiting for the first verified option…')
                    deadline=time.monotonic()+min(service.live.timeout,8)
                    while not record['plan']['journeys'] and not record.get('collection_complete',True) and time.monotonic()<deadline:
                        await asyncio.sleep(.5)
                        yield event('thinking','Checking timetables and seat availability…')
                        record=service.repository.get('search',record['id'],owner) or record
                yield event('reasoning','Validated dates, terminal transfers, availability and total known fares.')
                yield event('final_response',android_response(record))
            except ValueError as error:
                yield event('final_response',{'success':False,'response':str(error),'intent':'NEEDS_DETAILS','journeys':[]})
            except Exception:
                yield event('error','The server could not complete this search. Check its logs and try again.')
        headers={'Cache-Control':'no-cache'}
        if session_response.headers.get('set-cookie'):
            headers['Set-Cookie']=session_response.headers['set-cookie']
        return StreamingResponse(events(),media_type='text/event-stream',headers=headers)
    return app


app=create_app()
