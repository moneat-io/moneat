# MCP Server

Moneat includes a built-in **Model Context Protocol (MCP) server** that enables AI agents and IDE tools (Cursor, GitHub Copilot, custom SRE agents) to interact with your observability data programmatically.

## What is MCP?

The [Model Context Protocol](https://modelcontextprotocol.io/) is an open standard that lets AI assistants connect to external data sources and tools. Moneat's MCP server exposes your issues, logs, metrics, traces, dashboards, alerts, and workflows as MCP tools and resources.

## Use Cases

- **AI-powered SRE**: Let AI agents investigate production incidents by querying logs, traces, and metrics
- **IDE integration**: Connect Cursor or Copilot to your Moneat instance for in-editor observability
- **Automated triage**: Build agents that automatically triage and categorize incoming issues
- **Dashboard creation**: Let AI agents create dashboards and widgets based on natural language descriptions
- **Incident response**: AI agents can query on-call alerts, escalation status, and host health
- **Workflow operations**: Author, publish, run, and audit workflow automation from MCP clients

## Architecture

The MCP server runs inside the core Ktor backend process. It uses **Streamable HTTP** following the MCP specification, exposing one endpoint:

- `POST /v1/mcp` - Streamable HTTP JSON-RPC endpoint

Since the module runs inside the application process, MCP tools call existing services directly without HTTP round-trips.

## Quick Start

1. Generate an MCP key from **Settings → API Keys → MCP** in Moneat
2. Configure your MCP client to connect to `https://your-moneat-instance/v1/mcp` with `Authorization: Bearer YOUR_MCP_KEY`
3. Start using tools like `list_issues`, `query_logs`, `list_hosts`, `list_workflows`, etc.

See [Setup Guide](./setup) for detailed instructions.
