# LLM API

## Ingestion

### POST /api/{projectId}/llm/
Ingest LLM generation events.

**Authentication:** `X-Sentry-Auth` header or `sentry_key` query parameter with your project's public key.

**Headers:**
| name | type | description |
|------|------|-------------|
| X-Sentry-Auth | string | Sentry auth header containing the public key |
| Content-Encoding | string | Set to `gzip` for compressed payloads |

**Body:**
| name | type | required | description |
|------|------|----------|-------------|
| generations | array | yes | Array of generation objects |

**Generation object fields:**
| name | type | required | description |
|------|------|----------|-------------|
| trace_id | string | no | Groups related generations into a trace |
| span_id | string | no | Unique identifier for this generation |
| parent_span_id | string | no | Parent span for building trace trees |
| name | string | no | Operation name (e.g. "chat_completion") |
| model | string | no | Model identifier (e.g. "gpt-4o") |
| provider | string | no | Provider name (e.g. "openai") |
| type | string | no | chat, completion, embedding, tool_call, agent, chain, retriever |
| input | JSON | no | Input messages or prompt |
| output | JSON | no | Model response or completion |
| input_tokens | integer | no | Number of input tokens |
| output_tokens | integer | no | Number of output tokens |
| cost_usd | number | no | Cost of this generation in USD |
| duration_ms | number | no | Duration in milliseconds |
| status | string | no | "success" or "error" |
| error_message | string | no | Error message if status is "error" |
| status_code | integer | no | HTTP status code from the LLM API |
| timestamp | string | no | ISO 8601 timestamp |
| temperature | number | no | Temperature parameter |
| max_tokens | integer | no | Max tokens parameter |
| top_p | number | no | Top-p parameter |
| user_id | string | no | User identifier |
| session_id | string | no | Session identifier |
| environment | string | no | Environment (e.g. "production") |
| release | string | no | Release version |
| tags | object | no | Key-value pairs for filtering |
| metadata | JSON | no | Arbitrary extra data |

**Response:** `202 Accepted` with `{"accepted": <count>}`

---

## Dashboard Endpoints

All dashboard endpoints require JWT authentication and a `projectId` query parameter.

### GET /v1/llm/overview
Get overview stats, timeline, and top models for the AI dashboard.

**Query parameters:**
| name | type | default | description |
|------|------|---------|-------------|
| projectId | integer | - | Project ID (required) |
| range | string | 24h | Time range (1h, 6h, 24h, 7d, 14d, 30d) |

### GET /v1/llm/generations
List LLM generations with optional filters.

**Query parameters:**
| name | type | default | description |
|------|------|---------|-------------|
| projectId | integer | - | Project ID (required) |
| range | string | 24h | Time range |
| model | string | - | Filter by model name |
| provider | string | - | Filter by provider |
| type | string | - | Filter by generation type |
| status | string | - | Filter by status (success, error) |
| page | integer | 1 | Page number |
| pageSize | integer | 25 | Results per page |

### GET /v1/llm/generations/{id}
Get full details for a single generation, including input/output content.

**Query parameters:**
| name | type | description |
|------|------|-------------|
| projectId | integer | Project ID (required) |

### GET /v1/llm/traces/{traceId}
Get all generations in a trace with parent/child relationships.

**Query parameters:**
| name | type | description |
|------|------|-------------|
| projectId | integer | Project ID (required) |

### GET /v1/llm/models
Get aggregated statistics per model/provider.

**Query parameters:**
| name | type | default | description |
|------|------|---------|-------------|
| projectId | integer | - | Project ID (required) |
| range | string | 24h | Time range |

### GET /v1/llm/costs
Get cost breakdown by model/provider and cost timeline.

**Query parameters:**
| name | type | default | description |
|------|------|---------|-------------|
| projectId | integer | - | Project ID (required) |
| range | string | 24h | Time range |
