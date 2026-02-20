# You Shouldn't Need Five Tools to Know Your App Is Broken — Moneat Blog

Observability has a tooling problem. Not a shortage — an excess.

Most teams running a production application end up stitching together an error tracker, a log aggregator, an uptime monitor, a status page provider, and maybe an incident management tool. Each has its own billing model, its own dashboard, its own alerting rules. The cost adds up. The context stays fragmented. And when something breaks at 2 a.m., you're tabbing between four browser windows trying to correlate a spike in errors with a deploy you can't find in your log viewer.

Today, we're open-sourcing Moneat — a self-hostable, Sentry-compatible observability platform that puts error tracking, session replay, performance monitoring, logs, uptime monitoring, status pages, and AI observability into a single product. The core is licensed under AGPLv3. The code is on [GitHub](https://github.com/moneat-io/moneat).

Here's what that means, concretely, and why we built it this way.

## What Moneat Ships Today

Moneat isn't a roadmap announcement. Everything listed here is implemented, tested, and running in production on [moneat.io](https://moneat.io).

- **Error Monitoring** — Sentry-compatible ingestion with smart fingerprinting, issue management, source map uploads, and release tracking
- **Session Replay** — DOM-based recordings linked directly to error events, with network request overlays and console capture
- **Performance Monitoring** — Distributed tracing with span breakdowns and percentile-based latency analysis (p50, p75, p95, p99)
- **Logging** — Centralized log management via Sentry SDK or OpenTelemetry, with full-text search powered by ClickHouse
- **Uptime Monitoring** — HTTP, TCP, DNS, SSL, ping, WebSocket, database, Docker, and push monitors with configurable intervals down to 10 seconds
- **Status Pages** — Public and private, with custom domain support and automated incident updates
- **AI Observability** — Trace LLM calls with token counts, latency, cost tracking, and multi-step agent chain visualization
- **On-Call & Incident Management** — Rotation schedules, P0–P5 escalation policies, push notifications, and Slack integration (Enterprise tier)

> "The best monitoring switch is the one your code doesn't notice." Moneat implements the Sentry ingestion API — change your DSN, keep your SDKs.

## Sentry Compatibility as a Migration Path

Switching monitoring tools is usually painful. New SDKs, new instrumentation, new alert rules.

Moneat sidesteps this. It implements Sentry's envelope and store ingestion endpoints, so any application already using a Sentry SDK can migrate by changing a single line.

Supported SDKs include JavaScript/TypeScript, Python, Kotlin, Java, Android, iOS, Go, Ruby, and .NET. If the SDK sends to the standard Sentry envelope endpoint, it works.

This matters because the cost of evaluating a new tool drops to near zero. You don't need to re-instrument anything. Point one project's DSN at a Moneat instance, run it for a week, and compare. If it doesn't work for you, revert the DSN. No cleanup required.

The strongest argument isn't cost savings alone. It's that correlated data — where an error links to a session replay links to the deploy that caused it — reduces mean time to resolution. Fragmented tools make that correlation manual.

## Where This Goes

The repository is live. The code is readable. Issues and PRs are open.

If you're spending more than you'd like on monitoring, or if you're frustrated by the gaps between your tools, give Moneat thirty minutes. Clone it, point a staging app at it, and see what you think.

The best way to evaluate an observability tool is to observe something with it.

[GitHub](https://github.com/moneat-io/moneat) · [Docs](https://moneat.io/docs) · [Discord](https://discord.gg/skH5Pstr)
