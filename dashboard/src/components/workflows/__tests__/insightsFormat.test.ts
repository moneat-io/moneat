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
import {formatCount, formatDateTime, formatRate} from '../insightsFormat'

describe('formatRate', () => {
  it('formats a ratio as a whole-number percentage', () => {
    expect(formatRate(0.953)).toBe('95%')
    expect(formatRate(0)).toBe('0%')
    expect(formatRate(1)).toBe('100%')
  })

  it('clamps out-of-range values', () => {
    expect(formatRate(1.4)).toBe('100%')
    expect(formatRate(-0.2)).toBe('0%')
  })

  it('falls back for non-finite input', () => {
    expect(formatRate(Number.NaN)).toBe('0%')
  })
})

describe('formatCount', () => {
  it('rounds and groups numbers', () => {
    expect(formatCount(1234)).toBe('1,234')
    expect(formatCount(0)).toBe('0')
  })

  it('falls back for non-finite input', () => {
    expect(formatCount(Number.POSITIVE_INFINITY)).toBe('0')
  })
})

describe('formatDateTime', () => {
  it('formats an ISO timestamp', () => {
    expect(formatDateTime('2026-05-01T00:00:00Z')).toContain('2026')
  })

  it('returns a dash for missing values', () => {
    expect(formatDateTime()).toBe('—')
    expect(formatDateTime(null)).toBe('—')
  })
})
