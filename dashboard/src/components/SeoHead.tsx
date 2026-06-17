import {Helmet} from 'react-helmet-async'
import {DEFAULT_OG_IMAGE, SITE_NAME, TWITTER_CARD, absoluteUrl} from '@/lib/seo/constants'
import {jsonLdToString} from '@/lib/seo/headTags'
import type {PageSeo} from '@/lib/seo/types'

/**
 * Renders per-page SEO <head> tags (title, description, canonical, Open Graph, Twitter,
 * JSON-LD) from a shared PageSeo descriptor. The build-time prerender emits the equivalent
 * static markup via renderHeadTags(), so client navigation and prerendered HTML stay in sync.
 */
export function SeoHead({seo}: {readonly seo: PageSeo}) {
  const canonical = absoluteUrl(seo.path)
  const type = seo.type ?? 'website'
  const image = absoluteUrl(seo.image ?? DEFAULT_OG_IMAGE)
  const socialTitle = seo.socialTitle ?? seo.title
  const socialDescription = seo.socialDescription ?? seo.description

  return (
    <Helmet>
      <title>{seo.title}</title>
      <meta name="description" content={seo.description} />
      {seo.keywords && <meta name="keywords" content={seo.keywords} />}
      <link rel="canonical" href={canonical} />
      {seo.noindex && <meta name="robots" content="noindex, nofollow" />}

      <meta property="og:type" content={type} />
      <meta property="og:site_name" content={SITE_NAME} />
      <meta property="og:title" content={socialTitle} />
      <meta property="og:description" content={socialDescription} />
      <meta property="og:url" content={canonical} />
      <meta property="og:image" content={image} />

      {type === 'article' && seo.publishedTime && (
        <meta property="article:published_time" content={seo.publishedTime} />
      )}
      {type === 'article' && seo.author && <meta property="article:author" content={seo.author} />}

      <meta name="twitter:card" content={TWITTER_CARD} />
      <meta name="twitter:title" content={socialTitle} />
      <meta name="twitter:description" content={socialDescription} />
      <meta name="twitter:image" content={image} />

      {(seo.jsonLd ?? []).map((block, index) => (
        <script key={`ld-${index}-${String(block['@type'] ?? '')}`} type="application/ld+json">
          {jsonLdToString(block)}
        </script>
      ))}
    </Helmet>
  )
}
