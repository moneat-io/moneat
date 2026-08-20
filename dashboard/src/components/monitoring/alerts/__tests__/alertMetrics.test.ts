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
  ALERT_METRIC_GROUPS,
  ALERT_METRIC_OPTIONS,
  alertMetricLabel,
  alertMetricTone,
  describeAlertRule,
  findAlertMetric,
  formatAlertDuration,
  formatAlertThreshold,
} from '../alertMetrics'

describe('alertMetrics', () => {
  it('keeps the three load-average windows distinct', () => {
    const load = ALERT_METRIC_OPTIONS.filter((option) => option.group === 'Load')
    expect(load.map((option) => option.value)).toEqual(['load_1', 'load_5', 'load_15'])
    expect(new Set(load.map((option) => option.label)).size).toBe(3)
  })

  it('lists metric groups once each, in declaration order', () => {
    expect(ALERT_METRIC_GROUPS).toEqual(['Compute', 'Storage', 'Load', 'Hardware'])
  })

  it('resolves a known metric and falls back to the raw key', () => {
    expect(findAlertMetric('cpu_percent')?.label).toBe('CPU usage')
    expect(findAlertMetric('not_a_metric')).toBeUndefined()
    expect(alertMetricLabel('not_a_metric')).toBe('not_a_metric')
    expect(alertMetricTone('not_a_metric')).toBe('neutral')
    expect(alertMetricTone('disk_percent')).toBe('warning')
  })

  it('appends a unit only where the metric has one', () => {
    expect(formatAlertThreshold('cpu_percent', 80)).toBe('80%')
    expect(formatAlertThreshold('temp_max', 85)).toBe('85°C')
    expect(formatAlertThreshold('load_5', 4)).toBe('4')
    expect(formatAlertThreshold('not_a_metric', 7)).toBe('7')
  })

  it('formats a rule as the sentence it represents', () => {
    expect(describeAlertRule('cpu_percent', '>', 80)).toBe('CPU usage is above 80%')
    expect(describeAlertRule('battery_percent', '<=', 20)).toBe(
      'Battery level is at or below 20%'
    )
    // An unrecognised comparator is passed through rather than dropped.
    expect(describeAlertRule('load_1', '~=', 4)).toBe('Load average (1m) ~= 4')
  })

  it('describes the sustain window in human units', () => {
    expect(formatAlertDuration(0)).toBe('Immediately')
    expect(formatAlertDuration(-30)).toBe('Immediately')
    expect(formatAlertDuration(300)).toBe('for 5m')
    expect(formatAlertDuration(3600)).toBe('for 1h')
    expect(formatAlertDuration(5400)).toBe('for 1h 30m')
  })
})
