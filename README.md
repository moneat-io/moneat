<p align="center">
  <a href="https://moneat.io">
    <img alt="Moneat" src="dashboard/public/favicon.svg" width="96">
  </a>
</p>

<p align="center">
  <em>Open-source, self-hostable observability. Errors, replays, performance, logs, uptime, and incidents — all in one place.</em>
</p>

<p align="center">
  <a href="https://www.gnu.org/licenses/agpl-3.0"><img src="https://img.shields.io/badge/License-AGPLv3-blue.svg?style=flat-square" alt="License: AGPL v3"></a>
  <a href="enterprise/LICENSE"><img src="https://img.shields.io/badge/Enterprise-Proprietary-orange.svg?style=flat-square" alt="Enterprise"></a>
  <a href="https://github.com/moneat-io/moneat/pulls"><img src="https://img.shields.io/badge/PRs-welcome-brightgreen.svg?style=flat-square" alt="PRs Welcome"></a>
  <a href="https://discord.gg/skH5Pstr"><img src="https://img.shields.io/badge/Discord-community-5865F2.svg?style=flat-square&logo=discord&logoColor=white" alt="Discord"></a>
  <a href="https://github.com/moneat-io/moneat/commits"><img src="https://img.shields.io/github/commit-activity/m/moneat-io/moneat?style=flat-square" alt="Commit Activity"></a>
  <a href="https://github.com/moneat-io/moneat/stargazers"><img src="https://img.shields.io/github/stars/moneat-io/moneat?style=flat-square" alt="GitHub Stars"></a>
  <a href="https://github.com/moneat-io/moneat/actions/workflows/test.yml"><img src="https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/moneat-io/moneat/main/badges/coverage-badge.json&style=flat-square" alt="Coverage"></a>
</p>

<p align="center">
  <a href="https://moneat.io/docs"><b>Docs</b></a> ·
  <a href="https://moneat.io/community"><b>Community</b></a> ·
  <a href="https://github.com/moneat-io/moneat/issues/new?labels=enhancement&template=feature_request.md"><b>Feature Request</b></a> ·
  <a href="https://github.com/moneat-io/moneat/issues/new?labels=bug&template=bug_report.md"><b>Bug Report</b></a>
</p>

---

# Moneat: The open-source, self-hostable observability platform.

Moneat is the monitoring tool you wished you had — a self-hostable, Sentry-compatible observability platform that runs on a single VPS. No Kubernetes, no multi-node clusters, no surprise bills. Just Docker Compose on a cost-effective server and you're up. It's fully open source (AGPLv3), with optional enterprise add-ons for teams that need on-call and incident management.

**What you get out of the box:**

- 🐛 [**Error Monitoring**](#-error-monitoring) — Sentry-compatible, drop-in error tracking
- 🎥 [**Session Replay**](#-session-replay) — See exactly what users did before the crash
- ⚡ [**Performance Monitoring**](#-performance-monitoring) — Trace slow transactions and bottlenecks
- 📋 [**Logging**](#-logging) — Centralized, searchable log management
- 🟢 [**Uptime Monitoring & Status Pages**](#-uptime-monitoring--status-pages) — Know when things go down, tell users what's up
- 🤖 [**AI Observability**](#-ai-observability) — Trace and debug LLM calls
- 🚨 [**On-Call & Incident Management**](#-on-call--incident-management-enterprise) — PagerDuty-style escalations (Enterprise)

<p align="center">
  <img src="dashboard/public/screenshots/dashboard.png" alt="Moneat Dashboard" width="800">
</p>

---

## Table of Contents

- [Get Started](#get-started)
- [Features](#features)
  - [Error Monitoring](#-error-monitoring)
  - [Session Replay](#-session-replay)
  - [Performance Monitoring](#-performance-monitoring)
  - [Logging](#-logging)
  - [Uptime Monitoring & Status Pages](#-uptime-monitoring--status-pages)
  - [AI Observability](#-ai-observability)
  - [On-Call & Incident Management (Enterprise)](#-on-call--incident-management-enterprise)
- [Sentry Compatibility](#sentry-compatibility)
- [Architecture](#architecture)
- [Contributing](#contributing)
- [License](#license)

---

## Get Started

### Self-Hosted (Recommended)

Deploy Moneat on any VPS with Docker Compose. No Kubernetes required.

```bash
git clone https://github.com/moneat-io/moneat.git
cd moneat-deploy
# Follow the setup guide in the repo
docker-compose up -d
```

### Development Setup

<details>
<summary><b>Prerequisites</b></summary>

- Docker and Docker Compose
- Java 17+
- Node.js 18+

</details>

```bash
# 1. Start infrastructure (PostgreSQL, ClickHouse, Redis)
docker-compose up -d

# 2. Start the backend (API at localhost:8080)
cd backend && ./gradlew run

# Optional: Start backend with enterprise modules enabled (required for enterprise API/features)
cd backend && ./gradlew run -Penterprise

# 3. Start the dashboard (UI at localhost:5173)
cd dashboard && npm install && npm run dev

# Optional: Start dashboard with enterprise routes/components
cd dashboard && npm run dev:enterprise
```

### Enterprise

Need SSO, on-call schedules, escalation policies, or incident management? Check out the [Enterprise plan](https://moneat.io/pricing) or contact [licensing@moneat.io](mailto:licensing@moneat.io).

---

## Features

### 🐛 Error Monitoring

**Know when things break — and why.**

- Sentry-compatible ingestion — use your existing SDKs, zero migration effort
- Automatic error grouping with smart fingerprinting (exception type, message, stack frames)
- Issue management with resolve, unresolve, and ignore workflows
- Source map upload and release tracking for readable JavaScript stack traces
- Real-time alerting via email, Slack, and webhooks
- ClickHouse-powered analytics for querying millions of events in milliseconds

→ [Error Monitoring docs](https://moneat.io/docs)

<p align="center">
  <img src="dashboard/public/screenshots/error-tracking.png" alt="Error Monitoring" width="800">
</p>

### 🎥 Session Replay

**Understand what users actually did.**

- DOM-based session recordings with privacy controls
- Replay linked directly to error events — jump to the exact moment a crash happened
- Network request waterfall overlay
- Click and navigation breadcrumbs
- Console log capture with filtering

→ [Session Replay docs](https://moneat.io/docs)

<p align="center">
  <img src="dashboard/public/screenshots/session-replay.png" alt="Session Replay" width="800">
</p>

### ⚡ Performance Monitoring

**Find the slow spots.**

- Distributed tracing with transaction and span breakdowns
- Percentile-based latency analysis (p50, p75, p95, p99)
- Database query performance tracking
- Automatic slow-transaction detection with alerting
- Powered by ClickHouse for fast aggregation over high-cardinality data

→ [Performance docs](https://moneat.io/docs)

<p align="center">
  <img src="dashboard/public/screenshots/performance.png" alt="Performance Monitoring" width="800">
</p>

### 📋 Logging

**Centralized logs, actually searchable.**

- Ingest logs from any source via Sentry SDK or OpenTelemetry
- Full-text search across all log entries with ClickHouse
- Log correlation with errors, traces, and sessions
- Severity-level filtering and structured log support
- Retention policies for cost-efficient storage

→ [Logging docs](https://moneat.io/docs)

<p align="center">
  <img src="dashboard/public/screenshots/log-management.png" alt="Log Management" width="800">
</p>

### 🟢 Uptime Monitoring & Status Pages

**Know when things go down. Tell users what's up.**

- HTTP, TCP, and ping monitoring with configurable intervals
- Multi-region uptime checks for global coverage
- Public and private status pages with customizable branding
- Incident communication with automated status updates
- Alerting via email, Slack, and webhooks on downtime events

→ [Uptime docs](https://moneat.io/docs)

<p align="center">
  <img src="dashboard/public/screenshots/uptime.png" alt="Uptime Monitoring" width="400">
  <img src="dashboard/public/screenshots/status-pages.png" alt="Status Pages" width="400">
</p>

### 🤖 AI Observability

**Debug your LLM pipelines.**

- Trace LLM calls with input/output token counts and latency
- Visualize multi-step AI agent chains and tool calls
- Track model performance, cost, and error rates over time
- Compatible with OpenTelemetry-based LLM tracing standards

→ [AI Observability docs](https://moneat.io/docs)

### 🚨 On-Call & Incident Management (Enterprise)

**PagerDuty-style on-call, built right in.**

- On-call schedules with rotation support (daily, weekly, custom)
- Priority-based escalation policies (P0–P5) with business hours
- Incident timeline and full audit trail
- Push notifications, Slack DM integration, and Twilio SMS
- SSO/SAML/OIDC authentication
- Native Slack integration for acknowledgment and resolution from chat

→ [Enterprise pricing](https://moneat.io/pricing) · [Contact sales](mailto:support@moneat.io)

<p align="center">
  <img src="dashboard/public/screenshots/escalation-policies.png" alt="Escalation Policies" width="800">
</p>

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
├── docs/                    # Docusaurus documentation (served at /docs/)
├── enterprise/              # Enterprise features (proprietary license)
│   ├── backend/             #   SSO, on-call, escalation, incidents
│   └── dashboard/           #   Enterprise UI components
├── emails/                  # Maizzle email templates
├── e2e/                     # E2E test apps (web, Android, KMP)
└── docker-compose.yml       # Local development infrastructure
```

### Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Kotlin 1.9.22, Ktor 2.3.7, Exposed ORM |
| Operational DB | PostgreSQL |
| Analytics DB | ClickHouse |
| Cache & Queue | Redis |
| Frontend | React 18, TypeScript, TanStack Router/Query, shadcn/ui, Radix, TailwindCSS, Vite |
| Email Templates | Maizzle |
| Billing | Stripe |
| Notifications | Twilio (SMS), Slack, Push |
| Observability | OpenTelemetry, Sentry self-monitoring |

Production deployment configuration lives in the separate **[moneat-deploy](https://github.com/AElbadworthy/moneat-deploy)** repository.

---

## Contributing

We welcome contributions! Whether it's a bug fix, feature, or documentation improvement — we'd love your help.

1. Read the [Contributing Guide](CONTRIBUTING.md)
2. Sign our [Contributor License Agreement (CLA)](CLA.md)
3. Open a PR 🚀

---

## License

Copyright © 2026 Moneat

Moneat uses a **dual-license model:**

- **Core (AGPLv3)** — All code outside `enterprise/` is licensed under the [GNU Affero General Public License v3.0](LICENSE). Self-host, modify, and redistribute freely — modifications must be shared under the same license when used to provide a network service.

- **Enterprise (Proprietary)** — Code in `enterprise/` is licensed under the [Moneat Enterprise License](enterprise/LICENSE). Free for development and testing; production use requires a [paid subscription](https://moneat.io/pricing).

For licensing questions, contact [licensing@moneat.io](mailto:licensing@moneat.io).
