"""Bounded Jev intent probe using sanitized semantic state only.

This is an evaluation helper, not a production request path. It never prints
the API key, request headers, or raw passenger prose.
"""
import asyncio
import json
import os
import sys
import time
from unittest.mock import AsyncMock

import httpx

from sahara.jev_decisions import JevDecisions
from sahara.language import RequestInterpreter


QUESTIONS = {
    'intent': {
        'type': 'choice',
        'instructions': 'Choose the backend action from the sanitized semantic state. If insufficient choose unclear.',
        'criteria': {
            'plan': 'Find a new journey from an origin to a destination.',
            'constraints': 'Change budget, modes, walking, time or comfort for an existing search.',
            'tracking': 'Locate an assigned vehicle.',
            'connection': 'Check an active journey connection.',
            'alternative': 'Find other journeys.',
            'explanation': 'Explain existing recommendations.',
            'unclear': 'Intent is unclear or outside supported mobility actions.',
        },
    }
}


CASES = [
    ('new_route_en', 'Find a bus from Kottayam to Bengaluru tomorrow', None, 'plan'),
    ('new_route_ml', 'കോട്ടയം മുതൽ കോഴിക്കോട് വരെ നാളെ', None, 'plan'),
    ('unknown_origin_route', 'Thiruvalla to Calicut', None, 'plan'),
    ('tracking', 'Where is my bus?', None, 'tracking'),
    ('connection', 'Will I miss my train connection?', None, 'connection'),
    ('constraints', 'I can spend ₹200 more', {'origin': 'kty', 'destination': 'clt'}, 'constraints'),
]


async def local_guard_regression():
    """Exercise the route-versus-follow-up guard without a network request."""
    def wrong_tracking(request):
        body = json.loads(request.content)
        criteria = body['questions']['intent']['criteria']
        return httpx.Response(200, json={
            'model': 'typesafe/jev-1.13-20260917',
            'answers': {'intent': {
                'type': 'choice', 'choice': 'tracking', 'confidence': 1,
                'probabilities': {key: int(key == 'tracking') for key in criteria},
            }},
            'usage': {'input_tokens': 100, 'cost': 0.000001},
        })
    engine = JevDecisions('probe-key', transport=httpx.MockTransport(wrong_tracking))
    parser = RequestInterpreter('probe-key', decisions=engine)
    parser._extract = AsyncMock(return_value='route extraction')
    try:
        result = await parser.parse('Find a bus from Kottayam to Bengaluru tomorrow')
        assert result == 'route extraction'
        parser._extract.assert_awaited_once()
    finally:
        await engine.aclose()
    print('local_guard_regression: PASS')


async def main():
    key = os.getenv('OPENROUTER_API_KEY', '')
    if not key:
        raise SystemExit('OPENROUTER_API_KEY is not available in the probe environment')
    engine = JevDecisions(key, timeout=5)
    rows = []
    try:
        for label, query, previous, expected in CASES:
            state = RequestInterpreter.decision_context(query, previous)
            started = time.perf_counter()
            result = await engine.evaluate(state, QUESTIONS)
            elapsed_ms = round((time.perf_counter() - started) * 1000)
            answer = result.get('answers', {}).get('intent', {}) if result.get('status') == 'ok' else {}
            rows.append({
                'case': label,
                'expected': expected,
                'status': result.get('status'),
                'choice': answer.get('choice'),
                'confidence': answer.get('confidence'),
                'latency_ms': elapsed_ms,
                'cost': result.get('usage', {}).get('cost'),
                'state': state,
            })
    finally:
        await engine.aclose()
    print(json.dumps({'cases': rows, 'diagnostics': engine.diagnostics()}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    asyncio.run(local_guard_regression() if '--local' in sys.argv else main())
