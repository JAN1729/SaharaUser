import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch
import builtins
import httpx
from sahara.providers.collector import PageCollector


class ScraplingCollectorTests(unittest.IsolatedAsyncioTestCase):
    def test_optional_dependency_fallback_and_explicit_missing_engine(self):
        original=builtins.__import__
        def unavailable(name,*args,**kwargs):
            if name=='scrapling.fetchers': raise ImportError('optional package unavailable')
            return original(name,*args,**kwargs)
        with patch('builtins.__import__',side_effect=unavailable):
            self.assertEqual('httpx',PageCollector(http_engine='auto').http_engine)
            with self.assertRaisesRegex(RuntimeError,'Install'): PageCollector(http_engine='scrapling')

    def test_unknown_engine_is_rejected(self):
        with self.assertRaises(ValueError): PageCollector(http_engine='unknown')

    def setup_collector(self,body=b'<html><div class="train-card">dated fare</div></html>',status=200):
        self.session=SimpleNamespace(get=AsyncMock(return_value=SimpleNamespace(body=body,status=status,url='https://www.confirmtkt.com/trains',encoding='utf-8')))
        self.context=SimpleNamespace(__aenter__=AsyncMock(return_value=self.session),__aexit__=AsyncMock())
        self.factory=Mock(return_value=self.context)
        return PageCollector(http_engine='scrapling',session_factory=self.factory)

    async def test_reuses_async_session_and_returns_original_html_without_redirect_or_stealth(self):
        collector=self.setup_collector()
        for _ in range(2):
            self.assertIn('class="train-card"',await collector('https://www.confirmtkt.com/trains'))
        self.factory.assert_called_once()
        options=self.factory.call_args.kwargs
        self.assertFalse(options['follow_redirects'])
        self.assertFalse(options['stealthy_headers'])
        self.assertIsNone(options['impersonate'])
        self.assertEqual(1,options['retries'])
        await collector.aclose()
        self.context.__aexit__.assert_awaited_once()

    async def test_unapproved_host_is_rejected_before_any_network_or_session(self):
        collector=self.setup_collector()
        with self.assertRaises(ValueError): await collector('https://example.org/private')
        self.factory.assert_not_called()

    async def test_redirect_and_visible_challenge_are_not_accepted_as_inventory(self):
        for body,status,error in [(b'<html>redirect</html>',302,httpx.HTTPStatusError),(b'<html>Verify you are human</html>',200,ValueError)]:
            collector=self.setup_collector(body,status)
            try:
                with self.assertRaises(error): await collector('https://www.confirmtkt.com/trains')
            finally: await collector.aclose()

    async def test_oversized_response_is_rejected(self):
        collector=self.setup_collector(b'x'*4_000_001)
        try:
            with self.assertRaisesRegex(ValueError,'size'): await collector('https://www.confirmtkt.com/trains')
        finally: await collector.aclose()

    async def test_dormant_script_challenge_does_not_drop_visible_data(self):
        collector=self.setup_collector(b'<html><script>captcha</script><p>train cards</p></html>')
        try: self.assertIn('train cards',await collector('https://www.confirmtkt.com/trains'))
        finally: await collector.aclose()
