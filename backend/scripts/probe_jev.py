"""Small live synthetic probe, not a production accuracy benchmark. Never prints keys."""
import asyncio
import json
import os
from sahara.jev_decisions import JevDecisions


async def main():
    engine=JevDecisions(os.getenv('OPENROUTER_API_KEY',''),timeout=5)
    state={'stop_pair':{'a':'Mavoor Road Junction','b':'Mavoor Rd Jn','independent_checks':'passed'},
        'notice':'Mavoor Road outbound bus services are diverted to the adjacent boarding stop this evening.',
        'request':'Will I miss my train connection?'}
    questions={
        'stop_relationship':{'type':'choice','instructions':'How are the names in stop_pair related?',
            'criteria':{'same':'Same boarding stop, abbreviation only.','different':'Different boarding stops.','uncertain':'Cannot determine.'}},
        'notice_kind':{'type':'choice','instructions':'What change does notice describe?',
            'criteria':{'diversion':'Changed road or boarding point.','delay':'Late service.','cancellation':'Service cancelled.','unclear':'Unclear.'}},
        'request_intent':{'type':'choice','instructions':'What action does request ask for?',
            'criteria':{'planning':'New journey search.','connection':'Revalidate an existing connection.','tracking':'Find vehicle.','unclear':'Other or unclear.'}},
        'name_alias':{'type':'noul','instructions':'Are the naming differences in stop_pair common abbreviations?'}}
    try:
        result=await engine.evaluate(state,questions)
        cached=await engine.evaluate(state,questions) if result['status']=='ok' else None
        print(json.dumps({'notice':'Synthetic examples only; no real source inventory, passenger data or GPS.',
            'first':result,'cached':bool(cached and cached['cached']),'diagnostics':engine.diagnostics()},indent=2))
    finally:
        await engine.aclose()


if __name__=='__main__':
    asyncio.run(main())
