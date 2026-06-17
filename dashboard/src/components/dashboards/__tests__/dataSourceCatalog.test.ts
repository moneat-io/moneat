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
import {SOURCE_LOGO, VENDORS, getVendor, groupVendors} from '../dataSourceCatalog'

describe('catalog', () => {
  it('covers all 17 source types with a logo each', () => {
    expect(VENDORS).toHaveLength(17)
    for (const v of VENDORS) {
      expect(SOURCE_LOGO[v.key]).toBeDefined()
    }
  })

  it('looks up a vendor by key', () => {
    expect(getVendor('prometheus')?.label).toBe('Prometheus')
    expect(getVendor('nope')).toBeUndefined()
    expect(getVendor(null)).toBeUndefined()
  })

  it('groups by category and preserves order', () => {
    const groups = groupVendors()
    expect(groups.map((g) => g.category)).toEqual(['database', 'metrics', 'search', 'nosql', 'cloud'])
    expect(groups[0].vendors.some((v) => v.key === 'postgresql')).toBe(true)
  })

  it('filters by free-text query across key, label and blurb', () => {
    const groups = groupVendors('clickhouse')
    expect(groups).toHaveLength(1)
    expect(groups[0].vendors[0].key).toBe('clickhouse')

    expect(groupVendors('olap')[0].vendors[0].key).toBe('clickhouse')
    expect(groupVendors('zzz')).toHaveLength(0)
  })
})
