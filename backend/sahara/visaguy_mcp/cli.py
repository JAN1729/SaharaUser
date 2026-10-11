"""Command Line Interface for testing and managing VisaGuy MCP Connector."""

import asyncio
import json
import argparse
from .client import VisaGuyClient
from .hyperclassifier import JevHyperclassifier
from .server import run_server

def main():
    parser = argparse.ArgumentParser(description="VisaGuy MCP Connector & TypeSafe Jev CLI")
    subparsers = parser.add_subparsers(dest="command", help="Command to run")

    # Serve command
    serve_parser = subparsers.add_parser("serve", help="Run the MCP server")
    serve_parser.add_argument("--transport", choices=["stdio", "sse"], default="stdio")
    serve_parser.add_argument("--port", type=int, default=8000)

    # Classify command
    classify_parser = subparsers.add_parser("classify", help="Test TypeSafe JEV System-1 Hyperclassification")
    classify_parser.add_argument("query", help="User travel/commute query")
    classify_parser.add_argument("--nationality", default="Indian")
    classify_parser.add_argument("--salary", default="15000 AED")

    # Countries command
    countries_parser = subparsers.add_parser("countries", help="List assisted countries")
    countries_parser.add_argument("--category", help="Category filter: Schengen, e-visa, sticker-visa, etc.")
    countries_parser.add_argument("--search", help="Search string")

    # Eligibility command
    elig_parser = subparsers.add_parser("eligibility", help="Test eligibility recommendation engine")
    elig_parser.add_argument("--destination", required=True)
    elig_parser.add_argument("--nationality", default="Indian")
    elig_parser.add_argument("--salary", default="15000 AED")

    # Voice command
    voice_parser = subparsers.add_parser("voice", help="Get WebRTC voice agent session config")
    voice_parser.add_argument("--zone", default="uae", choices=["uae", "sa", "qa"])

    args = parser.parse_args()

    if args.command == "serve":
        run_server()
    elif args.command == "classify":
        asyncio.run(_run_classify(args))
    elif args.command == "countries":
        asyncio.run(_run_countries(args))
    elif args.command == "eligibility":
        asyncio.run(_run_eligibility(args))
    elif args.command == "voice":
        asyncio.run(_run_voice(args))
    else:
        parser.print_help()

async def _run_classify(args):
    clf = JevHyperclassifier()
    res = await clf.classify(args.query, {"nationality": args.nationality, "salary": args.salary})
    print("\n--- TypeSafe Jev System-1 Hyperclassification Result ---")
    print(json.dumps(res.model_dump(), indent=2))

async def _run_countries(args):
    client = VisaGuyClient()
    countries = await client.get_countries(category=args.category, search=args.search)
    print(f"\nFound {len(countries)} countries:")
    for c in countries:
        print(f"- {c.name} (slug: {c.slug}) [Categories: {', '.join(c.categories)}]")

async def _run_eligibility(args):
    from .schemas import EligibilityAssessmentRequest
    client = VisaGuyClient()
    res = await client.check_eligibility(
        EligibilityAssessmentRequest(
            destination=args.destination,
            nationality=args.nationality,
            salary=args.salary
        )
    )
    print("\n--- Eligibility Assessment Result ---")
    print(json.dumps(res.model_dump(), indent=2))

async def _run_voice(args):
    client = VisaGuyClient()
    res = await client.get_voice_agent_config(zone=args.zone)
    print("\n--- Voice Agent Credentials ---")
    print(f"Zone: {res.zone}")
    print(f"Client Secret (truncated): {res.client_secret[:30]}...")
    print(f"Instructions Length: {len(res.instructions)} chars")

if __name__ == "__main__":
    main()
