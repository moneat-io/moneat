// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

export type CompetitorSlug = 'datadog' | 'sentry' | 'better-stack' | 'signoz'
export type CompetitorRoute =
  | '/datadog-alternative'
  | '/sentry-alternative'
  | '/better-stack-alternative'
  | '/signoz-alternative'

interface SourceLink {
  label: string
  href: string
}

export interface ComparisonShortVersionRow {
  dimension: string
  moneat: string
  competitor: string
}

export interface ComparisonFeatureRow {
  label: string
  moneat: string
  competitor: string
  note?: string
}

export interface ComparisonCostRow {
  scenario: string
  moneat: string
  competitor: string
}

export interface ComparisonMisconception {
  myth: string
  reality: string
}

export interface ComparisonMigrationStep {
  title: string
  detail: string
}

export interface CompetitorPageData {
  slug: CompetitorSlug
  name: string
  route: CompetitorRoute
  title: string
  h1: string
  description: string
  metaDescription: string
  heroImage: string
  heroImageAlt: string
  lede: string
  summary: string
  shortVersionRows: ComparisonShortVersionRow[]
  bestFor: string[]
  competitorStrengths: string[]
  chooseCompetitor: string[]
  chooseMoneat: string[]
  misconceptions: ComparisonMisconception[]
  migrationSteps: ComparisonMigrationStep[]
  proofPoints: Array<{label: string; value: string}>
  featureRows: ComparisonFeatureRow[]
  costRows: ComparisonCostRow[]
  caveats: string[]
  sources: SourceLink[]
}

export const SOURCE_REVIEW_DATE = 'May 26, 2026'

export const moneatPricingSummary =
  'Moneat pricing starts at free, then $29 Pro and $79 Team, with custom Enterprise pricing for larger teams. ' +
  'Core telemetry is covered by tier ingestion, with paid overage at $0.40/GB. ' +
  'APM spans, custom metrics, infrastructure metric series-hours, and analytics pageviews have separate ' +
  'limits and overages: $0.30/1M spans, $0.50/100K custom metrics, $0.10/100K infra series-hours, ' +
  'and $10/100K pageviews on paid tiers where overage applies.'

const moneatSource: SourceLink = {
  label: 'Moneat pricing',
  href: '/pricing',
}

const comparisonHeroImage = '/marketing/observability-comparison-hero-a.webp'
const comparisonHeroImageAlt =
  'Light observability platform illustration with traces, logs, metrics, uptime, and incident response panels'

export const competitorPages: CompetitorPageData[] = [
  {
    slug: 'datadog',
    name: 'Datadog',
    route: '/datadog-alternative',
    title: 'Datadog Alternative',
    h1: 'Datadog Alternative',
    description:
      'Moneat gives infrastructure teams logs, APM, profiling, errors, replay, uptime, status pages, ' +
      'and on-call without Datadog-style per-host multipliers.',
    metaDescription:
      'Compare Moneat with Datadog in 2026. See pricing model differences, Datadog Agent compatibility, ' +
      'APM, logs, profiling, infrastructure monitoring, and on-call costs.',
    heroImage: comparisonHeroImage,
    heroImageAlt: comparisonHeroImageAlt,
    lede:
      'Datadog is the default answer for many infrastructure teams, but the bill can become hard to ' +
      'forecast once infrastructure, APM, profiling, logs, containers, and responders are all metered separately.',
    summary:
      'Choose Moneat when Datadog gives you broad coverage but the bill grows with every host, ' +
      'indexed log event, span, replay, and incident responder. Moneat keeps the migration practical ' +
      'with Datadog Agent compatibility and an open-source deployment path.',
    shortVersionRows: [
      {
        dimension: 'Best fit',
        moneat: 'Teams that want production observability and response without per-host multipliers.',
        competitor: 'Large enterprises that need Datadog breadth, security products, and mature integrations.',
      },
      {
        dimension: 'Migration',
        moneat: 'Point Datadog Agent data, Sentry SDK events, or OTLP telemetry at Moneat.',
        competitor: 'Stay inside Datadog agents, APIs, dashboards, and product-specific configuration.',
      },
      {
        dimension: 'Bill model',
        moneat: 'Tiered plans plus core ingestion overage and separate limits for spans, metrics, infra series, and pageviews.',
        competitor: 'Separate host, container, product, indexed-event, and responder line items.',
      },
      {
        dimension: 'Main caveat',
        moneat: 'You trade some Datadog enterprise/security depth for a simpler operational platform.',
        competitor: 'You keep the deepest ecosystem, but cost planning usually needs SKU-by-SKU modeling.',
      },
    ],
    bestFor: [
      'Teams replacing host-based observability bills',
      'Stacks already running the Datadog Agent',
      'Operators who want observability plus incident response in one product',
    ],
    competitorStrengths: [
      'Very broad enterprise ecosystem',
      'Large integration catalog',
      'Deep security and cloud-management add-ons',
    ],
    chooseCompetitor: [
      'You need Datadog Cloud SIEM, cloud security, cloud cost, or compliance products.',
      'Your organization has already standardized incident, dashboard, and integration workflows in Datadog.',
      'You can justify separate per-host and per-product costs for broad enterprise depth.',
    ],
    chooseMoneat: [
      'You want Datadog Agent compatibility without a per-host APM/profiling/network multiplier.',
      'You need errors, logs, APM, profiling, infrastructure, uptime, status pages, and on-call in one product.',
      'You want an open-source and self-hostable path for data ownership or cost control.',
    ],
    misconceptions: [
      {
        myth: 'Datadog log ingest pricing is the whole log bill.',
        reality:
          'Datadog separates log ingestion from indexed log event pricing, so retention and indexing choices matter.',
      },
      {
        myth: 'A low infra host price means the total Datadog bill stays low.',
        reality:
          'APM, profiling, network monitoring, containers, indexed spans, logs, and on-call can each add their own meter.',
      },
      {
        myth: 'A Datadog replacement means rewriting all telemetry.',
        reality: 'Moneat can receive Datadog Agent data as well as OTLP and Sentry-compatible events.',
      },
    ],
    migrationSteps: [
      {
        title: 'Mirror one service first',
        detail: 'Send a low-risk Datadog Agent workload to Moneat and compare host, log, trace, and alert coverage.',
      },
      {
        title: 'Recreate the operational path',
        detail: 'Move the monitors that page humans into Moneat uptime, status page, and on-call workflows.',
      },
      {
        title: 'Model the invoice',
        detail: 'Use current host, container, indexed event, span, and responder counts before replacing a full Datadog estate.',
      },
    ],
    proofPoints: [
      {label: 'Moneat Pro', value: '$29/mo'},
      {label: 'Datadog infra', value: '$15/host/mo'},
      {label: 'On-call delta', value: '$5 vs $20/seat'},
    ],
    featureRows: [
      {
        label: 'Migration path',
        moneat: 'Accepts Datadog Agent data, Sentry SDK events, and OTLP telemetry.',
        competitor: 'Native Datadog agents, APIs, and product-specific configuration.',
      },
      {
        label: 'Pricing model',
        moneat: 'Tiered plans with no per-host fee for standard host coverage.',
        competitor: 'Per-host, per-product, per-container, per-indexed-event, and per-seat line items.',
      },
      {
        label: 'Infrastructure',
        moneat: 'Host and infrastructure monitoring are included on paid plans.',
        competitor: 'Infrastructure Pro is listed at $15 per infra host per month on annual billing.',
      },
      {
        label: 'APM',
        moneat: 'APM spans have tier limits, with paid overage at $0.30 per 1M spans.',
        competitor: 'APM is listed at $31 per APM host per month, with indexed span charges separate.',
      },
      {
        label: 'Logs',
        moneat: 'Core telemetry overage is a flat $0.40/GB on paid plans.',
        competitor: 'Log ingestion is $0.10/GB, with indexed log event pricing by retention window.',
      },
      {
        label: 'On-call',
        moneat: 'On-call is $5/user/month when enabled.',
        competitor: 'Datadog On-Call is listed at $20/seat/month on annual billing.',
      },
    ],
    costRows: [
      {
        scenario: '20 hosts with infrastructure, APM, profiling, and network monitoring',
        moneat: 'A paid Moneat plan covers host monitoring without per-host multipliers.',
        competitor:
          'Datadog annual rates list $15 infra, $31 APM, $19 profiler, and $5 network per host.',
      },
      {
        scenario: '100GB logs with standard indexing',
        moneat: 'Included on Team before overage; paid ingestion overage is $0.40/GB.',
        competitor:
          'Datadog lists $0.10/GB ingestion plus indexed log event charges by retention.',
      },
      {
        scenario: '5 on-call responders',
        moneat: '$25/month.',
        competitor: '$100/month on annual Datadog On-Call rates.',
      },
    ],
    caveats: [
      'Datadog has a much larger enterprise integration and security product ecosystem.',
      'Datadog may still fit teams that need its deepest cloud security, SIEM, or cloud-cost products.',
      'Moneat is strongest when the goal is production observability and response without per-host pricing.',
    ],
    sources: [
      moneatSource,
      {label: 'Datadog pricing', href: 'https://www.datadoghq.com/pricing/list/'},
    ],
  },
  {
    slug: 'sentry',
    name: 'Sentry',
    route: '/sentry-alternative',
    title: 'Sentry Alternative',
    h1: 'Sentry Alternative',
    description:
      'Moneat is a Sentry-compatible alternative for teams that want error monitoring, replay, logs, ' +
      'APM, infrastructure, uptime, status pages, and on-call in one product.',
    metaDescription:
      'Compare Moneat with Sentry in 2026. Review Sentry SDK compatibility, error monitoring, logs, ' +
      'session replay, tracing, uptime, status pages, and incident response.',
    heroImage: comparisonHeroImage,
    heroImageAlt: comparisonHeroImageAlt,
    lede:
      'Sentry is strong when the primary problem is developer debugging. The question is what happens ' +
      'when the same team also needs logs, infrastructure context, uptime, status pages, and incident response.',
    summary:
      'Choose Moneat when Sentry already fits your application code but you also need the operating ' +
      'surface around it: logs, infrastructure, uptime checks, status pages, on-call, and Datadog or ' +
      'OTLP ingestion.',
    shortVersionRows: [
      {
        dimension: 'Best fit',
        moneat: 'Teams that want Sentry-compatible error intake plus production operations in one place.',
        competitor: 'Developer teams focused on Sentry-native error triage, debugging, and SDK workflows.',
      },
      {
        dimension: 'Migration',
        moneat: 'Keep existing Sentry SDKs and point the DSN at Moneat for compatible event ingestion.',
        competitor: 'Use Sentry-hosted issue workflows, quotas, integrations, and Seer features.',
      },
      {
        dimension: 'Bill model',
        moneat: 'Tiered plans with larger core ingestion buckets and separate usage controls for spans, metrics, and analytics.',
        competitor: 'Base plan plus category quotas for errors, logs, metrics, spans, replays, monitors, and profiles.',
      },
      {
        dimension: 'Main caveat',
        moneat: 'Moneat is broader operationally, but does not claim every Sentry-native debugging workflow.',
        competitor: 'Sentry remains excellent for teams whose center of gravity is application debugging.',
      },
    ],
    bestFor: [
      'Teams already using Sentry SDKs',
      'Teams that need error tracking and infrastructure context together',
      'Teams that want a self-hostable Sentry-compatible migration path',
    ],
    competitorStrengths: [
      'Deep developer workflow around errors',
      'Large SDK ecosystem',
      'Mature tracing, replay, profiling, and AI debugging surfaces',
    ],
    chooseCompetitor: [
      'Your team lives in Sentry issue triage and wants the most mature Sentry-native debugging workflow.',
      'You only need application errors, tracing, replay, and profiling rather than infrastructure and response.',
      'You want Sentry Seer and related AI debugging features inside the Sentry workflow.',
    ],
    chooseMoneat: [
      'You want to keep Sentry SDKs but add logs, infrastructure, uptime, status pages, and on-call.',
      'You need one operating surface for application debugging and production response.',
      'You want Sentry-compatible ingestion plus OTLP and Datadog Agent migration paths.',
    ],
    misconceptions: [
      {
        myth: 'Sentry-compatible means every Sentry workflow is identical.',
        reality:
          'Compatibility lowers ingestion risk, but product workflows, issue triage, and AI debugging surfaces still differ.',
      },
      {
        myth: 'Sentry pricing is just the base plan.',
        reality:
          'Logs, errors, spans, replays, monitors, profiling hours, and Seer usage each have their own quota or add-on behavior.',
      },
      {
        myth: 'Error tracking is enough observability for production operations.',
        reality:
          'Most incident workflows also need infrastructure, uptime, logs, status communication, and responder escalation.',
      },
    ],
    migrationSteps: [
      {
        title: 'Start with one DSN swap',
        detail: 'Point one service at Moneat and verify grouping, release context, source maps, and alert behavior.',
      },
      {
        title: 'Add operating context',
        detail: 'Bring in logs, traces, hosts, and uptime checks around the same service before changing broader SDK rollout.',
      },
      {
        title: 'Move response workflows last',
        detail: 'Only replace paging and customer status communication after the debugging signal is trusted.',
      },
    ],
    proofPoints: [
      {label: 'SDK migration', value: 'Change DSN'},
      {label: 'Moneat Pro', value: '50GB ingestion'},
      {label: 'Sentry Team', value: '5GB logs'},
    ],
    featureRows: [
      {
        label: 'SDK compatibility',
        moneat: 'Works with existing Sentry SDKs by pointing the DSN at Moneat.',
        competitor: 'Native Sentry SDKs and Sentry-hosted product workflows.',
      },
      {
        label: 'Error monitoring',
        moneat: 'Error tracking is part of a broader observability and response workspace.',
        competitor: 'Sentry Team and Business list 50K errors in the public pricing table.',
      },
      {
        label: 'Logs',
        moneat: 'Pro includes 50GB of core telemetry ingestion.',
        competitor: 'Sentry Team and Business list 5GB logs plus $0.50/GB additional.',
      },
      {
        label: 'Tracing',
        moneat: 'APM spans have tier limits, plus OTLP and Datadog-compatible ingestion paths.',
        competitor: 'Sentry lists 5M spans for Developer, Team, and Business.',
      },
      {
        label: 'Session replay',
        moneat: 'Session replay is included as part of the Moneat product surface.',
        competitor: 'Sentry lists 50 replays for Developer, Team, and Business.',
      },
      {
        label: 'Operations',
        moneat: 'Uptime monitoring, status pages, and on-call live in the same product.',
        competitor: 'Sentry pricing lists uptime monitors and cron monitors as separate quota rows.',
      },
    ],
    costRows: [
      {
        scenario: 'Developer team moving from errors into logs and infrastructure',
        moneat: 'Pro starts at $29/month with 50GB core ingestion and infrastructure coverage.',
        competitor: 'Sentry Team is listed at $26/month annually with 5GB logs and 50K errors.',
      },
      {
        scenario: 'Adding uptime and incident response',
        moneat: 'Uptime, status pages, and $5/user/month on-call are in the same product family.',
        competitor: 'Sentry pricing lists uptime and cron quotas, but not a bundled on-call seat.',
      },
      {
        scenario: 'Keeping Sentry SDKs',
        moneat: 'Change the DSN and keep the familiar SDK instrumentation path.',
        competitor: 'Staying on Sentry keeps the native hosted workflow and ecosystem.',
      },
    ],
    caveats: [
      'Sentry is excellent for developer-focused error debugging and has a mature SDK ecosystem.',
      'Teams that only need Sentry-native debugging workflows may prefer staying with Sentry.',
      'Moneat is strongest when Sentry-compatible ingestion needs logs, infra, uptime, and response.',
    ],
    sources: [
      moneatSource,
      {label: 'Sentry pricing', href: 'https://sentry.io/pricing/'},
    ],
  },
  {
    slug: 'better-stack',
    name: 'Better Stack',
    route: '/better-stack-alternative',
    title: 'Better Stack Alternative',
    h1: 'Better Stack Alternative',
    description:
      'Moneat is a Better Stack alternative for teams that want Sentry-compatible errors, ' +
      'Datadog Agent ingestion, OTLP telemetry, infrastructure views, and incident response together.',
    metaDescription:
      'Compare Moneat with Better Stack in 2026. Review on-call pricing, logs, traces, session replay, ' +
      'status pages, Sentry compatibility, Datadog Agent compatibility, and open-source deployment.',
    heroImage: comparisonHeroImage,
    heroImageAlt: comparisonHeroImageAlt,
    lede:
      'Better Stack has moved beyond simple uptime monitoring into incidents, telemetry, error tracking, ' +
      'session replay, web events, and AI-assisted workflows. The decision is whether you want that managed ' +
      'suite or a Moneat migration path that also covers Sentry SDKs, Datadog Agent data, and self-hosting.',
    summary:
      'Choose Moneat when Better Stack has the incident and telemetry pieces you like, but you also ' +
      'want a Sentry-compatible and Datadog-Agent-compatible replacement path with open-source control.',
    shortVersionRows: [
      {
        dimension: 'Best fit',
        moneat: 'Teams replacing multiple observability tools while keeping migration paths open.',
        competitor: 'Teams buying managed uptime, status, incident, telemetry, replay, and web-event workflows.',
      },
      {
        dimension: 'Migration',
        moneat: 'Sentry SDK, Datadog Agent, and OTLP are first-class entry points.',
        competitor: 'Better Stack lists Sentry-compatible error tracking and imports for tools like Datadog and Grafana.',
      },
      {
        dimension: 'Bill model',
        moneat: 'Base plan plus core ingestion and separate overages for spans, metrics, infra series, and pageviews.',
        competitor: 'Responder licenses, telemetry usage, status-page add-ons, monitors, replay, and web events.',
      },
      {
        dimension: 'Main caveat',
        moneat: 'Moneat is strongest when open-source control and migration compatibility matter.',
        competitor: 'Better Stack can be compelling when you want a polished hosted incident and monitoring suite.',
      },
    ],
    bestFor: [
      'Teams comparing incident tooling and observability together',
      'Teams that want lower-cost responder seats',
      'Teams migrating from Sentry SDKs or the Datadog Agent',
    ],
    competitorStrengths: [
      'Strong uptime, status page, and incident management packaging',
      'Generous free tier for personal projects',
      'Current pricing includes error tracking, session replay, web events, and AI SRE options',
    ],
    chooseCompetitor: [
      'You want a hosted incident-management-first suite with polished status pages and responder workflows.',
      'You value Better Stack web events, replay, and AI SRE packaging more than self-hosting.',
      'Your team prefers modular hosted add-ons over operating an open-source observability stack.',
    ],
    chooseMoneat: [
      'You need Sentry-compatible, Datadog-Agent-compatible, and OTLP migration paths in one platform.',
      'You want lower-cost on-call seats for teams with several responders.',
      'You want open-source and self-hostable control over the observability backend.',
    ],
    misconceptions: [
      {
        myth: 'Better Stack does not have session replay.',
        reality:
          'That is stale. Better Stack currently lists included session replays and paid additional replay usage.',
      },
      {
        myth: 'Responder pricing is the whole Better Stack bill.',
        reality:
          'Telemetry, status-page options, web events, monitors, replay, and enterprise features can each affect cost.',
      },
      {
        myth: 'Sentry-compatible error tracking means a complete Sentry replacement.',
        reality:
          'Error ingestion compatibility helps, but release workflows, infrastructure context, and deployment control still matter.',
      },
    ],
    migrationSteps: [
      {
        title: 'Map responder costs first',
        detail: 'Count the people who need on-call licenses and compare that to Moneat on-call seats.',
      },
      {
        title: 'Test telemetry compatibility',
        detail: 'Send one Sentry SDK service, one Datadog Agent host, and one OTLP service to Moneat.',
      },
      {
        title: 'Decide hosted vs controlled',
        detail: 'Use Better Stack if hosted incident polish wins; use Moneat if self-hosting and migration breadth win.',
      },
    ],
    proofPoints: [
      {label: 'Moneat on-call', value: '$5/user/mo'},
      {label: 'Better responder', value: '$29-$34/license'},
      {label: 'Replay claim', value: 'Both support replay'},
    ],
    featureRows: [
      {
        label: 'Session replay',
        moneat: 'Session replay is included as part of the Moneat product surface.',
        competitor: 'Better Stack pricing lists 5,000 included session replays.',
      },
      {
        label: 'On-call pricing',
        moneat: '$5/user/month for on-call.',
        competitor: 'Responder licenses are listed at $29 annually or $34 monthly.',
      },
      {
        label: 'Logs and traces',
        moneat: 'Pro includes 50GB core ingestion with $0.40/GB paid overage.',
        competitor: 'Logs and traces are listed at $0.10/GB ingestion plus retention pricing.',
      },
      {
        label: 'Migration path',
        moneat: 'Sentry SDK, Datadog Agent, and OTLP paths are first-class migration options.',
        competitor: 'Better Stack lists integrations such as Sentry, Datadog, and Grafana imports.',
      },
      {
        label: 'Infrastructure',
        moneat: 'Host, container, process, database, network, and Kubernetes views are in Moneat.',
        competitor: 'Better Stack lists OpenTelemetry-native infrastructure monitoring.',
      },
      {
        label: 'Deployment control',
        moneat: 'Moneat is open-source under AGPL v3 and can be self-hosted.',
        competitor: 'Better Stack is a hosted platform with enterprise options.',
      },
    ],
    costRows: [
      {
        scenario: '5 responders',
        moneat: '$25/month for on-call users.',
        competitor: '$145/month on annual responder licenses, or $170/month on monthly licenses.',
      },
      {
        scenario: 'Pure logs and traces',
        moneat: 'Pro includes 50GB before paid ingestion overage.',
        competitor: 'Better Stack may be cheaper for pure log-heavy workloads at listed ingest rates.',
      },
      {
        scenario: 'Sentry or Datadog migration',
        moneat: 'Built around Sentry-compatible DSNs and Datadog Agent compatibility.',
        competitor: 'Better Stack lists import and integration paths, but not the same drop-in scope.',
      },
    ],
    caveats: [
      'Do not position Better Stack as lacking replay; its current pricing page lists session replay.',
      'Better Stack can be compelling for teams primarily buying uptime, status pages, and incident flows.',
      'Moneat is strongest when migration compatibility and self-hostable observability matter.',
    ],
    sources: [
      moneatSource,
      {label: 'Better Stack pricing', href: 'https://betterstack.com/pricing'},
    ],
  },
  {
    slug: 'signoz',
    name: 'SigNoz',
    route: '/signoz-alternative',
    title: 'SigNoz Alternative',
    h1: 'SigNoz Alternative',
    description:
      'Moneat is a SigNoz alternative for teams that like OpenTelemetry but also want Sentry SDKs, ' +
      'Datadog Agent compatibility, replay, uptime, status pages, on-call, and product analytics.',
    metaDescription:
      'Compare Moneat with SigNoz in 2026. Review OpenTelemetry support, Sentry compatibility, Datadog Agent ' +
      'compatibility, logs, traces, metrics, incident response, and pricing models.',
    heroImage: comparisonHeroImage,
    heroImageAlt: comparisonHeroImageAlt,
    lede:
      'SigNoz is one of the clearest OpenTelemetry-native choices. The question is whether OpenTelemetry ' +
      'alone is enough, or whether your migration also needs Sentry SDK compatibility, Datadog Agent intake, ' +
      'replay, uptime, status communication, on-call, and product analytics.',
    summary:
      'Choose Moneat when SigNoz gives you the OpenTelemetry-first model you like, but you need more ' +
      'migration paths and an operations layer around traces, logs, metrics, errors, replays, and incidents.',
    shortVersionRows: [
      {
        dimension: 'Best fit',
        moneat: 'Teams that need OTel plus Sentry, Datadog Agent, replay, uptime, status pages, and response.',
        competitor: 'Teams standardizing on OpenTelemetry logs, traces, metrics, and ClickHouse-backed observability.',
      },
      {
        dimension: 'Migration',
        moneat: 'Accepts OTLP, Sentry SDK events, and Datadog Agent data.',
        competitor: 'Best when teams can standardize instrumentation around OpenTelemetry.',
      },
      {
        dimension: 'Bill model',
        moneat: 'Tiered plans with core ingestion, plus separate spans, metrics, infra series-hours, and pageview limits.',
        competitor: 'Usage-based Teams Cloud pricing for logs, traces, and metric samples after included credit.',
      },
      {
        dimension: 'Main caveat',
        moneat: 'Do not frame Moneat as the only open-source path; SigNoz also has open-source and self-host options.',
        competitor: 'SigNoz may be simpler for teams that only need OTel-native observability data.',
      },
    ],
    bestFor: [
      'Teams standardizing on OpenTelemetry while keeping Sentry SDKs',
      'Teams replacing both app debugging and infrastructure monitoring tools',
      'Teams that want product, uptime, status, and response workflows together',
    ],
    competitorStrengths: [
      'Simple usage-based logs, traces, and metrics pricing',
      'Strong OpenTelemetry-native positioning',
      'Open-source Community Edition and enterprise self-host options',
    ],
    chooseCompetitor: [
      'You want OpenTelemetry-native logs, traces, metrics, dashboards, and alerts as the core product.',
      'You value SigNoz usage-based pricing and do not need Sentry SDK or Datadog Agent compatibility.',
      'You are comfortable adding separate tools for status pages, on-call, replay, or product analytics if needed.',
    ],
    chooseMoneat: [
      'You need OpenTelemetry plus Sentry SDK and Datadog Agent migration paths.',
      'You want errors, replay, infrastructure, uptime, status pages, on-call, and product analytics together.',
      'You want a self-hostable platform where OpenTelemetry is one input, not the whole migration story.',
    ],
    misconceptions: [
      {
        myth: 'Moneat is open source and SigNoz is not.',
        reality:
          'SigNoz also has open-source and self-host options. The contrast is migration breadth and operational workflow coverage.',
      },
      {
        myth: 'OpenTelemetry support alone decides the platform.',
        reality:
          'OTel matters, but incident response, status pages, replay, Sentry compatibility, and Datadog migration can matter too.',
      },
      {
        myth: 'A lower per-signal data price always means lower total cost.',
        reality:
          'For mixed workflows, adjacent tools for paging, customer communication, replay, and analytics can change the real bill.',
      },
    ],
    migrationSteps: [
      {
        title: 'Keep OTLP where it already works',
        detail: 'Send logs, traces, and metrics through OTLP and compare query, dashboard, and alert coverage.',
      },
      {
        title: 'Add non-OTel migration paths',
        detail: 'Test a Sentry SDK service and a Datadog Agent host if your estate is not purely OpenTelemetry.',
      },
      {
        title: 'Validate response workflows',
        detail: 'Model the incident path from alert to status update to on-call escalation before switching platforms.',
      },
    ],
    proofPoints: [
      {label: 'Moneat Pro', value: '$29/mo'},
      {label: 'SigNoz Teams', value: '$49/mo'},
      {label: 'Migration paths', value: 'Sentry + DD + OTLP'},
    ],
    featureRows: [
      {
        label: 'Telemetry standard',
        moneat: 'Supports OTLP while also accepting Sentry SDK and Datadog Agent data.',
        competitor: 'OpenTelemetry-native logs, traces, and metrics platform.',
      },
      {
        label: 'Pricing model',
        moneat: 'Starts at $29/month for Pro, with tier limits and usage-specific overages.',
        competitor: 'Teams Cloud starts at $49/month with $49 of included usage credit.',
      },
      {
        label: 'Logs and traces',
        moneat: 'Core ingestion plus separate APM span limits.',
        competitor: 'Logs and traces are listed at $0.30/GB after the included usage credit.',
      },
      {
        label: 'Metrics',
        moneat: 'Custom metrics and infra metric series-hours have tier limits and low overage rates.',
        competitor: 'Metrics are listed at $0.10 per million samples.',
      },
      {
        label: 'App debugging',
        moneat: 'Sentry-compatible error tracking and replay workflows are part of the same platform.',
        competitor: 'SigNoz focuses on OpenTelemetry traces, logs, metrics, and exceptions.',
      },
      {
        label: 'Operations',
        moneat: 'Uptime, status pages, on-call, product analytics, and AI observability are included.',
        competitor: 'SigNoz pricing emphasizes observability data volume and enterprise support.',
      },
    ],
    costRows: [
      {
        scenario: 'Small production team',
        moneat: 'Pro starts at $29/month.',
        competitor: 'Teams Cloud starts at $49/month with usage credit.',
      },
      {
        scenario: 'Pure metrics-heavy workload',
        moneat: 'Custom metric overage is $0.50 per 100K metrics.',
        competitor: 'SigNoz may be cheaper for pure metrics at $0.10 per million samples.',
      },
      {
        scenario: 'Mixed Sentry, Datadog, and OTLP migration',
        moneat: 'All three ingestion paths are positioned as first-class entry points.',
        competitor: 'SigNoz is strongest when teams can standardize on OpenTelemetry.',
      },
    ],
    caveats: [
      'SigNoz is a strong fit for teams already committed to OpenTelemetry-only instrumentation.',
      'SigNoz metrics pricing can be attractive for pure metrics workloads.',
      'Moneat is strongest when OpenTelemetry is only one part of the migration story.',
    ],
    sources: [
      moneatSource,
      {label: 'SigNoz pricing', href: 'https://signoz.io/pricing/'},
    ],
  },
]

export const competitorPageBySlug = new Map(
  competitorPages.map((page) => [page.slug, page]),
)

export type MarkKind = 'yes' | 'partial' | 'no'
export type CellValue = MarkKind | string

export const COMPARE_ORDER: CompetitorSlug[] = ['datadog', 'sentry', 'signoz', 'better-stack']

export const compareColumns = COMPARE_ORDER.map((slug) => competitorPages.find((c) => c.slug === slug))
  .filter((page): page is (typeof competitorPages)[number] => Boolean(page))
  .map((page) => ({slug: page.slug, name: page.name, route: page.route}))

export const compareRows: Array<{
  label: string
  moneat: CellValue
  values: Record<CompetitorSlug, CellValue>
}> = [
  {
    label: 'Error tracking',
    moneat: 'yes',
    values: {datadog: 'yes', sentry: 'yes', signoz: 'partial', 'better-stack': 'partial'},
  },
  {
    label: 'Log management',
    moneat: 'yes',
    values: {datadog: 'yes', sentry: 'partial', signoz: 'yes', 'better-stack': 'yes'},
  },
  {
    label: 'APM & distributed tracing',
    moneat: 'yes',
    values: {datadog: 'yes', sentry: 'yes', signoz: 'yes', 'better-stack': 'partial'},
  },
  {
    label: 'Infrastructure & host metrics',
    moneat: 'yes',
    values: {datadog: 'yes', sentry: 'no', signoz: 'yes', 'better-stack': 'partial'},
  },
  {
    label: 'Uptime & status pages',
    moneat: 'yes',
    values: {datadog: 'partial', sentry: 'partial', signoz: 'partial', 'better-stack': 'yes'},
  },
  {
    label: 'On-call & incidents',
    moneat: 'yes',
    values: {datadog: 'yes', sentry: 'no', signoz: 'no', 'better-stack': 'yes'},
  },
  {
    label: 'Open source & self-host',
    moneat: 'yes',
    values: {datadog: 'no', sentry: 'partial', signoz: 'yes', 'better-stack': 'no'},
  },
  {
    label: 'Pricing model',
    moneat: 'Tiered — no per-host fee',
    values: {
      datadog: 'Per-host + per-product',
      sentry: 'Per-event volume',
      signoz: 'Usage or self-host',
      'better-stack': 'Per-monitor + seat',
    },
  },
]

export function getCompetitorPage(slug: CompetitorSlug): CompetitorPageData {
  const page = competitorPageBySlug.get(slug)
  if (!page) {
    throw new Error(`Unknown competitor comparison page: ${slug}`)
  }
  return page
}
