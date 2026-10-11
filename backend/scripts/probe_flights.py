"""Read-only Google Flights DOM probe.

This script opens only the public results page and reports parsed card counts.
It never logs in, books, calls provider inventory endpoints, or prints page
credentials.  Google Flights normally needs ``--browser`` because its initial
HTML is a JavaScript shell.
"""

import argparse
import asyncio
import json
from datetime import datetime, timedelta, timezone

from sahara.domain import SearchRequest
from sahara.places import TERMINALS
from sahara.providers.collector import PageCollector
from sahara.providers.flights import FlightProvider
from sahara.providers.parsing import IST


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--browser', action='store_true', help='Render the public page with Playwright')
    parser.add_argument('--diagnose-dom', action='store_true', help='Print only sanitized visible DOM text and URL')
    parser.add_argument('--origin', default='cok', choices=('cok', 'ccj', 'trv', 'blr', 'maa', 'del', 'bom', 'hyd'))
    parser.add_argument('--destination', default='blr', choices=('cok', 'ccj', 'trv', 'blr', 'maa', 'del', 'bom', 'hyd'))
    args = parser.parse_args()

    now = datetime.now(timezone.utc)
    start = now.astimezone(IST).date() + timedelta(days=1)
    request = SearchRequest(
        origin=args.origin,
        destination=args.destination,
        depart_after=datetime.combine(start, datetime.min.time(), tzinfo=IST),
        arrive_by=datetime.combine(start, datetime.min.time(), tzinfo=IST) + timedelta(hours=23),
        data_mode='live',
    )
    collector = PageCollector()
    provider = FlightProvider(enabled=True, browser=args.browser, collector=collector)
    try:
        if args.diagnose_dom:
            from playwright.async_api import async_playwright

            url = provider.url(args.origin.upper(), args.destination.upper(), start)
            async with async_playwright() as playwright:
                browser = await playwright.chromium.launch(headless=True)
                page = await browser.new_page()
                try:
                    await page.goto(url, wait_until='domcontentloaded', timeout=15000)
                    try:
                        await page.wait_for_timeout(4000)
                    except Exception:
                        pass
                    visible = ' '.join((await page.locator('body').inner_text()).split())
                    lowered = visible.casefold()
                    markers = [marker for marker in ('captcha', 'access denied', 'unusual traffic', 'verify you are human') if marker in lowered]
                    print(json.dumps({
                        'checked_at': now.isoformat(),
                        'url': page.url,
                        'title': await page.title(),
                        'visible_challenge_markers': markers,
                        'visible_text_excerpt': visible[:1200],
                    }, indent=2))
                finally:
                    await page.close()
                    await browser.close()
            return
        result = await asyncio.wait_for(
            provider.search(request, [(TERMINALS[args.origin], TERMINALS[args.destination])], now),
            30,
        )
        print(json.dumps({
            'checked_at': now.isoformat(),
            'route': [args.origin, args.destination],
            'browser': args.browser,
            'provider': result.provider,
            'status': result.status,
            'parsed_legs': len(result.legs),
            'message': result.message,
            'evidence': sorted({leg.observation.evidence for leg in result.legs}),
            'source_hosts': sorted({leg.observation.source_url.split('/')[2] for leg in result.legs if leg.observation.source_url}),
        }, indent=2))
    except Exception as error:
        print(json.dumps({
            'checked_at': now.isoformat(),
            'route': [args.origin, args.destination],
            'browser': args.browser,
            'provider': provider.name,
            'status': 'error',
            'message': type(error).__name__ + ': ' + str(error),
        }, indent=2))
    finally:
        await collector.aclose()


if __name__ == '__main__':
    asyncio.run(main())
