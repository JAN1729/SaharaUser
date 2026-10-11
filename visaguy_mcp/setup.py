from setuptools import setup, find_packages

setup(
    name="visaguy-mcp",
    version="1.0.0",
    description="Official VisaGuy MCP Server with TypeSafe JEV System-1 and Multimodal Commute Federation",
    packages=find_packages(),
    python_requires=">=3.10",
    install_requires=[
        "mcp>=2.3.0",
        "pydantic>=2.0.0",
        "httpx>=0.27.0",
        "fastapi>=0.110.0",
        "uvicorn>=0.28.0",
        "beautifulsoup4>=4.12.0",
    ],
    entry_points={
        "console_scripts": [
            "visaguy-mcp=visaguy_mcp.cli:main",
        ],
    },
)
