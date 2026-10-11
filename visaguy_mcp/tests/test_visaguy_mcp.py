"""Standalone Unit and integration tests for VisaGuy MCP Server directory package."""

import unittest
import sys
import os

# Ensure package is on sys.path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from visaguy_mcp.hyperclassifier import JevHyperclassifier
from visaguy_mcp.client import VisaGuyClient
from visaguy_mcp.server import create_visaguy_mcp_server
from visaguy_mcp.schemas import EligibilityAssessmentRequest

class TestVisaGuyMCP(unittest.IsolatedAsyncioTestCase):

    async def test_jev_hyperclassifier_commute_intent(self):
        clf = JevHyperclassifier()
        res = await clf.classify(
            "I am flying from Dubai to London Heathrow next week. Do I need a visa?",
            {"origin": "Dubai, UAE", "nationality": "Indian", "has_image": True}
        )
        self.assertIn(res.intent, ["COMMUTE_TRANSIT_COMPLIANCE", "VISA_REQUIREMENT_CHECK"])
        self.assertIn(res.suggested_mcp_tool, ["visaguy_check_commute_compliance", "visaguy_get_country_details"])
        self.assertTrue(res.is_multimodal_context)
        self.assertGreaterEqual(res.confidence_score, 0.90)

    async def test_jev_hyperclassifier_voice_intent(self):
        clf = JevHyperclassifier()
        res = await clf.classify("Connect me to the voice agent to discuss my visa application")
        self.assertEqual(res.intent, "VOICE_CONSULTATION_SESSION")
        self.assertEqual(res.suggested_mcp_tool, "visaguy_get_voice_agent_config")
        self.assertIn("zone", res.tool_arguments)

    async def test_jev_hyperclassifier_eligibility_intent(self):
        clf = JevHyperclassifier()
        res = await clf.classify("Am I eligible for a Schengen tourist visa from UAE?")
        self.assertEqual(res.intent, "ELIGIBILITY_EVALUATION")
        self.assertEqual(res.suggested_mcp_tool, "visaguy_check_eligibility")
        self.assertIn("destination", res.tool_arguments)

    async def test_visaguy_client_live_countries(self):
        client = VisaGuyClient()
        countries = await client.get_countries(per_page=3)
        self.assertGreater(len(countries), 0)
        first = countries[0]
        self.assertTrue(bool(first.name))
        self.assertTrue(bool(first.slug))

    async def test_visaguy_client_country_details(self):
        client = VisaGuyClient()
        uk = await client.get_country_details("UK")
        self.assertIsNotNone(uk)
        self.assertIn("uk", uk.slug.lower())

    async def test_visaguy_client_live_eligibility(self):
        client = VisaGuyClient()
        req = EligibilityAssessmentRequest(
            destination="UK",
            nationality="Indian",
            salary="15000 AED",
            purpose="Tourism"
        )
        res = await client.check_eligibility(req)
        self.assertTrue(res.success)
        self.assertGreater(len(res.recommendations), 0)
        self.assertGreaterEqual(res.score, 50)

    async def test_visaguy_client_voice_agent_config(self):
        client = VisaGuyClient()
        res = await client.get_voice_agent_config(zone="uae")
        self.assertTrue(res.success)
        self.assertGreater(len(res.client_secret), 0)
        self.assertGreater(len(res.instructions), 100)

    async def test_visaguy_mcp_server_initialization(self):
        server = create_visaguy_mcp_server()
        self.assertEqual(server.name, "visaguy-mcp-connector")
        tools = await server.list_tools()
        tool_names = [t.name for t in tools]
        expected_tools = [
            "jev_hyperclassify",
            "visaguy_get_countries",
            "visaguy_get_country_details",
            "visaguy_search_visa_rules",
            "visaguy_check_eligibility",
            "visaguy_get_voice_agent_config",
            "visaguy_check_commute_compliance",
            "visaguy_book_consultation",
        ]
        for et in expected_tools:
            self.assertIn(et, tool_names)

        # Test invoking jev_hyperclassify tool
        res = await server.call_tool(
            "jev_hyperclassify",
            {"query": "Traveling from Dubai to Paris France on flight Emirates EK073"}
        )
        self.assertIsNotNone(res)
        self.assertFalse(res.is_error)
        self.assertGreater(len(res.content), 0)

        # Test invoking visaguy_health_check tool
        res_health = await server.call_tool("visaguy_health_check", {})
        self.assertFalse(res_health.is_error)
        self.assertGreater(len(res_health.content), 0)

        # Test invoking visaguy_configure_upstream_mcp tool
        res_upstream = await server.call_tool("visaguy_configure_upstream_mcp", {"upstream_mcp_url": "https://visaguy.ae/mcp"})
        self.assertFalse(res_upstream.is_error)

        # Test invoking visaguy_check_commute_compliance tool
        res_commute = await server.call_tool(
            "visaguy_check_commute_compliance",
            {
                "commute_origin": "Dubai, UAE",
                "commute_destination": "UK",
                "traveler_nationality": "Indian",
                "salary": "15000 AED"
            }
        )
        self.assertFalse(res_commute.is_error)
        self.assertGreater(len(res_commute.content), 0)

    async def test_visaguy_client_health(self):
        client = VisaGuyClient()
        health = await client.check_health()
        self.assertIn("be_checker_health", health)
        self.assertIn("headless_cms_api", health)
        self.assertEqual(health["be_checker_health"]["status"], "healthy")
        self.assertEqual(health["headless_cms_api"]["status"], "healthy")

    async def test_mcp_discovery_and_web_agent(self):
        from visaguy_mcp.discovery import MCPDiscoveryAgent
        discovery = MCPDiscoveryAgent()
        scan = await discovery.scan_for_official_doorway()
        self.assertIn("official_mcp_detected", scan)
        self.assertIn("recommendation", scan)

        web_res = await discovery.execute_web_task("extract_popular_routes")
        self.assertEqual(web_res["status"], "success")
        self.assertGreater(len(web_res["popular_destinations"]), 0)

    async def test_federation_registry(self):
        from visaguy_mcp.federation import federation_registry
        # Test registering an external web MCP server
        reg = await federation_registry.register_mcp(
            name="google_maps_transit",
            url_or_command="https://maps-mcp.example.com/sse",
            transport="sse",
            metadata={"category": "transit"}
        )
        self.assertTrue(reg["success"])

        # Test listing registered MCPs
        servers = federation_registry.list_mcps()
        names = [s["name"] for s in servers]
        self.assertIn("google_maps_transit", names)

    async def test_commute_services(self):
        from visaguy_mcp.commute_services import CommuteServiceEngine
        plan = CommuteServiceEngine.plan_multimodal_commute(
            origin="Dubai, UAE",
            destination="Oman",
            traveler_nationality="Indian"
        )
        self.assertIn("corridor", plan)
        self.assertGreater(len(plan["legs"]), 0)
        self.assertGreater(len(plan["document_checklist"]), 0)

        adv = CommuteServiceEngine.get_cross_border_advisory("UAE to Oman road border", "Indian")
        self.assertIn("operating_hours", adv)

    async def test_server_multiagent_orchestrator(self):
        server = create_visaguy_mcp_server()
        res = await server.call_tool(
            "multiagent_commute_orchestrator",
            {
                "query": "Road trip commute from Dubai to Muscat Oman next weekend",
                "traveler_nationality": "Indian",
                "salary": "15000 AED"
            }
        )
        self.assertFalse(res.is_error)
        self.assertGreater(len(res.content), 0)

    async def test_gateway_endpoints(self):
        from fastapi.testclient import TestClient
        from visaguy_mcp.gateway import app
        client = TestClient(app)

        # Health endpoint
        r_health = client.get("/health")
        self.assertEqual(r_health.status_code, 200)
        self.assertEqual(r_health.json()["status"], "healthy")

        # Federation servers endpoint
        r_servers = client.get("/federation/servers")
        self.assertEqual(r_servers.status_code, 200)
        self.assertIn("connected_servers", r_servers.json())

    async def test_sahara_adapter_integration(self):
        from visaguy_mcp.sahara_adapter import SaharaAgenticRoles, sahara_mcp_engine

        # Verify 5 Sahara specific agent roles
        self.assertIn("intent_router", SaharaAgenticRoles.AGENT_ROLES)
        self.assertIn("multimodal_planner", SaharaAgenticRoles.AGENT_ROLES)
        self.assertIn("consular_compliance", SaharaAgenticRoles.AGENT_ROLES)
        self.assertIn("travel_mesh_federator", SaharaAgenticRoles.AGENT_ROLES)
        self.assertIn("doorway_watcher", SaharaAgenticRoles.AGENT_ROLES)

        # Process a Sahara chat query
        res = await sahara_mcp_engine.process_sahara_chat_query(
            query="Commute from Dubai Marina to London Heathrow for business travel",
            user_id="user_test_123",
            traveler_nationality="Indian"
        )
        self.assertTrue(res["sahara_chat_compatible"])
        self.assertIn("visa_clearance", res)
        self.assertIn("multimodal_commute_plan", res)
        self.assertIn("external_travel_mesh", res)
        self.assertGreater(len(res["sahara_guidance_warnings"]), 0)

        # Test journey enrichment
        mock_journey = {"journey": {"id": "j_1", "legs": [], "warnings": ["Existing delay warning"]}}
        enriched = sahara_mcp_engine.enrich_sahara_journey_record(
            mock_journey,
            {"score": 90, "recommendations": ["Check visa status"]}
        )
        self.assertIn("[VisaGuy Clearance] Visa Eligibility Score: 90/100", enriched["journey"]["warnings"][1])

if __name__ == "__main__":
    unittest.main()
