import json
import unittest
from datetime import datetime, timezone
import httpx


class LanguageInterpreterTests(unittest.IsolatedAsyncioTestCase):
    async def test_state_destination_asks_for_city_without_paid_models(self):
        from sahara.language import RequestInterpreter
        from sahara.jev_decisions import JevDecisions
        forbidden=httpx.MockTransport(lambda _:self.fail('State clarification must not call a paid model'))
        engine=JevDecisions('dummy',transport=forbidden)
        parser=RequestInterpreter('dummy',transport=forbidden,decisions=engine)
        try:
            with self.assertRaisesRegex(ValueError,'Gujarat is a state'):
                await parser.parse('Kozhikode to gujarat day after tomorrow')
        finally:
            await engine.aclose()

    async def test_day_after_tomorrow_uses_two_days_without_paid_models(self):
        from sahara.language import RequestInterpreter
        from sahara.jev_decisions import JevDecisions
        from zoneinfo import ZoneInfo
        forbidden=httpx.MockTransport(lambda _:self.fail('Clear route must not call a paid model'))
        engine=JevDecisions('dummy',transport=forbidden)
        parser=RequestInterpreter('dummy',transport=forbidden,decisions=engine)
        try:
            request=await parser.parse('Kozhikode to Bengaluru day after tomorrow',
                now=datetime(2026,10,11,8,tzinfo=ZoneInfo('Asia/Kolkata')))
            self.assertEqual('2026-10-13',request.depart_after.date().isoformat())
        finally:
            await engine.aclose()

    def test_vehicle_position_terms_are_preserved_without_coordinates(self):
        from sahara.language import RequestInterpreter
        context=RequestInterpreter.decision_context('Check the position of our vehicle approaching the station at 11.25,75.78',None)
        self.assertIn('position',context['request_terms'])
        self.assertIn('approaching',context['request_terms'])
        self.assertIn('station',context['request_terms'])
        self.assertNotIn('11.25',json.dumps(context))
        self.assertNotIn('75.78',json.dumps(context))

    def test_decision_context_preserves_public_route_semantics_without_raw_place_text(self):
        from sahara.language import RequestInterpreter
        context = RequestInterpreter.decision_context('Thiruvalla to Calicut tomorrow', None)
        self.assertEqual(['clt'], context['recognized_terminal_ids'])
        self.assertEqual('route_request', context['query_shape'])
        self.assertIn('to', context['route_markers'])
        self.assertIn('tomorrow', context['request_terms'])
        self.assertNotIn('thiruvalla', json.dumps(context).casefold())

    def test_decision_context_recognizes_non_english_public_terminals(self):
        from sahara.language import RequestInterpreter
        context = RequestInterpreter.decision_context('കോട്ടയം മുതൽ കോഴിക്കോട് വരെ നാളെ', None)
        self.assertEqual(['kty', 'clt'], context['recognized_terminal_ids'])
        self.assertEqual('ml', context['language'])
        self.assertEqual('route_request', context['query_shape'])

    def test_interpret_supports_known_malayalam_route_without_inventing_unknown_places(self):
        from datetime import datetime
        from zoneinfo import ZoneInfo
        from sahara.interpret import interpret
        parsed = interpret('കോട്ടയം മുതൽ കോഴിക്കോട് വരെ നാളെ',
                           now=datetime(2026, 10, 11, 8, tzinfo=ZoneInfo('Asia/Kolkata')))
        self.assertEqual(('kty', 'clt'), (parsed.origin, parsed.destination))
        self.assertEqual('2026-10-12', parsed.depart_after.date().isoformat())
        with self.assertRaisesRegex(ValueError, 'Place not in configured coverage'):
            interpret('അപരിചിതം മുതൽ കോഴിക്കോട് വരെ നാളെ')

    async def test_relative_budget_uses_code_arithmetic_and_is_not_absolute_rupee_amount(self):
        from sahara.language import RequestInterpreter
        fields=dict(origin='clt',destination='sbc',depart_after='2026-10-15T08:00:00+05:30',
            arrive_by=None,passengers=1,budget_minor=999999,preference='balanced',modes=['train','bus'])
        transport=httpx.MockTransport(lambda _:httpx.Response(200,json={'choices':[{'message':{'content':json.dumps(fields)}}]}))
        result=await RequestInterpreter('test-key',transport=transport).parse('I can spend ₹200 more',dict(fields,budget_minor=90000))
        self.assertEqual(110000,result.budget_minor)
    async def test_explicit_budget_and_group_size_cannot_be_dropped_by_model(self):
        from sahara.language import RequestInterpreter
        fields=dict(origin='clt',destination='sbc',depart_after='2026-10-15T08:00:00+05:30',
                    arrive_by=None,passengers=1,budget_minor=None,preference='balanced',modes=['train','bus'])
        transport=httpx.MockTransport(lambda request:httpx.Response(200,json={'choices':[{'message':{'content':json.dumps(fields)}}]}))
        result=await RequestInterpreter('test-key',transport=transport).parse('Kozhikode to Bengaluru for 2 people, only buses, under 1500 rupees')
        self.assertEqual((2,150000,['bus']),(result.passengers,result.budget_minor,[str(m) for m in result.modes]))

    async def test_validated_request_uses_model_but_never_model_inventory(self):
        from sahara.language import RequestInterpreter
        sent = []
        fields = dict(origin='clt', destination='sbc', depart_after='2026-10-15T08:00:00+05:30',
                      arrive_by=None, passengers=2, budget_minor=150000, preference='cheapest', modes=['train', 'bus'])
        def handler(request):
            sent.append(json.loads(request.content))
            return httpx.Response(200, json={'choices': [{'message': {'content': json.dumps(fields)}}]})
        interpreter = RequestInterpreter('test-key', 'deepseek/deepseek-v4-flash', transport=httpx.MockTransport(handler))
        result = await interpreter.parse('A Malayalam journey request', now=datetime(2026, 10, 10, tzinfo=timezone.utc))
        self.assertEqual(('clt', 'sbc', 2, 150000), (result.origin, result.destination, result.passengers, result.budget_minor))
        self.assertEqual('deepseek/deepseek-v4-flash', sent[0]['model'])
        self.assertEqual({'enabled':False}, sent[0]['reasoning'])
        self.assertNotIn('tools', sent[0])
        self.assertNotIn('test-key', json.dumps(sent[0]))

    async def test_invented_terminals_and_inventory_fields_cannot_enter_planner(self):
        from sahara.language import RequestInterpreter
        fields = dict(origin='made-up', destination='sbc', depart_after='2026-10-15T08:00:00+05:30',
                      arrive_by=None, passengers=1, budget_minor=None, preference='balanced', modes=['train'])
        for payload in [fields, dict(fields, origin='clt', journeys=[{'fare': 0}])]:
            transport = httpx.MockTransport(lambda request: httpx.Response(200, json={'choices': [{'message': {'content': json.dumps(payload)}}]}))
            with self.assertRaises(ValueError):
                await RequestInterpreter('test-key', transport=transport).parse('unknown request')

    async def test_missing_key_keeps_deterministic_parser_without_network(self):
        from sahara.language import RequestInterpreter
        def forbidden(request):
            self.fail('No language request should be made without a key')
        parsed = await RequestInterpreter('', transport=httpx.MockTransport(forbidden)).parse('Kottayam to Bengaluru tomorrow')
        self.assertEqual('kty', parsed.origin)

    async def test_gateway_failure_is_sanitized_and_basic_query_falls_back(self):
        from sahara.language import RequestInterpreter
        transport = httpx.MockTransport(lambda request: httpx.Response(401, json={'error': 'credential detail'}))
        interpreter = RequestInterpreter('test-key', transport=transport)
        self.assertEqual('kty', (await interpreter.parse('Kottayam to Bengaluru tomorrow')).origin)
        with self.assertRaises(ValueError) as error:
            await interpreter.parse('unsupported request')
        self.assertNotIn('test-key', str(error.exception))
        self.assertNotIn('credential detail', str(error.exception))
