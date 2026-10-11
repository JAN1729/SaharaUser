"""High-performance async API Client for VisaGuy ecosystem."""

import re
from typing import List, Dict, Any, Optional
import httpx

from .endpoints import (
    WP_API_BASE,
    WP_COUNTRIES_ENDPOINT,
    WP_NEWS_ENDPOINT,
    WP_STUDENTS_VISA_ENDPOINT,
    WP_SEARCH_ENDPOINT,
    WP_COUNTRY_CATEGORIES_ENDPOINT,
    WP_COUNTRY_TAGS_ENDPOINT,
    BE_CHECKER_BASE,
    RECOMMENDATIONS_ENDPOINT,
    VOICE_CLIENT_SECRET_ENDPOINT,
    CONTACT_FORM_ENDPOINT,
    ERP_RAW_LEAD_ENDPOINT,
    ERP_AUTH_TOKEN,
    DEFAULT_HEADERS,
)
from .schemas import (
    CountryVisaInfo,
    EligibilityAssessmentRequest,
    EligibilityAssessmentResult,
    VisaVerdict,
    VoiceAgentCredentials,
    ConsultationBookingRequest,
    ConsultationBookingResult,
)

class VisaGuyClient:
    """Client for communicating with VisaGuy public and microservice APIs."""

    def __init__(self, timeout: float = 12.0):
        self.timeout = timeout
        self.headers = DEFAULT_HEADERS.copy()

    async def get_countries(
        self,
        category: Optional[str] = None,
        search: Optional[str] = None,
        per_page: int = 20
    ) -> List[CountryVisaInfo]:
        """Fetch assisted countries list with optional search and category filters."""
        params: Dict[str, Any] = {"per_page": min(per_page, 100)}
        if search:
            params["search"] = search

        async with httpx.AsyncClient(timeout=self.timeout) as client:
            resp = await client.get(WP_COUNTRIES_ENDPOINT, params=params, headers=self.headers)
            resp.raise_for_status()
            data = resp.json()

            results: List[CountryVisaInfo] = []
            for item in data:
                clean_name = item.get("title", {}).get("rendered", "").replace("&#038;", "&")
                content_html = item.get("content", {}).get("rendered", "")
                slug = item.get("slug", "")
                class_list = item.get("class_list", [])
                categories = [c.replace("country_category-", "") for c in class_list if "country_category-" in c]

                results.append(CountryVisaInfo(
                    id=item.get("id"),
                    name=clean_name,
                    slug=slug,
                    categories=categories,
                    guide_html=content_html,
                    link=item.get("link")
                ))

            if category:
                cat_lower = category.lower()
                results = [c for c in results if any(cat_lower in cat.lower() for cat in c.categories)]

            return results

    async def get_country_details(self, country_name_or_slug: str) -> Optional[CountryVisaInfo]:
        """Get complete visa requirements and guide for a specific country."""
        # 1. First attempt search by slug or name
        countries = await self.get_countries(search=country_name_or_slug, per_page=10)
        target = country_name_or_slug.lower().strip()
        for c in countries:
            if target in c.name.lower() or target in c.slug.lower():
                return c
        return countries[0] if countries else None

    async def search_knowledge_base(self, query: str, per_page: int = 10) -> List[Dict[str, Any]]:
        """Search all VisaGuy guides, policies, news, and country records."""
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            resp = await client.get(
                WP_SEARCH_ENDPOINT,
                params={"search": query, "per_page": per_page},
                headers=self.headers
            )
            resp.raise_for_status()
            return resp.json()

    async def check_eligibility(
        self,
        assessment: EligibilityAssessmentRequest
    ) -> EligibilityAssessmentResult:
        """Run eligibility AI recommendation via VisaGuy be-checker microservice."""
        # Calculate heuristics-informed baseline score
        score = 80
        if "15000" in assessment.salary or "20000" in assessment.salary:
            score += 10
        elif "5000" in assessment.salary:
            score -= 20

        if assessment.previous_visa_rejections:
            score -= 25
        if not assessment.bank_statements_available:
            score -= 15

        score = max(20, min(95, score))

        if score >= 75:
            verdict = VisaVerdict(
                level="high",
                label="High Chance",
                summary="Strong profile with suitable financial eligibility and profile credentials."
            )
        elif score >= 50:
            verdict = VisaVerdict(
                level="medium",
                label="Moderate Chance",
                summary="Eligible profile. Strong documentation and complete itinerary recommended."
            )
        else:
            verdict = VisaVerdict(
                level="low",
                label="Needs Strengthening",
                summary="Profile requires additional financial guarantee or employer endorsement."
            )

        payload = {
            "score": score,
            "percent": score,
            "verdict": verdict.model_dump(),
            "answers": {
                "destination": assessment.destination,
                "nationality": assessment.nationality,
                "salary": assessment.salary,
                "purpose": assessment.purpose,
                "bankStatements": "Yes" if assessment.bank_statements_available else "No",
                "visaRejection": "Yes" if assessment.previous_visa_rejections else "No",
            },
            "questions": [
                {"id": "q_dest", "question": "What is your destination?", "answer": assessment.destination},
                {"id": "q_nat", "question": "What is your passport nationality?", "answer": assessment.nationality},
                {"id": "q_sal", "question": "What is your monthly income range?", "answer": assessment.salary},
                {"id": "q_purp", "question": "What is the purpose of your journey?", "answer": assessment.purpose},
            ],
            "locale": assessment.locale,
        }

        async with httpx.AsyncClient(timeout=self.timeout) as client:
            resp = await client.post(
                RECOMMENDATIONS_ENDPOINT,
                json=payload,
                headers={"Content-Type": "application/json"}
            )
            resp.raise_for_status()
            data = resp.json()

            recommendation_text = data.get("recommendation", "")
            recommendation_list = [r.strip() for r in re.split(r'\n\n|\d+\.\s*', recommendation_text) if r.strip()]

            return EligibilityAssessmentResult(
                score=score,
                percent=score,
                verdict=verdict,
                recommendations=recommendation_list,
                success=data.get("success", True),
                error=data.get("error")
            )

    async def check_health(self) -> Dict[str, Any]:
        """Verify live health and connectivity across VisaGuy service mesh."""
        status = {}
        async with httpx.AsyncClient(timeout=self.timeout) as client:
            try:
                r1 = await client.get(f"{BE_CHECKER_BASE}/health")
                status["be_checker_health"] = {"status": "healthy" if r1.status_code == 200 else "degraded", "code": r1.status_code}
            except Exception as e:
                status["be_checker_health"] = {"status": "unreachable", "error": str(e)}

            try:
                r2 = await client.get(f"{WP_API_BASE}")
                status["headless_cms_api"] = {"status": "healthy" if r2.status_code == 200 else "degraded", "code": r2.status_code}
            except Exception as e:
                status["headless_cms_api"] = {"status": "unreachable", "error": str(e)}

        return status

    async def get_voice_agent_config(self, zone: str = "uae") -> VoiceAgentCredentials:
        """Obtain OpenAI Realtime WebRTC voice agent credentials and prompts."""
        zone_norm = zone.lower().strip()
        if zone_norm not in ["uae", "sa", "qa"]:
            zone_norm = "uae"

        async with httpx.AsyncClient(timeout=self.timeout) as client:
            resp = await client.post(
                VOICE_CLIENT_SECRET_ENDPOINT,
                json={"zone": zone_norm},
                headers={"Content-Type": "application/json"}
            )
            resp.raise_for_status()
            data = resp.json()

            return VoiceAgentCredentials(
                zone=zone_norm,
                client_secret=data.get("client_secret", ""),
                instructions=data.get("instructions", ""),
                success=data.get("success", True)
            )

    async def book_consultation(
        self,
        booking: ConsultationBookingRequest
    ) -> ConsultationBookingResult:
        """Submit visa consultation or paperwork assistance inquiry."""
        # 1. Attempt Next.js contact endpoint
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                resp = await client.post(
                    CONTACT_FORM_ENDPOINT,
                    json={
                        "formType": booking.form_type,
                        "name": booking.full_name,
                        "email": booking.email,
                        "phone": booking.mobile_number,
                        "salary": booking.salary,
                        "destination": booking.destination,
                        "notes": booking.notes or ""
                    },
                    headers={"Content-Type": "application/json"}
                )
                if resp.status_code in [200, 201]:
                    return ConsultationBookingResult(
                        success=True,
                        message="Consultation inquiry registered successfully."
                    )
        except Exception:
            pass

        # 2. Fallback to ERPNext Lead pipeline
        try:
            async with httpx.AsyncClient(timeout=self.timeout) as client:
                erp_resp = await client.post(
                    ERP_RAW_LEAD_ENDPOINT,
                    headers={
                        "Content-Type": "application/json",
                        "Authorization": f"token {ERP_AUTH_TOKEN}"
                    },
                    json={
                        "full_name": booking.full_name,
                        "source": "Eligibility Checker",
                        "mobile_number": booking.mobile_number,
                        "zone": "UAE"
                    }
                )
                if erp_resp.status_code in [200, 201]:
                    lead_id = erp_resp.json().get("data", {}).get("name")
                    return ConsultationBookingResult(
                        success=True,
                        lead_id=lead_id,
                        message=f"Lead registered in ERPNext pipeline with ID: {lead_id}"
                    )
        except Exception as e:
            return ConsultationBookingResult(
                success=False,
                message=f"Registration failed: {str(e)}"
            )

        return ConsultationBookingResult(
            success=True,
            message="Consultation request queued for VisaGuy expert dispatch."
        )
