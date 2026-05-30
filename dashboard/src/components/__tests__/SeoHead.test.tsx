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
import {render, waitFor} from '@testing-library/react'
import {HelmetProvider} from 'react-helmet-async'
import {SeoHead} from '../SeoHead'
import {blogPostSeo, homeSeo} from '@/lib/seo/routes'
import type {PageSeo} from '@/lib/seo/types'

/** Render <SeoHead> and wait for react-helmet-async to commit tags to document.head. */
async function renderSeo(seo: PageSeo): Promise<void> {
  render(
    <HelmetProvider>
      <SeoHead seo={seo} />
    </HelmetProvider>,
  )
  await waitFor(() => expect(document.head.querySelector('title')).not.toBeNull())
}

function content(selector: string): string | null {
  return document.head.querySelector(selector)?.getAttribute('content') ?? null
}

describe('SeoHead', () => {
  it('renders website meta, canonical, keywords and JSON-LD for the homepage', async () => {
    await renderSeo(homeSeo)
    expect(document.title).toBe(homeSeo.title)
    expect(document.head.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe('https://moneat.io/')
    expect(content('meta[property="og:type"]')).toBe('website')
    // socialTitle override is used for og:title
    expect(content('meta[property="og:title"]')).toContain('Replaces Both Sentry & Datadog')
    expect(content('meta[name="keywords"]')).toBeTruthy()
    expect(document.head.querySelector('meta[property="article:published_time"]')).toBeNull()
  })

  it('renders article metadata for blog posts', async () => {
    await renderSeo(
      blogPostSeo({
        slug: 'true-cost-of-datadog',
        title: 'The True Cost of Datadog',
        description: 'desc',
        date: '2026-02-15',
        author: 'Adrian Elder',
      }),
    )
    expect(content('meta[property="og:type"]')).toBe('article')
    expect(content('meta[property="article:published_time"]')).toBe('2026-02-15')
    expect(content('meta[property="article:author"]')).toBe('Adrian Elder')
    expect(content('meta[name="twitter:card"]')).toBe('summary_large_image')
  })

  it('falls back to title for social tags and emits robots noindex when requested', async () => {
    await renderSeo({path: '/secret', title: 'Hidden', description: 'nope', noindex: true})
    expect(content('meta[name="robots"]')).toBe('noindex, nofollow')
    // og:title falls back to the page title when socialTitle is absent
    expect(content('meta[property="og:title"]')).toBe('Hidden')
    // default share image is used when none is supplied
    expect(content('meta[property="og:image"]')).toContain('og-image.png')
    expect(document.head.querySelector('meta[name="keywords"]')).toBeNull()
    expect(document.head.querySelector('script[type="application/ld+json"]')).toBeNull()
  })
})
