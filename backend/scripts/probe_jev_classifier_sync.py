"""Small synchronous live Jev classifier probe; prints no credentials or raw request text."""
import json
import os
import time

import httpx

from sahara.jev_decisions import validate_answers
from sahara.language import RequestInterpreter
from scripts.probe_jev_classifier import CASES, QUESTIONS


def main():
    key = os.getenv('OPENROUTER_API_KEY', '')
    if not key:
        raise SystemExit('OPENROUTER_API_KEY is not available in the probe environment')
    rows = []
    with httpx.Client(timeout=5, follow_redirects=False) as client:
        for label, query, previous, expected in CASES:
            state = RequestInterpreter.decision_context(query, previous)
            started = time.perf_counter()
            try:
                response = client.post(
                    'https://openrouter.ai/api/alpha/decisions',
                    headers={'Authorization': 'Bearer ' + key, 'X-Title': 'Sahara Mobility'},
                    json={'model': 'typesafe/jev-1.13', 'state': state, 'questions': QUESTIONS},
                )
                elapsed_ms = round((time.perf_counter() - started) * 1000)
                body = response.json()
                answers, usage = validate_answers(body, QUESTIONS) if response.is_success else ({}, {})
                answer = answers.get('intent', {})
                rows.append({'case': label, 'expected': expected, 'status': 'ok' if response.is_success else 'http_error',
                    'http_status': response.status_code, 'choice': answer.get('choice'),
                    'confidence': answer.get('confidence'), 'latency_ms': elapsed_ms,
                    'cost': usage.get('cost'), 'state': state})
            except (httpx.HTTPError, ValueError, KeyError, TypeError) as error:
                rows.append({'case': label, 'expected': expected, 'status': 'error',
                    'error_type': type(error).__name__,
                    'latency_ms': round((time.perf_counter() - started) * 1000), 'state': state})
    print(json.dumps({'cases': rows}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
