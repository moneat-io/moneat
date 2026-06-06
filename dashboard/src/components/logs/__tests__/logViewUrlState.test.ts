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
import {buildLogContextQuery} from '@/components/logs/logViewUrlState'

describe('buildLogContextQuery', () => {
  it('returns the raw query untouched when there are no facets', () => {
    expect(buildLogContextQuery('error', [])).toBe('error')
  })

  it('flattens a facet-only context into a key:value expression', () => {
    expect(buildLogContextQuery('', [{key: 'service', value: 'api-server'}])).toBe('service:api-server')
  })

  it('combines raw query text with active facets', () => {
    expect(buildLogContextQuery('error', [{key: 'service', value: 'api'}])).toBe('error service:api')
  })

  it('represents excluded facets with the search bar dash form', () => {
    expect(
      buildLogContextQuery('timeout', [
        {key: 'service', value: 'api', exclude: false},
        {key: 'environment', value: 'dev', exclude: true},
      ])
    ).toBe('timeout service:api -environment:dev')
  })

  it('ignores blank query text and facets without a value', () => {
    expect(
      buildLogContextQuery('   ', [
        {key: 'service', value: ''},
        {key: 'environment', value: 'prod'},
      ])
    ).toBe('environment:prod')
  })
})
