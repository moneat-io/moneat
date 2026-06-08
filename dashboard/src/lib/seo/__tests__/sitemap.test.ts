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
import {buildSitemapXml} from '../sitemap'

describe('buildSitemapXml', () => {
  it('emits a well-formed urlset with absolute locs', () => {
    const xml = buildSitemapXml([{path: '/'}, {path: '/blog'}])
    expect(xml).toContain('<?xml version="1.0" encoding="UTF-8"?>')
    expect(xml).toContain('<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">')
    expect(xml).toContain('<loc>https://moneat.io/</loc>')
    expect(xml).toContain('<loc>https://moneat.io/blog</loc>')
    expect(xml.trim().endsWith('</urlset>')).toBe(true)
  })

  it('includes optional lastmod, changefreq and formatted priority', () => {
    const xml = buildSitemapXml([
      {path: '/blog/post', lastmod: '2026-02-15', changefreq: 'monthly', priority: 0.7},
    ])
    expect(xml).toContain('<lastmod>2026-02-15</lastmod>')
    expect(xml).toContain('<changefreq>monthly</changefreq>')
    expect(xml).toContain('<priority>0.7</priority>')
  })

  it('omits optional fields when not provided', () => {
    const xml = buildSitemapXml([{path: '/plain'}])
    expect(xml).not.toContain('<lastmod>')
    expect(xml).not.toContain('<changefreq>')
    expect(xml).not.toContain('<priority>')
  })
})
