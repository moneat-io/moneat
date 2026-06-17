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

import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import {
  parseDate,
  formatDateTime,
  formatDate,
  formatMonthDay,
  formatTime,
  formatTimeHM,
  formatTimeHM12,
  formatDateTimeWithMs,
  formatTimeWithMs,
  formatDateTimeFull,
  browserTimezone,
} from '../date-format'

const ISO = '2025-01-15T14:34:56.789Z'
const DATE_OBJ = new Date(ISO)
const EPOCH_MS = DATE_OBJ.getTime()
const TZ = 'UTC'

// ──── parseDate ────

describe('parseDate', () => {
  let originalDate: typeof Date

  beforeEach(() => {
    originalDate = global.Date
  })

  afterEach(() => {
    global.Date = originalDate
  })

  it('handles ClickHouse DateTime64 format in strict mobile date parsers', () => {
    global.Date = class extends originalDate {
      constructor(value?: string | number | Date) {
        if (value === undefined) {
          super()
        } else if (typeof value === 'string' && value.includes(' ')) {
          super(Number.NaN)
        } else {
          super(value)
        }
      }
    } as unknown as DateConstructor

    expect(parseDate('2025-01-15 14:34:56.789').toISOString()).toBe(ISO)
  })
})

// ──── formatDateTime ────

describe('formatDateTime', () => {
  it('formats a Date object', () => {
    const result = formatDateTime(DATE_OBJ, TZ)
    expect(result).toContain('Jan')
    expect(result).toContain('15')
    expect(result).toContain('34')
    expect(result).toContain('56')
  })

  it('formats a string date', () => {
    const result = formatDateTime(ISO, TZ)
    expect(result).toContain('Jan')
    expect(result).toContain('15')
  })

  it('formats a numeric timestamp', () => {
    const result = formatDateTime(EPOCH_MS, TZ)
    expect(result).toContain('Jan')
    expect(result).toContain('15')
  })

  it('returns the raw string for an invalid date', () => {
    expect(formatDateTime('invalid', TZ)).toBe('invalid')
  })
})

// ──── formatDate ────

describe('formatDate', () => {
  it('formats a Date object', () => {
    const result = formatDate(DATE_OBJ, TZ)
    expect(result).toContain('Jan')
    expect(result).toContain('15')
    expect(result).toContain('2025')
  })

  it('formats a string date', () => {
    const result = formatDate(ISO, TZ)
    expect(result).toContain('2025')
  })

  it('formats a numeric timestamp', () => {
    const result = formatDate(EPOCH_MS, TZ)
    expect(result).toContain('2025')
  })

  it('returns the raw string for an invalid date', () => {
    expect(formatDate('invalid', TZ)).toBe('invalid')
  })
})

// ──── formatMonthDay ────

describe('formatMonthDay', () => {
  it('formats a Date object', () => {
    const result = formatMonthDay(DATE_OBJ, TZ)
    expect(result).toContain('Jan')
    expect(result).toContain('15')
  })

  it('formats a string date', () => {
    const result = formatMonthDay(ISO, TZ)
    expect(result).toContain('Jan')
  })

  it('formats a numeric timestamp', () => {
    const result = formatMonthDay(EPOCH_MS, TZ)
    expect(result).toContain('15')
  })

  it('returns the raw string for an invalid date', () => {
    expect(formatMonthDay('invalid', TZ)).toBe('invalid')
  })
})

// ──── formatTime ────

describe('formatTime', () => {
  it('formats a Date object in 24h', () => {
    const result = formatTime(DATE_OBJ, TZ)
    expect(result).toContain('14')
    expect(result).toContain('34')
    expect(result).toContain('56')
  })

  it('formats a string date', () => {
    const result = formatTime(ISO, TZ)
    expect(result).toContain('34')
  })

  it('formats a numeric timestamp', () => {
    const result = formatTime(EPOCH_MS, TZ)
    expect(result).toContain('56')
  })

  it('returns the raw string for an invalid date', () => {
    expect(formatTime('invalid', TZ)).toBe('invalid')
  })
})

// ──── formatTimeHM ────

describe('formatTimeHM', () => {
  it('formats a Date object in 24h HH:MM', () => {
    const result = formatTimeHM(DATE_OBJ, TZ)
    expect(result).toContain('14')
    expect(result).toContain('34')
    expect(result).not.toContain('56')
  })

  it('formats a string date', () => {
    const result = formatTimeHM(ISO, TZ)
    expect(result).toContain('14')
  })

  it('formats a numeric timestamp', () => {
    const result = formatTimeHM(EPOCH_MS, TZ)
    expect(result).toContain('34')
  })

  it('returns the raw string for an invalid date', () => {
    expect(formatTimeHM('invalid', TZ)).toBe('invalid')
  })
})

// ──── formatTimeHM12 ────

describe('formatTimeHM12', () => {
  it('formats a Date object in 12h', () => {
    const result = formatTimeHM12(DATE_OBJ, TZ)
    expect(result).toContain('2')
    expect(result).toContain('34')
    expect(result).toMatch(/PM/i)
  })

  it('formats a string date', () => {
    const result = formatTimeHM12(ISO, TZ)
    expect(result).toMatch(/PM/i)
  })

  it('formats a numeric timestamp', () => {
    const result = formatTimeHM12(EPOCH_MS, TZ)
    expect(result).toContain('34')
  })

  it('returns the raw string for an invalid date', () => {
    expect(formatTimeHM12('invalid', TZ)).toBe('invalid')
  })
})

// ──── formatDateTimeWithMs ────

describe('formatDateTimeWithMs', () => {
  it('includes milliseconds for a Date object', () => {
    const result = formatDateTimeWithMs(DATE_OBJ, TZ)
    expect(result).toContain('.789')
  })

  it('formats a string date with ms', () => {
    const result = formatDateTimeWithMs(ISO, TZ)
    expect(result).toContain('.789')
  })

  it('formats a numeric timestamp with ms', () => {
    const result = formatDateTimeWithMs(EPOCH_MS, TZ)
    expect(result).toContain('.789')
  })

  it('returns the raw string for an invalid date', () => {
    expect(formatDateTimeWithMs('invalid', TZ)).toBe('invalid')
  })
})

// ──── formatTimeWithMs ────

describe('formatTimeWithMs', () => {
  it('includes milliseconds for a Date object', () => {
    const result = formatTimeWithMs(DATE_OBJ, TZ)
    expect(result).toContain('14')
    expect(result).toContain('.789')
  })

  it('formats a string date', () => {
    const result = formatTimeWithMs(ISO, TZ)
    expect(result).toContain('.789')
  })

  it('formats a numeric timestamp', () => {
    const result = formatTimeWithMs(EPOCH_MS, TZ)
    expect(result).toContain('.789')
  })

  it('returns the raw string for an invalid date', () => {
    expect(formatTimeWithMs('invalid', TZ)).toBe('invalid')
  })
})

// ──── formatDateTimeFull ────

describe('formatDateTimeFull', () => {
  it('includes weekday and timezone for a Date object', () => {
    const result = formatDateTimeFull(DATE_OBJ, TZ)
    expect(result).toContain('Wed')
    expect(result).toContain('Jan')
    expect(result).toContain('2025')
    expect(result).toMatch(/UTC|Coordinated/)
  })

  it('formats a string date', () => {
    const result = formatDateTimeFull(ISO, TZ)
    expect(result).toContain('Wed')
  })

  it('formats a numeric timestamp', () => {
    const result = formatDateTimeFull(EPOCH_MS, TZ)
    expect(result).toContain('2025')
  })

  it('returns the raw string for an invalid date', () => {
    expect(formatDateTimeFull('invalid', TZ)).toBe('invalid')
  })
})

// ──── browserTimezone ────

describe('browserTimezone', () => {
  it('returns a non-empty string', () => {
    const tz = browserTimezone()
    expect(typeof tz).toBe('string')
    expect(tz.length).toBeGreaterThan(0)
  })
})
