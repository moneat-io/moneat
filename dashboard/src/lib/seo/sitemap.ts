import {absoluteUrl} from './constants'
import {escapeHtml} from './headTags'
import type {SitemapEntry} from './types'

/** Build a urlset sitemap XML document. Paths are resolved to absolute URLs. */
export function buildSitemapXml(entries: SitemapEntry[]): string {
  const urls = entries
    .map((entry) => {
      const parts = [`    <loc>${escapeHtml(absoluteUrl(entry.path))}</loc>`]
      if (entry.lastmod) parts.push(`    <lastmod>${escapeHtml(entry.lastmod)}</lastmod>`)
      if (entry.changefreq) parts.push(`    <changefreq>${entry.changefreq}</changefreq>`)
      if (entry.priority !== undefined) parts.push(`    <priority>${entry.priority.toFixed(1)}</priority>`)
      return `  <url>\n${parts.join('\n')}\n  </url>`
    })
    .join('\n')
  return (
    '<?xml version="1.0" encoding="UTF-8"?>\n' +
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n' +
    `${urls}\n` +
    '</urlset>\n'
  )
}
