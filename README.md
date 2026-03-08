<p align="center">
  <a href="https://moneat.io">
    <img alt="Moneat" src="dashboard/public/favicon.svg" width="96">
  </a>
</p>

<p align="center">
  Open-source, self-hostable observability.<br>
  Errors, replays, performance, logs, uptime, and incidents in one place.
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
  <a href="https://moneat.io/pricing"><b>Enterprise</b></a> ·
  <a href="https://discord.gg/Fanh3mem"><b>Discord</b></a> ·
  <a href="https://github.com/moneat-io/moneat/issues/new?labels=bug&template=bug_report.md"><b>Bug Report</b></a> ·
  <a href="https://github.com/moneat-io/moneat/issues/new?labels=enhancement&template=feature_request.md"><b>Feature Request</b></a>
</p>

---

## Self-Hosting

The interactive installer handles version selection, secrets, port allocation, and Docker setup. No need to clone the repo.

**curl:**

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/moneat-io/moneat/main/install.sh)
```

**wget:**

```bash
bash <(wget -qO- https://raw.githubusercontent.com/moneat-io/moneat/main/install.sh)
```

<details>
<summary><b>Manual Setup</b></summary>

```bash
# Download the compose file and env template for a specific release
curl -fsSL https://raw.githubusercontent.com/moneat-io/moneat/v1.0.0/docker-compose.yml -o docker-compose.yml
curl -fsSL https://raw.githubusercontent.com/moneat-io/moneat/v1.0.0/.env.example -o .env.example
cp .env.example .env
# Edit .env — set JWT_SECRET, DATABASE_PASSWORD, CLICKHOUSE_PASSWORD, FRONTEND_URL, BACKEND_URL
docker compose up -d
```

</details>

<details>
<summary><b>Local Development</b></summary>

Run infrastructure in Docker, backend and dashboard locally for hot-reload.

**Prerequisites:** Docker, Java 17+, Node.js 18+

```bash
cp .env.example .env

docker compose up -d postgres clickhouse redis

# Backend (API at localhost:8080)
cd backend && ./gradlew run

# Dashboard (UI at localhost:5173)
cd dashboard && npm install && npm run dev
```

</details>

## Features

Moneat is Sentry SDK and Datadog Agent compatible. Point your existing SDKs and agents at your Moneat instance with no code changes.

| Feature | Description | Docs |
|---------|-------------|------|
| Error Monitoring | Sentry-compatible, drop-in error tracking with smart grouping | [Docs](https://moneat.io/docs) |
| Session Replay | DOM-based recordings linked to error events | [Docs](https://moneat.io/docs) |
| Performance Monitoring | Distributed tracing with transaction and span breakdowns | [Docs](https://moneat.io/docs) |
| Continuous Profiling | Flamegraph visualization (pprof, JFR, Sentry formats) | [Docs](https://moneat.io/docs) |
| Logging | Centralized, searchable log management via ClickHouse | [Docs](https://moneat.io/docs) |
| Uptime & Status Pages | HTTP/TCP/ping checks with public status pages | [Docs](https://moneat.io/docs) |
| Synthetics | API, multi-step, SSL, DNS, TCP, and UDP synthetic tests | [Docs](https://moneat.io/docs) |
| Custom Dashboards | Drag-and-drop widgets, Grafana dashboard import | [Docs](https://moneat.io/docs) |
| Product Analytics | Funnels, retention cohorts, event-based tracking | [Docs](https://moneat.io/docs) |
| Releases | Crash-free rates, regression detection, source map upload | [Docs](https://moneat.io/docs) |
| AI Observability | Trace and debug LLM calls | [Docs](https://moneat.io/docs) |
| MCP Server | Model Context Protocol endpoint for AI coding assistants | [Docs](https://moneat.io/docs) |
| User Feedback | Sentry-compatible feedback ingestion with status workflows | [Docs](https://moneat.io/docs) |
| Datadog Compatibility | Ingest from existing Datadog agents with no code changes | [Docs](https://moneat.io/docs) |
| On-Call & Incidents | PagerDuty-style escalations *(Enterprise)* | [Pricing](https://moneat.io/pricing) |
| Terraform Provider | Manage Moneat resources as code | [Registry](https://registry.terraform.io/providers/moneat-io/moneat/latest) |

### Sentry SDK Compatibility

```javascript
Sentry.init({
  dsn: "https://<key>@<your-moneat-host>/api/<project-id>",
});
```

Works with `@sentry/browser`, `@sentry/node`, `@sentry/react`, `@sentry/nextjs`, `sentry-sdk` (Python), `sentry-kotlin`, `sentry-java`, `sentry-android`, `sentry-cocoa`, `sentry-go`, `sentry-ruby`, `Sentry.NET`, and any SDK that sends to the standard Sentry envelope endpoint.

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


## Telemetry

Self-hosted deployments collect anonymous usage telemetry (CPU count, memory, aggregate event counts, deployment config). No personal data, event contents, or secrets are collected. [Learn more.](https://moneat.io/docs/self-hosting/telemetry)

Opt out:

```bash
TELEMETRY_ENABLED=false
```

## Contributing

1. Read the [Contributing Guide](CONTRIBUTING.md)
2. Sign our [CLA](CLA.md)
3. Open a PR

## License

Copyright &copy; 2026 Moneat

| Directory | License |
|-----------|---------|
| `ee/` | [Moneat Enterprise License](ee/LICENSE) |
| Everything else | [GNU AGPLv3](LICENSE) |

Enterprise modules are gated by a signed license key (`MONEAT_LICENSE_KEY`). Without a valid key, only the open-source core is active. The AGPL does not apply to files in `ee/`.

For licensing questions: [licensing@moneat.io](mailto:licensing@moneat.io)

---

Sentry is a registered trademark of Functional Software, Inc. Datadog is a registered trademark of Datadog, Inc. PagerDuty is a registered trademark of PagerDuty, Inc. Moneat is not affiliated with, endorsed by, or sponsored by any of these companies.
