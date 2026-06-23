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
import {stringifySearchWithRepeatedPrimitiveArrays} from '@/lib/routerSearch'

describe('routerSearch', () => {
  it('serializes primitive arrays as repeated params', () => {
    expect(
      stringifySearchWithRepeatedPrimitiveArrays({
        kind: ['host', 'pod', 'network-device', 'service'],
        env: 'prod',
      })
    ).toBe('?kind=host&kind=pod&kind=network-device&kind=service&env=prod')
  })

  it('keeps object arrays JSON encoded for legacy structured search values', () => {
    expect(
      stringifySearchWithRepeatedPrimitiveArrays({
        facets: [{key: 'status', value: 'unresolved'}],
      })
    ).toBe('?facets=%5B%7B%22key%22%3A%22status%22%2C%22value%22%3A%22unresolved%22%7D%5D')
  })
})
