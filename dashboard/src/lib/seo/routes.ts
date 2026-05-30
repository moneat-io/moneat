import {blogPostingLd, breadcrumbLd, organizationLd, softwareApplicationLd, webSiteLd} from './jsonLd'
import type {PageSeo, SitemapEntry} from './types'

const COMPARISON_OG_IMAGE = '/marketing/observability-comparison-hero-a.webp'

export interface FeatureSeoInput {
  readonly slug: string
  readonly title: string
  readonly metaDescription: string
  readonly image?: string
}

/** Product feature/marketing pages rendered via FeaturePageTemplate (slug === path). */
export const FEATURE_PAGE_SEO_INPUTS = [
  {
    slug: 'error-tracking',
    title: 'Error Tracking',
    metaDescription:
      'Error tracking with smart fingerprinting, stack traces, and breadcrumbs. Compatible with Sentry SDKs. ' +
      'Start free with Moneat.',
    image: '/screenshots/error-tracking.png',
  },
  {
    slug: 'log-management',
    title: 'Log Management',
    metaDescription:
      'Log management with structured JSON, full-text search, real-time streaming, and powerful filtering. ' +
      'Start free with Moneat.',
    image: '/screenshots/log-management.png',
  },
  {
    slug: 'infrastructure-monitoring',
    title: 'Infrastructure Monitoring',
    metaDescription:
      'Infrastructure monitoring for hosts, containers, Kubernetes, and databases. Compatible with the Datadog ' +
      'Agent. Start free with Moneat.',
    image: '/screenshots/containers.png',
  },
  {
    slug: 'uptime-monitoring',
    title: 'Uptime Monitoring',
    metaDescription:
      'Uptime monitoring with instant alerts via phone, SMS, Slack. Public status pages included. Start free ' +
      'with Moneat.',
    image: '/screenshots/uptime.png',
  },
  {
    slug: 'session-replay',
    title: 'Session Replay',
    metaDescription:
      'Session replay with click tracking, console output, and error correlation. Replay user sessions to debug ' +
      'issues faster. Start free with Moneat.',
    image: '/screenshots/session-replay.png',
  },
  {
    slug: 'performance-monitoring',
    title: 'APM & Traces',
    metaDescription:
      'Application performance monitoring with distributed tracing, transaction tracking, and span analysis. ' +
      'Start free with Moneat.',
    image: '/screenshots/performance.png',
  },
  {
    slug: 'profiling',
    title: 'Continuous Profiling',
    metaDescription:
      'Continuous profiling with CPU, heap, and wall-time flamegraphs. Pinpoint performance bottlenecks in ' +
      'production. Start free with Moneat.',
    image: '/screenshots/profiles.png',
  },
  {
    slug: 'on-call-management',
    title: 'On-Call & Incidents',
    metaDescription:
      'On-call scheduling with phone, SMS, and Slack alerts. Escalation policies and rotation management. ' +
      'Start free with Moneat.',
    image: '/screenshots/escalation-policies.png',
  },
  {
    slug: 'public-status-pages',
    title: 'Status Pages',
    metaDescription:
      'Public status pages with custom domains, automated from uptime monitors. Free on all plans. Start free ' +
      'with Moneat.',
    image: '/screenshots/status-page-public.png',
  },
  {
    slug: 'alerting',
    title: 'Alerting & Integrations',
    metaDescription:
      'Alerting with Slack, Discord, email, phone, and SMS. Flexible routing rules and escalation policies. ' +
      'Start free with Moneat.',
    image: '/screenshots/alerting.png',
  },
  {
    slug: 'ai-observability',
    title: 'AI & LLM Observability',
    metaDescription:
      'AI and LLM observability with token tracking, cost analysis, and prompt monitoring across providers. ' +
      'Start free with Moneat.',
    image: '/screenshots/ai.png',
  },
  {
    slug: 'mcp-server',
    title: 'MCP Server',
    metaDescription:
      'MCP server for AI-powered observability. Query issues, logs, and traces from Cursor, GitHub Copilot, ' +
      'or any MCP client. Start free with Moneat.',
    image: '/screenshots/dashboard.png',
  },
  {
    slug: 'custom-dashboards',
    title: 'Dashboards',
    metaDescription:
      'Custom dashboards with drag-and-drop widgets. Connect any data source — PostgreSQL, ClickHouse, BigQuery, ' +
      'and more. Start free with Moneat.',
    image: '/screenshots/dashboard.png',
  },
  {
    slug: 'security-sbom',
    title: 'Security & SBOM',
    metaDescription:
      'Security monitoring with SBOM inventory and CVE tracking. Know which vulnerabilities affect your services. ' +
      'Start free with Moneat.',
    image: '/screenshots/security.png',
  },
] as const satisfies readonly FeatureSeoInput[]

export const homeSeo: PageSeo = {
  path: '/',
  title: 'Moneat | Open-Source Observability for Sentry and Datadog Teams',
  description:
    'The only observability platform that works as a drop-in replacement for both Sentry and Datadog. Use your ' +
    'existing Sentry SDKs and Datadog Agent — zero code changes. Open-source errors, logs, APM, infrastructure, ' +
    'on-call, and AI observability in one platform.',
  socialTitle: 'Moneat — The Only Platform That Replaces Both Sentry & Datadog',
  socialDescription:
    'Stop paying for Sentry and Datadog separately. The only platform that works as a drop-in replacement for ' +
    'both. Use your existing SDKs and agents — zero code changes.',
  keywords:
    'Sentry alternative, Datadog alternative, open source Sentry alternative, self-hosted Datadog replacement, ' +
    'drop-in Datadog replacement, error monitoring, log management, APM, infrastructure monitoring, ' +
    'observability platform',
  image: '/screenshots/dashboard.png',
  jsonLd: [softwareApplicationLd(), organizationLd(), webSiteLd()],
}

export const blogIndexSeo: PageSeo = {
  path: '/blog',
  title: 'Blog — Moneat',
  description: 'Engineering deep-dives, observability best practices, and product updates.',
}

export const compareHubSeo: PageSeo = {
  path: '/compare',
  title: 'Compare Moneat 2026 | Moneat',
  description:
    'Compare Moneat with Datadog, Sentry, Better Stack, and SigNoz using evidence-led pricing and feature comparisons.',
  socialTitle: 'Compare Moneat 2026',
  socialDescription:
    'Evidence-led comparison pages for Moneat alternatives to Datadog, Sentry, Better Stack, and SigNoz.',
  image: COMPARISON_OG_IMAGE,
}

export const pricingSeo: PageSeo = {
  path: '/pricing',
  title: 'Pricing | Moneat',
  description:
    'Simple, transparent pricing for Moneat. Per-type limits so you only pay for what you use. Unlimited team ' +
    'members on every plan. Start free.',
}

export const termsSeo: PageSeo = {
  path: '/legal/terms',
  title: 'Terms of Use | Moneat',
  description: 'Terms of Use for Moneat monitoring service.',
}

export const privacySeo: PageSeo = {
  path: '/legal/privacy',
  title: 'Privacy Policy | Moneat',
  description: 'Privacy Policy for Moneat monitoring service.',
}

export const STATIC_SEO_PAGES = [
  homeSeo,
  blogIndexSeo,
  compareHubSeo,
  pricingSeo,
  termsSeo,
  privacySeo,
] as const

export interface CompetitorSeoInput {
  title: string
  route: string
  metaDescription: string
}

/** PageSeo for a competitor "X alternative" comparison page. */
export function competitorPageSeo(page: CompetitorSeoInput): PageSeo {
  return {
    path: page.route,
    title: `${page.title} 2026 | Moneat`,
    description: page.metaDescription,
    image: COMPARISON_OG_IMAGE,
  }
}

/** PageSeo for a product feature page (slug === path, e.g. /error-tracking). */
export function featurePageSeo(page: FeatureSeoInput): PageSeo {
  return {
    path: `/${page.slug}`,
    title: `${page.title} | Moneat`,
    description: page.metaDescription,
    image: page.image,
  }
}

export interface BlogPostSeoInput {
  slug: string
  title: string
  description: string
  date: string
  author: string
  image?: string
}

/** PageSeo (og:type article) for a blog post, including BlogPosting + breadcrumb JSON-LD. */
export function blogPostSeo(post: BlogPostSeoInput): PageSeo {
  const path = `/blog/${post.slug}`
  return {
    path,
    title: `${post.title} — Moneat Blog`,
    description: post.description,
    type: 'article',
    image: post.image,
    publishedTime: post.date,
    author: post.author,
    jsonLd: [
      blogPostingLd({
        title: post.title,
        description: post.description,
        path,
        date: post.date,
        author: post.author,
        image: post.image,
      }),
      breadcrumbLd([
        {name: 'Blog', path: '/blog'},
        {name: post.title, path},
      ]),
    ],
  }
}

export interface SitemapInput {
  posts: {slug: string; date?: string}[]
  competitors: {route: string}[]
  /** YYYY-MM-DD used for routes without their own modification date. */
  buildDate: string
}

/** Assemble the full list of indexable marketing/content routes for sitemap.xml. */
export function buildSitemapEntries(input: SitemapInput): SitemapEntry[] {
  const {posts, competitors, buildDate} = input
  const entries: SitemapEntry[] = [
    {path: '/', changefreq: 'weekly', priority: 1.0, lastmod: buildDate},
    {path: '/blog', changefreq: 'weekly', priority: 0.8, lastmod: buildDate},
    {path: '/compare', changefreq: 'monthly', priority: 0.7},
    {path: '/pricing', changefreq: 'monthly', priority: 0.6},
    {path: '/docs', changefreq: 'weekly', priority: 0.6},
  ]
  for (const competitor of competitors) {
    entries.push({path: competitor.route, changefreq: 'monthly', priority: 0.8})
  }
  for (const page of FEATURE_PAGE_SEO_INPUTS) {
    entries.push({path: `/${page.slug}`, changefreq: 'monthly', priority: 0.6})
  }
  for (const post of posts) {
    entries.push({path: `/blog/${post.slug}`, changefreq: 'monthly', priority: 0.7, lastmod: post.date})
  }
  entries.push(
    {path: '/legal/terms', changefreq: 'yearly', priority: 0.2},
    {path: '/legal/privacy', changefreq: 'yearly', priority: 0.2},
  )
  return entries
}
