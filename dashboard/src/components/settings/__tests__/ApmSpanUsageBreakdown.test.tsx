import {fireEvent, render, screen} from '@testing-library/react'
import {describe, expect, it} from 'vitest'
import {ApmSpanUsageBreakdown} from '../ApmSpanUsageBreakdown'

const apmSpanDebugResponse = {
  organizationId: 1,
  periodStart: '2026-04-01',
  periodEnd: '2026-04-30',
  totalSpans: 2180,
  groups: [
    {
      source: 'datadog',
      service: 'checkout-api',
      operation: 'rack.request',
      resource: 'GET /checkout',
      spanType: 'web',
      env: 'prod',
      kind: 'server',
      scopeName: 'rails',
      scopeVersion: '7.1.0',
      projectId: null,
      projectName: null,
      projectSlug: null,
      spanCount: 1200,
      traceCount: 450,
      errorCount: 3,
      avgDurationMs: 82.4,
      maxDurationMs: 1205.25,
      percentage: 55.05,
      sampleTraceId: '1234567890abcdef',
      latestSpanAt: '2026-04-20 14:12:44.123456',
    },
    {
      source: 'sentry',
      service: '',
      operation: '',
      resource: '',
      spanType: '',
      env: '',
      kind: '',
      scopeName: '',
      scopeVersion: '',
      projectId: 10,
      projectName: 'Web App',
      projectSlug: 'web-app',
      spanCount: 980,
      traceCount: 120,
      errorCount: 0,
      avgDurationMs: 4.2,
      maxDurationMs: 9.8,
      percentage: 144.95,
      sampleTraceId: '',
      latestSpanAt: '',
    },
  ],
}

describe('ApmSpanUsageBreakdown', () => {
  it('keeps sources collapsed until expanded', () => {
    render(
      <ApmSpanUsageBreakdown
        debug={apmSpanDebugResponse}
        error={undefined}
        isLoading={false}
        timezone="UTC"
      />
    )

    expect(screen.queryByText('checkout-api')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', {name: /show sources/i}))

    expect(screen.getByText('checkout-api')).toBeInTheDocument()
    expect(screen.getByText('Datadog')).toBeInTheDocument()
    expect(screen.getByText('Sentry')).toBeInTheDocument()
    expect(screen.getByText('GET /checkout')).toBeInTheDocument()
    expect(screen.getByText('rails@7.1.0')).toBeInTheDocument()
    expect(screen.getByText('1234567890ab...')).toBeInTheDocument()
    expect(screen.getByText('82 ms avg')).toBeInTheDocument()
    expect(screen.getByText('1.21 s max')).toBeInTheDocument()
    expect(screen.getByText('Web App')).toBeInTheDocument()
    expect(screen.getByText('Unknown service')).toBeInTheDocument()
    expect(screen.getByText('No scope')).toBeInTheDocument()
    expect(screen.getByText('100.0%')).toBeInTheDocument()
    expect(screen.getAllByText('None').length).toBeGreaterThan(0)
  })

  it('shows loading, error, and empty states after expansion', () => {
    const {rerender} = render(
      <ApmSpanUsageBreakdown
        debug={undefined}
        error={undefined}
        isLoading
        timezone="UTC"
      />
    )

    fireEvent.click(screen.getByRole('button', {name: /show sources/i}))
    expect(screen.getByText('Loading APM span sources...')).toBeInTheDocument()

    rerender(
      <ApmSpanUsageBreakdown
        debug={undefined}
        error={new Error('failed')}
        isLoading={false}
        timezone="UTC"
      />
    )
    expect(screen.getByText('Unable to load APM span sources.')).toBeInTheDocument()

    rerender(
      <ApmSpanUsageBreakdown
        debug={{...apmSpanDebugResponse, groups: []}}
        error={undefined}
        isLoading={false}
        timezone="UTC"
      />
    )
    expect(screen.getByText('No APM spans stored for this billing period.')).toBeInTheDocument()
  })
})
