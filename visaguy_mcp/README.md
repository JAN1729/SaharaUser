# VisaGuy MCP Connector & TypeSafe JEV System-1 Directory Package

Dedicated, self-contained Model Context Protocol (MCP) server package for **VisaGuy.ae** integrated with **Sahara Commute** and the wider Multi-MCP Travel Federation mesh.

---

## 📁 Directory Structure

```
visaguy_mcp/
├── pyproject.toml              # Modern PEP 517/621 package build config
├── setup.py                    # Setuptools build script
├── requirements.txt            # Package dependencies
├── run_mcp.py                  # Standalone executable launcher
├── mcp_config.json             # MCP client registration manifest
├── README.md                   # Complete architectural and usage guide
├── visaguy_mcp/                # Python package source
│   ├── __init__.py             # Exports client, server, hyperclassifier, federation
│   ├── __main__.py             # Direct execution entrypoint (python -m visaguy_mcp)
│   ├── cli.py                  # CLI command runner (serve, classify, countries, etc.)
│   ├── client.py               # Live async client for be-checker, CMS, ERPNext APIs
│   ├── commute_services.py     # Multimodal journey planner & cross-border advisory
│   ├── discovery.py            # Live MCP doorway scanner and DOM scraper agent
│   ├── endpoints.py            # Verified endpoints for all VisaGuy backend services
│   ├── federation.py           # Multi-MCP federation registry (flights, transit, etc.)
│   ├── gateway.py              # FastAPI SSE gateway for remote MCP clients
│   ├── hyperclassifier.py      # TypeSafe JEV System-1 sub-10ms intent routing
│   ├── sahara_adapter.py       # Sahara Commute application adapter & agent roles
│   ├── schemas.py              # Pydantic data schemas mirroring OpenAPI 3.1.0
│   └── server.py               # MCP 2.3.0 server exposing 20 registered tools
└── tests/                      # Standalone test suite
    ├── __init__.py
    └── test_visaguy_mcp.py     # 15 end-to-end integration and unit tests
```

---

## 🚀 Quick Start

### 1. Install in editable mode
```bash
cd C:\Users\Admin\Sahara\visaguy_mcp
pip install -e .
```

### 2. Build distribution artifacts (`dist/`)
```bash
python setup.py sdist bdist_wheel
```

### 3. Run MCP Server (Stdio)
```bash
python run_mcp.py serve --transport stdio
# or
python -m visaguy_mcp.cli serve --transport stdio
```

### 4. Run MCP Server (SSE Gateway on port 8000)
```bash
python -m visaguy_mcp.gateway 8000
```
SSE endpoint: `http://localhost:8000/mcp-sse/sse`

---

## ⚡ TypeSafe JEV System-1 Hyperclassification
Rapidly classifies multimodal travel queries in sub-10ms:
```bash
python run_mcp.py classify "Traveling from Dubai to London Heathrow next Tuesday, need visa and flight connection"
```

## 🛠️ Register in MCP Clients (Claude Desktop, Cursor, Antigravity)
Add this snippet to your `mcp_config.json`:
```json
{
  "mcpServers": {
    "visaguy-mcp": {
      "command": "python",
      "args": ["-m", "visaguy_mcp.cli", "serve", "--transport", "stdio"],
      "cwd": "C:/Users/Admin/Sahara/visaguy_mcp"
    }
  }
}
```
