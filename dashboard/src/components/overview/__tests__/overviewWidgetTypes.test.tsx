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
import {OVERVIEW_WIDGET_TYPES} from '../../dashboards/extendedWidgetTypes'
import {OVERVIEW_WIDGETS, overviewWidgetDef} from '../overviewWidgetTypes'

describe('overview widget registry', () => {
  it('has a definition for every registered overview widget type', () => {
    for (const type of OVERVIEW_WIDGET_TYPES) {
      const def = OVERVIEW_WIDGETS[type]
      expect(def, `missing registry entry for ${type}`).toBeDefined()
      expect(typeof def.component).toBe('function')
      expect(def.label.length).toBeGreaterThan(0)
      expect(def.defaultSize.w).toBeGreaterThanOrEqual(1)
      expect(def.defaultSize.w).toBeLessThanOrEqual(12)
      expect(def.defaultSize.h).toBeGreaterThanOrEqual(1)
      expect(def.minH).toBeGreaterThanOrEqual(1)
    }
  })

  it('does not define extra types beyond the registered set', () => {
    for (const type of Object.keys(OVERVIEW_WIDGETS)) {
      expect(OVERVIEW_WIDGET_TYPES.has(type)).toBe(true)
    }
  })

  it('overviewWidgetDef returns undefined for unknown types', () => {
    expect(overviewWidgetDef('not_a_widget')).toBeUndefined()
    expect(overviewWidgetDef('service_health')).toBeDefined()
  })
})
