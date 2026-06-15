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
| `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET` | `STRIPE_ENABLED=true` | Stripe billing. |
| `EXPO_TOKEN` | `ONCALL_ENABLED=true` | Mobile push notifications. |

Workflow secrets must be distinct from `JWT_SECRET` and `DATA_SOURCE_ENCRYPTION_KEY`.

## Operational Rollout Controls

These variables are optional. Ingestion queues use Redis Streams by default.

| Variable | Default | Purpose |
| --- | --- | --- |
| `MONEAT_PROCESS_ROLE` | `all` | Start only one deployable role: `all`, `api`, `scheduler`, `ingestion-worker`, or `workflow-egress`. |
| `INGESTION_PIPELINES` | `all` | Comma-separated ingestion workers to run on an `ingestion-worker`, for example `logs,otlp-traces`. |
| `INGESTION_<PIPELINE>_BATCH_SIZE` | `50` | Redis Streams `XREADGROUP COUNT` batch size for a pipeline. |
| `INGESTION_<PIPELINE>_CLAIM_IDLE_MS` | `300000` | Minimum idle time before `XAUTOCLAIM`; keep above worst-case batch processing time. |
| `INGESTION_<PIPELINE>_MAX_DELIVERIES` | `5` | Delivery count before a stream message is written to the DLQ stream. |
| `INGESTION_<PIPELINE>_STREAM_MAXLEN` | `250000` | Approximate maximum length for the primary stream before Redis trims old acknowledged entries. |
| `INGESTION_<PIPELINE>_DLQ_STREAM_MAXLEN` | `10000` | Approximate maximum length for the DLQ stream. |

Redis Streams are the durable ingestion buffer. Run Redis with persistence and HA appropriate for production, and
alert on pending entries, oldest pending age, and DLQ growth.
