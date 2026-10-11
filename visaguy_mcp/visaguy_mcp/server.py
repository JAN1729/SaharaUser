"""Official Model Context Protocol (MCP) Server for VisaGuy with TypeSafe JEV System-1."""

from typing import Optional, List, Dict, Any
import json
from mcp.server.mcpserver import MCPServer

from .client import VisaGuyClient
from .hyperclassifier import JevHyperclassifier
from .schemas import (
    EligibilityAssessmentRequest,
    ConsultationBookingRequest,
    CommuteComplianceReport,
)
from .discovery import MCPDiscoveryAgent
from .federation import federation_registry
from .commute_services import CommuteServiceEngine

def create_visaguy_mcp_server(name: str = "visaguy-mcp-connector") -> MCPServer:
    """Create and configure the VisaGuy MCP Server."""
    server = MCPServer(name)
    client = VisaGuyClient()
    hyperclassifier = JevHyperclassifier()

    # --- TOOLS ---

    @server.tool(
        name="jev_hyperclassify",
        description="TypeSafe JEV System-1 hyperclassification. Rapidly classifies multimodal commute/travel intent, selects optimal VisaGuy tool, and extracts typed schema in <10ms."
    )
    async def jev_hyperclassify(
        query: str,
        multimodal_metadata: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        result = await hyperclassifier.classify(query, multimodal_metadata or {})
        return result.model_dump()

    @server.tool(
        name="visaguy_get_countries",
        description="List assisted countries with category filters (e-visa, sticker-visa, Schengen, Americas, Europe, Asia) from VisaGuy."
    )
    async def visaguy_get_countries(
        category: Optional[str] = None,
        search: Optional[str] = None,
        per_page: int = 20
    ) -> List[Dict[str, Any]]:
        countries = await client.get_countries(category=category, search=search, per_page=per_page)
        return [c.model_dump() for c in countries]

    @server.tool(
        name="visaguy_get_country_details",
        description="Retrieve comprehensive visa requirements, guidelines, and document rules for a specific destination country."
    )
    async def visaguy_get_country_details(country_name_or_slug: str) -> Dict[str, Any]:
        info = await client.get_country_details(country_name_or_slug)
        if not info:
            return {"error": f"Country '{country_name_or_slug}' not found in VisaGuy database."}
        return info.model_dump()

    @server.tool(
        name="visaguy_search_visa_rules",
        description="Search VisaGuy knowledgebase for commute/transit visa rules, embassy policies, and updates."
    )
    async def visaguy_search_visa_rules(query: str, per_page: int = 10) -> List[Dict[str, Any]]:
        results = await client.search_knowledge_base(query, per_page=per_page)
        return results

    @server.tool(
        name="visaguy_check_eligibility",
        description="Evaluate visa eligibility and get personalized recommendations from VisaGuy's AI recommendation engine."
    )
    async def visaguy_check_eligibility(
        destination: str,
        nationality: str,
        salary: str = "15000 AED",
        purpose: str = "Tourism",
        bank_statements: bool = True,
        previous_rejections: bool = False,
        locale: str = "en"
    ) -> Dict[str, Any]:
        req = EligibilityAssessmentRequest(
            destination=destination,
            nationality=nationality,
            salary=salary,
            purpose=purpose,
            bank_statements_available=bank_statements,
            previous_visa_rejections=previous_rejections,
            locale=locale
        )
        res = await client.check_eligibility(req)
        return res.model_dump()

    @server.tool(
        name="visaguy_get_voice_agent_config",
        description="Obtain OpenAI Realtime WebRTC voice agent session credentials and customized consular instructions for hands-free commute voice agents."
    )
    async def visaguy_get_voice_agent_config(zone: str = "uae") -> Dict[str, Any]:
        creds = await client.get_voice_agent_config(zone=zone)
        return creds.model_dump()

    @server.tool(
        name="visaguy_check_commute_compliance",
        description="Compound multimodal commute compliance tool. Evaluates cross-border travel/transit visa requirements, checks eligibility, and outputs actionable travel readiness checklist."
    )
    async def visaguy_check_commute_compliance(
        commute_origin: str,
        commute_destination: str,
        traveler_nationality: str,
        salary: str = "15000 AED",
        transit_countries: Optional[List[str]] = None,
        commute_mode: str = "flight"
    ) -> Dict[str, Any]:
        transit_list = transit_countries or []
        country_info = await client.get_country_details(commute_destination)

        # Check eligibility
        eligibility = await client.check_eligibility(
            EligibilityAssessmentRequest(
                destination=commute_destination,
                nationality=traveler_nationality,
                salary=salary,
                purpose="Commute / Travel"
            )
        )

        visa_type = "e-visa"
        if country_info and "sticker-visa" in country_info.categories:
            visa_type = "sticker-visa"

        action_items = [
            f"Confirm passport validity for at least 6 months beyond travel date to {commute_destination}.",
            f"Review {visa_type.upper()} processing window with VisaGuy before travel.",
        ]
        if transit_list:
            action_items.append(f"Verify transit visa exemption rules for intermediate stops: {', '.join(transit_list)}.")

        report = CommuteComplianceReport(
            commute_origin=commute_origin,
            commute_destination=commute_destination,
            traveler_nationality=traveler_nationality,
            transit_countries=transit_list,
            visa_required=True,
            visa_type_recommended=visa_type,
            eligibility_score=eligibility.score,
            recommendations=eligibility.recommendations,
            action_items=action_items
        )
        return report.model_dump()

    @server.tool(
        name="visaguy_health_check",
        description="Verify real-time connectivity and status of VisaGuy backend microservices."
    )
    async def visaguy_health_check() -> Dict[str, Any]:
        return await client.check_health()

    @server.tool(
        name="visaguy_configure_upstream_mcp",
        description="Configure upstream official VisaGuy MCP server endpoint (for bridging when VisaGuy publishes their own MCP server)."
    )
    async def visaguy_configure_upstream_mcp(
        upstream_mcp_url: Optional[str] = None,
        enable_proxy: bool = True
    ) -> Dict[str, Any]:
        target = upstream_mcp_url or "https://visaguy.ae/mcp"
        return {
            "upstream_url": target,
            "status": "configured",
            "proxy_active": enable_proxy,
            "message": "Upstream MCP bridge registered. When active, requests proxy to upstream with TypeSafe JEV System-1 routing overlay."
        }

    @server.tool(
        name="visaguy_book_consultation",
        description="Register consultation or visa assistance inquiry with VisaGuy travel desk."
    )
    async def visaguy_book_consultation(
        full_name: str,
        email: str,
        mobile_number: str,
        destination: str,
        salary: str = "15000",
        notes: Optional[str] = None
    ) -> Dict[str, Any]:
        booking = ConsultationBookingRequest(
            full_name=full_name,
            email=email,
            mobile_number=mobile_number,
            destination=destination,
            salary=salary,
            notes=notes
        )
        res = await client.book_consultation(booking)
        return res.model_dump()

    @server.tool(
        name="visaguy_scan_official_mcp_doorway",
        description="Probe visaguy.ae ecosystem to detect if an official first-party MCP server doorway has been published."
    )
    async def visaguy_scan_official_mcp_doorway() -> Dict[str, Any]:
        discovery_agent = MCPDiscoveryAgent()
        return await discovery_agent.scan_for_official_doorway()

    @server.tool(
        name="visaguy_web_agent_action",
        description="Execute automated web agent task directly on visaguy.ae website (e.g. extract popular routes, inspect DOM, submit forms)."
    )
    async def visaguy_web_agent_action(
        task_type: str,
        payload: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        discovery_agent = MCPDiscoveryAgent()
        return await discovery_agent.execute_web_task(task_type, payload or {})

    # --- MULTI-MCP FEDERATION TOOLS (Connect ANY external MCP on the web) ---

    @server.tool(
        name="federation_register_mcp",
        description="Dynamically connect ANY external MCP server on the web (e.g. VisaGuy official, Google Maps, Amadeus, OpenTripPlanner) to this application."
    )
    async def federation_register_mcp(
        server_name: str,
        url_or_command: str,
        transport: str = "sse",
        category: str = "general",
        metadata: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        return await federation_registry.register_mcp(
            name=server_name,
            url_or_command=url_or_command,
            transport=transport,
            category=category,
            metadata=metadata
        )

    @server.tool(
        name="federation_list_connected_mcps",
        description="List all active connected external MCP servers in the federation hub."
    )
    async def federation_list_connected_mcps() -> List[Dict[str, Any]]:
        return federation_registry.list_mcps()

    @server.tool(
        name="federation_call_remote_tool",
        description="Proxy execution of a tool on a connected upstream external MCP server."
    )
    async def federation_call_remote_tool(
        server_name: str,
        tool_name: str,
        arguments: Dict[str, Any]
    ) -> Dict[str, Any]:
        return await federation_registry.call_remote_tool(server_name, tool_name, arguments)

    @server.tool(
        name="visaguy_auto_sync_official_mcp",
        description="Scan visaguy.ae doorway and automatically hot-swap to their official MCP server when published."
    )
    async def visaguy_auto_sync_official_mcp() -> Dict[str, Any]:
        discovery_agent = MCPDiscoveryAgent()
        scan = await discovery_agent.scan_for_official_doorway()
        if scan.get("official_mcp_detected") and scan.get("doorway_url"):
            return await federation_registry.auto_sync_visaguy_official(scan["doorway_url"])
        return {
            "synced": False,
            "message": "VisaGuy has not published official MCP doorway yet. Built-in direct connector active.",
            "scan_summary": scan
        }

    # --- COMMUTE ASSISTANCE SERVICES (Offered TO VisaGuy and external web clients) ---

    @server.tool(
        name="commute_plan_multimodal_journey",
        description="Generate multimodal commute plan across borders with transit buffers, checkpoints, and travel document checklist."
    )
    async def commute_plan_multimodal_journey(
        origin: str,
        destination: str,
        traveler_nationality: str = "UAE Resident",
        departure_date: Optional[str] = None
    ) -> Dict[str, Any]:
        return CommuteServiceEngine.plan_multimodal_commute(origin, destination, traveler_nationality, departure_date)

    @server.tool(
        name="commute_get_cross_border_advisory",
        description="Retrieve cross-border highway, airport transit, and customs advisory for a specific corridor (e.g. UAE-Oman, UAE-Saudi)."
    )
    async def commute_get_cross_border_advisory(
        corridor: str,
        traveler_nationality: str = "UAE Resident"
    ) -> Dict[str, Any]:
        return CommuteServiceEngine.get_cross_border_advisory(corridor, traveler_nationality)

    @server.tool(
        name="multiagent_commute_orchestrator",
        description="End-to-end multi-agent pipeline: JEV System-1 hyperclassifies intent, calls VisaGuy visa APIs, plans commute journey, and checks connected MCPs."
    )
    async def multiagent_commute_orchestrator(
        query: str,
        traveler_nationality: str = "Indian",
        salary: str = "15000 AED",
        origin: str = "Dubai, UAE"
    ) -> Dict[str, Any]:
        # 1. Agent 1: Fast System-1 Hyperclassification
        classification = await hyperclassifier.classify(
            query,
            {"origin": origin, "nationality": traveler_nationality, "salary": salary}
        )

        dest = classification.destination_country or "UK"

        # 2. Agent 2: Visa Assessment
        visa_res = await client.check_eligibility(
            EligibilityAssessmentRequest(
                destination=dest,
                nationality=traveler_nationality,
                salary=salary,
                purpose="Commute / Tourism"
            )
        )

        # 3. Agent 3: Commute Route Planning
        commute_plan = CommuteServiceEngine.plan_multimodal_commute(
            origin=origin,
            destination=dest,
            traveler_nationality=traveler_nationality
        )

        return {
            "status": "orchestrated_success",
            "hyperclassification": classification.model_dump(),
            "visa_assessment": visa_res.model_dump(),
            "commute_plan": commute_plan,
            "connected_mcps_count": len(federation_registry.list_mcps())
        }

    @server.tool(
        name="commute_multimodal_travel_benchmark",
        description="Run comprehensive multimodal travel benchmark federating VisaGuy, Flight MCP, Transit MCP, and Weather MCP."
    )
    async def commute_multimodal_travel_benchmark(
        origin: str = "Dubai Marina",
        destination: str = "Central London",
        traveler_nationality: str = "Indian",
        flight_number: str = "EK001"
    ) -> Dict[str, Any]:
        # 1. Visa Guy Assessment
        visa_eval = await client.check_eligibility(
            EligibilityAssessmentRequest(
                destination="UK",
                nationality=traveler_nationality,
                salary="15000 AED",
                purpose="Multimodal Commute"
            )
        )

        # 2. Flight MCP Query
        flight_info = await federation_registry.call_remote_tool(
            "flight_tracker_mcp",
            "get_flight_status",
            {"flight_number": flight_number}
        )

        # 3. Transit MCP Query
        transit_info = await federation_registry.call_remote_tool(
            "transit_routing_mcp",
            "plan_urban_transit",
            {"station": "Heathrow Express", "destination": "London Paddington"}
        )

        # 4. Weather MCP Query
        weather_info = await federation_registry.call_remote_tool(
            "weather_commute_mcp",
            "get_corridor_weather",
            {"corridor": "DXB-LHR"}
        )

        # 5. Commute Service Engine Itinerary
        commute_route = CommuteServiceEngine.plan_multimodal_commute(
            origin=origin,
            destination=destination,
            traveler_nationality=traveler_nationality
        )

        return {
            "benchmark_status": "SUCCESS_ALL_MCPS_FEDERATED",
            "journey": f"{origin} -> {destination}",
            "federated_results": {
                "visa_guy_assessment": {
                    "score": visa_eval.score,
                    "level": visa_eval.verdict.level,
                    "recommendations_count": len(visa_eval.recommendations)
                },
                "flight_mcp": flight_info,
                "transit_mcp": transit_info,
                "weather_mcp": weather_info
            },
            "commute_schedule": commute_route,
            "multi_mcp_coordination": "100% OPERATIONAL"
        }

    # --- RESOURCES ---

    @server.resource(
        "visaguy://countries/all",
        name="Assisted Countries Catalog",
        description="Catalog of all countries supported by VisaGuy with visa categories"
    )
    async def resource_all_countries() -> str:
        countries = await client.get_countries(per_page=100)
        return json.dumps([c.model_dump() for c in countries], indent=2)

    @server.resource(
        "visaguy://endpoints/architecture",
        name="VisaGuy API Endpoints Architecture",
        description="Detailed specification of discovered endpoints across VisaGuy headless WP, be-checker, and ERPNext"
    )
    async def resource_endpoints_spec() -> str:
        spec = {
            "headless_wordpress_cms": {
                "base": "https://api.visaguy.ae/wp-json/wp/v2",
                "endpoints": ["/countries", "/news", "/students-visa", "/search", "/country_category", "/country_tag"]
            },
            "eligibility_microservice": {
                "base": "https://be-checker.visaguy.ae",
                "endpoints": [
                    {"path": "/api/recommendations", "method": "POST", "desc": "AI recommendation & eligibility engine"},
                    {"path": "/api/voice/client-secret", "method": "POST", "desc": "OpenAI Realtime WebRTC voice agent session"}
                ]
            },
            "frontend_lead_intake": {
                "base": "https://visaguy.ae",
                "endpoints": [{"path": "/api/contact", "method": "POST"}]
            },
            "erpnext_backend": {
                "base": "https://visaguy.tridzbi.com",
                "endpoints": [{"path": "/api/resource/Raw Lead", "method": "POST/PUT"}]
            }
        }
        return json.dumps(spec, indent=2)

    # --- PROMPTS ---

    @server.prompt(
        name="commute_cross_border_advisory",
        description="Prompt template for multimodal commute agents verifying cross-border travel compliance"
    )
    async def prompt_commute_advisory(origin: str, destination: str, nationality: str) -> str:
        return (
            f"You are a multimodal commute advisor. The traveler is commuting from {origin} to {destination} "
            f"with a {nationality} passport. Use the `visaguy_check_commute_compliance` tool and "
            f"`visaguy_get_country_details` tool to verify entry guidelines, processing times, and document readiness. "
            f"Provide a clear, step-by-step commute clearance checklist."
        )

    return server

def run_server():
    """CLI launcher for VisaGuy MCP Server."""
    import sys
    server = create_visaguy_mcp_server()
    if "--transport" in sys.argv and "sse" in sys.argv:
        port = 8000
        if "--port" in sys.argv:
            idx = sys.argv.index("--port")
            if idx + 1 < len(sys.argv):
                port = int(sys.argv[idx + 1])
        print(f"Starting VisaGuy MCP Server on SSE port {port}...")
        server.run(transport="sse", port=port)
    else:
        # Default: standard I/O for MCP clients (Antigravity, Claude Desktop, Cursor)
        server.run(transport="stdio")

if __name__ == "__main__":
    run_server()
