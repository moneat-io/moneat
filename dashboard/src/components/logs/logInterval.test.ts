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
import {logIntervalToMs} from './logInterval'

describe('logIntervalToMs', () => {
  it('maps supported log aggregate intervals to milliseconds', () => {
    expect(logIntervalToMs('1m')).toBe(60_000)
    expect(logIntervalToMs('5m')).toBe(300_000)
    expect(logIntervalToMs('15m')).toBe(900_000)
    expect(logIntervalToMs('1h')).toBe(3_600_000)
    expect(logIntervalToMs('2h')).toBe(7_200_000)
    expect(logIntervalToMs('4h')).toBe(14_400_000)
    expect(logIntervalToMs('12h')).toBe(43_200_000)
    expect(logIntervalToMs('1d')).toBe(86_400_000)
  })

  it('defaults to one hour for missing or unknown intervals', () => {
    expect(logIntervalToMs(undefined)).toBe(3_600_000)
    expect(logIntervalToMs('bogus')).toBe(3_600_000)
  })
})
