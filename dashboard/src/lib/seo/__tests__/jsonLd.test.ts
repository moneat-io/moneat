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
  blogPostingLd,
  breadcrumbLd,
  organizationLd,
  softwareApplicationLd,
  webSiteLd,
} from '../jsonLd'

describe('jsonLd builders', () => {
  it('organizationLd / webSiteLd / softwareApplicationLd carry schema.org context and absolute urls', () => {
    expect(organizationLd()).toMatchObject({'@context': 'https://schema.org', '@type': 'Organization', url: 'https://moneat.io'})
    expect(webSiteLd()).toMatchObject({'@type': 'WebSite', url: 'https://moneat.io'})
    const app = softwareApplicationLd()
    expect(app['@type']).toBe('SoftwareApplication')
    expect(app.offers).toMatchObject({price: '0', priceCurrency: 'USD'})
    expect(organizationLd().logo).toBe('https://moneat.io/logo.svg')
  })

  it('blogPostingLd maps post fields and resolves an absolute image', () => {
    const ld = blogPostingLd({
      title: 'My Post',
      description: 'desc',
      path: '/blog/my-post',
      date: '2026-02-15',
      author: 'Adrian Elder',
      image: '/screenshots/x.png',
    })
    expect(ld).toMatchObject({
      '@type': 'BlogPosting',
      headline: 'My Post',
      datePublished: '2026-02-15',
      dateModified: '2026-02-15',
      url: 'https://moneat.io/blog/my-post',
      image: 'https://moneat.io/screenshots/x.png',
    })
    expect(ld.author).toMatchObject({'@type': 'Person', name: 'Adrian Elder'})
    expect(ld.mainEntityOfPage).toMatchObject({'@id': 'https://moneat.io/blog/my-post'})
  })

  it('blogPostingLd falls back to the default share image when none is provided', () => {
    const ld = blogPostingLd({title: 't', description: 'd', path: '/blog/p', date: '2026-01-01', author: 'a'})
    expect(ld.image).toBe('https://moneat.io/og-image.png')
  })

  it('breadcrumbLd numbers items from 1 and resolves absolute item urls', () => {
    const ld = breadcrumbLd([
      {name: 'Blog', path: '/blog'},
      {name: 'Post', path: '/blog/post'},
    ])
    expect(ld['@type']).toBe('BreadcrumbList')
    expect(ld.itemListElement).toEqual([
      {'@type': 'ListItem', position: 1, name: 'Blog', item: 'https://moneat.io/blog'},
      {'@type': 'ListItem', position: 2, name: 'Post', item: 'https://moneat.io/blog/post'},
    ])
  })
})
