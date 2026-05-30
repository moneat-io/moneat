import {DEFAULT_OG_IMAGE, SITE_NAME, SITE_ORIGIN, absoluteUrl} from './constants'

type Json = Record<string, unknown>

/** schema.org Organization sub-object used as the `publisher` of articles. */
function publisher(): Json {
  return {
    '@type': 'Organization',
    name: SITE_NAME,
    logo: {'@type': 'ImageObject', url: absoluteUrl('/logo.svg')},
  }
}

/** schema.org Organization describing Moneat (site-wide identity). */
export function organizationLd(): Json {
  return {
    '@context': 'https://schema.org',
    '@type': 'Organization',
    name: SITE_NAME,
    url: SITE_ORIGIN,
    logo: absoluteUrl('/logo.svg'),
  }
}

/** schema.org WebSite entry for moneat.io. */
export function webSiteLd(): Json {
  return {
    '@context': 'https://schema.org',
    '@type': 'WebSite',
    name: SITE_NAME,
    url: SITE_ORIGIN,
  }
}

/** schema.org SoftwareApplication describing the Moneat product (used on the homepage). */
export function softwareApplicationLd(): Json {
  return {
    '@context': 'https://schema.org',
    '@type': 'SoftwareApplication',
    name: SITE_NAME,
    url: SITE_ORIGIN,
    applicationCategory: 'DeveloperApplication',
    description:
      'The only open-source observability platform that works as a drop-in replacement for both Sentry and Datadog. ' +
      'Errors, logs, infrastructure, APM, AI observability, on-call, and status pages in one platform — compatible ' +
      'with existing Sentry SDKs and the Datadog Agent.',
    operatingSystem: 'Web',
    alternateName: [
      'Sentry alternative',
      'Datadog alternative',
      'open source Sentry alternative',
      'self-hosted Datadog replacement',
    ],
    offers: {'@type': 'Offer', price: '0', priceCurrency: 'USD'},
  }
}

export interface BlogPostingInput {
  title: string
  description: string
  path: string
  date: string
  author: string
  image?: string
}

/** schema.org BlogPosting structured data for a single blog post. */
export function blogPostingLd(post: BlogPostingInput): Json {
  const url = absoluteUrl(post.path)
  return {
    '@context': 'https://schema.org',
    '@type': 'BlogPosting',
    headline: post.title,
    description: post.description,
    datePublished: post.date,
    dateModified: post.date,
    author: {'@type': 'Person', name: post.author},
    publisher: publisher(),
    image: absoluteUrl(post.image ?? DEFAULT_OG_IMAGE),
    mainEntityOfPage: {'@type': 'WebPage', '@id': url},
    url,
  }
}

export interface BreadcrumbItem {
  name: string
  path: string
}

/** schema.org BreadcrumbList built from an ordered list of name/path pairs. */
export function breadcrumbLd(items: BreadcrumbItem[]): Json {
  return {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: items.map((item, index) => ({
      '@type': 'ListItem',
      position: index + 1,
      name: item.name,
      item: absoluteUrl(item.path),
    })),
  }
}
