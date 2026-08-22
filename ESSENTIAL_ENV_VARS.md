# Essential Environment Variables

Moneat validates critical runtime configuration on startup and fails fast when required values are missing.

## Critical

| Variable | Purpose |
| --- | --- |
| `JWT_SECRET` | Signs dashboard authentication tokens. Use a long random value. |
| `DATABASE_PASSWORD` | PostgreSQL application database password. |
| `CLICKHOUSE_PASSWORD` | ClickHouse application database password. |
| `DATA_SOURCE_ENCRYPTION_KEY` | Encrypts custom data source credentials. Must differ from all workflow keys. |
| `FRONTEND_URL` | Public dashboard URL, for redirects and generated links. |
| `BACKEND_URL` | Public API URL, for callbacks and generated links. |

## Conditional

Workflow runtime variables are required when `WORKFLOWS_ENABLED=true` (the default). Set
`WORKFLOWS_ENABLED=false` to disable Temporal validation during rollout.

| Variable | Required when | Purpose |
| --- | --- | --- |
| `WORKFLOWS_ENABLED` | Optional rollout override | Set to `false` to bypass workflow runtime validation while workflows are disabled. |
| `TEMPORAL_TARGET` | Workflows are enabled | Temporal frontend address, for example `temporal:7233`. |
| `TEMPORAL_NAMESPACE` | Workflows are enabled | Temporal namespace, normally `default`. |
| `TEMPORAL_DB_USER`, `TEMPORAL_DB_PASSWORD` | Using bundled Temporal | Dedicated Postgres role for Temporal runtime databases. |
| `WORKFLOWS_CONNECTION_KEK` | Workflows are enabled | Dedicated workflow connection key-encryption key. |
| `WORKFLOWS_SIGNING_KEY` | Workflows are enabled | Dedicated HMAC key for workflow tokens added by later phases. |
| `WORKFLOWS_TEMPORAL_PAYLOAD_KEY` | Workflows are enabled | Encrypts workflow payloads before they are stored in Temporal history. |
| `SLACK_CLIENT_ID`, `SLACK_CLIENT_SECRET`, `SLACK_REDIRECT_URI` | `SLACK_ENABLED=true` | Slack integration OAuth. |
| `DISCORD_CLIENT_ID`, `DISCORD_CLIENT_SECRET`, `DISCORD_REDIRECT_URI`, `DISCORD_BOT_TOKEN` | `DISCORD_ENABLED=true` | Discord integration OAuth and bot delivery. |
| `GOOGLE_ADS_DEVELOPER_TOKEN`, `GOOGLE_ADS_CLIENT_ID`, `GOOGLE_ADS_CLIENT_SECRET` | `GOOGLE_ADS_ENABLED=true` | Google Ads connector API access and OAuth token refresh. |
| `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` | `STRIPE_ENABLED=true` | Stripe billing. |
| `EXPO_TOKEN` | `ONCALL_ENABLED=true` | Mobile push notifications. |

Workflow secrets must be distinct from `JWT_SECRET` and `DATA_SOURCE_ENCRYPTION_KEY`.

## AI Chat Assistant

The in-app AI assistant is optional and turns on for admin users once a provider is
configured: either an API key resolves (`AI_API_KEY` or a legacy provider key), or
`AI_AUTH_TYPE=none` points the runtime at a keyless local endpoint. The runtime is
provider-neutral: point it at OpenAI, Anthropic, or any OpenAI-compatible endpoint. Set
`AI_PROVIDER=openai-compatible` for the vendor-agnostic, self-hosted path (vLLM, Ollama,
LM Studio, and hosted OpenAI-compatible gateways). Keep API keys out of committed files.

| Variable | Default | Purpose |
| --- | --- | --- |
| `AI_PROVIDER` | `openai` | Provider dialect: `openai`, `openai-compatible`, or `anthropic`. Use `openai-compatible` for any endpoint that speaks the OpenAI Chat Completions API. |
| `AI_BASE_URL` | Provider default (required when `AI_AUTH_TYPE=none`) | API base URL. Defaults to `https://api.openai.com` for the `openai` dialects and `https://api.anthropic.com` for `anthropic`. Set your own endpoint for `openai-compatible`; give the provider root or a URL already ending in `/v1`, and the runtime appends `/v1` once without doubling it. Keyless mode (`AI_AUTH_TYPE=none`) has no default and never falls back to a public provider endpoint, so it must be set explicitly. |
| `AI_MODEL` | Provider default | Model identifier. Defaults to `gpt-4o-mini` for the `openai` dialects and `claude-sonnet-4-20250514` for `anthropic`. |
| `AI_API_KEY` | none | Provider-neutral API key. Falls back to `OPENAI_API_KEY` (`openai`/`openai-compatible`) or `ANTHROPIC_API_KEY` (`anthropic`) when unset. |
| `AI_AUTH_TYPE` | `default` | Auth scheme: `default` (Bearer for the `openai` dialects, `x-api-key` for `anthropic`), `bearer`, `header`, or `none`. |
| `AI_AUTH_HEADER` | `Authorization` | Header name, used only when `AI_AUTH_TYPE=header`. |
| `AI_AUTH_PREFIX` | none | Value prefix, used only when `AI_AUTH_TYPE=header`; the header value becomes `<prefix> <key>`, or just `<key>` with no prefix. |
| `AI_REQUEST_TIMEOUT_MS` | `120000` | Per-request timeout in milliseconds. Must be positive. |
| `AI_MAX_RETRIES` | `2` | Retries after a failed request, `0`-`5`. |
| `AI_CAPABILITIES` | Provider default | Comma-separated features the endpoint supports: `json_mode`, `streaming`, `tool_calling`. Defaults to all three for the `openai` dialects and `streaming,tool_calling` for `anthropic`. Narrow it when a compatible endpoint lacks a capability. |

## Operational Rollout Controls

These variables are optional. Ingestion queues use Redis Streams by default.

| Variable | Default | Purpose |
| --- | --- | --- |
| `MONEAT_PROCESS_ROLE` | `all` | Start only one deployable role: `all`, `api`, `scheduler`, `ingestion-worker`, or `workflow-egress`. |
| `INGESTION_PIPELINES` | `all` | Comma-separated ingestion workers to run on an `ingestion-worker`, for example `logs,otlp-traces`. |
| `INGESTION_<PIPELINE>_BATCH_SIZE` | `50` | Redis Streams `XREADGROUP COUNT` batch size for a pipeline. |
| `INGESTION_<PIPELINE>_WORKER_COUNT` | Pipeline default | Override the number of consumers for one pipeline. Per-pipeline processing concurrency still applies. |
| `INGESTION_<PIPELINE>_CLAIM_IDLE_MS` | `300000` | Minimum idle time before `XAUTOCLAIM`; keep above worst-case batch processing time. |
| `INGESTION_<PIPELINE>_MAX_DELIVERIES` | `5` | Delivery count before a stream message is written to the DLQ stream. |
| `INGESTION_QUEUE_MAX_PENDING_ENTRIES` | `250000` | Default hard admission capacity for every primary ingestion stream. Full queues reject requests without trimming. |
| `INGESTION_<PIPELINE>_MAX_PENDING_ENTRIES` | Global default | Override hard admission capacity for one pipeline. |
| `INGESTION_MAX_CONCURRENT_BATCHES` | `2` | Default maximum concurrently processed batches for each ingestion pipeline. |
| `INGESTION_<PIPELINE>_MAX_CONCURRENT_BATCHES` | Global default | Override maximum concurrently processed batches for one pipeline. |
| `INGESTION_<PIPELINE>_STREAM_MAXLEN` | `250000` | Deprecated compatibility alias for that pipeline's admission capacity. Primary streams are no longer trimmed. |
| `INGESTION_<PIPELINE>_DLQ_STREAM_MAXLEN` | `10000` | Approximate maximum length for the DLQ stream. |
| `GOOGLE_ADS_API_VERSION` | `v24` | Google Ads API version used by the connector client. |
| `MONEAT_FEATURE_FLAG_ENVIRONMENT` | `production` | Feature-flag environment this deployment evaluates flags against. Optional. Each deployment must set it to the environment its staged rollout rules (such as native incident response) are configured for; a mismatch resolves flags in the wrong environment. |

Redis Streams are the durable ingestion buffer. Run Redis with persistence and HA appropriate for production, and
alert on pending entries, oldest pending age, and DLQ growth.

Use `scripts/ingestion-load-test.py` to exercise normal mixed intake, bursts, queue saturation, a manually
controlled ClickHouse interruption, and recovery. Run the test against a disposable environment with deliberately
small per-pipeline capacities so the saturation phase produces `429` responses. The test fails on any HTTP `5xx`,
transport failure, missing `Retry-After` header, absent saturation rejection, or lack of accepted recovery traffic.
