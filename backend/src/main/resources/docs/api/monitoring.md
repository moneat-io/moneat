# Infrastructure Monitoring API

## Endpoints

### GET /v1/monitoring/systems
List all monitored systems/servers for the organization.

### GET /v1/monitoring/systems/{id}
Get system details with latest metrics.

### GET /v1/monitoring/systems/{id}/metrics
Get historical metrics for a system.

**Query parameters:**
| name | type | default | description |
|------|------|---------|-------------|
| range | string | 1h | Time range (1h, 6h, 24h, 7d) |
| metric | string | all | Specific metric (cpu, memory, disk, network) |

### DELETE /v1/monitoring/systems/{id}
Remove a monitored system. **Destructive - requires confirmation.**
