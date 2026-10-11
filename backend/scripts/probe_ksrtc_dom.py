"""Read-only regular-Chrome probe for one public KSRTC rendered page.

The probe records the visible ``.srch-card`` fields and runs the same DOM
parser used by the backend.  It does not inspect or call the site's internal
request endpoints, submit a booking, or log in.
"""
import argparse
import asyncio
import json
import os
import re
from datetime import date, datetime, timezone
from pathlib import Path
from urllib.parse import urlencode

from bs4 import BeautifulSoup
from playwright.async_api import async_playwright

from sahara.places import TERMINALS
from sahara.providers.ksrtc import parse_ksrtc


def _card_snapshot(card):
    lines = list(card.stripped_strings)
    leaf_lines = [node.get_text(' ', strip=True) for node in card.find_all() if not node.find()]

    def text(selector):
        node = card.select_one(selector)
        return node.get_text(' ', strip=True) if node else None

    fare = next((line for line in leaf_lines if re.fullmatch(r'₹[\d,]+', line)), None)
    status = next((line for line in leaf_lines if re.search(r'(?:Seats?|Berths?)\s+Left|Sold Out|Available', line, re.I)), None)
    booking = next((line for line in leaf_lines if 'booking for this distance starts' in line.lower()), None)
    timings = [node.get_text(' ', strip=True) for node in card.select('.triptimebold')]
    return {
        'route_code': text('.service--route'),
        'departure_label': timings[0] if timings else None,
        'arrival_label': timings[1] if len(timings) > 1 else None,
        'duration_label': text('.duration'),
        'fare_label': fare,
        'availability_label': status,
        'bus_type': text('.bus--type'),
        'booking_window': booking,
        'visible_text': lines,
    }


async def probe(args):
    day = date.fromisoformat(args.date)
    origin = TERMINALS[args.origin]
    destination = TERMINALS[args.destination]
    url = 'https://onlineksrtcswift.com/search?' + urlencode({
        'fromCity': origin.codes['ksrtc'],
        'toCity': destination.codes['ksrtc'],
        'departDate': day.strftime('%d-%m-%Y'),
        'mode': 'oneway',
        'src': 'h',
    })
    output = Path(args.output or f'data/live-tests/{args.origin.removesuffix("-bus")}-bengaluru-{day:%Y-%m-%d}/chrome-live-card-probe.json')
    output.parent.mkdir(parents=True, exist_ok=True)
    executable = os.getenv('SAHARA_BROWSER_EXECUTABLE') or r'C:\Program Files\Google\Chrome\Application\chrome.exe'
    captured_at = datetime.now(timezone.utc)
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(headless=True, executable_path=executable)
        try:
            page = await browser.new_page()
            try:
                await page.goto(url, wait_until='commit', timeout=args.navigation_timeout)
                try:
                    await page.wait_for_selector('.srch-card', state='attached', timeout=args.selector_timeout)
                except Exception:
                    pass
                html = await page.content()
                title = await page.title()
                final_url = page.url
            finally:
                await page.close()
        finally:
            await browser.close()

    soup = BeautifulSoup(html, 'html.parser')
    cards = [_card_snapshot(card) for card in soup.select('.srch-card')]
    legs = parse_ksrtc(html, args.origin, args.destination, day, captured_at, url)
    result = {
        'evidence_kind': 'actual-public-page-dom-probe',
        'fixture': False,
        'capture_tool': 'regular Chrome via Playwright; visible rendered DOM only',
        'captured_at': captured_at.isoformat(),
        'requested_url': url,
        'final_url': final_url,
        'page_title': title,
        'css_selector': '.srch-card',
        'raw_card_count': len(cards),
        'raw_cards': cards,
        'parsed_legs': [leg.model_dump(mode='json') for leg in legs],
        'parsed_leg_count': len(legs),
    }
    output.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding='utf-8')
    print(json.dumps({
        'output': str(output),
        'requested_url': url,
        'raw_card_count': len(cards),
        'parsed_leg_count': len(legs),
        'route_codes': [leg.name.split(' · ', 1)[0] for leg in legs],
    }, indent=2, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--date', default='2026-10-12', help='Local KSRTC service date (YYYY-MM-DD)')
    parser.add_argument('--origin', default='kozhikode-bus', choices=['kozhikode-bus', 'ernakulam-bus', 'coimbatore-bus'])
    parser.add_argument('--destination', default='bengaluru-bus', choices=['bengaluru-bus'])
    parser.add_argument('--output', help='Evidence JSON path')
    parser.add_argument('--navigation-timeout', type=int, default=20000)
    parser.add_argument('--selector-timeout', type=int, default=10000)
    args = parser.parse_args()
    asyncio.run(probe(args))


if __name__ == '__main__':
    main()
