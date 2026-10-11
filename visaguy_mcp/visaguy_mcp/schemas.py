"""Data models and schemas for VisaGuy MCP and TypeSafe JEV System-1."""

from typing import List, Optional, Dict, Any, Literal
from pydantic import BaseModel, Field

class CountryVisaInfo(BaseModel):
    """Country visa details from VisaGuy."""
    id: int
    name: str
    slug: str
    categories: List[str] = Field(default_factory=list, description="Categories like Europe, Schengen, E-Visa, Sticker-Visa")
    guide_html: str = Field("", description="Guideline HTML content")
    featured_media_url: Optional[str] = None
    link: Optional[str] = None

class VisaVerdict(BaseModel):
    level: Literal["high", "medium", "low"] = "high"
    label: str = "High Chance"
    summary: str = ""

class EligibilityAssessmentRequest(BaseModel):
    destination: str = Field(..., description="Destination country, e.g., UK, France, Japan, USA")
    nationality: str = Field(..., description="Passport nationality of traveler")
    salary: str = Field("15000 AED", description="Monthly salary or income range, e.g. 15000 AED")
    purpose: str = Field("Tourism", description="Travel purpose: Tourism, Business, Transit, Study")
    bank_statements_available: bool = Field(True, description="Whether 3-6 months bank statements exist")
    previous_visa_rejections: bool = Field(False, description="Whether traveler had any previous visa rejection")
    locale: Literal["en", "ar"] = "en"

class EligibilityAssessmentResult(BaseModel):
    score: int
    percent: int
    verdict: VisaVerdict
    recommendations: List[str]
    success: bool = True
    error: Optional[str] = None

class VoiceAgentCredentials(BaseModel):
    zone: str
    client_secret: str
    instructions: str
    success: bool = True

class ConsultationBookingRequest(BaseModel):
    full_name: str
    email: str
    mobile_number: str
    destination: str
    salary: str = "15000"
    notes: Optional[str] = None
    form_type: str = "Consultation Modal"

class ConsultationBookingResult(BaseModel):
    success: bool
    lead_id: Optional[str] = None
    message: str

class HyperclassificationResult(BaseModel):
    """Schema-constrained result from TypeSafe Jev System 1 Hyperclassifier."""
    intent: Literal[
        "VISA_REQUIREMENT_CHECK",
        "ELIGIBILITY_EVALUATION",
        "COMMUTE_TRANSIT_COMPLIANCE",
        "VOICE_CONSULTATION_SESSION",
        "SEARCH_POLICY_RULES",
        "BOOK_CONSULTATION",
        "GENERAL_INQUIRY"
    ]
    destination_country: Optional[str] = None
    origin_country: Optional[str] = "UAE"
    traveler_nationality: Optional[str] = None
    commute_mode: Literal["flight", "road_transit", "rail", "marine", "unknown"] = "unknown"
    confidence_score: float = Field(ge=0.0, le=1.0)
    suggested_mcp_tool: str
    tool_arguments: Dict[str, Any]
    routing_reasoning: str
    is_multimodal_context: bool = False

class CommuteComplianceReport(BaseModel):
    commute_origin: str
    commute_destination: str
    traveler_nationality: str
    transit_countries: List[str] = Field(default_factory=list)
    visa_required: bool
    visa_type_recommended: str  # e.g., "e-visa", "schengen-sticker", "visa-on-arrival"
    eligibility_score: int
    recommendations: List[str]
    action_items: List[str]
