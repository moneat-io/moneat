<p align="center">
  <a href="https://moneat.io">
    <img alt="Moneat" src="dashboard/public/favicon.svg" width="96">
  </a>
</p>

<p align="center">
  <em>Open-source, self-hostable observability. Errors, replays, performance, logs, uptime, and incidents — all in one place.</em>
</p>

<p align="center">
  <a href="#license"><img src="https://img.shields.io/badge/License-AGPLv3%20%2B%20Enterprise-blue.svg?style=flat-square" alt="License: AGPLv3 + Enterprise"></a>
  <a href="https://github.com/moneat-io/moneat/pulls"><img src="https://img.shields.io/badge/PRs-welcome-brightgreen.svg?style=flat-square" alt="PRs Welcome"></a>
  <a href="https://discord.gg/Fanh3mem"><img src="https://img.shields.io/badge/Discord-community-5865F2.svg?style=flat-square&logo=discord&logoColor=white" alt="Discord"></a>
  <a href="https://github.com/moneat-io/moneat/commits"><img src="https://img.shields.io/github/commit-activity/m/moneat-io/moneat?style=flat-square" alt="Commit Activity"></a>
  <a href="https://github.com/moneat-io/moneat/stargazers"><img src="https://img.shields.io/github/stars/moneat-io/moneat?style=flat-square" alt="GitHub Stars"></a>
</p>

<p align="center">
  <a href="https://moneat.io/docs"><b>Docs</b></a> ·
  <a href="https://github.com/moneat-io/moneat/issues/new?labels=enhancement&template=feature_request.md"><b>Feature Request</b></a> ·
  <a href="https://github.com/moneat-io/moneat/issues/new?labels=bug&template=bug_report.md"><b>Bug Report</b></a>
</p>

---

# Moneat: The open-source, self-hostable observability platform.

Moneat is a self-hostable observability platform with Sentry SDK and Datadog Agent compatibility.

- [**Error Monitoring**](#error-monitoring) — Sentry-compatible, drop-in error tracking
- [**Session Replay**](#session-replay) — DOM-based session recordings linked to error events
- [**User Feedback**](#user-feedback) — Sentry-compatible feedback ingestion with status workflows
- [**Performance Monitoring**](#performance-monitoring) — Distributed tracing with transaction and span breakdowns
- [**Continuous Profiling**](#continuous-profiling) — Flamegraph visualization for CPU, heap, and allocation profiles
- [**Logging**](#logging) — Centralized, searchable log management
- [**Uptime Monitoring & Status Pages**](#uptime-monitoring--status-pages) — HTTP/TCP/ping checks with public status pages
- [**Synthetics**](#synthetics) — API, multi-step, SSL, DNS, TCP, and UDP synthetic tests
- [**Custom Dashboards**](#custom-dashboards) — Drag-and-drop widgets with Grafana dashboard import and 15+ datasources
- [**Product Analytics**](#product-analytics) — Funnels, retention cohorts, and event-based tracking
- [**Releases**](#releases) — Release health tracking with crash-free rates and regression detection
- [**AI Observability**](#ai-observability) — Trace and debug LLM calls
- [**MCP Server**](#mcp-server) — Model Context Protocol endpoint for AI coding assistants
- [**On-Call & Incident Management**](#on-call--incident-management-enterprise) — PagerDuty-style escalations (Enterprise)
- [**Datadog Compatibility**](#datadog-compatibility) — Ingest from existing Datadog agents with no code changes

---

## Table of Contents

- [Get Started](#get-started)
- [Features](#features)
  - [Error Monitoring](#error-monitoring)
  - [Session Replay](#session-replay)
  - [User Feedback](#user-feedback)
  - [Performance Monitoring](#performance-monitoring)
  - [Continuous Profiling](#continuous-profiling)
  - [Logging](#logging)
  - [Uptime Monitoring & Status Pages](#uptime-monitoring--status-pages)
  - [Synthetics](#synthetics)
  - [Custom Dashboards](#custom-dashboards)
  - [Product Analytics](#product-analytics)
  - [Releases](#releases)
  - [AI Observability](#ai-observability)
  - [MCP Server](#mcp-server)
  - [On-Call & Incident Management (Enterprise)](#on-call--incident-management-enterprise)
  - [Datadog Compatibility](#datadog-compatibility)
  - [Terraform Provider](#terraform-provider)
- [Sentry Compatibility](#sentry-compatibility)
- [Architecture](#architecture)
- [Screenshots](#screenshots)
- [Contributing](#contributing)
- [License](#license)

---

## Get Started

### Self-Hosted

Deploy Moneat with Docker Compose using pre-built images from GitHub Container Registry. See the [deployment repository](https://github.com/moneat-io/moneat-deploy) for production compose files and setup instructions.

The self-hosted image includes the open-source core (AGPLv3). Enterprise modules (SSO, On-Call) are present but **inactive by default** — they require a valid `MONEAT_LICENSE_KEY` and are subject to the [Moneat Enterprise License](ee/LICENSE), not the AGPL. See [License](#license) for details.

Multi-architecture Docker images (amd64 + arm64) are published on every release:

| Image | Registry |
|-------|----------|
| Backend | `ghcr.io/moneat-io/moneat-backend` |
| Dashboard | `ghcr.io/moneat-io/moneat-dashboard` |

Versioned releases and changelogs are available on the [Releases page](https://github.com/moneat-io/moneat/releases).

### Telemetry

Self-hosted Moneat deployments collect anonymous usage telemetry to help us understand how Moneat is deployed and which features are most valuable. This data is never tied to individual users — only to a randomly-generated deployment identifier.

**What we collect:**
- System info: CPU count, memory usage, OS, JVM version
- Aggregate counts: projects, users, events, issues
- Deployment config: whether SSL is enabled

**What we don't collect:**
- No user emails, names, or any personal data
- No event contents, stack traces, or session replays
- No API keys or secrets

To opt out, set the following in your `.env`:

```bash
TELEMETRY_ENABLED=false
```

### Development Setup

For active development, run infrastructure in Docker but the backend and dashboard locally for hot-reload.

<details>
<summary><b>Prerequisites</b></summary>

- Docker and Docker Compose
- Java 17+
- Node.js 18+

</details>

```bash
# 1. Create your local environment config
cp .env.example .env

# 2. Start infrastructure only (PostgreSQL, ClickHouse, Redis)
docker compose up -d postgres clickhouse redis

# 3. Start the backend with hot-reload (API at localhost:8080)
cd backend && ./gradlew run

# 4. Start the dashboard with hot-reload (UI at localhost:5173)
cd dashboard && npm install && npm run dev
```

### Enterprise

Need SSO, on-call, or incident management? Check out the [Enterprise plan](https://moneat.io/pricing) or contact [support@moneat.io](mailto:support@moneat.io).

---

## Features

### Error Monitoring

- Sentry-compatible ingestion — use your existing SDKs, zero migration effort
- Automatic error grouping with smart fingerprinting (exception type, message, stack frames)
- Issue management with resolve, unresolve, and ignore workflows
- Source map upload and release tracking for readable JavaScript stack traces
- Real-time alerting via email, Slack, and webhooks
- ClickHouse-powered analytics

→ [Error Monitoring docs](https://moneat.io/docs)

### Session Replay

- DOM-based session recordings with privacy controls
- Replay linked directly to error events — jump to the exact moment a crash happened
- Network request waterfall overlay
- Click and navigation breadcrumbs
- Console log capture with filtering

→ [Session Replay docs](https://moneat.io/docs)

### User Feedback

- Sentry-compatible feedback ingestion via envelope format
- Status workflow: unresolved → resolved → archived, with bulk operations
- Links feedback to associated error events and session replays
- Captures message, contact email, URL, environment, release, and tags
- Stored in ClickHouse

→ [User Feedback docs](https://moneat.io/docs)

### Performance Monitoring

- Distributed tracing with transaction and span breakdowns
- Percentile-based latency analysis (p50, p75, p95, p99)
- Database query performance tracking
- Automatic slow-transaction detection with alerting
- Powered by ClickHouse for fast aggregation over high-cardinality data

→ [Performance docs](https://moneat.io/docs)

### Continuous Profiling

- CPU, heap, allocation, wall-clock, goroutine, mutex, and block profile ingestion
- Flamegraph visualization in the dashboard (pprof, JFR, and Sentry profile formats)
- Supports both Sentry SDK and Datadog agent as profile sources
- Filter by service, environment, and profile type

→ [Profiling docs](https://moneat.io/docs)

### Logging

- Ingest logs from any source via Sentry SDK or OpenTelemetry
- Full-text search across all log entries with ClickHouse
- Log correlation with errors, traces, and sessions
- Severity-level filtering and structured log support
- Retention policies for cost-efficient storage

→ [Logging docs](https://moneat.io/docs)

### Uptime Monitoring & Status Pages

- HTTP, TCP, and ping monitoring with configurable intervals
- Multi-region uptime checks for global coverage
- Public and private status pages with customizable branding
- Incident communication with automated status updates
- Alerting via email, Slack, and webhooks on downtime events

→ [Uptime docs](https://moneat.io/docs)

### Synthetics

- Synthetic test types: API, multi-step, SSL certificate, DNS, TCP, and UDP
- Multi-step tests with variable extraction between steps (JSON path, header)
- Assertions: status code, body content, JSON path, response time, header values, certificate expiry, DNS resolution, connection time
- Configurable intervals (1–60 min), timeouts, retries, and alert-on-failure
- Results stored in ClickHouse with uptime %, average and P95 response time

→ [Synthetics docs](https://moneat.io/docs)

### Custom Dashboards

- Drag-and-drop widget builder with charts, tables, and counters
- Query any data stored in ClickHouse — errors, logs, spans, and custom events
- Pre-built templates for common use cases (release health, API performance, infrastructure)
- Share dashboards across your team or make them personal
- Auto-refreshing dashboards for live monitoring on big screens

→ [Custom Dashboards docs](https://moneat.io/docs)

### Product Analytics

- Event-based tracking with custom properties and user identification
- Funnel analysis to measure conversion across multi-step flows
- Retention cohort charts to track user engagement over time
- Trend analysis with breakdowns by any event property
- Correlates with error and performance data

→ [Product Analytics docs](https://moneat.io/docs)

### Releases

- Release health tracking: crash-free rate, event count, new issues, affected users
- Automatic release detection from ingested events or explicit creation via Sentry CLI
- Per-release detail view with events-over-time chart, events by level, and top issues
- Source map upload for readable JavaScript stack traces per release

→ [Releases docs](https://moneat.io/docs)

### AI Observability

- Trace LLM calls with input/output token counts and latency
- Visualize multi-step AI agent chains and tool calls
- Track model performance, cost, and error rates over time
- Compatible with OpenTelemetry-based LLM tracing standards

→ [AI Observability docs](https://moneat.io/docs)

### MCP Server

- Model Context Protocol (MCP) endpoint for AI coding assistants (Cursor, GitHub Copilot, etc.)
- SSE and JSON-RPC transport with API token authentication
- 30+ tools: query issues, logs, traces, hosts, dashboards, alerts, uptime monitors, releases
- Write operations: update issue status, create dashboards, create uptime monitors, manage hosts
- Resources: organization overview, project list, host status, active alerts, infrastructure health

→ [MCP Server docs](https://moneat.io/docs)

### On-Call & Incident Management (Enterprise)

- On-call schedules with rotation support (daily, weekly, custom)
- Priority-based escalation policies (P0–P5) with business hours
- Incident timeline and full audit trail
- Push notifications, Slack DM integration, and Twilio SMS
- SSO/SAML/OIDC authentication
- Native Slack integration for acknowledgment and resolution from chat

→ [Enterprise pricing](https://moneat.io/pricing) · [Contact sales](mailto:support@moneat.io)

---

### Datadog Compatibility

Moneat implements the Datadog agent ingestion protocol, so you can redirect any existing Datadog agent deployment to your self-hosted Moneat instance. Configure your agent's `dd_url` / `apm_config.apm_dd_url` to point at your Moneat host.

**Metrics**
- Full metrics API compatibility (v1, v2, v3 series) — gauges, counts, rates
- DogStatsD proxy endpoint for application-emitted custom metrics
- Distribution metrics and sketches (percentile aggregation)

**APM & Tracing**
- Trace ingestion across all agent wire formats (v0.3–v0.7, msgpack and JSON)
- Trace stats aggregation for service-level throughput and error rates
- Service dependency map — visualize upstream/downstream call graphs
- Trace detail view with span waterfall, tags, and resource breakdown

**Dynamic Instrumentation (Live Debugger)**
- Probe types: log probes, snapshots, span decorations, and metric probes
- Create and manage probes by file/line or type/method
- Agent-reported probe status (received, installed, emitting, error, blocked)
- Ingestion via Datadog debugger API (v1 and v2)

**Logs**
- Log ingestion via the Datadog agent log collector (v2 API)
- Full-text search, severity filtering, and retention policies — same pipeline as Sentry logs

**Infrastructure**
- Host metadata collection (OS, platform, CPU, memory, agent version)
- Per-host metric history — CPU, memory, disk, network, load (5-minute buckets)
- Host metric alerts with configurable thresholds and duration
- Container stats — name, image, state, CPU, memory, and network I/O
- Process list with CPU/memory, command, user, thread count, and open file descriptors
- Network connections with protocol, direction, and byte counters
- SBOM inventory — packages per host/image with CVE tracking

**Events & Service Checks**
- Infrastructure events (v1 `check_run` and v2 `events` / `service_checks`)
- Filterable event timeline in the dashboard

**Database Monitoring (DBM)**
- Query samples and execution plans from the Datadog Database Monitoring agent
- Query metrics aggregation and active session tracking
- Schema and metadata ingestion for query explain support

**Network Device Monitoring (NDM)**
- SNMP device discovery — vendor, model, OS version, reachability
- SNMP trap ingestion with severity and OID
- NetFlow / sFlow network flow records
- Network path topology (traceroute-style hops and RTTs)

**Cloud Security Management (CSM)**
- Runtime security event ingestion with rule name, category, and severity
- Activity dump collection for forensic process trees
- Compliance finding ingestion (CIS, PCI-DSS, SOC 2, etc.) with pass/fail/skip status
- Compliance summary view grouped by framework

**Kubernetes Orchestration**
- Kubernetes resource and manifest payloads from the Datadog orchestrator check

### Terraform Provider

Manage your Moneat infrastructure as code with the official Terraform provider. Configure projects, uptime monitors, status pages, dashboards, alerts, and more — all from your Terraform configurations.

```hcl
resource "moneat_project" "backend" {
  name     = "backend-api"
  platform = "python"
}

resource "moneat_uptime_monitor" "api_health" {
  name             = "API Health Check"
  url              = "https://api.example.com/health"
  type             = "http"
  interval_seconds = 60
}
```

- **[Terraform Registry](https://registry.terraform.io/providers/moneat-io/moneat/latest)** — Install and docs
- **[GitHub Repository](https://github.com/moneat-io/terraform-provider-moneat)** — Source code and issues
- **[Provider Documentation](https://registry.terraform.io/providers/moneat-io/moneat/latest/docs)** — Full resource reference

---

## Sentry Compatibility

Moneat implements the **Sentry ingestion API** (envelope and legacy store endpoints), so you can use existing Sentry SDKs with zero code changes. Just point your DSN at your Moneat instance.

```javascript
// Example: JavaScript SDK — just change the DSN
Sentry.init({
  dsn: "https://<key>@<your-moneat-host>/api/<project-id>",
});
```

**Supported SDKs:**

| Platform | SDK |
|----------|-----|
| JavaScript / TypeScript | `@sentry/browser`, `@sentry/node`, `@sentry/react`, `@sentry/nextjs` |
| Python | `sentry-sdk` |
| Kotlin / Java | `sentry-kotlin`, `sentry-java` |
| Android | `sentry-android` |
| iOS / macOS | `sentry-cocoa` |
| Go | `sentry-go` |
| Ruby | `sentry-ruby` |
| .NET | `Sentry.NET` |

Any SDK that sends to the standard Sentry envelope endpoint should work out of the box.

---

## Architecture

```
moneat/
├── backend/                 # Kotlin/Ktor backend (AGPLv3)
├── dashboard/               # React frontend (AGPLv3)
├── ee/                      # Enterprise modules (Moneat Enterprise License — see ee/LICENSE)
├── docs/                    # Docusaurus documentation (served at /docs/)
├── emails/                  # Maizzle email templates
├── e2e/                     # E2E test apps (web, Android, KMP)
└── docker-compose.yml       # Local development infrastructure
```

### Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Kotlin 2.3.x, Ktor 3.4.x, Exposed |
| Operational DB | PostgreSQL 18 |
| Analytics DB | ClickHouse 26 |
| Cache & Queue | Redis |
| Frontend | React 18, TypeScript, TanStack Router/Query, shadcn/ui, Radix, TailwindCSS, Vite |
| Email Templates | Maizzle |
| Observability | OpenTelemetry, Sentry self-monitoring |

---

## Screenshots

<p align="center">
  <img src="dashboard/public/screenshots/dashboard.png" alt="Moneat Dashboard" width="800">
</p>

<p align="center">
  <img src="dashboard/public/screenshots/error-tracking.png" alt="Error Monitoring" width="800">
</p>

<p align="center">
  <img src="dashboard/public/screenshots/session-replay.png" alt="Session Replay" width="800">
</p>

<p align="center">
  <img src="dashboard/public/screenshots/performance.png" alt="Performance Monitoring" width="800">
</p>

<p align="center">
  <img src="dashboard/public/screenshots/log-management.png" alt="Log Management" width="800">
</p>

<p align="center">
  <img src="dashboard/public/screenshots/uptime.png" alt="Uptime Monitoring" width="400">
  <img src="dashboard/public/screenshots/status-pages.png" alt="Status Pages" width="400">
</p>

<p align="center">
  <img src="dashboard/public/screenshots/escalation-policies.png" alt="Escalation Policies" width="800">
</p>

---

## Contributing

We welcome contributions! Whether it's a bug fix, feature, or documentation improvement — we'd love your help.

1. Read the [Contributing Guide](CONTRIBUTING.md)
2. Sign our [Contributor License Agreement (CLA)](CLA.md)
3. Open a PR

---

## License

Copyright © 2026 Moneat

This repository uses a **dual-license model**:

| Directory | License | Details |
|-----------|---------|---------|
| `ee/` | [Moneat Enterprise License](ee/LICENSE) | Source-available; production use requires a paid subscription |
| Everything else | [GNU Affero General Public License v3.0](LICENSE) | Free to self-host, modify, and redistribute; modifications must be shared under the same license when used to provide a network service |

The enterprise modules (`ee/`) are loaded at runtime via Java ServiceLoader and are gated by a signed license key (`MONEAT_LICENSE_KEY`). Without a valid key, only the open-source core is active. The two codebases are separate works — the AGPL does not apply to files in `ee/`.

For licensing questions, contact [licensing@moneat.io](mailto:licensing@moneat.io).

---

## Trademarks

Sentry is a registered trademark of Functional Software, Inc. Datadog is a registered trademark of Datadog, Inc. PagerDuty is a registered trademark of PagerDuty, Inc. All other trademarks are the property of their respective owners. Moneat is not affiliated with, endorsed by, or sponsored by any of these companies. Use of these names is solely for the purpose of describing compatibility.
