import {SITE_ORIGIN} from '@/lib/site'

export {SITE_ORIGIN}

export const SITE_NAME = 'Moneat'

/** Default social share image (Open Graph / Twitter). Served from dashboard/public. */
export const DEFAULT_OG_IMAGE = '/og-image.png'

export const TWITTER_CARD = 'summary_large_image'

/** Resolve a site-root-relative path to an absolute URL. Absolute URLs pass through unchanged. */
export function absoluteUrl(pathOrUrl: string): string {
  if (/^https?:\/\//i.test(pathOrUrl)) return pathOrUrl
  return SITE_ORIGIN + (pathOrUrl.startsWith('/') ? pathOrUrl : `/${pathOrUrl}`)
}
