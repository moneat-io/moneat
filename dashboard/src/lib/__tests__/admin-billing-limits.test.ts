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
import {
  formatQuotaLimit,
  formatTotalQuotaLimit,
  normalizeQuotaForForm,
  normalizeQuotaForRequest,
  totalQuotaForRequest,
} from '../admin-billing-limits'

const JS_ROUNDED_LONG_MAX = 9_223_372_036_854_776_000
const SAFE_UNLIMITED_SENTINEL = 9_007_199_254_740_000

describe('admin billing limit helpers', () => {
  it('normalizes JavaScript-rounded Long.MAX_VALUE into a safe unlimited sentinel', () => {
    expect(normalizeQuotaForForm(JS_ROUNDED_LONG_MAX)).toBe(SAFE_UNLIMITED_SENTINEL)
    expect(normalizeQuotaForRequest(JS_ROUNDED_LONG_MAX)).toBe(SAFE_UNLIMITED_SENTINEL)
  })

  it('formats unlimited sentinels instead of showing giant integers', () => {
    expect(formatQuotaLimit(JS_ROUNDED_LONG_MAX)).toBe('Unlimited')
    expect(formatTotalQuotaLimit([JS_ROUNDED_LONG_MAX, 0, 0, 0])).toBe('Unlimited')
  })

  it('uses the safe sentinel for total request limits when any component is unlimited', () => {
    expect(totalQuotaForRequest([SAFE_UNLIMITED_SENTINEL, 1, 2, 3])).toBe(SAFE_UNLIMITED_SENTINEL)
  })

  it('caps oversized aggregate request limits at the safe sentinel', () => {
    const almostUnlimited = SAFE_UNLIMITED_SENTINEL - 1

    expect(totalQuotaForRequest([almostUnlimited, 10])).toBe(SAFE_UNLIMITED_SENTINEL)
    expect(formatTotalQuotaLimit([almostUnlimited, 10])).toBe('Unlimited')
  })

  it('keeps finite totals numeric', () => {
    expect(formatTotalQuotaLimit([10, 20, 30, 40])).toBe('100')
    expect(totalQuotaForRequest([10, 20, 30, 40])).toBe(100)
  })
})
