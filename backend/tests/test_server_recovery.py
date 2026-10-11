import unittest
import tempfile
import asyncio
import time
from pathlib import Path
from sahara.providers.collector import PageCollector


class BrowserTimeoutTests(unittest.IsolatedAsyncioTestCase):
    async def test_missing_card_selector_still_returns_public_dom(self):
        from playwright.async_api import TimeoutError as BrowserTimeoutError
        class Page:
            url='https://onlineksrtcswift.com/search'
            async def route(self,*args): pass
            async def goto(self,*args,**kwargs): pass
            async def wait_for_selector(self,*args,**kwargs): raise BrowserTimeoutError('No cards yet')
            async def content(self): return '<html>Source has no cards</html>'
            async def close(self): pass
        class Browser:
            async def new_page(self): return Page()
        collector=PageCollector()
        async def instance(): return Browser()
        collector._instance=instance
        self.assertEqual('<html>Source has no cards</html>',await collector('https://onlineksrtcswift.com/search',True))

    async def test_cancelled_dom_operation_cancels_navigation_immediately(self):
        collector=PageCollector()
        started=asyncio.Event()
        async def slow_operation():
            started.set()
            await asyncio.sleep(60)
        task=asyncio.create_task(collector._dom_operation(slow_operation()))
        await started.wait()
        tick=time.perf_counter()
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertLess(time.perf_counter()-tick,0.5)


class InterruptedSearchTests(unittest.TestCase):
    def test_process_restart_finishes_orphaned_search_with_explicit_error(self):
        from sahara.service import PlanningService
        from sahara.settings import Settings
        with tempfile.TemporaryDirectory() as folder:
            settings=Settings(database=Path(folder)/'db')
            service=PlanningService(settings)
            service.repository.save('search','s','owner',{'id':'s','collection_complete':False,
                'pending_providers':['ksrtc'],'providers':[{'provider':'ksrtc','status':'partial','message':'Loading'}],
                'plan':{'journeys':[]}})
            from fastapi.testclient import TestClient
            from sahara.api import create_app
            with TestClient(create_app(settings)) as client:
                result=client.app.state.service.repository.get('search','s','owner')
            self.assertTrue(result['collection_complete'])
            self.assertEqual([],result['pending_providers'])
            self.assertIn('restart',result['collection_error'])
            self.assertEqual('error',result['providers'][0]['status'])
