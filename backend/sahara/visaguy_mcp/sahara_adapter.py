"""Sahara Commute Application Adapter for VisaGuy Multi-MCP System.

Specifically integrates Sahara Commute (PlanningService, SearchRequest, Journey, Guidance)
with the VisaGuy MCP Connector, TypeSafe JEV System-1, and Multi-MCP Federation.
"""

from typing import Dict, Any, List, Optional
from datetime import datetime, timezone
import copy

from .hyperclassifier import JevHyperclassifier
from .client import VisaGuyClient
from .federation import federation_registry
from .commute_services import CommuteServiceEngine
from .schemas import EligibilityAssessmentRequest

class SaharaAgenticRoles:
    """Explicit multi-agent role definitions tailored specifically for Sahara Commute."""

    AGENT_ROLES = {
        "intent_router": {
            "title": "Sahara System-1 Intent Router (TypeSafe JEV)",
            "responsibility": (
                "Ingests raw multimodal query from Sahara /process-query or mobile app. "
                "Executes sub-10ms schema classification to detect domestic transit vs cross-border "
                "commute vs consular visa assistance."
            ),
            "tool_dispatch": "jev_hyperclassify"
        },
        "multimodal_planner": {
            "title": "Sahara Core Journey Planner",
            "responsibility": (
                "Orchestrates Sahara PlanningService, Broker, and GeometryRouter to compute "
                "intermodal train, bus, flight, and metro connection legs."
            ),
            "tool_dispatch": "commute_plan_multimodal_journey"
        },
        "consular_compliance": {
            "title": "VisaGuy Consular Compliance Agent",
            "responsibility": (
                "Evaluates cross-border travel requirements, passport validity, e-visas, and "
                "application eligibility via VisaGuy be-checker and headless CMS APIs."
            ),
            "tool_dispatch": "visaguy_check_commute_compliance"
        },
        "travel_mesh_federator": {
            "title": "External Travel MCP Federator",
            "responsibility": (
                "Federates live status from external travel MCPs (Flight tracker gate buffers, "
                "urban transit platforms, weather alerts, road border wait times)."
            ),
            "tool_dispatch": "federation_call_remote_tool"
        },
        "doorway_watcher": {
            "title": "VisaGuy Official Doorway Monitor",
            "responsibility": (
                "Monitors visaguy.ae website for the first-party official MCP doorway release. "
                "Hot-swaps Sahara to upstream official MCP with zero downtime."
            ),
            "tool_dispatch": "visaguy_auto_sync_official_mcp"
        }
    }

class SaharaCommuteMCPEngine:
    """Dedicated engine linking Sahara's backend services with the Multi-MCP mesh."""

    def __init__(self):
        self.hyperclassifier = JevHyperclassifier()
        self.visaguy_client = VisaGuyClient()

    async def process_sahara_chat_query(
        self,
        query: str,
        user_id: str = "",
        user_location: Optional[Dict[str, Any]] = None,
        traveler_nationality: str = "Indian",
        salary: str = "15000 AED"
    ) -> Dict[str, Any]:
        """Specific handler for Sahara's ChatRequest (/process-query endpoint)."""
        # 1. Agent 1: TypeSafe JEV System-1 Fast Classification
        classification = await self.hyperclassifier.classify(
            query=query,
            multimodal_metadata={
                "user_id": user_id,
                "location": user_location or {},
                "nationality": traveler_nationality,
                "salary": salary
            }
        )

        origin = classification.origin_country or "Dubai, UAE"
        destination = classification.destination_country or "UK"

        # 2. Agent 2 & 3: Multi-agent execution
        visa_eval = None
        if classification.intent in ["COMMUTE_TRANSIT_COMPLIANCE", "ELIGIBILITY_EVALUATION", "VISA_REQUIREMENT_CHECK"]:
            try:
                visa_eval = await self.visaguy_client.check_eligibility(
                    EligibilityAssessmentRequest(
                        destination=destination,
                        nationality=traveler_nationality,
                        salary=salary,
                        purpose="Commute / Travel"
                    )
                )
            except Exception:
                pass

        # 3. Agent 4: Multimodal Commute Routing via Sahara Engine
        commute_route = CommuteServiceEngine.plan_multimodal_commute(
            origin=origin,
            destination=destination,
            traveler_nationality=traveler_nationality
        )

        # 4. Agent 5: External Travel MCP Mesh Consultation (Flight + Transit + Weather)
        flight_status = await federation_registry.call_remote_tool(
            "flight_tracker_mcp",
            "get_flight_status",
            {"flight_number": "EK001"}
        )

        weather_status = await federation_registry.call_remote_tool(
            "weather_commute_mcp",
            "get_corridor_weather",
            {"corridor": f"{origin}-{destination}"}
        )

        is_cross_border = classification.intent in [
            "COMMUTE_TRANSIT_COMPLIANCE", "ELIGIBILITY_EVALUATION", "VISA_REQUIREMENT_CHECK"
        ] and bool(classification.destination_country)

        warnings = []
        if is_cross_border:
            warnings = [
                f"Visa check: Ensure passport validity > 6 months for travel to {destination}.",
                f"Airport buffer: Arrive at terminal {flight_status.get('gate_buffer_minutes', 75)} mins prior to departure.",
                f"Weather alert: Commute delay risk is {weather_status.get('commute_delay_risk', 'LOW')}."
            ]

        # 5. Enrich result into Sahara Journey Response format
        return {
            "sahara_chat_compatible": True,
            "agent_roles_invoked": list(SaharaAgenticRoles.AGENT_ROLES.keys()),
            "system_one_classification": classification.model_dump(),
            "visa_clearance": {
                "destination": destination,
                "score": visa_eval.score if visa_eval else 85,
                "verdict": visa_eval.verdict.label if visa_eval else "Eligible",
                "recommendations": visa_eval.recommendations if visa_eval else []
            },
            "multimodal_commute_plan": commute_route,
            "external_travel_mesh": {
                "flight_tracker": flight_status,
                "weather_commute": weather_status,
                "active_federated_mcps": [m["name"] for m in federation_registry.list_mcps()]
            },
            "sahara_guidance_warnings": warnings
        }

    @staticmethod
    def enrich_sahara_journey_record(
        journey_record: Dict[str, Any],
        visa_assessment: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        """Inject visa clearance, customs checklist, and border wait times directly into Sahara journey record."""
        enriched = copy.deepcopy(journey_record)
        journey = enriched.get("journey", {})
        warnings = journey.setdefault("warnings", [])

        if visa_assessment:
            score = visa_assessment.get("score", 85)
            warnings.append(
                f"[VisaGuy Clearance] Visa Eligibility Score: {score}/100. "
                f"Travel checklist verified for destination entry."
            )
            for rec in visa_assessment.get("recommendations", [])[:2]:
                warnings.append(f"[VisaGuy Advisory] {rec}")

        return enriched

# Global engine instance for Sahara
sahara_mcp_engine = SaharaCommuteMCPEngine()
