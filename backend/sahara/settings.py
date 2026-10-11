import os
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class Settings:
    database: Path = field(default_factory=lambda: Path(os.getenv('SAHARA_DATABASE','data/sahara.sqlite3')))
    api_key: str = field(default_factory=lambda: os.getenv('SAHARA_API_KEY','Enl6MUBS7LPmAhRFTVBt_yF0a5l5EonYeXZabOescvw'))
    scraping: bool = field(default_factory=lambda: os.getenv('SAHARA_SCRAPING_ENABLED','true').lower()=='true')
    browser: bool = field(default_factory=lambda: os.getenv('SAHARA_BROWSER_ENABLED','true').lower()=='true')
    local_feed: str = field(default_factory=lambda: os.getenv('SAHARA_LOCAL_FEED',''))
    openrouter_key: str = field(default_factory=lambda: os.getenv('OPENROUTER_API_KEY',''),repr=False)
    openrouter_model: str = field(default_factory=lambda: os.getenv('OPENROUTER_MODEL','deepseek/deepseek-v4-flash'))
    route_graph: str = field(default_factory=lambda: os.getenv('SAHARA_ROUTE_GRAPH',''))
    driver_url: str = field(default_factory=lambda: os.getenv('SAHARA_DRIVER_URL',''))
    driver_key: str = field(default_factory=lambda: os.getenv('SAHARA_DRIVER_READ_KEY',''),repr=False)
    jev_enabled: bool = field(default_factory=lambda: os.getenv('SAHARA_JEV_ENABLED','true').lower()=='true')
    jev_model: str = field(default_factory=lambda: os.getenv('SAHARA_JEV_MODEL','typesafe/jev-1.13'))
    jev_timeout: float = field(default_factory=lambda: float(os.getenv('SAHARA_JEV_TIMEOUT_SECONDS','4')))
    jev_accept_matches: bool = field(default_factory=lambda: os.getenv('SAHARA_JEV_ACCEPT_MATCHES','false').lower()=='true')
    notice_sources: str = field(default_factory=lambda: os.getenv('SAHARA_NOTICE_SOURCES',''))
