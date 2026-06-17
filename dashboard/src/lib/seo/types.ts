/** Shared SEO types used by both the runtime <SeoHead> component and the build-time prerender. */

export type OgType = 'website' | 'article'

export interface PageSeo {
  /** Site-root-relative path, e.g. '/', '/blog', '/blog/my-post'. */
  path: string
  /** Full <title> text (including any " | Moneat" suffix). */
  title: string
  /** Meta description and default og/twitter description. */
  description: string
  /** og:type — defaults to 'website'. */
  type?: OgType
  /** Share image: absolute URL or site-root-relative path. Falls back to the site default. */
  image?: string
  /** Overrides og:title / twitter:title when the share headline differs from the page title. */
  socialTitle?: string
  /** Overrides og:description / twitter:description. */
  socialDescription?: string
  /** Optional comma-separated keywords (legacy; ignored by most engines but harmless). */
  keywords?: string
  /** Emit robots noindex,nofollow when true. */
  noindex?: boolean
  /** article:published_time (ISO date) — only emitted for type 'article'. */
  publishedTime?: string
  /** article:author — only emitted for type 'article'. */
  author?: string
  /** JSON-LD blocks rendered as <script type="application/ld+json">. */
  jsonLd?: Record<string, unknown>[]
}

export type ChangeFreq = 'always' | 'hourly' | 'daily' | 'weekly' | 'monthly' | 'yearly' | 'never'

export interface SitemapEntry {
  path: string
  /** YYYY-MM-DD */
  lastmod?: string
  changefreq?: ChangeFreq
  /** 0.0 – 1.0 */
  priority?: number
}
