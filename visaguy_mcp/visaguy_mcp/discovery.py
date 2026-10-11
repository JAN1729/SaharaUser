"""Autonomous MCP Doorway Discovery and Web Agent Fallback Module."""

import re
from typing import Dict, Any, List, Optional
import httpx
from bs4 import BeautifulSoup

from .endpoints import (
    VISAGUY_FRONTEND_BASE,
    BE_CHECKER_BASE,
    WP_API_BASE,
    DEFAULT_HEADERS
)

CANDIDATE_DOORWAYS = [
    f"{VISAGUY_FRONTEND_BASE}/.well-known/mcp",
    f"{VISAGUY_FRONTEND_BASE}/.well-known/mcp.json",
    f"{VISAGUY_FRONTEND_BASE}/.well-known/model-context-protocol",
    f"{VISAGUY_FRONTEND_BASE}/mcp",
    f"{VISAGUY_FRONTEND_BASE}/api/mcp",
    f"{BE_CHECKER_BASE}/mcp",
    f"{BE_CHECKER_BASE}/api/mcp",
    f"{WP_API_BASE}/mcp",
    "https://mcp.visaguy.ae",
    "https://mcp.visaguy.ae/sse",
]

class MCPDiscoveryAgent:
    """Agent scanner to detect when VisaGuy publishes an official first-party MCP server."""

    def __init__(self, timeout: float = 6.0):
        self.timeout = timeout
        self.headers = DEFAULT_HEADERS.copy()

    async def scan_for_official_doorway(self) -> Dict[str, Any]:
        """Probe all potential MCP doorways on visaguy.ae ecosystem."""
        results: List[Dict[str, Any]] = []
        found_doorway: Optional[str] = None
        transport_type: Optional[str] = None

        async with httpx.AsyncClient(timeout=self.timeout, follow_redirects=True) as client:
            # 1. Probe candidate endpoints
            for url in CANDIDATE_DOORWAYS:
                try:
                    resp = await client.get(url, headers=self.headers)
                    status = resp.status_code
                    content_type = resp.headers.get("content-type", "")

                    is_mcp = False
                    # Check for JSON-RPC, SSE, or MCP declaration
                    if status == 200 and ("json" in content_type or "event-stream" in content_type):
                        try:
                            body = resp.text
                            if "mcp" in body.lower() or "jsonrpc" in body or "tools" in body:
                                is_mcp = True
                                found_doorway = url
                                transport_type = "sse" if "event-stream" in content_type else "http"
                        except Exception:
                            pass

                    results.append({
                        "url": url,
                        "status_code": status,
                        "content_type": content_type,
                        "is_mcp_doorway": is_mcp
                    })
                except Exception as e:
                    results.append({"url": url, "error": str(e), "is_mcp_doorway": False})

            # 2. Inspect homepage HTML meta tags for MCP links
            try:
                home_resp = await client.get(VISAGUY_FRONTEND_BASE, headers=self.headers)
                if home_resp.status_code == 200:
                    soup = BeautifulSoup(home_resp.text, "html.parser")
                    mcp_meta = soup.find("link", rel=lambda r: r and "mcp" in r.lower()) or soup.find("meta", attrs={"name": "mcp-server"})
                    if mcp_meta:
                        found_doorway = mcp_meta.get("href") or mcp_meta.get("content")
                        transport_type = "sse"
            except Exception:
                pass

        return {
            "official_mcp_detected": bool(found_doorway),
            "doorway_url": found_doorway,
            "transport_type": transport_type or "none",
            "probed_candidates": len(results),
            "details": results,
            "recommendation": (
                f"Connect upstream to {found_doorway}" if found_doorway
                else "No official MCP found on visaguy.ae yet. Use our built-in VisaGuy MCP connector."
            )
        }

    async def execute_web_task(self, task_type: str, payload: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """Autonomous web agent fallback to interact directly with visaguy.ae website."""
        payload = payload or {}
        async with httpx.AsyncClient(timeout=self.timeout, follow_redirects=True) as client:
            if task_type == "extract_popular_routes":
                resp = await client.get(VISAGUY_FRONTEND_BASE, headers=self.headers)
                soup = BeautifulSoup(resp.text, "html.parser")
                # Extract marquee country tags
                countries = [span.get_text(strip=True) for span in soup.find_all("span") if span.get_text(strip=True) in ["France", "Italy", "Spain", "Germany", "UK", "Japan", "China", "Thailand", "USA", "Canada"]]
                return {
                    "task": "extract_popular_routes",
                    "status": "success",
                    "popular_destinations": list(dict.fromkeys(countries))
                }

            elif task_type == "submit_web_form":
                form_url = f"{VISAGUY_FRONTEND_BASE}/api/contact"
                form_data = {
                    "formType": "Consultation Modal",
                    "name": payload.get("name", "Traveler"),
                    "email": payload.get("email", "traveler@example.com"),
                    "phone": payload.get("phone", "+971501234567"),
                    "salary": str(payload.get("salary", "15000")),
                    "destination": payload.get("destination", "UK")
                }
                resp = await client.post(form_url, json=form_data, headers={"Content-Type": "application/json"})
                return {
                    "task": "submit_web_form",
                    "status_code": resp.status_code,
                    "success": resp.status_code in [200, 201]
                }

            return {"task": task_type, "status": "unknown_task"}
