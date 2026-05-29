# Deployment Notes

## Temporal Workflows Runtime

Moneat bundles Temporal as shared infrastructure for workflow execution. Temporal is not blue/green; it should be
started before backend instances and kept running during backend deploys.

Services added by the self-host compose file:

| Service | Purpose |
| --- | --- |
| `temporal-bootstrap` | Idempotently creates the `temporal` and `temporal_visibility` Postgres databases and the restricted runtime role. |
| `temporal` | Temporal frontend and history services, backed by the existing Postgres instance. |
| `temporal-ui` | Operator UI for local/self-host administration. Keep it internal on production deployments. |

`temporal-bootstrap` owns database creation and password rotation for the dedicated Temporal role. The Temporal server
then runs with `SKIP_DB_CREATE=true`, so its restricted database role only migrates and uses the pre-created
`temporal` and `temporal_visibility` databases.

The backend connects with:

```env
TEMPORAL_TARGET=temporal:7233
TEMPORAL_NAMESPACE=default
```

Workflow payloads are encrypted by the backend Temporal data converter with `WORKFLOWS_TEMPORAL_PAYLOAD_KEY` before
they are persisted in Temporal history. Keep `WORKFLOWS_CONNECTION_KEK`, `WORKFLOWS_SIGNING_KEY`, and
`WORKFLOWS_TEMPORAL_PAYLOAD_KEY` distinct from `JWT_SECRET` and `DATA_SOURCE_ENCRYPTION_KEY`.

Temporal uses the existing Postgres volume, so the existing Postgres backup process must include the `temporal` and
`temporal_visibility` databases. The Temporal Web UI is cluster-wide and not tenant-aware; do not expose it on customer
tenant routes in production.
