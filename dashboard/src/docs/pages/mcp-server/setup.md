# MCP Server Setup

## Prerequisites

- Moneat Enterprise edition
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
      "url": "https://your-moneat-instance/v1/mcp/sse",
      "headers": {
        "Authorization": "Bearer YOUR_API_TOKEN"
      }
    }
  }
}
```

> **Fallback (not recommended):** Some clients do not support custom headers. In that case you can append `?token=YOUR_API_TOKEN` to the URL, but be aware that the token will appear in server logs, shell history, and screenshots.

## Connecting from a Custom Agent

Use any MCP-compatible client library. The connection uses SSE transport:

1. Connect to `GET /v1/mcp/sse` via SSE, passing `Authorization: Bearer YOUR_TOKEN` as a header
2. The server sends an `endpoint` event with the session-specific message URL
3. Send JSON-RPC requests to that message URL via POST
4. Receive responses via the SSE stream

### Example with curl

```bash
# Establish SSE connection (note the endpoint URL printed by the server)
curl -N -H "Authorization: Bearer YOUR_TOKEN" \
  "https://your-moneat-instance/v1/mcp/sse"
# The server emits an "endpoint" event, e.g.:
# event: endpoint
# data: https://your-moneat-instance/v1/mcp/message?sessionId=SESSION_ID

# In another terminal, send a tools/list request to the session endpoint
endpoint="https://your-moneat-instance/v1/mcp/message?sessionId=SESSION_ID"
curl -X POST "$endpoint" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

## Authentication

The MCP server authenticates using Moneat API tokens. The token can be provided:

- As a query parameter: `?token=YOUR_TOKEN`
- As an Authorization header: `Authorization: Bearer YOUR_TOKEN`

The token determines which organization's data the MCP session can access.

## Available Tools

Once connected, run `tools/list` to see all available tools. See the [Tools Reference](./tools-reference) for the complete list.
