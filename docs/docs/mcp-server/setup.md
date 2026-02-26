# MCP Server Setup

## Prerequisites

- Moneat Enterprise edition
- An API token with appropriate scopes

## Generating an API Token

1. Log into your Moneat dashboard
2. Navigate to **Settings → API Tokens**
3. Click **Create Token**
4. Give it a descriptive name (e.g., "MCP - Cursor IDE")
5. Select scopes: `project:read`, `event:read`, `org:read`
6. Copy the generated token (it won't be shown again)

## Connecting from Cursor IDE

Add to your Cursor MCP configuration (`.cursor/mcp.json`):

```json
{
  "mcpServers": {
    "moneat": {
      "url": "https://your-moneat-instance/v1/mcp/sse?token=YOUR_API_TOKEN"
    }
  }
}
```

## Connecting from a Custom Agent

Use any MCP-compatible client library. The connection uses SSE transport:

1. Connect to `GET /v1/mcp/sse?token=YOUR_TOKEN` via SSE
2. The server sends an `endpoint` event with the message URL
3. Send JSON-RPC requests to the message URL via POST
4. Receive responses via the SSE stream

### Example with curl

```bash
# Establish SSE connection
curl -N "https://your-moneat-instance/v1/mcp/sse?token=YOUR_TOKEN"

# In another terminal, send a tools/list request
curl -X POST "https://your-moneat-instance/v1/mcp/message?sessionId=SESSION_ID" \
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
