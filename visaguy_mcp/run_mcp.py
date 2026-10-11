#!/usr/bin/env python3
"""Standalone runner script for VisaGuy MCP Server.

Usage:
    python run_mcp.py serve --transport stdio
    python run_mcp.py serve --transport sse --port 8000
    python run_mcp.py classify "I need a visa to London tomorrow for transit"
    python run_mcp.py countries --category Schengen
"""

import sys
import os

# Add local directory to sys.path so it works without prior installation
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from visaguy_mcp.cli import main

if __name__ == "__main__":
    main()
