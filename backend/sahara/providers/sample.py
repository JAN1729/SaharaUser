from datetime import timedelta
from ..domain import Leg, Observation, ProviderResult


class SampleProvider:
    name='sample'
    async def search(self,request,pairs,now):
        base=request.depart_after+timedelta(hours=1)
        observation=Observation(provider='sample',evidence='fixture',fetched_at=now,
            source_url=None,parser_version='scenario-1')
        rows=[
            ('local-kty','kottayam-bus','kty',0,.3,2000,'local'),
            ('rail-kty-ers','kty','ers',1,2.5,9000,'train'),
            ('bus-ers-blr','ernakulam-bus','bengaluru-bus',3.5,14,65000,'bus'),
            ('rail-direct','kty','sbc',6,22,85000,'train'),
            ('bus-direct','kottayam-bus','bengaluru-bus',2,15,110000,'bus'),
            ('rail-ers-cbe','ers','cbe',3,7,28000,'train'),
            ('rail-cbe-sbc','cbe','sbc',9,16,45000,'train'),
            ('flight-cok-blr','cok','blr',5,6.5,480000,'flight'),
            ('rail-tvc-kty','tvc','kty',0,3,22000,'train'),
            ('bus-tvc-ers','trivandrum-bus','ernakulam-bus',1,6,35000,'bus'),
            ('flight-trv-blr','trv','blr',5,6.5,520000,'flight'),
            ('flight-cok-del','cok','del',5,8.25,650000,'flight'),
            ('flight-cok-maa','cok','maa',5,6.5,380000,'flight'),
            ('flight-cok-bom','cok','bom',5,7,550000,'flight'),
            ('flight-cok-hyd','cok','hyd',5,6.75,460000,'flight')]
        for a,b in pairs:
            if getattr(a,'kind',None)=='rail' and getattr(b,'kind',None)=='rail' and a.id!=b.id:
                pair_id=f'rail-{a.id}-{b.id}'
                if not any(r[0]==pair_id for r in rows):
                    rows.append((pair_id,a.id,b.id,2,16,120000,'train'))
        legs=[Leg(id=id,provider='sample',service_id=id,name={'train':'Sample train','bus':'Sample KSRTC service',
            'flight':'Sample domestic flight','local':'Sample local connection'}[mode],mode=mode,
            origin=origin,destination=destination,departure=base+timedelta(hours=start),
            arrival=base+timedelta(hours=end),fare_minor=fare,seats=9,availability='available',observation=observation)
            for id,origin,destination,start,end,fare,mode in rows]
        return ProviderResult(provider='sample',status='ok',legs=legs,message='Generated demonstration services, fares and seats. Not real inventory.')
