import {describe, expect, it} from 'vitest'

import {
  APM_TIME_RANGE_OPTIONS,
  SERVICE_FACET_SCHEMA,
  apdexHeatClass,
  apmRangeLabel,
  errorSeverityBorder,
  errorSeverityTone,
  formatMs,
  formatRps,
  serviceTypeLabel,
  severityFill,
  statusBadgeVariant,
  statusLabel,
  statusTone,
  type ServiceType,
} from '@/lib/apm-view'
import type {ApmTimeRange} from '@/lib/api'

describe('apm view helpers', () => {
  it('formats rate and latency values for table cells', () => {
    expect(formatRps(999)).toBe('999')
    expect(formatRps(1_250)).toBe('1.3k')
    expect(formatMs(250)).toBe('250ms')
    expect(formatMs(1_000)).toBe('1s')
    expect(formatMs(1_250)).toBe('1.25s')
  })

  it('maps service status and severity values to UI tones', () => {
    expect(statusBadgeVariant('alerting')).toBe('danger')
    expect(statusBadgeVariant('degraded')).toBe('warning')
    expect(statusBadgeVariant('healthy')).toBe('success')
    expect(statusTone('alerting')).toBe('danger')
    expect(statusTone('degraded')).toBe('warning')
    expect(statusTone('healthy')).toBe('success')
    expect(statusLabel('degraded')).toBe('degraded')
    expect(severityFill('bad')).toBe('bg-danger-solid')
    expect(severityFill('warn')).toBe('bg-warning-solid')
    expect(severityFill('good')).toBe('bg-success-solid')
  })

  it('maps service and error detail helpers', () => {
    expect(serviceTypeLabel('web')).toBe('web')
    expect(serviceTypeLabel('queue' as ServiceType)).toBe('queue')
    expect(errorSeverityTone('warn')).toBe('warning')
    expect(errorSeverityTone('error')).toBe('danger')
    expect(errorSeverityBorder('warn')).toBe('border-l-warning-solid')
    expect(errorSeverityBorder('fatal')).toBe('border-l-danger-solid')
  })

  it('maps apdex heat and range labels', () => {
    expect(apdexHeatClass('success')).toContain('bg-success-bg')
    expect(apdexHeatClass('warning')).toContain('bg-warning-bg')
    expect(apdexHeatClass('danger')).toContain('bg-danger-bg')
    expect(apdexHeatClass('neutral')).toContain('bg-muted')
    expect(apmRangeLabel('24h')).toBe('past 24h')
    expect(apmRangeLabel('3h' as ApmTimeRange)).toBe('past 3h')
  })

  it('exposes catalog filter metadata', () => {
    expect(APM_TIME_RANGE_OPTIONS.map((option) => option.value)).toEqual(['1h', '6h', '24h', '7d', '30d', '90d'])
    expect(SERVICE_FACET_SCHEMA.map((facet) => facet.key)).toEqual(['env', 'type', 'source'])
  })
})
