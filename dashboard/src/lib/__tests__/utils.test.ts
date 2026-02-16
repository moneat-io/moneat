// Moneat - Mobile-First Error Monitoring Platform
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
import { cn, formatRelativeTime } from '../utils'

describe('utils', () => {
  describe('cn (className merger)', () => {
    it('merges class names', () => {
      expect(cn('foo', 'bar')).toBe('foo bar')
    })

    it('handles conditional classes', () => {
      expect(cn('foo', false && 'bar', 'baz')).toBe('foo baz')
    })

    it('merges tailwind classes correctly', () => {
      expect(cn('px-2 py-1', 'px-4')).toBe('py-1 px-4')
    })

    it('handles empty inputs', () => {
      expect(cn()).toBe('')
    })
  })

  describe('formatRelativeTime', () => {
    let originalDate: typeof Date

    beforeEach(() => {
      originalDate = global.Date
      const mockNow = new Date('2024-02-11T12:00:00Z')
      global.Date = class extends originalDate {
        constructor(...args: any[]) {
          if (args.length === 0) {
            super(mockNow.toISOString())
          } else {
            super(...(args as [string]))
          }
        }
        static now() {
          return mockNow.getTime()
        }
      } as any
    })

    afterEach(() => {
      global.Date = originalDate
    })

    it('returns "unknown" for undefined', () => {
      expect(formatRelativeTime(undefined)).toBe('unknown')
    })

    it('returns "just now" for < 60 seconds', () => {
      const thirtySecondsAgo = new Date('2024-02-11T11:59:30Z').getTime()
      expect(formatRelativeTime(thirtySecondsAgo)).toBe('just now')
    })

    it('returns minutes for < 1 hour', () => {
      const fiveMinutesAgo = new Date('2024-02-11T11:55:00Z').getTime()
      expect(formatRelativeTime(fiveMinutesAgo)).toBe('5m ago')
    })

    it('returns hours for < 1 day', () => {
      const threeHoursAgo = new Date('2024-02-11T09:00:00Z').getTime()
      expect(formatRelativeTime(threeHoursAgo)).toBe('3h ago')
    })

    it('returns days for < 1 week', () => {
      const threeDaysAgo = new Date('2024-02-08T12:00:00Z').getTime()
      expect(formatRelativeTime(threeDaysAgo)).toBe('3d ago')
    })

    it('returns localized date for >= 1 week', () => {
      const twoWeeksAgo = new Date('2024-01-28T12:00:00Z').getTime()
      const result = formatRelativeTime(twoWeeksAgo)
      expect(result).toMatch(/1\/28\/2024/)
    })

    it('handles ISO 8601 string format with T', () => {
      const isoString = '2024-02-11T11:55:00Z'
      expect(formatRelativeTime(isoString)).toBe('5m ago')
    })

    it('handles ClickHouse DateTime format (YYYY-MM-DD HH:MM:SS) as UTC', () => {
      const clickhouseFormat = '2024-02-11 11:55:00'
      expect(formatRelativeTime(clickhouseFormat)).toBe('5m ago')
    })

    it('handles Unix timestamp (number)', () => {
      const timestamp = new Date('2024-02-11T11:55:00Z').getTime()
      expect(formatRelativeTime(timestamp)).toBe('5m ago')
    })
  })
})
