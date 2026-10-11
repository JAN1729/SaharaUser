"""Public HTTP first; optional regular browser rendering, never challenge bypass."""
import asyncio
import os
from urllib.parse import urlparse
import httpx
from bs4 import BeautifulSoup

ALLOWED={'www.confirmtkt.com','www.railyatri.in','onlineksrtcswift.com','www.google.com'}
_http_limit=asyncio.Semaphore(3)
_browser_limit=asyncio.Semaphore(1)


def visible_page_text(html):
    """Extract user-visible text without inspecting scripts or network data."""
    soup = BeautifulSoup(html, 'html.parser')
    for element in soup.find_all(('script', 'style', 'noscript', 'template')):
        element.decompose()
    for element in soup.find_all(attrs={'hidden': True}):
        element.decompose()
    for element in soup.find_all(attrs={'aria-hidden': True}):
        attrs = getattr(element, 'attrs', None) or {}
        if str(attrs.get('aria-hidden', '')).casefold() == 'true':
            element.decompose()
    for element in soup.find_all(style=True):
        attrs = getattr(element, 'attrs', None) or {}
        style = str(attrs.get('style', '')).replace(' ', '').casefold()
        if 'display:none' in style or 'visibility:hidden' in style:
            element.decompose()
    return ' '.join(soup.get_text(' ', strip=True).split())


def has_visible_challenge(html):
    text = visible_page_text(html).casefold()
    markers = (
        'captcha',
        'access denied',
        'unusual traffic',
        'verify you are human',
        'verify you are a human',
        'checking your browser',
        'robot check',
    )
    return any(marker in text for marker in markers)


class PageCollector:
    """Reuse a regular browser; read rendered DOM only, without calling provider APIs."""
    def __init__(self,http_engine=None,session_factory=None):
        requested=http_engine or os.getenv('SAHARA_HTTP_ENGINE','auto')
        if requested not in ('auto','httpx','scrapling'): raise ValueError('Unknown HTTP collector engine')
        if requested!='httpx' and session_factory is None:
            try:
                from scrapling.fetchers import FetcherSession
                session_factory=FetcherSession
            except ImportError:
                if requested=='scrapling': raise RuntimeError('Install the backend scrapling extra before selecting Scrapling') from None
        self.http_engine='scrapling' if requested!='httpx' and session_factory else 'httpx'
        self.session_factory=session_factory
        self.http_context=None
        self.http_session=None
        self.http_lock=asyncio.Lock()
        self.http_pages=asyncio.Semaphore(3)
        self.playwright=None
        self.browser=None
        self.lock=asyncio.Lock()
        self.pages=asyncio.Semaphore(2)

    async def _scrapling_html(self,url):
        async with self.http_pages:
            async with self.http_lock:
                if self.http_session is None:
                    context=self.session_factory(timeout=12,retries=1,follow_redirects=False,
                        impersonate=None,stealthy_headers=False,
                        headers={'User-Agent':'SaharaCommute/0.1 (public journey research)'},
                        selector_config={'adaptive':False})
                    self.http_session=await context.__aenter__()
                    self.http_context=context
            response=await asyncio.wait_for(self.http_session.get(url),timeout=12)
            final=urlparse(response.url)
            if final.scheme!='https' or final.hostname not in ALLOWED:
                raise ValueError('Source redirected outside approved public hosts')
            if not 200<=response.status<300:
                request=httpx.Request('GET',url)
                raise httpx.HTTPStatusError(f'Source HTTP {response.status}',request=request,
                    response=httpx.Response(response.status,request=request))
            body=response.body
            if len(body)>4_000_000: raise ValueError('Provider response exceeded size limit')
            encoding=getattr(response,'encoding','utf-8') or 'utf-8'
            html=body.decode(encoding,errors='replace') if isinstance(body,bytes) else body
            if has_visible_challenge(html): raise ValueError('Source data unavailable or access challenge')
            return html

    async def _instance(self):
        async with self.lock:
            if self.browser is None or not self.browser.is_connected():
                from playwright.async_api import async_playwright
                if self.playwright is None:
                    self.playwright=await async_playwright().start()
                executable=os.getenv('SAHARA_BROWSER_EXECUTABLE')
                self.browser=await self.playwright.chromium.launch(headless=True,
                    **({'executable_path':executable} if executable else {}))
            return self.browser

    async def _dom_operation(self,coroutine):
        # Cancel the protocol operation with the caller.  Draining an uncancelled
        # Playwright operation here used to wait out the full navigation timeout
        # after a provider deadline had already expired, keeping the shared
        # browser page slot occupied and turning a bounded search into a 25s+
        # timeout.  The task is still awaited so its exception is observed.
        task=asyncio.create_task(coroutine)
        try:
            return await asyncio.shield(task)
        except asyncio.CancelledError:
            task.cancel()
            await asyncio.gather(task,return_exceptions=True)
            raise

    async def __call__(self,url,browser=False):
        parsed=urlparse(url)
        if parsed.scheme!='https' or parsed.hostname not in ALLOWED:
            raise ValueError('Collector host is not approved')
        if not browser:
            if self.http_engine=='scrapling': return await self._scrapling_html(url)
            return await collect_html(url,False)
        from playwright.async_api import TimeoutError as BrowserTimeoutError
        # JS pages need their scripts and ordinary site data requests to render.
        # We extract DOM only; never request or read the site's API payloads ourselves.
        selector={
            'onlineksrtcswift.com':'.srch-card',
            'www.railyatri.in':'.Mui-seat__layout_box',
            'www.confirmtkt.com':'.train-card .cache-card-wrapper, div.rounded-10.border-b .cache-card-wrapper',
            # Google Flights renders result cards after the page scripts run.
            # Keep the selector anchored to the public result-card/list semantics;
            # class names are only a fallback because Google rotates them.
            'www.google.com':'li[aria-label*="₹"], li[aria-label*="INR"], [class~="pIav2d"]',
        }[parsed.hostname]
        async with self.pages:
            instance=await self._instance()
            page=await instance.new_page()
            async def trim(route):
                if route.request.resource_type in ('image','media','font'):
                    await route.abort()
                else:
                    await route.continue_()
            try:
                await page.route('**/*',trim)
                # The search cards are either in the initial HTML or rendered
                # shortly after the response commits.  Waiting for the whole
                # document lifecycle makes a healthy public page spend the
                # entire navigation budget on unrelated analytics requests.
                await self._dom_operation(page.goto(url,wait_until='commit',timeout=12000))
                try:
                    await self._dom_operation(page.wait_for_selector(selector,state='attached',timeout=6000))
                except BrowserTimeoutError:
                    pass
                final=urlparse(page.url)
                if final.scheme!='https' or final.hostname not in ALLOWED:
                    raise ValueError('Source redirected outside approved public hosts')
                html=await page.content()
                if len(html)>4_000_000 or has_visible_challenge(html):
                    raise ValueError('Source data unavailable or access challenge')
                return html
            finally:
                await page.close()

    async def aclose(self):
        if self.http_context:
            await self.http_context.__aexit__(None,None,None)
            self.http_context=None
            self.http_session=None
        if self.browser:
            await self.browser.close()
            self.browser=None
        if self.playwright:
            await self.playwright.stop()
            self.playwright=None


async def collect_html(url,browser=False):
    parsed=urlparse(url)
    if parsed.scheme!='https' or parsed.hostname not in ALLOWED:
        raise ValueError('Collector host is not approved')
    async with _http_limit:
        async with httpx.AsyncClient(timeout=12,follow_redirects=False) as client:
            response=await client.get(url,headers={'User-Agent':'SaharaCommute/0.1 (public journey research)'})
            response.raise_for_status()
            html=response.text
    if len(html)>4_000_000:
        raise ValueError('Provider response exceeded size limit')
    if has_visible_challenge(html):
        raise ValueError('Source challenge: use authorized provider access')
    card_selector=('.train-card, div.rounded-10.border-b, .srch-card, '
                   '.Mui-seat__layout_box, li[aria-label*="₹"], '
                   'li[aria-label*="INR"], [class~="pIav2d"]')
    if browser and not BeautifulSoup(html,'html.parser').select_one(card_selector):
        async with _browser_limit:
            from playwright.async_api import async_playwright
            async with async_playwright() as playwright:
                executable=os.getenv('SAHARA_BROWSER_EXECUTABLE')
                instance=await playwright.chromium.launch(headless=True,**({'executable_path':executable} if executable else {}))
                try:
                    page=await instance.new_page()
                    await page.goto(url,wait_until='commit',timeout=15000)
                    try:
                        await page.wait_for_selector(card_selector,timeout=6000)
                    except Exception:
                        pass
                    html=await page.content()
                finally:
                    await instance.close()
    if len(html)>4_000_000 or has_visible_challenge(html):
        raise ValueError('Source data unavailable or access challenge')
    return html
