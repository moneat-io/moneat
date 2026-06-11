import {describe, expect, it} from 'vitest'
import {
  ALL_TELEMETRY_SOURCE_IDS,
  feedbackSourceLabel,
  getTelemetrySourceStatus,
  loadTelemetrySourceIdsForService,
  parseTelemetrySourceIds,
  serializeTelemetrySourceIds,
  storeTelemetrySourceIdsForService,
  toggleTelemetrySourceId,
} from '@/lib/telemetry-sources'

describe('telemetry-sources', () => {
  it('parses valid source IDs and drops unknown values', () => {
    expect(parseTelemetrySourceIds('opentelemetry,unknown,sentry-sdk,opentelemetry')).toEqual([
      'opentelemetry',
      'sentry-sdk',
    ])
  })

  it('maps the old HTTP logs source to OpenTelemetry', () => {
    expect(parseTelemetrySourceIds('http-logs,datadog-agent')).toEqual(['opentelemetry', 'datadog-agent'])
  })

  it('serializes source IDs in selection order', () => {
    expect(serializeTelemetrySourceIds(['datadog-agent', 'opentelemetry'])).toBe('datadog-agent,opentelemetry')
  })

  it('does not let toggling remove the final selected source', () => {
    expect(toggleTelemetrySourceId(['opentelemetry'], 'opentelemetry')).toEqual(['opentelemetry'])
  })

  it('stores source selections per service', () => {
    storeTelemetrySourceIdsForService('svc-42', ['sentry-sdk', 'opentelemetry'])
    expect(loadTelemetrySourceIdsForService('svc-42')).toEqual(['sentry-sdk', 'opentelemetry'])
    expect(loadTelemetrySourceIdsForService('svc-43')).toEqual([])
  })

  it('reports Sentry-compatible SDK progress from service events', () => {
    expect(getTelemetrySourceStatus('sentry-sdk', {serviceEventCount: 0}).state).toBe('waiting')
    expect(getTelemetrySourceStatus('sentry-sdk', {serviceEventCount: 1}).state).toBe('receiving')
  })

  it('reports OTLP-style progress from API key usage', () => {
    expect(getTelemetrySourceStatus('opentelemetry', {otlpKeys: []}).state).toBe('waiting')
    expect(getTelemetrySourceStatus('opentelemetry', {otlpKeys: [{lastUsedAt: null}]}).state).toBe('configured')
    expect(getTelemetrySourceStatus('opentelemetry', {otlpKeys: [{lastUsedAt: '2026-05-26'}]}).state)
      .toBe('receiving')
  })

  it('reports Datadog Agent progress from key or host usage', () => {
    expect(getTelemetrySourceStatus('datadog-agent', {agentKeys: []}).state).toBe('waiting')
    expect(getTelemetrySourceStatus('datadog-agent', {agentKeys: [{lastUsedAt: null}]}).state)
      .toBe('configured')
    expect(getTelemetrySourceStatus('datadog-agent', {monitorHosts: [{last_seen_at: 123}]}).state)
      .toBe('receiving')
  })

  it('keeps the all-source fallback in the intended product order', () => {
    expect(ALL_TELEMETRY_SOURCE_IDS).toEqual([
      'opentelemetry',
      'sentry-sdk',
      'datadog-agent',
    ])
  })

  it('labels feedback telemetry sources from source metadata', () => {
    expect(feedbackSourceLabel('otlp', '')).toBe('OpenTelemetry')
    expect(feedbackSourceLabel('datadog', null)).toBe('Datadog')
    expect(feedbackSourceLabel('sentry', undefined)).toBe('Sentry-compatible SDK')
    expect(feedbackSourceLabel('custom', '  Embedded widget  ')).toBe('Embedded widget')
    expect(feedbackSourceLabel('custom', '')).toBe('Telemetry source')
  })
})
