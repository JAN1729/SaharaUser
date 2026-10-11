"""API Endpoints and constants for VisaGuy ecosystem."""

# Headless WordPress CMS (Public REST API)
WP_API_BASE = "https://api.visaguy.ae/wp-json/wp/v2"
WP_COUNTRIES_ENDPOINT = f"{WP_API_BASE}/countries"
WP_NEWS_ENDPOINT = f"{WP_API_BASE}/news"
WP_STUDENTS_VISA_ENDPOINT = f"{WP_API_BASE}/students-visa"
WP_PROMO_ENDPOINT = f"{WP_API_BASE}/promo"
WP_SEARCH_ENDPOINT = f"{WP_API_BASE}/search"
WP_COUNTRY_CATEGORIES_ENDPOINT = f"{WP_API_BASE}/country_category"
WP_COUNTRY_TAGS_ENDPOINT = f"{WP_API_BASE}/country_tag"

# Eligibility Microservice (Public AI assessment & OpenAI Realtime Voice WebRTC)
BE_CHECKER_BASE = "https://be-checker.visaguy.ae"
RECOMMENDATIONS_ENDPOINT = f"{BE_CHECKER_BASE}/api/recommendations"
VOICE_CLIENT_SECRET_ENDPOINT = f"{BE_CHECKER_BASE}/api/voice/client-secret"

# Next.js Frontend Direct Endpoints
VISAGUY_FRONTEND_BASE = "https://visaguy.ae"
CONTACT_FORM_ENDPOINT = f"{VISAGUY_FRONTEND_BASE}/api/contact"
NEWSLETTER_ENDPOINT = f"{VISAGUY_FRONTEND_BASE}/api/newsletter"

# Enterprise CRM / ERPNext Backend
ERP_BASE = "https://visaguy.tridzbi.com"
ERP_RAW_LEAD_ENDPOINT = f"{ERP_BASE}/api/resource/Raw Lead"
ERP_AUTH_TOKEN = "5ca4de83ed302c9:0056ab67b88f575"

# Common Default Headers
DEFAULT_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
    "Accept": "application/json",
}

# TypeSafe Jev API defaults
TYPESAFE_API_DEFAULT_URL = "https://api.typesafe.ai/v1/classify"
