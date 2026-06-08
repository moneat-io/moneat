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
  compactHostMetricChartData,
  formatHostMetricAxisTick,
  formatHostMetricTooltipLabel,
  toHostMetricChartTimestamp,
} from '../host-metrics-chart'

const TZ = 'UTC'
const ONE_HOUR_SECONDS = 3_600
const SEVEN_DAYS_SECONDS = 604_800
const THIRTY_DAYS_SECONDS = 2_592_000
const HOUR_MS = 3_600_000

describe('host metric chart helpers', () => {
  it('keeps repeated clock labels at distinct x-axis positions', () => {
    const firstDay = toHostMetricChartTimestamp(Date.UTC(2026, 0, 15, 17, 50) / 1000)
    const secondDay = toHostMetricChartTimestamp(Date.UTC(2026, 0, 16, 17, 50) / 1000)

    expect(formatHostMetricAxisTick(firstDay, ONE_HOUR_SECONDS, TZ)).toBe('17:50')
    expect(formatHostMetricAxisTick(secondDay, ONE_HOUR_SECONDS, TZ)).toBe('17:50')
    expect(firstDay).not.toBe(secondDay)
  })

  it('formats long-range ticks with dates instead of repeated clock-only labels', () => {
    const timestamp = toHostMetricChartTimestamp(Date.UTC(2026, 0, 15, 17, 50) / 1000)

    expect(formatHostMetricAxisTick(timestamp, THIRTY_DAYS_SECONDS, TZ)).toContain('Jan')
    expect(formatHostMetricAxisTick(timestamp, THIRTY_DAYS_SECONDS, TZ)).toContain('15')
  })

  it('uses a full timestamp in tooltip labels', () => {
    const timestamp = toHostMetricChartTimestamp(Date.UTC(2026, 0, 15, 17, 50) / 1000)
    const label = formatHostMetricTooltipLabel(timestamp, TZ)

    expect(label).toContain('Jan')
    expect(label).toContain('15')
    expect(label).toContain('50')
  })

  it('passes through invalid labels', () => {
    expect(formatHostMetricAxisTick('not-a-time', THIRTY_DAYS_SECONDS, TZ)).toBe('not-a-time')
    expect(formatHostMetricTooltipLabel('not-a-time', TZ)).toBe('not-a-time')
  })

  it('keeps short ranges at raw resolution', () => {
    const data = [
      {timestamp: 1000, CPU: 10},
      {timestamp: 2000, CPU: 20},
    ]

    expect(compactHostMetricChartData(data, SEVEN_DAYS_SECONDS, ['CPU'], 1)).toBe(data)
  })

  it('compacts long ranges and averages only present values', () => {
    const start = Date.UTC(2026, 0, 1)
    const data = Array.from({length: 720}, (_, index) => ({
      timestamp: start + index * HOUR_MS,
      Received: index % 2 === 0 ? 120 : null,
      Sent: 60,
    }))

    const compacted = compactHostMetricChartData(data, THIRTY_DAYS_SECONDS, ['Received', 'Sent'], 24)

    expect(compacted).toHaveLength(24)
    expect(compacted.every((point) => point.Sent === 60)).toBe(true)
    expect(compacted.every((point) => point.Received === 120)).toBe(true)
    expect(compacted.map((point) => point.timestamp)).toEqual(
      compacted.map((point) => point.timestamp).sort((left, right) => left - right)
    )
  })
})
