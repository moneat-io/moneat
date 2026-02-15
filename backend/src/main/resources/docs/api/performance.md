# Performance API

## Endpoints

### GET /v1/projects/{projectId}/transactions
List transaction summaries for a project.

**Query parameters:**
| name | type | default | description |
|------|------|---------|-------------|
| range | string | 24h | Time range |
| sort | string | count | Sort by: count, p50, p75, p95, failureRate |
| query | string | - | Search transaction names |

Returns: name, op, count, p50, p75, p95, failureRate, tpm.

### GET /v1/projects/{projectId}/transactions/stats
Get performance overview stats: apdex, throughput timeline, slowest transactions.

### GET /v1/transactions/{eventId}
Get full transaction details.

### GET /v1/transactions/{eventId}/spans
Get all spans for a transaction.

### GET /v1/transactions/{eventId}/related-errors
Get error events related to a transaction (same trace).

### GET /v1/projects/{projectId}/traces/{traceId}
Get a full distributed trace with all spans.

### GET /v1/projects/{projectId}/spans/{spanId}
Get details for a specific span.
