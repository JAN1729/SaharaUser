"""Small public-HTML transport comparison; not an inventory/seat verification."""
import asyncio
import argparse
import json
import statistics
import time
from sahara.providers.collector import PageCollector


async def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--url',action='append',help='Approved public HTML page; never an inventory API')
    parser.add_argument('--runs',type=int,choices=(1,2,3),default=2)
    args=parser.parse_args()
    urls=args.url or ['https://www.confirmtkt.com/','https://onlineksrtcswift.com/']
    if len(urls)>3: parser.error('At most three public pages')
    output=[]
    for url in urls:
        for engine in ('httpx','scrapling'):
            samples=[]
            collector=PageCollector(http_engine=engine)
            try:
                for _ in range(args.runs):
                    start=time.perf_counter()
                    try:
                        html=await collector(url)
                        sample={'ok':True,'html_chars':len(html)}
                    except Exception as error:
                        sample={'ok':False,'error':type(error).__name__}
                    sample['seconds']=round(time.perf_counter()-start,3)
                    samples.append(sample)
            finally: await collector.aclose()
            output.append({'url':url,'engine':engine,'samples':samples,
                'median_seconds':statistics.median(s['seconds'] for s in samples)})
    print(json.dumps({'notice':'Sequential small sample; webpage latency varies. HTML success is not usable dated inventory.',
        'results':output},indent=2))


if __name__=='__main__': asyncio.run(main())
