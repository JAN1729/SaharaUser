"""Commute Assistance Services offered to VisaGuy and External Web MCPs.

Provides bidirectional assistance: When VisaGuy or external travel platforms
connect to this MCP endpoint, our application provides multimodal commute
planning, cross-border transit logistics, and airport transfer guidance.
"""

from typing import Dict, Any, List, Optional
from datetime import datetime
from pydantic import BaseModel, Field

class CommuteLeg(BaseModel):
    mode: str  # "flight", "road_transit", "metro", "airport_shuttle"
    origin: str
    destination: str
    duration_minutes: int
    transit_buffer_minutes: int
    border_control: bool = False
    notes: str = ""

class CommuteItinerary(BaseModel):
    corridor: str
    total_duration_minutes: int
    legs: List[CommuteLeg]
    recommended_departure: str
    border_checkpoints: List[str]
    document_checklist: List[str]

class CommuteServiceEngine:
    """Multimodal commute engine for cross-border travelers and visa applicants."""

    @staticmethod
    def plan_multimodal_commute(
        origin: str,
        destination: str,
        traveler_nationality: str = "UAE Resident",
        departure_date: Optional[str] = None
    ) -> Dict[str, Any]:
        """Generate structured multimodal commute plan with border transit buffers."""
        dep_time = departure_date or datetime.now().strftime("%Y-%m-%d 08:00")
        orig_clean = origin.strip().title()
        dest_clean = destination.strip().title()

        # Detect cross-border road corridor vs international flight
        is_road_gulf = any(g in dest_clean.lower() for g in ["oman", "muscat", "saudi", "riyadh", "qatar", "doha", "bahrain"])
        
        legs: List[CommuteLeg] = []
        border_points: List[str] = []
        docs: List[str] = [
            "Original passport with minimum 6 months validity.",
            "Valid UAE residence visa & Emirates ID.",
        ]

        if is_road_gulf and ("Oman" in dest_clean or "Muscat" in dest_clean):
            # Road commute Dubai -> Oman corridor
            legs.append(CommuteLeg(
                mode="road_transit",
                origin=orig_clean,
                destination="Hatta Border Post (UAE side)",
                duration_minutes=90,
                transit_buffer_minutes=15,
                border_control=False,
                notes="Route via Dubai-Hatta road (E44/E102)."
            ))
            legs.append(CommuteLeg(
                mode="border_clearance",
                origin="Hatta Border Post",
                destination="Al Wajajah Border Post (Oman side)",
                duration_minutes=45,
                transit_buffer_minutes=30,
                border_control=True,
                notes="Customs, vehicle insurance (Orange Card), and visa on arrival or e-visa clearance."
            ))
            legs.append(CommuteLeg(
                mode="road_transit",
                origin="Al Wajajah Border Post",
                destination=dest_clean,
                duration_minutes=180,
                transit_buffer_minutes=0,
                border_control=False,
                notes="Continue to Muscat via Route 1."
            ))
            border_points = ["Hatta UAE Exit", "Al Wajajah Oman Entry"]
            docs.extend(["Vehicle Registration (Mulkiya)", "Oman Car Insurance (Orange Card)"])

        else:
            # International flight corridor
            airport = "Dubai International Airport (DXB)"
            if "Abu Dhabi" in orig_clean:
                airport = "Zayed International Airport (AUH)"

            legs.append(CommuteLeg(
                mode="metro_or_taxi",
                origin=orig_clean,
                destination=airport,
                duration_minutes=40,
                transit_buffer_minutes=20,
                border_control=False,
                notes="Airport transfer via Dubai Metro Red Line or private commute."
            ))
            legs.append(CommuteLeg(
                mode="airport_checkin_and_emigration",
                origin=airport,
                destination=f"{airport} Departure Gate",
                duration_minutes=90,
                transit_buffer_minutes=45,
                border_control=True,
                notes="Smart gate passport control and security clearance."
            ))
            legs.append(CommuteLeg(
                mode="flight",
                origin=airport,
                destination=dest_clean,
                duration_minutes=420,
                transit_buffer_minutes=60,
                border_control=True,
                notes=f"International flight leg to {dest_clean} with immigration clearance upon arrival."
            ))
            border_points = [f"{airport} UAE Immigration", f"{dest_clean} International Border Control"]
            docs.extend(["Boarding Pass / E-Ticket", f"{dest_clean} Entry Visa / E-Visa printout", "Proof of accommodation and return commute ticket"])

        total_min = sum(l.duration_minutes + l.transit_buffer_minutes for l in legs)

        itinerary = CommuteItinerary(
            corridor=f"{orig_clean} -> {dest_clean}",
            total_duration_minutes=total_min,
            legs=legs,
            recommended_departure=dep_time,
            border_checkpoints=border_points,
            document_checklist=docs
        )
        return itinerary.model_dump()

    @staticmethod
    def get_cross_border_advisory(corridor: str, traveler_nationality: str) -> Dict[str, Any]:
        """Specific travel regulations and transit advice for a given commute corridor."""
        corr_lower = corridor.lower()
        if "oman" in corr_lower:
            return {
                "corridor": "UAE <-> Oman Cross-Border Highway",
                "operating_hours": "24/7 (Hatta / Al Wajajah)",
                "peak_wait_times": "Thursday 16:00-22:00, Sunday 15:00-21:00",
                "visa_rule": "GCC Residents eligible for Oman e-visa (29A) or Visa On Arrival subject to approved profession.",
                "vehicle_requirement": "Orange Card insurance mandatory at border.",
                "action_items": [
                    "Apply for Oman e-visa online to bypass border queuing.",
                    "Verify passport valid > 6 months and UAE residency > 3 months."
                ]
            }
        elif "saudi" in corr_lower or "ksa" in corr_lower:
            return {
                "corridor": "UAE <-> Saudi Arabia Transit",
                "checkpoints": "Al Batha Border / DXB-RUH Flight",
                "visa_rule": "GCC Residents eligible for Instant Saudi Tourist e-Visa (valid 1 year, multiple entry).",
                "action_items": [
                    "Ensure GCC residency validity > 3 months.",
                    "Download Saudi e-visa PDF before arriving at transit terminal."
                ]
            }
        return {
            "corridor": corridor,
            "status": "international_transit",
            "advisory": f"Confirm transit and entry visa rules for {corridor} with VisaGuy assistance.",
            "recommendation": "Use visaguy_check_commute_compliance tool for full assessment."
        }
