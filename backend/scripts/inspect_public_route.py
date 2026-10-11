"""Bounded public-page diagnostics for the requested corridor; no booking or login."""
import json
import hashlib
from pathlib import Path
from urllib.parse import urlencode,urljoin
from datetime import datetime,timezone
import httpx
from bs4 import BeautifulSoup


def main():
    folder=Path('data/live-tests/kozhikode-bengaluru-2026-10-15')
    folder.mkdir(parents=True,exist_ok=True)
    urls={
        'ksrtc':'https://onlineksrtcswift.com/search?'+urlencode({'fromCity':'10072|Kozhikode (11)','toCity':'298|Bangalore','departDate':'15-10-2026','mode':'oneway','src':'h'}),
        'confirmtkt':'https://www.confirmtkt.com/rbooking/trains/from/CLT/to/SBC/15-10-2026',
        'railyatri':'https://www.railyatri.in/booking/trains-between-stations?from_code=CLT&to_code=SBC&journey_date=15-10-2026'
    }
    results=[]
    with httpx.Client(timeout=15,follow_redirects=False) as client:
        for name,url in urls.items():
            try:
                response=client.get(url,headers={'User-Agent':'SaharaCommute/0.1 (public journey research)'})
                html=response.text
                soup=BeautifulSoup(html,'html.parser')
                (folder/(name+'-http.html')).write_text(html,encoding='utf-8')
                results.append({'provider':name,'url':url,'status':response.status_code,'bytes':len(response.content),
                    'title':soup.title.get_text(strip=True) if soup.title else None,
                    'cards':len(soup.select('.train-card, div.rounded-10.border-b, .srch-card, .Mui-seat__layout_box')),
                    'script_urls':[urljoin(url,s['src']) for s in soup.select('script[src]')][:15],
                    'body_excerpt':soup.get_text(' ',strip=True)[:800],
                    'sha256':hashlib.sha256(response.content).hexdigest()})
            except Exception as error:
                results.append({'provider':name,'url':url,'error':type(error).__name__})
    result={'checked_at':datetime.now(timezone.utc).isoformat(),'results':results}
    (folder/'http-diagnostics.json').write_text(json.dumps(result,indent=2,ensure_ascii=False),encoding='utf-8')
    print(json.dumps(result,indent=2,ensure_ascii=True))


if __name__=='__main__': main()
