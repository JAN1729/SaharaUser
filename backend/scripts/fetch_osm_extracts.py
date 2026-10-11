"""Fetch bounded public OSM files for local road guidance, never transport inventory."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import httpx

BASE='https://download.openstreetmap.fr/extracts/asia/india/'
REGIONS=('kerala','karnataka','tamil_nadu')
ROOT=Path('data/osm')
LIMIT=250_000_000


def fetch(region):
    ROOT.mkdir(parents=True,exist_ok=True)
    file=ROOT/(region+'.osm.pbf')
    metadata=file.with_suffix(file.suffix+'.source.json')
    if file.exists() and metadata.exists():
        info=json.loads(metadata.read_text(encoding='utf-8'))
        if file.stat().st_size==info['bytes'] and hashlib.sha256(file.read_bytes()).hexdigest()==info['sha256']:
            print(region+': using verified local extract',flush=True)
            return info
    url=BASE+region+'-latest.osm.pbf'
    partial=file.with_suffix(file.suffix+'.part')
    with httpx.Client(timeout=httpx.Timeout(30,connect=15),follow_redirects=False) as client:
        for attempt in range(3):
            offset=partial.stat().st_size if partial.exists() else 0
            if offset>LIMIT: raise ValueError('Partial OSM file exceeds bounded size')
            try:
                with client.stream('GET',url,headers={'User-Agent':'Sahara mobility map preparation','Range':f'bytes={offset}-'} if offset else {'User-Agent':'Sahara mobility map preparation'}) as response:
                    response.raise_for_status()
                    if response.status_code not in (200,206): raise ValueError('Unsupported OSM download response')
                    if offset and response.status_code!=206: offset=0
                    expected=int(response.headers.get('content-length',0))+offset
                    if expected>LIMIT: raise ValueError('OSM extract exceeds bounded size')
                    if response.status_code==206 and not response.headers.get('content-range','').startswith('bytes '+str(offset)+'-'):
                        raise ValueError('OSM resume range mismatch')
                    count=offset
                    last=count//20_000_000
                    with partial.open('ab' if offset else 'wb') as stream:
                        for chunk in response.iter_bytes(1024*1024):
                            count+=len(chunk)
                            if count>LIMIT: raise ValueError('OSM extract exceeds bounded size')
                            stream.write(chunk)
                            if count//20_000_000>last:
                                last=count//20_000_000
                                print(region+f': {count//1_000_000} MB downloaded',flush=True)
                    if expected and count!=expected: raise ValueError('OSM extract length mismatch')
                    info={'region':region,'source_url':url,'bytes':count,'last_modified':response.headers.get('last-modified'),
                        'sha256':hashlib.sha256(partial.read_bytes()).hexdigest()}
                    partial.replace(file)
                    metadata.write_text(json.dumps(info,indent=2),encoding='utf-8')
                    print(region+': download complete',flush=True)
                    return info
            except httpx.HTTPError:
                print(region+f': connection interrupted, retry {attempt+1}/3',flush=True)
                if attempt==2: raise


if __name__=='__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results=list(pool.map(fetch,REGIONS))
    print(json.dumps({'extracts':results},indent=2))
