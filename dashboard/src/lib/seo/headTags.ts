import {DEFAULT_OG_IMAGE, SITE_NAME, TWITTER_CARD, absoluteUrl} from './constants'
import type {PageSeo} from './types'

/** Escape a string for safe interpolation into HTML text or double-quoted attribute values. */
export function escapeHtml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** Serialize JSON-LD for embedding in a <script> tag, neutralizing any "</script>" breakouts. */
export function jsonLdToString(data: unknown): string {
  return JSON.stringify(data).replace(/</g, '\\u003c')
}

/**
 * Render the full set of SEO <head> tags for a page as an HTML string.
 *
 * Used by the build-time prerender to inject static metadata; the runtime <SeoHead>
 * component renders the equivalent tags via react-helmet-async so client navigation
 * and the prerendered HTML stay in sync.
 */
export function renderHeadTags(seo: PageSeo): string {
  const canonical = absoluteUrl(seo.path)
  const type = seo.type ?? 'website'
  const image = absoluteUrl(seo.image ?? DEFAULT_OG_IMAGE)
  const socialTitle = seo.socialTitle ?? seo.title
  const socialDescription = seo.socialDescription ?? seo.description

  const tags = [
    `<title>${escapeHtml(seo.title)}</title>`,
    `<meta name="description" content="${escapeHtml(seo.description)}" />`,
    `<link rel="canonical" href="${escapeHtml(canonical)}" />`,
  ]
  if (seo.keywords) tags.push(`<meta name="keywords" content="${escapeHtml(seo.keywords)}" />`)
  if (seo.noindex) tags.push('<meta name="robots" content="noindex, nofollow" />')

  tags.push(
    `<meta property="og:type" content="${type}" />`,
    `<meta property="og:site_name" content="${escapeHtml(SITE_NAME)}" />`,
    `<meta property="og:title" content="${escapeHtml(socialTitle)}" />`,
    `<meta property="og:description" content="${escapeHtml(socialDescription)}" />`,
    `<meta property="og:url" content="${escapeHtml(canonical)}" />`,
    `<meta property="og:image" content="${escapeHtml(image)}" />`,
  )
  if (type === 'article') {
    if (seo.publishedTime) {
      tags.push(`<meta property="article:published_time" content="${escapeHtml(seo.publishedTime)}" />`)
    }
    if (seo.author) {
      tags.push(`<meta property="article:author" content="${escapeHtml(seo.author)}" />`)
    }
  }
  tags.push(
    `<meta name="twitter:card" content="${TWITTER_CARD}" />`,
    `<meta name="twitter:title" content="${escapeHtml(socialTitle)}" />`,
    `<meta name="twitter:description" content="${escapeHtml(socialDescription)}" />`,
    `<meta name="twitter:image" content="${escapeHtml(image)}" />`,
  )
  for (const block of seo.jsonLd ?? []) {
    tags.push(`<script type="application/ld+json">${jsonLdToString(block)}</script>`)
  }
  return tags.join('\n    ')
}
