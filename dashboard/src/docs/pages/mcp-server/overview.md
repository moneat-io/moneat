# MCP Server

Moneat includes a built-in **Model Context Protocol (MCP) server** that enables AI agents and IDE tools (Cursor, GitHub Copilot, custom SRE agents) to interact with your observability data programmatically.

## What is MCP?

The [Model Context Protocol](https://modelcontextprotocol.io/) is an open standard that lets AI assistants connect to external data sources and tools. Moneat's MCP server exposes your issues, logs, metrics, traces, dashboards, and alerts as MCP tools and resources.

## Use Cases

- **AI-powered SRE**: Let AI agents investigate production incidents by querying logs, traces, and metrics
- **IDE integration**: Connect Cursor or Copilot to your Moneat instance for in-editor observability
- **Automated triage**: Build agents that automatically triage and categorize incoming issues
- **Dashboard creation**: Let AI agents create dashboards and widgets based on natural language descriptions
- **Incident response**: AI agents can query on-call incidents, alert status, and host health

## Architecture

The MCP server runs as an enterprise module inside the Ktor backend process. It uses **SSE (Server-Sent Events) transport** following the MCP specification, exposing two endpoints:

- `GET /v1/mcp/sse` — SSE connection endpoint
- `POST /v1/mcp/message` — JSON-RPC message endpoint

Since the module runs inside the application process, MCP tools call existing services directly without HTTP round-trips.

## Quick Start

1. [Generate an API token](/docs/api-tokens) in Moneat
2. Configure your MCP client to connect to `https://your-moneat-instance/v1/mcp/sse?token=YOUR_TOKEN`
3. Start using tools like `list_issues`, `query_logs`, `list_hosts`, etc.

See [Setup Guide](./setup) for detailed instructions.
