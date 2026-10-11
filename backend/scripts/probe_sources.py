"""Read-only, bounded public source probe. No account login or booking calls."""
import asyncio
import json
import argparse
from datetime import datetime,timedelta,timezone
from sahara.domain import SearchRequest
from sahara.places import TERMINALS
from sahara.providers.rail import RailProvider
from sahara.providers.ksrtc import KSRTCProvider
from sahara.providers.parsing import IST
from sahara.providers.collector import PageCollector


async def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--browser',action='store_true',help='Try regular browser rendering')
    parser.add_argument('--corridor', choices=['ernakulam','kozhikode'], default='ernakulam')
    args=parser.parse_args()
    now=datetime.now(timezone.utc)
    start=now.astimezone(IST).replace(hour=0,minute=0,second=0,microsecond=0)+timedelta(days=1)
    request=SearchRequest(origin='ers',destination='sbc',depart_after=start,
                          arrive_by=start+timedelta(hours=23),data_mode='live')
    collector=PageCollector()
    origin='clt' if args.corridor=='kozhikode' else 'ers'
    bus_origin='kozhikode-bus' if args.corridor=='kozhikode' else 'ernakulam-bus'
    providers=[(RailProvider(browser=args.browser,collector=collector),[(TERMINALS[origin],TERMINALS['sbc'])]),
               (KSRTCProvider(browser=args.browser,collector=collector),[(TERMINALS[bus_origin],TERMINALS['bengaluru-bus'])])]
    async def check(provider,pairs):
        try:
            result=await asyncio.wait_for(provider.search(request,pairs,now),25)
            return {'provider':result.provider,'status':result.status,'parsed_legs':len(result.legs),
                    'message':result.message,'evidence':sorted({l.observation.evidence for l in result.legs})}
        except Exception as error:
            return {'provider':provider.name,'status':'error','message':type(error).__name__}
    try:
        print(json.dumps({'checked_at':now.isoformat(),'results':await asyncio.gather(*(check(p,pairs) for p,pairs in providers))},indent=2))
    finally:
        await collector.aclose()


if __name__=='__main__':
    asyncio.run(main())
