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

import {describe, expect, it} from 'vitest'
import {isHttpUrl, parseHttpUrl} from '../safe-url'

describe('parseHttpUrl', () => {
  it('accepts and normalizes http(s) URLs', () => {
    expect(parseHttpUrl('https://example.com/a/b?q=1')).toBe('https://example.com/a/b?q=1')
    expect(parseHttpUrl('  http://example.com  ')).toBe('http://example.com/')
    expect(parseHttpUrl('HTTPS://Example.com/Path')).toBe('https://example.com/Path')
  })

  it('rejects non-http(s) schemes', () => {
    expect(parseHttpUrl('javascript:alert(1)')).toBeNull()
    expect(parseHttpUrl('data:text/html,<script>')).toBeNull()
    expect(parseHttpUrl('mailto:a@b.com')).toBeNull()
    expect(parseHttpUrl('ftp://example.com')).toBeNull()
    expect(parseHttpUrl('file:///etc/passwd')).toBeNull()
  })

  it('rejects malformed or empty values', () => {
    expect(parseHttpUrl('not a url')).toBeNull()
    expect(parseHttpUrl('example.com')).toBeNull()
    expect(parseHttpUrl('')).toBeNull()
    expect(parseHttpUrl('   ')).toBeNull()
    expect(parseHttpUrl(null)).toBeNull()
    expect(parseHttpUrl(undefined)).toBeNull()
  })

  it('isHttpUrl mirrors parseHttpUrl', () => {
    expect(isHttpUrl('https://example.com')).toBe(true)
    expect(isHttpUrl('javascript:void(0)')).toBe(false)
  })
})
