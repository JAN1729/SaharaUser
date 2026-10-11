"""Validate configured OpenRouter and one bounded extraction; never print credentials."""
import asyncio
import json
import os
import httpx


async def main():
    key=os.environ.get('OPENROUTER_API_KEY','')
    if not key:
        raise SystemExit('OpenRouter key is not configured in the server environment')
    async with httpx.AsyncClient(timeout=30) as client:
        status=await client.get('https://openrouter.ai/api/v1/key',headers={'Authorization':'Bearer '+key})
        print(json.dumps({'authentication_status':status.status_code}))
        if status.status_code!=200:
            return
        from sahara.language import RequestInterpreter
        try:
            parsed=await RequestInterpreter(key,os.getenv('OPENROUTER_MODEL','deepseek/deepseek-v4-flash')).parse(
                'Kozhikode to Bengaluru tomorrow for 2 people, only buses, under 1500 rupees',allow_fallback=False)
        except ValueError as error:
            print(json.dumps({'model_check':str(error)}))
            return
        print(json.dumps({'model':os.getenv('OPENROUTER_MODEL','deepseek/deepseek-v4-flash'),
            'origin':parsed.origin,'destination':parsed.destination,'passengers':parsed.passengers,
            'budget_minor':parsed.budget_minor,'modes':[str(mode) for mode in parsed.modes],
            'request_mode':parsed.data_mode},indent=2))
        assert parsed.passengers==2 and [str(mode) for mode in parsed.modes]==['bus'] and parsed.budget_minor==150000


if __name__=='__main__':
    asyncio.run(main())
