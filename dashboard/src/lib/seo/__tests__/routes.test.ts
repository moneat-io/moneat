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

import {describe, it, expect} from 'vitest'
import {
  FEATURE_PAGE_SLUGS,
  blogIndexSeo,
  blogPostSeo,
  buildSitemapEntries,
  compareHubSeo,
  competitorPageSeo,
  featurePageSeo,
  homeSeo,
} from '../routes'

describe('static route descriptors', () => {
  it('home/blog/compare descriptors have required fields', () => {
    expect(homeSeo.path).toBe('/')
    expect(homeSeo.jsonLd?.length).toBeGreaterThan(0)
    expect(homeSeo.socialTitle).toBeTruthy()
    expect(blogIndexSeo.path).toBe('/blog')
    expect(compareHubSeo.path).toBe('/compare')
    expect(compareHubSeo.image).toContain('/marketing/')
  })
})

describe('blogPostSeo', () => {
  it('builds an article descriptor with BlogPosting + breadcrumb JSON-LD', () => {
    const seo = blogPostSeo({
      slug: 'true-cost-of-datadog',
      title: 'The True Cost of Datadog',
      description: 'desc',
      date: '2026-02-15',
      author: 'Adrian Elder',
    })
    expect(seo).toMatchObject({
      path: '/blog/true-cost-of-datadog',
      title: 'The True Cost of Datadog — Moneat Blog',
      type: 'article',
      publishedTime: '2026-02-15',
      author: 'Adrian Elder',
    })
    const types = (seo.jsonLd ?? []).map((b) => b['@type'])
    expect(types).toEqual(['BlogPosting', 'BreadcrumbList'])
  })
})

describe('competitorPageSeo / featurePageSeo', () => {
  it('competitorPageSeo suffixes the title and sets the comparison image', () => {
    const seo = competitorPageSeo({title: 'Datadog Alternative', route: '/datadog-alternative', metaDescription: 'd'})
    expect(seo.path).toBe('/datadog-alternative')
    expect(seo.title).toBe('Datadog Alternative 2026 | Moneat')
    expect(seo.image).toContain('/marketing/')
  })

  it('featurePageSeo derives path and title from the slug', () => {
    const seo = featurePageSeo({slug: 'error-tracking', title: 'Error Tracking', metaDescription: 'd', image: '/s.png'})
    expect(seo.path).toBe('/error-tracking')
    expect(seo.title).toBe('Error Tracking | Moneat')
    expect(seo.image).toBe('/s.png')
  })
})

describe('buildSitemapEntries', () => {
  const entries = buildSitemapEntries({
    posts: [{slug: 'a', date: '2026-01-01'}, {slug: 'b'}],
    docs: [{slug: 'getting-started'}, {slug: ''}],
    competitors: [{route: '/datadog-alternative'}, {route: '/sentry-alternative'}],
    buildDate: '2026-05-30',
  })
  const paths = entries.map((e) => e.path)

  it('includes core, competitor, feature, blog, doc and legal routes', () => {
    expect(paths).toContain('/')
    expect(paths).toContain('/blog')
    expect(paths).toContain('/datadog-alternative')
    expect(paths).toContain('/sentry-alternative')
    expect(paths).toContain('/blog/a')
    expect(paths).toContain('/blog/b')
    expect(paths).toContain('/docs/getting-started')
    expect(paths).toContain('/legal/terms')
    expect(paths).toContain('/legal/privacy')
    for (const slug of FEATURE_PAGE_SLUGS) {
      expect(paths).toContain(`/${slug}`)
    }
  })

  it('skips docs with an empty slug and carries post lastmod', () => {
    // The empty-slug doc must not produce a "/docs/" entry...
    expect(paths).not.toContain('/docs/')
    // ...and "/docs" appears exactly once (the index added separately, not from the docs loop).
    expect(paths.filter((p) => p === '/docs')).toHaveLength(1)
    expect(entries.find((e) => e.path === '/blog/a')?.lastmod).toBe('2026-01-01')
    expect(entries.find((e) => e.path === '/blog/b')?.lastmod).toBeUndefined()
    expect(entries.find((e) => e.path === '/')?.priority).toBe(1.0)
  })
})
