# MCP Server Setup

## Prerequisites

- A Moneat backend with MCP enabled
- An API token with appropriate scopes

## Generating an API Token

1. Log into your Moneat dashboard
2. Navigate to **Settings → API Tokens**
3. Click **Create Token**
4. Give it a descriptive name (e.g., "MCP - Cursor IDE")
5. Select scopes based on the tools you need:
  - **Read-only tools**: `project:read`, `event:read`, `org:read`
  - **Mutating tools** (e.g., `create_dashboard`, `create_host`, `update_issue_status`, `create_uptime_monitor`): also add `project:write`
  - **Release tools**: also add `releases:read` and/or `releases:write`
6. Copy the generated token (it won't be shown again)

## Connecting from Cursor IDE

Add to your Cursor MCP configuration (`.cursor/mcp.json`):

```json
{
  "mcpServers": {
    "moneat": {
      "url": "https://your-moneat-instance/v1/mcp",
      "headers": {
        "Authorization": "Bearer YOUR_API_TOKEN"
      }
    }
  }
}
```

## Connecting from a Custom Agent

Use any MCP-compatible client library that supports Streamable HTTP:

1. Initialize a Streamable HTTP MCP session with `POST /v1/mcp`
2. Pass `Authorization: Bearer YOUR_TOKEN` as a header
3. Include `Accept: application/json, text/event-stream`
4. Reuse the returned `mcp-session-id` header for subsequent JSON-RPC requests
5. Receive direct JSON responses

### Example with curl

```bash
MCP_SESSION_ID=$(
  curl -i -X POST "https://your-moneat-instance/v1/mcp" \
    -H "Authorization: Bearer YOUR_TOKEN" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"curl","version":"1.0.0"}}}' \
    | awk -F': ' 'tolower($1) == "mcp-session-id" {print $2}' \
    | tr -d '\r'
)

curl -X POST "https://your-moneat-instance/v1/mcp" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "mcp-session-id: ${MCP_SESSION_ID}" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

## Authentication

The MCP server authenticates using Moneat API tokens. Tokens must be provided as an Authorization header:

- `Authorization: Bearer YOUR_TOKEN`

The token determines which organization's data the MCP session can access.

## Available Tools

Once connected, run `tools/list` to see all available tools. See the [Tools Reference](./tools-reference) for the complete list.
