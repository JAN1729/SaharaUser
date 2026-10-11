"""Read-only reuse of SaharaDriver's existing fleet schema; never stores driver data."""
import asyncio
import math
import re
from datetime import datetime, timedelta, timezone
from urllib.parse import urlparse
import httpx


def _point(lat,lng):
    return isinstance(lat,(int,float)) and not isinstance(lat,bool) and isinstance(lng,(int,float)) and not isinstance(lng,bool) and math.isfinite(lat) and math.isfinite(lng) and -85.05112878<=lat<=85.05112878 and -180<=lng<=180


def _distance(a,b,c,d):
    lat1,lat2=map(math.radians,(a,c))
    h=math.sin((lat2-lat1)/2)**2+math.cos(lat1)*math.cos(lat2)*math.sin(math.radians(d-b)/2)**2
    return 6371000*2*math.asin(math.sqrt(min(1,h)))


class ExistingDriverFeed:
    def __init__(self,url='',key='',transport=None):
        parsed=urlparse(url)
        self.configured=bool(key and parsed.scheme=='https' and parsed.hostname and parsed.hostname.endswith('.supabase.co')
            and not parsed.username and not parsed.password and parsed.path in ('','/') and not parsed.query and not parsed.fragment)
        self.url=url.rstrip('/') if self.configured else ''
        self.key,self.transport=key,transport
        self.lock=asyncio.Lock()
        self.cache=None

    async def nearby(self,lat,lng,radius_m=5000,now=None):
        now=now or datetime.now(timezone.utc)
        if not _point(lat,lng) or not 0<radius_m<=10000: raise ValueError('Invalid fleet search area')
        if not self.configured:
            return {'status':'unconfigured','vehicles':[],'notice':'Existing driver connection is not configured.'}
        try:
            async with self.lock:
                if not self.cache or self.cache[0]<=now:
                    async with httpx.AsyncClient(base_url=self.url,headers={'apikey':self.key,'Authorization':'Bearer '+self.key},
                        timeout=6,follow_redirects=False,transport=self.transport) as client:
                        vehicles=await self._rows(client,'vehicles',{'select':'id,name,is_active','is_active':'eq.true','order':'id','limit':'101'})
                        truncated=len(vehicles)>100
                        vehicles=vehicles[:100]
                        vehicles=[v for v in vehicles if isinstance(v,dict) and v.get('is_active') is True and re.fullmatch(r'[A-Za-z0-9_-]{1,100}',str(v.get('id','')))]
                        ids=','.join(v['id'] for v in vehicles)
                        if ids:
                            stops,locations=await asyncio.gather(
                                self._rows(client,'route_stops',{'select':'id,vehicle_id,name,latitude,longitude,stop_order','vehicle_id':'in.('+ids+')','order':'vehicle_id,stop_order','limit':'2001'}),
                                self._rows(client,'locations',{'select':'bus_id,latitude,longitude,heading,speed,accuracy,timestamp_iso,trip_status',
                                    'bus_id':'in.('+ids+')','timestamp_iso':'gte.'+(now-timedelta(seconds=90)).isoformat(),
                                    'order':'timestamp_iso.desc','limit':'1001'}))
                            truncated=truncated or len(stops)>2000 or len(locations)>1000
                            stops,locations=stops[:2000],locations[:1000]
                        else: stops,locations=[],[]
                        self.cache=(now+timedelta(seconds=20),vehicles,stops,locations,truncated)
            _,vehicles,stops,locations,truncated=self.cache
            registered={v['id']:v for v in vehicles}
            latest={}
            for location in locations:
                if not isinstance(location,dict) or location.get('bus_id') not in registered: continue
                try:
                    stamp=datetime.fromisoformat(location['timestamp_iso'].replace('Z','+00:00'))
                    age=(now-stamp).total_seconds()
                    accuracy=location['accuracy']
                    if stamp.tzinfo is None or not -15<=age<=90 or not isinstance(accuracy,(int,float)) or isinstance(accuracy,bool) or not math.isfinite(accuracy) or not 0<=accuracy<=50: continue
                    if location.get('trip_status')!='started' or not _point(location.get('latitude'),location.get('longitude')): continue
                    old=latest.get(location['bus_id'])
                    if old is None or old[0]<stamp: latest[location['bus_id']]=(stamp,location)
                except (KeyError,ValueError,TypeError): continue
            result=[]
            for id,(stamp,location) in latest.items():
                distance=_distance(lat,lng,location['latitude'],location['longitude'])
                if distance>radius_m: continue
                route=[]
                for stop in stops:
                    if not isinstance(stop,dict) or stop.get('vehicle_id')!=id or not _point(stop.get('latitude'),stop.get('longitude')): continue
                    if not isinstance(stop.get('stop_order'),int) or isinstance(stop.get('stop_order'),bool): continue
                    route.append({'id':str(stop.get('id','')),'name':str(stop.get('name',''))[:200],
                        'lat':stop['latitude'],'lng':stop['longitude'],'order':stop['stop_order'],'precision':'operator_reported'})
                result.append({'vehicle_id':id,'name':str(registered[id].get('name','Vehicle'))[:200],
                    'lat':location['latitude'],'lng':location['longitude'],'observed_at':stamp.isoformat(),
                    'accuracy_m':location['accuracy'],'distance_m':round(distance),'stops':sorted(route,key=lambda s:s['order']),
                    'source':'existing_driver_backend','eta_minutes':None})
            result.sort(key=lambda v:v['distance_m'])
            truncated=truncated or len(result)>30
            return {'status':'partial' if truncated else 'ok' if result else 'empty','vehicles':result[:30],'updated_at':now.isoformat(),
                'notice':('Fleet coverage is incomplete because the bounded read limit was reached. ' if truncated else '')+
                    'Registered vehicle locations only. This feed does not verify fares, seats, timetables or arrival estimates.'}
        except (httpx.HTTPError,ValueError,TypeError,KeyError):
            return {'status':'error','vehicles':[],'notice':'Existing driver data could not be checked. No live positions are being assumed.'}

    @staticmethod
    async def _rows(client,table,params):
        response=await client.get('/rest/v1/'+table,params=params)
        response.raise_for_status()
        if len(response.content)>2_000_000: raise ValueError('Driver data exceeded the bounded response size')
        rows=response.json()
        if not isinstance(rows,list): raise ValueError('Driver data has an unsupported shape')
        return rows
