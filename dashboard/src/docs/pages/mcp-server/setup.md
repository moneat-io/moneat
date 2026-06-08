# MCP Server Setup

## Prerequisites

- A Moneat backend with MCP enabled
- A dedicated MCP key with the tools your client should be able to use

## Generating an MCP Key

1. Log into your Moneat dashboard
2. Navigate to **Settings → API Keys → MCP**
3. Click **Create Key**
4. Give it a descriptive name (e.g., "Cursor IDE")
5. Select the tool sections this client should be able to use
6. Copy the generated key (it won't be shown again)

## Connecting from Cursor IDE

Add to your Cursor MCP configuration (`.cursor/mcp.json`):

```json
{
  "mcpServers": {
    "moneat": {
      "url": "https://your-moneat-instance/v1/mcp",
      "headers": {
        "Authorization": "Bearer YOUR_MCP_KEY"
      }
    }
  }
}
```

## Connecting from a Custom Agent

Use any MCP-compatible client library that supports Streamable HTTP:

1. Initialize a Streamable HTTP MCP session with `POST /v1/mcp`
2. Pass `Authorization: Bearer YOUR_MCP_KEY` as a header
3. Include `Accept: application/json, text/event-stream`
4. Reuse the returned `mcp-session-id` header for subsequent JSON-RPC requests
5. Receive direct JSON responses

### Example with curl

```bash
MCP_SESSION_ID=$(
  curl -i -X POST "https://your-moneat-instance/v1/mcp" \
    -H "Authorization: Bearer YOUR_MCP_KEY" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json, text/event-stream" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"curl","version":"1.0.0"}}}' \
    | awk -F': ' 'tolower($1) == "mcp-session-id" {print $2}' \
    | tr -d '\r'
)

curl -X POST "https://your-moneat-instance/v1/mcp" \
  -H "Authorization: Bearer YOUR_MCP_KEY" \
  -H "mcp-session-id: ${MCP_SESSION_ID}" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

## Authentication

The MCP server authenticates using dedicated Moneat MCP keys. Keys must be provided as an Authorization header:

- `Authorization: Bearer YOUR_MCP_KEY`

Existing Sentry API tokens are still accepted for compatibility, but dedicated MCP keys are recommended.

The key determines which organization's data and which MCP tools the session can access.

## Available Tools

Once connected, run `tools/list` to see all available tools. See the
[Tools Reference](./tools-reference) for the complete list.
