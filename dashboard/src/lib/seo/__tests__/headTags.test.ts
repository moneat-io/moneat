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
import {escapeHtml, jsonLdToString, renderHeadTags} from '../headTags'
import type {PageSeo} from '../types'

describe('escapeHtml', () => {
  it('escapes all HTML-sensitive characters', () => {
    expect(escapeHtml(`a & b < c > d " e ' f`)).toBe('a &amp; b &lt; c &gt; d &quot; e &#39; f')
  })
})

describe('jsonLdToString', () => {
  it('neutralizes </script> breakouts', () => {
    expect(jsonLdToString({x: '</script>'})).toContain('\\u003c/script>')
    expect(jsonLdToString({x: '</script>'})).not.toContain('</script>')
  })
})

describe('renderHeadTags', () => {
  it('renders website tags with canonical, og, twitter and default image', () => {
    const out = renderHeadTags({path: '/compare', title: 'Compare | Moneat', description: 'desc'})
    expect(out).toContain('<title>Compare | Moneat</title>')
    expect(out).toContain('<link rel="canonical" href="https://moneat.io/compare" />')
    expect(out).toContain('<meta property="og:type" content="website" />')
    expect(out).toContain('<meta property="og:title" content="Compare | Moneat" />')
    expect(out).toContain('<meta name="twitter:card" content="summary_large_image" />')
    expect(out).toContain('<meta property="og:image" content="https://moneat.io/og-image.png" />')
    expect(out).not.toContain('article:published_time')
  })

  it('honors socialTitle/socialDescription, keywords, noindex and explicit image', () => {
    const seo: PageSeo = {
      path: '/',
      title: 'Page Title',
      description: 'meta desc',
      socialTitle: 'Share Title',
      socialDescription: 'share desc',
      keywords: 'a, b',
      noindex: true,
      image: '/screenshots/x.png',
    }
    const out = renderHeadTags(seo)
    expect(out).toContain('<meta name="keywords" content="a, b" />')
    expect(out).toContain('<meta name="robots" content="noindex, nofollow" />')
    expect(out).toContain('<meta property="og:title" content="Share Title" />')
    expect(out).toContain('<meta name="twitter:description" content="share desc" />')
    expect(out).toContain('<meta property="og:image" content="https://moneat.io/screenshots/x.png" />')
  })

  it('renders article metadata and embedded JSON-LD for posts', () => {
    const out = renderHeadTags({
      path: '/blog/p',
      title: 'P — Moneat Blog',
      description: 'd',
      type: 'article',
      publishedTime: '2026-02-15',
      author: 'Adrian Elder',
      jsonLd: [{'@type': 'BlogPosting', headline: 'P'}],
    })
    expect(out).toContain('<meta property="og:type" content="article" />')
    expect(out).toContain('<meta property="article:published_time" content="2026-02-15" />')
    expect(out).toContain('<meta property="article:author" content="Adrian Elder" />')
    expect(out).toContain('<script type="application/ld+json">{"@type":"BlogPosting","headline":"P"}</script>')
  })

  it('escapes values that would otherwise break attributes', () => {
    const out = renderHeadTags({path: '/x', title: 'A & "B"', description: "it's <b>"})
    expect(out).toContain('<title>A &amp; &quot;B&quot;</title>')
    expect(out).toContain('content="it&#39;s &lt;b&gt;"')
  })
})
