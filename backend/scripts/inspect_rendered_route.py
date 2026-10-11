"""Regular headless rendering to diagnose the requested public inventory pages."""
import asyncio
import json
import hashlib
import os
from pathlib import Path
from datetime import datetime,timezone
from urllib.parse import urlparse,urlencode
from playwright.async_api import async_playwright
from bs4 import BeautifulSoup


async def main():
    folder=Path('data/live-tests/kozhikode-bengaluru-2026-10-15')
    folder.mkdir(parents=True,exist_ok=True)
    urls={
        'ksrtc':'https://onlineksrtcswift.com/search?'+urlencode({'fromCity':'10072|Kozhikode (11)','toCity':'298|Bangalore','departDate':'15-10-2026','mode':'oneway','src':'h'}),
        'confirmtkt':'https://www.confirmtkt.com/rbooking/trains/from/CLT/to/SBC/15-10-2026',
        'railyatri':'https://www.railyatri.in/booking/trains-between-stations?from_code=CLT&to_code=SBC&journey_date=15-10-2026'}
    results=[]
    async with async_playwright() as playwright:
        browser=await playwright.chromium.launch(headless=True,executable_path=os.getenv('SAHARA_BROWSER_EXECUTABLE',r'C:\Program Files\Google\Chrome\Application\chrome.exe'))
        try:
            for name,url in urls.items():
                page=await browser.new_page()
                events=[]
                page.on('response',lambda response:events.append({'url':response.url,'status':response.status}) if urlparse(response.url).hostname in {'onlineksrtcswift.com','assets.onlineksrtcswift.com','www.confirmtkt.com','www.railyatri.in','api.railyatri.in'} and response.request.resource_type in ('xhr','fetch') else None)
                result={'provider':name,'requested_url':url,'checked_at':datetime.now(timezone.utc).isoformat()}
                try:
                    await page.goto(url,wait_until='domcontentloaded',timeout=20000)
                    try:
                        await page.wait_for_selector('.srch-card, div.rounded-10.border-b, .train-card, a[href*="/time-table/"]',timeout=10000)
                    except Exception:
                        result['selector_found']=False
                    html=await page.content()
                    soup=BeautifulSoup(html,'html.parser')
                    (folder/(name+'-rendered.html')).write_text(html,encoding='utf-8')
                    result.update({'final_url':page.url,'bytes':len(html),'cards':len(soup.select('.srch-card, div.rounded-10.border-b, .train-card, a[href*="/time-table/"]')),
                        'title':await page.title(),'body_excerpt':soup.get_text(' ',strip=True)[:1500],
                        'source_inputs':[{'name':i.get('name'),'id':i.get('id'),'value':i.get('value')} for i in soup.select('input') if any(t in (i.get('name','')+' '+i.get('id','')).lower() for t in ['depart','journey','date','from','to'])],
                        'sha256':hashlib.sha256(html.encode()).hexdigest()})
                except Exception as error:
                    result['error']=type(error).__name__+': '+str(error)[:200]
                finally:
                    result['public_data_requests']=events
                    await page.close()
                results.append(result)
                print(json.dumps(result,ensure_ascii=True),flush=True)
        finally:
            await browser.close()
    (folder/'rendered-diagnostics.json').write_text(json.dumps(results,indent=2,ensure_ascii=False),encoding='utf-8')


if __name__=='__main__': asyncio.run(main())
