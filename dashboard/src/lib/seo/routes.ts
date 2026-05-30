import {blogPostingLd, breadcrumbLd, organizationLd, softwareApplicationLd, webSiteLd} from './jsonLd'
import type {PageSeo, SitemapEntry} from './types'

const COMPARISON_OG_IMAGE = '/marketing/observability-comparison-hero-a.webp'

/** Product feature/marketing pages rendered via FeaturePageTemplate (slug === path). */
export const FEATURE_PAGE_SLUGS = [
  'error-tracking',
  'log-management',
  'infrastructure-monitoring',
  'uptime-monitoring',
  'session-replay',
  'performance-monitoring',
  'profiling',
  'on-call-management',
  'public-status-pages',
  'alerting',
  'ai-observability',
  'mcp-server',
  'custom-dashboards',
  'security-sbom',
] as const

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
    'drop-in Datadog replacement, error monitoring, log management, APM, infrastructure monitoring, observability platform',
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

export interface CompetitorSeoInput {
  title: string
  route: string
  metaDescription: string
}

export function competitorPageSeo(page: CompetitorSeoInput): PageSeo {
  return {
    path: page.route,
    title: `${page.title} 2026 | Moneat`,
    description: page.metaDescription,
    image: COMPARISON_OG_IMAGE,
  }
}

export interface FeatureSeoInput {
  slug: string
  title: string
  metaDescription: string
  image?: string
}

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
  docs: {slug: string}[]
  competitors: {route: string}[]
  /** YYYY-MM-DD used for routes without their own modification date. */
  buildDate: string
}

/** Assemble the full list of indexable marketing/content routes for sitemap.xml. */
export function buildSitemapEntries(input: SitemapInput): SitemapEntry[] {
  const {posts, docs, competitors, buildDate} = input
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
  for (const slug of FEATURE_PAGE_SLUGS) {
    entries.push({path: `/${slug}`, changefreq: 'monthly', priority: 0.6})
  }
  for (const post of posts) {
    entries.push({path: `/blog/${post.slug}`, changefreq: 'monthly', priority: 0.7, lastmod: post.date})
  }
  for (const doc of docs) {
    if (!doc.slug) continue
    entries.push({path: `/docs/${doc.slug}`, changefreq: 'monthly', priority: 0.5})
  }
  entries.push(
    {path: '/signup', changefreq: 'monthly', priority: 0.5},
    {path: '/legal/terms', changefreq: 'yearly', priority: 0.2},
    {path: '/legal/privacy', changefreq: 'yearly', priority: 0.2},
  )
  return entries
}
