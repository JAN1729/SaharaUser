"""FastAPI / Starlette Web Gateway for VisaGuy Multi-MCP Server.

Exposes:
- GET  /sse                 -> MCP Server-Sent Events endpoint
- POST /messages/           -> MCP JSON-RPC message endpoint
- GET  /health              -> Health check of all connected services
- GET  /federation/servers  -> List of all connected upstream MCP servers
- POST /federation/connect  -> Connect any new MCP on the web dynamically
- POST /agent/orchestrate   -> Multi-agent commute & visa execution
"""

import sys
import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from typing import Dict, Any, Optional

from .server import create_visaguy_mcp_server
from .federation import federation_registry
from .commute_services import CommuteServiceEngine
from .hyperclassifier import JevHyperclassifier
from .client import VisaGuyClient
from .schemas import EligibilityAssessmentRequest

app = FastAPI(
    title="Sahara Commute & VisaGuy Multi-MCP Gateway",
    description="Federated MCP endpoint connecting VisaGuy, external web MCPs, and multimodal commute services.",
    version="1.0.0"
)

# Initialize MCP server and Starlette SSE application
mcp_server = create_visaguy_mcp_server()
sse_app = mcp_server.sse_app()
app.mount("/mcp-sse", sse_app)

client = VisaGuyClient()
hyperclassifier = JevHyperclassifier()

class ConnectMCPRequest(BaseModel):
    name: str = Field(..., description="Unique name for the remote MCP")
    url_or_command: str = Field(..., description="Remote SSE/HTTP URL or stdio command")
    transport: str = Field("sse", description="'sse', 'http', or 'stdio'")
    metadata: Optional[Dict[str, Any]] = None

class OrchestrateRequest(BaseModel):
    query: str
    origin: str = "Dubai, UAE"
    traveler_nationality: str = "Indian"
    salary: str = "15000 AED"

@app.get("/health")
async def health():
    backend_health = await client.check_health()
    return {
        "status": "healthy",
        "mcp_gateway": "online",
        "mcp_sse_endpoint": "/mcp-sse/sse",
        "backend_services": backend_health,
        "connected_mcps_count": len(federation_registry.list_mcps())
    }

@app.get("/federation/servers")
async def list_servers():
    return {"connected_servers": federation_registry.list_mcps()}

@app.post("/federation/connect")
@app.post("/federation/register")
async def connect_server(req: ConnectMCPRequest):
    res = await federation_registry.register_mcp(
        name=req.name,
        url_or_command=req.url_or_command,
        transport=req.transport,
        metadata=req.metadata
    )
    return res

@app.post("/agent/orchestrate")
async def orchestrate(req: OrchestrateRequest):
    # Agent 1: Fast Intent Classification (<10ms)
    clf = await hyperclassifier.classify(
        req.query,
        {"origin": req.origin, "nationality": req.traveler_nationality, "salary": req.salary}
    )
    dest = clf.destination_country or "UK"

    # Agent 2: Visa Evaluation
    visa = await client.check_eligibility(
        EligibilityAssessmentRequest(
            destination=dest,
            nationality=req.traveler_nationality,
            salary=req.salary
        )
    )

    # Agent 3: Multimodal Commute Routing
    commute = CommuteServiceEngine.plan_multimodal_commute(
        origin=req.origin,
        destination=dest,
        traveler_nationality=req.traveler_nationality
    )

    return {
        "classification": clf.model_dump(),
        "visa_advisory": visa.model_dump(),
        "commute_plan": commute,
        "federated_mcps": federation_registry.list_mcps()
    }

def run_gateway(port: int = 8000, host: str = "0.0.0.0"):
    print(f"Starting Multi-MCP Gateway on http://{host}:{port}...")
    print(f"MCP SSE Endpoint available at http://{host}:{port}/mcp-sse/sse")
    uvicorn.run(app, host=host, port=port)

if __name__ == "__main__":
    port = 8000
    if len(sys.argv) > 1 and sys.argv[1].isdigit():
        port = int(sys.argv[1])
    run_gateway(port=port)
