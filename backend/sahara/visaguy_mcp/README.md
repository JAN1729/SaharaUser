# VisaGuy MCP Server with TypeSafe JEV System-1 Hyperclassification

Model Context Protocol (MCP) server for integrating VisaGuy (`visaguy.ae`) services into multimodal agentic commute and travel applications.

## Architecture & Discovered Endpoints

| Service Layer | Base URL | Discovered Endpoints | Purpose |
|---|---|---|---|
| **Headless WordPress REST API** | `https://api.visaguy.ae/wp-json/wp/v2` | `/countries`, `/news`, `/students-visa`, `/search`, `/country_category`, `/country_tag` | Public read access to country visa guidelines, e-visa vs sticker visa taxonomy, rule changes, embassy updates. |
| **Eligibility Microservice** | `https://be-checker.visaguy.ae` | `POST /api/recommendations`, `POST /api/voice/client-secret` | Microservice powering `visa-eligibility.visaguy.ae`. Real-time AI eligibility scoring, recommendations, and OpenAI Realtime WebRTC voice agent session credentials. |
| **Frontend Direct API** | `https://visaguy.ae` | `POST /api/contact`, `POST /api/newsletter` | Direct booking, consultation form intake, and subscriber alerts. |
| **ERPNext CRM Resource Backend** | `https://visaguy.tridzbi.com` | `POST/PUT /api/resource/Raw Lead` | ERP pipeline with bearer token authentication (`token 5ca4de83ed302c9:0056ab67b88f575`). |

---

## TypeSafe JEV System-1 Hyperclassification

Based on TypeSafe AI's System One architecture ([TypeSafe Blog](https://typesafe.ai/blog/introducing-system-one-models-and-jev)):
- **Problem**: Multi-turn generative LLMs (System 2) introduce 1-3 seconds of latency and token cost when deciding which tool to call or classifying travel state.
- **Solution**: Jev System 1 Hyperclassification uses schema-constrained, deterministic decisions (<10ms) to classify user intent, multimodal OCR tickets/boarding passes, and commuter corridor data.
- **Routing**: Automatically routes inputs to:
  - `COMMUTE_TRANSIT_COMPLIANCE` -> `visaguy_check_commute_compliance`
  - `ELIGIBILITY_EVALUATION` -> `visaguy_check_eligibility`
  - `VISA_REQUIREMENT_CHECK` -> `visaguy_get_country_details`
  - `VOICE_CONSULTATION_SESSION` -> `visaguy_get_voice_agent_config`
  - `BOOK_CONSULTATION` -> `visaguy_book_consultation`
  - `SEARCH_POLICY_RULES` -> `visaguy_search_visa_rules`

---

## MCP Tools Provided

1. `jev_hyperclassify(query, multimodal_metadata)`: Sub-10ms System-1 classification.
2. `visaguy_get_countries(category, search, per_page)`: List countries (filter by Schengen, e-visa, Americas, Europe, Asia).
3. `visaguy_get_country_details(country_name_or_slug)`: Get full visa rules, documentation checklist, and processing guides.
4. `visaguy_search_visa_rules(query, per_page)`: Full-text search across VisaGuy knowledgebase.
5. `visaguy_check_eligibility(destination, nationality, salary, ...)`: Live AI eligibility assessment and recommendations.
6. `visaguy_get_voice_agent_config(zone)`: WebRTC voice session credentials (`zone="uae" | "sa" | "qa"`).
7. `visaguy_check_commute_compliance(origin, destination, nationality, ...)`: Compound multimodal commute compliance check.
8. `visaguy_book_consultation(full_name, email, mobile_number, destination, ...)`: Register lead or booking.

---

## Running the Server

### 1. Standard I/O (stdio) Transport
For Claude Desktop, Cursor, Antigravity, or local agent harnesses:
```powershell
python -m sahara.visaguy_mcp.server
```

### 2. Server-Sent Events (SSE) Transport
For remote or microservice access:
```powershell
python -m sahara.visaguy_mcp.server --transport sse --port 8000
```

### 3. CLI Testing
```powershell
# Hyperclassify commute intent
python -m sahara.visaguy_mcp.cli classify "Road trip from Dubai to Muscat Oman next weekend"

# Get countries
python -m sahara.visaguy_mcp.cli countries --category schengen

# Check eligibility
python -m sahara.visaguy_mcp.cli eligibility --destination UK --nationality Indian

# Get voice credentials
python -m sahara.visaguy_mcp.cli voice --zone uae
```
