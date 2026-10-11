"""Server-only, bounded typed decisions through OpenRouter; never transport inventory."""
import asyncio
import copy
import hashlib
import json
import math
import re
import time
from collections import OrderedDict, deque
import httpx


def probability(value):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not 0 <= value <= 1:
        raise ValueError('Invalid decision probability')
    return float(value)


def validate_answers(body, questions):
    if not isinstance(body, dict) or not isinstance(body.get('model'), str) or not re.fullmatch(r'typesafe/jev-1\.13(?:-\d{8})?',body['model']):
        raise ValueError('Unexpected decision model')
    answers = body.get('answers')
    if not isinstance(answers, dict) or set(answers) != set(questions):
        raise ValueError('Incomplete decision answers')
    clean = {}
    for key, question in questions.items():
        item = answers[key]
        kind = question['type']
        if not isinstance(item, dict) or item.get('type') != kind:
            raise ValueError('Unexpected answer type')
        if kind == 'noul':
            clean[key] = {'type': kind, 'noul': probability(item.get('noul'))}
            continue
        levels = question['criteria']
        options = list(levels) if kind == 'choice' else [str(i) for i in range(len(levels))]
        probs = item.get('probabilities')
        if not isinstance(probs, dict) or set(probs) != set(options):
            raise ValueError('Unexpected answer options')
        probs = {k: probability(v) for k, v in probs.items()}
        if abs(sum(probs.values()) - 1) > .01:
            raise ValueError('Invalid probability distribution')
        confidence = probability(item.get('confidence'))
        clean[key] = {'type': kind, 'confidence': confidence, 'probabilities': probs}
        if kind == 'choice':
            choice = item.get('choice')
            if choice not in options or probs[choice] < max(probs.values()) - .001:
                raise ValueError('Invalid chosen option')
            clean[key]['choice'] = choice
        else:
            score = item.get('score')
            expected = sum(int(k) * v for k, v in probs.items())
            if isinstance(score, bool) or not isinstance(score, (int, float)) or not math.isfinite(score) or abs(score-expected) > .02:
                raise ValueError('Invalid rubric score')
            clean[key].update(score=score, legend={str(i): level for i, level in enumerate(levels)})
    usage = body.get('usage', {})
    if not isinstance(usage, dict):
        raise ValueError('Invalid usage record')
    cost = usage.get('cost', 0)
    tokens = usage.get('input_tokens', 0)
    if isinstance(cost, bool) or not isinstance(cost, (int, float)) or not math.isfinite(cost) or cost < 0:
        raise ValueError('Invalid usage cost')
    if isinstance(tokens, bool) or not isinstance(tokens, int) or tokens < 0:
        raise ValueError('Invalid usage tokens')
    return clean, {'cost': cost, 'input_tokens': tokens}


class JevDecisions:
    def __init__(self, key='', model='typesafe/jev-1.13', transport=None, timeout=4):
        if model!='typesafe/jev-1.13' or not .01 <= timeout <= 10:
            raise ValueError('Invalid Jev model/deadline')
        self.key, self.model, self.timeout = key, model, timeout
        self.client = None
        self.transport = transport
        self.capacity = asyncio.Semaphore(4)
        self.cache = OrderedDict()
        self.pending = {}
        self.cooldown_until = 0
        self.last_failure = None
        self.latencies = deque(maxlen=500)
        self.metrics = {'requests': 0, 'cache_hits': 0, 'failures': 0, 'input_tokens': 0, 'cost_usd': 0.0,
            'usage_unavailable_requests': 0}

    @property
    def configured(self):
        return bool(self.key)

    async def evaluate(self, state, questions):
        if not self.key:
            return {'status': 'disabled', 'answers': {}, 'cached': False}
        if not 1 <= len(questions) <= 32:
            raise ValueError('Use one to thirty-two atomic questions')
        for question in questions.values():
            kind = question.get('type')
            criteria = question.get('criteria')
            if kind not in ('choice', 'noul', 'score') or not question.get('instructions'):
                raise ValueError('Invalid decision question')
            if kind == 'choice' and (not isinstance(criteria, dict) or not 2 <= len(criteria) <= 32):
                raise ValueError('Invalid choice criteria')
            if kind == 'score' and (not isinstance(criteria, list) or not 2 <= len(criteria) <= 10):
                raise ValueError('Invalid score criteria')
        payload = {'model': self.model, 'state': state, 'questions': questions}
        raw = json.dumps(payload, sort_keys=True, ensure_ascii=False, allow_nan=False)
        if len(raw.encode('utf-8')) > 32000:
            raise ValueError('Decision context exceeds bounded size')
        key = hashlib.sha256(raw.encode('utf-8')).hexdigest()
        stamp = time.monotonic()
        cached = self.cache.get(key)
        if cached and cached[0] > stamp:
            self.metrics['cache_hits'] += 1
            self.cache.move_to_end(key)
            return dict(copy.deepcopy(cached[1]), cached=True)
        if self.cooldown_until > stamp:
            return {'status': 'cooldown', 'answers': {}, 'cached': False}
        task = self.pending.get(key)
        if task is None:
            if len(self.pending) >= 32:
                return {'status': 'busy', 'answers': {}, 'cached': False}
            # Cache identity and submitted payload must be the same immutable snapshot.
            snapshot = json.loads(raw)
            task = asyncio.create_task(self._evaluate(key, snapshot, snapshot['questions']))
            self.pending[key] = task
            task.add_done_callback(lambda _: self.pending.pop(key, None))
        return copy.deepcopy(await asyncio.shield(task))

    async def _evaluate(self, key, payload, questions):
        started = time.monotonic()
        attempted = False
        status = None
        try:
            # Includes time waiting for the concurrency slot.
            async with asyncio.timeout(self.timeout):
                async with self.capacity:
                    if self.client is None:
                        self.client = httpx.AsyncClient(transport=self.transport, timeout=self.timeout, follow_redirects=False)
                    attempted = True
                    self.metrics['requests'] += 1
                    response = await self.client.post('https://openrouter.ai/api/alpha/decisions',
                        headers={'Authorization': 'Bearer '+self.key, 'X-Title': 'Sahara Mobility'}, json=payload)
                    status = response.status_code
                    response.raise_for_status()
                    if len(response.content) > 250000:
                        raise ValueError('Decision response exceeds bounded size')
                    body = response.json()
                    answers, usage = validate_answers(body, questions)
            elapsed = round((time.monotonic()-started)*1000, 3)
            result = {'status': 'ok', 'model': body['model'], 'answers': answers,
                'usage': usage, 'latency_ms': elapsed, 'cached': False}
            self.metrics['input_tokens'] += usage['input_tokens']
            self.metrics['cost_usd'] += usage['cost']
            self.latencies.append(elapsed)
            self.cache[key] = (time.monotonic()+300, copy.deepcopy(result))
            while len(self.cache) > 256:
                self.cache.popitem(last=False)
            return result
        except (httpx.HTTPError, TimeoutError, ValueError, TypeError, KeyError) as error:
            self.metrics['failures'] += 1
            self.metrics['usage_unavailable_requests'] += int(attempted)
            self.cooldown_until = time.monotonic()+(300 if status in (401, 402, 403) else 30)
            category=('deadline' if isinstance(error,(TimeoutError,httpx.TimeoutException)) else
                'http_status' if isinstance(error,httpx.HTTPStatusError) else
                'connection' if isinstance(error,httpx.HTTPError) else 'invalid_response')
            elapsed=round((time.monotonic()-started)*1000, 3)
            # Never retain provider error bodies, submitted prose, headers or keys.
            self.last_failure={'category':category,'http_status':status,'latency_ms':elapsed}
            return {'status': 'unavailable', 'answers': {}, 'cached': False,
                'http_status': status, 'latency_ms': elapsed,'failure_kind':category}

    def diagnostics(self):
        values = sorted(self.latencies)
        return dict(self.metrics, configured=self.configured, model=self.model,
            deadline_seconds=self.timeout,last_failure=copy.deepcopy(self.last_failure),
            cooldown=self.cooldown_until > time.monotonic(),
            p50_ms=values[len(values)//2] if values else None,
            p95_ms=values[min(len(values)-1, math.ceil(len(values)*.95)-1)] if values else None,
            notice='Process-local reported API usage; failed/timed-out requests may be billed without returned usage. Model confidence is not arrival reliability.')

    async def aclose(self):
        tasks = list(self.pending.values())
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        if self.client is not None:
            await self.client.aclose()
