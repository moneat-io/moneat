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
import type {DashboardVariable} from '@/lib/api'
import {
  activePreset, effectiveValue, realOptions, refreshLabel, selectedValues,
  timeRangeLabel, timeRangeWindow, variableDisplay,
} from '../dashboardToolbarHelpers'

const variable = (overrides: Partial<DashboardVariable> = {}): DashboardVariable => ({
  name: 'v', type: 'custom', options: [], ...overrides,
})

describe('time helpers', () => {
  it('matches presets exactly', () => {
    expect(activePreset({from: 'now-24h', to: 'now'})?.label).toBe('24h')
    expect(activePreset({from: 'now-3h', to: 'now'})).toBeUndefined()
  })

  it('labels relative presets and custom ranges', () => {
    expect(timeRangeLabel({from: 'now-1h', to: 'now'})).toBe('Past 1 hour')
    expect(timeRangeLabel({from: 'now-24h', to: 'now'})).toBe('Past 24 hours')
    expect(timeRangeLabel({from: 'now-3h', to: 'now'})).toBe('Custom range')
    expect(timeRangeLabel({from: '', to: ''})).toBe('Custom')
  })

  it('renders the resolved window', () => {
    expect(timeRangeWindow({from: 'now-1h', to: 'now'})).toBe('now-1h → now')
  })
})

describe('refreshLabel', () => {
  it('maps known intervals and falls back to Off', () => {
    expect(refreshLabel(0)).toBe('Off')
    expect(refreshLabel(30_000)).toBe('30s')
    expect(refreshLabel(123)).toBe('Off')
  })
})

describe('variable value helpers', () => {
  it('splits multi-values and ignores All/empty', () => {
    expect(selectedValues('a,b , c')).toEqual(['a', 'b', 'c'])
    expect(selectedValues('$__all')).toEqual([])
    expect(selectedValues('')).toEqual([])
    expect(selectedValues(undefined)).toEqual([])
  })

  it('strips the All sentinel from options', () => {
    expect(realOptions(variable({options: ['$__all', 'a', 'b']}))).toEqual(['a', 'b'])
  })

  it('resolves the effective value through the fallback chain', () => {
    expect(effectiveValue(variable({current: 'c', default_value: 'd'}), 'v')).toBe('v')
    expect(effectiveValue(variable({current: 'c', default_value: 'd'}), undefined)).toBe('c')
    expect(effectiveValue(variable({default_value: 'd'}), undefined)).toBe('d')
    expect(effectiveValue(variable(), undefined)).toBe('')
  })

  it('formats the pill display text', () => {
    expect(variableDisplay(variable(), '')).toBe('(none)')
    expect(variableDisplay(variable(), '$__all')).toBe('all')
    expect(variableDisplay(variable(), 'production')).toBe('production')
    expect(variableDisplay(variable({multi: true, options: ['a', 'b', 'c']}), 'a,b')).toBe('2 of 3')
    expect(variableDisplay(variable({multi: true, options: ['a', 'b']}), 'a')).toBe('a')
    expect(variableDisplay(variable({multi: true, options: []}), 'a,b')).toBe('2 selected')
  })
})
