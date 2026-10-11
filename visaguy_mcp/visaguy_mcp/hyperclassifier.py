"""TypeSafe JEV System-1 Hyperclassification Module.

Inspired by TypeSafe AI's System One architecture (fast, schema-constrained,
deterministic decision-making vs. heavy generative System-2 LLMs).
Optimizes multimodal agentic commute pipelines by routing user requests,
itineraries, or multimodal OCR text directly into structured MCP tool calls.
"""

import os
import re
from typing import Dict, Any, Optional
import httpx

from .schemas import HyperclassificationResult
from .endpoints import TYPESAFE_API_DEFAULT_URL

class JevHyperclassifier:
    """TypeSafe Jev System-1 Hyperclassifier for multimodal travel and commute."""

    def __init__(self, api_key: Optional[str] = None, api_url: Optional[str] = None):
        self.api_key = api_key or os.getenv("TYPESAFE_API_KEY") or os.getenv("JEV_API_KEY")
        self.api_url = api_url or os.getenv("TYPESAFE_API_URL", TYPESAFE_API_DEFAULT_URL)

    async def classify(
        self,
        query: str,
        multimodal_metadata: Optional[Dict[str, Any]] = None
    ) -> HyperclassificationResult:
        """Run System-1 hyperclassification on the commute or travel query."""
        multimodal_metadata = multimodal_metadata or {}
        
        # 1. If remote TypeSafe JEV API configured with key, attempt System-1 API call
        if self.api_key:
            try:
                async with httpx.AsyncClient(timeout=3.0) as client:
                    resp = await client.post(
                        self.api_url,
                        headers={
                            "Authorization": f"Bearer {self.api_key}",
                            "Content-Type": "application/json"
                        },
                        json={
                            "state": {
                                "query": query,
                                "metadata": multimodal_metadata
                            },
                            "schema": {
                                "type": "object",
                                "properties": {
                                    "intent": {
                                        "type": "string",
                                        "enum": [
                                            "VISA_REQUIREMENT_CHECK",
                                            "ELIGIBILITY_EVALUATION",
                                            "COMMUTE_TRANSIT_COMPLIANCE",
                                            "VOICE_CONSULTATION_SESSION",
                                            "SEARCH_POLICY_RULES",
                                            "BOOK_CONSULTATION",
                                            "GENERAL_INQUIRY"
                                        ]
                                    },
                                    "destination_country": {"type": "string"},
                                    "confidence": {"type": "number"}
                                },
                                "required": ["intent", "confidence"]
                            }
                        }
                    )
                    if resp.status_code == 200:
                        data = resp.json()
                        return self._map_to_tool(data, query, multimodal_metadata)
            except Exception:
                # Gracefully fallback to high-speed local System-1 classifier
                pass

        # 2. Local High-Speed System-1 Deterministic Hyperclassification Engine (<1ms)
        return self._local_system_one_classify(query, multimodal_metadata)

    def _local_system_one_classify(
        self,
        query: str,
        meta: Dict[str, Any]
    ) -> HyperclassificationResult:
        """Deterministic, ultra-fast System-1 decision tree and schema binder."""
        q_lower = query.lower()
        combined_text = f"{q_lower} {str(meta).lower()}"

        # Detect countries
        country_aliases = {
            "uk": "United Kingdom",
            "united kingdom": "United Kingdom",
            "britain": "United Kingdom",
            "london": "United Kingdom",
            "us": "USA",
            "usa": "USA",
            "united states": "USA",
            "america": "USA",
            "schengen": "Schengen",
            "france": "France",
            "paris": "France",
            "italy": "Italy",
            "rome": "Italy",
            "spain": "Spain",
            "madrid": "Spain",
            "germany": "Germany",
            "berlin": "Germany",
            "japan": "Japan",
            "tokyo": "Japan",
            "china": "China",
            "beijing": "China",
            "thailand": "Thailand",
            "bangkok": "Thailand",
            "vietnam": "Vietnam",
            "singapore": "Singapore",
            "saudi": "Saudi Arabia",
            "ksa": "Saudi Arabia",
            "riyadh": "Saudi Arabia",
            "oman": "Oman",
            "muscat": "Oman",
            "qatar": "Qatar",
            "doha": "Qatar",
            "canada": "Canada",
            "brazil": "Brazil",
            "turkey": "Turkey",
            "georgia": "Georgia",
            "azerbaijan": "Azerbaijan",
            "india": "India",
            "australia": "Australia",
        }

        detected_country = meta.get("destination")
        if not detected_country:
            for alias, country_name in country_aliases.items():
                if re.search(r'\b' + re.escape(alias) + r'\b', q_lower):
                    detected_country = country_name
                    break

        # Detect commute travel mode
        commute_mode = "unknown"
        if any(w in combined_text for w in ["flight", "airport", "boarding pass", "fly", "airline", "terminal"]):
            commute_mode = "flight"
        elif any(w in combined_text for w in ["drive", "road", "car", "border crossing", "bus", "highway"]):
            commute_mode = "road_transit"
        elif any(w in combined_text for w in ["train", "rail", "metro", "station"]):
            commute_mode = "rail"
        elif any(re.search(r'\b' + re.escape(w) + r'\b', combined_text) for w in ["ferry", "cruise", "boat", "port", "ship"]):
            commute_mode = "marine"

        # Detect nationality
        detected_nationality = meta.get("nationality")
        if not detected_nationality:
            nat_match = re.search(r'\b(indian|pakistani|filipino|egyptian|british|american|canadian|jordanian|lebanese|syrian|nigerian)\b', combined_text)
            if nat_match:
                detected_nationality = nat_match.group(1).capitalize()

        # Decision routing logic
        is_multimodal = bool(meta.get("has_image") or meta.get("ocr_text") or meta.get("audio_transcription"))

        # Case A: Voice Agent / Audio Consultation Request
        if any(w in combined_text for w in ["voice agent", "call expert", "speak with agent", "audio assessment", "talk to human", "webrtc"]):
            zone = meta.get("zone", "uae")
            if "saudi" in combined_text or "ksa" in combined_text:
                zone = "sa"
            elif "qatar" in combined_text:
                zone = "qa"
            return HyperclassificationResult(
                intent="VOICE_CONSULTATION_SESSION",
                destination_country=detected_country,
                commute_mode=commute_mode,
                traveler_nationality=detected_nationality,
                confidence_score=0.98,
                suggested_mcp_tool="visaguy_get_voice_agent_config",
                tool_arguments={"zone": zone},
                routing_reasoning="System-1 classified user request as live voice agent session initialization.",
                is_multimodal_context=is_multimodal
            )

        # Case B: End-to-end Commute / Transit Compliance
        if (commute_mode in ["flight", "road_transit"] and detected_country) or any(w in combined_text for w in ["commute", "layover", "transit visa", "cross border", "border"]):
            return HyperclassificationResult(
                intent="COMMUTE_TRANSIT_COMPLIANCE",
                destination_country=detected_country or "General Transit",
                commute_mode=commute_mode,
                traveler_nationality=detected_nationality or "UAE Resident",
                confidence_score=0.95,
                suggested_mcp_tool="visaguy_check_commute_compliance",
                tool_arguments={
                    "commute_origin": meta.get("origin", "Dubai, UAE"),
                    "commute_destination": detected_country or "UK",
                    "nationality": detected_nationality or "Indian",
                    "salary": meta.get("salary", "15000 AED"),
                    "commute_mode": commute_mode
                },
                routing_reasoning="System-1 classified as cross-border commute / transit compliance check.",
                is_multimodal_context=is_multimodal
            )

        # Case C: Eligibility Evaluation (checking chances, score, likelihood)
        if any(w in combined_text for w in ["eligible", "eligibility", "chance", "probability", "qualify", "am i eligible", "visa checker"]):
            return HyperclassificationResult(
                intent="ELIGIBILITY_EVALUATION",
                destination_country=detected_country or "UK",
                commute_mode=commute_mode,
                traveler_nationality=detected_nationality or "UAE Resident",
                confidence_score=0.94,
                suggested_mcp_tool="visaguy_check_eligibility",
                tool_arguments={
                    "destination": detected_country or "UK",
                    "nationality": detected_nationality or "Indian",
                    "salary": meta.get("salary", "15000 AED"),
                    "purpose": meta.get("purpose", "Tourism")
                },
                routing_reasoning="System-1 classified as visa eligibility assessment.",
                is_multimodal_context=is_multimodal
            )

        # Case D: Booking / Consultation Lead
        if any(w in combined_text for w in ["book", "consultation", "apply now", "hire", "appointment", "contact agent"]):
            return HyperclassificationResult(
                intent="BOOK_CONSULTATION",
                destination_country=detected_country,
                commute_mode=commute_mode,
                traveler_nationality=detected_nationality,
                confidence_score=0.92,
                suggested_mcp_tool="visaguy_book_consultation",
                tool_arguments={
                    "full_name": meta.get("name", "Commute Traveler"),
                    "email": meta.get("email", "traveler@example.com"),
                    "mobile_number": meta.get("phone", "+971501234567"),
                    "destination": detected_country or "Europe",
                    "salary": meta.get("salary", "15000")
                },
                routing_reasoning="System-1 classified as consultation booking intent.",
                is_multimodal_context=is_multimodal
            )

        # Case E: Country Visa Requirement Lookup
        if detected_country:
            return HyperclassificationResult(
                intent="VISA_REQUIREMENT_CHECK",
                destination_country=detected_country,
                commute_mode=commute_mode,
                traveler_nationality=detected_nationality,
                confidence_score=0.91,
                suggested_mcp_tool="visaguy_get_country_details",
                tool_arguments={"country_name": detected_country},
                routing_reasoning=f"System-1 identified destination '{detected_country}' for direct rule lookup.",
                is_multimodal_context=is_multimodal
            )

        # Case F: Search policy or generic inquiry
        return HyperclassificationResult(
            intent="SEARCH_POLICY_RULES",
            destination_country=None,
            commute_mode=commute_mode,
            traveler_nationality=detected_nationality,
            confidence_score=0.80,
            suggested_mcp_tool="visaguy_search_visa_rules",
            tool_arguments={"query": query},
            routing_reasoning="System-1 fell back to knowledge base search for general visa query.",
            is_multimodal_context=is_multimodal
        )

    def _map_to_tool(self, api_data: Dict[str, Any], query: str, meta: Dict[str, Any]) -> HyperclassificationResult:
        intent = api_data.get("intent", "SEARCH_POLICY_RULES")
        dest = api_data.get("destination_country")
        conf = float(api_data.get("confidence", 0.9))

        tool_map = {
            "VISA_REQUIREMENT_CHECK": ("visaguy_get_country_details", {"country_name": dest or "UK"}),
            "ELIGIBILITY_EVALUATION": ("visaguy_check_eligibility", {"destination": dest or "UK", "nationality": meta.get("nationality", "UAE Resident")}),
            "COMMUTE_TRANSIT_COMPLIANCE": ("visaguy_check_commute_compliance", {"commute_destination": dest or "UK"}),
            "VOICE_CONSULTATION_SESSION": ("visaguy_get_voice_agent_config", {"zone": meta.get("zone", "uae")}),
            "BOOK_CONSULTATION": ("visaguy_book_consultation", {"destination": dest or "UK"}),
            "SEARCH_POLICY_RULES": ("visaguy_search_visa_rules", {"query": query}),
        }
        suggested_tool, args = tool_map.get(intent, ("visaguy_search_visa_rules", {"query": query}))

        return HyperclassificationResult(
            intent=intent,
            destination_country=dest,
            confidence_score=conf,
            suggested_mcp_tool=suggested_tool,
            tool_arguments=args,
            routing_reasoning="TypeSafe Jev System-1 API inference classified state and dispatched tool.",
            is_multimodal_context=bool(meta.get("has_image") or meta.get("ocr_text"))
        )
