import React from 'react'
import {render, screen, within} from '@testing-library/react'
import {describe, expect, it, vi} from 'vitest'

vi.mock('recharts', () => {
  const ChartPart = ({children, ...props}: {children?: React.ReactNode; [key: string]: unknown}) => (
    <g data-testid="chart-part" data-key={String(props.dataKey ?? props.x ?? props.y ?? '')}>
      {children}
    </g>
  )
  const ChartRoot = ({children}: {children?: React.ReactNode}) => (
    <svg data-testid="chart-root">{children}</svg>
  )
  return {
    Area: ChartPart,
    AreaChart: ChartRoot,
    CartesianGrid: ChartPart,
    Line: ChartPart,
    LineChart: ChartRoot,
    ReferenceArea: ChartPart,
    ReferenceLine: ChartPart,
    ResponsiveContainer: ({children}: {children?: React.ReactNode}) => (
      <div data-testid="responsive-container">{children}</div>
    ),
    Tooltip: ChartPart,
    XAxis: ChartPart,
    YAxis: ChartPart,
  }
})

import {
  ApdexHeat,
  ApmKpiRow,
  BarGauge,
  EnvPills,
  ErrorBars,
  ErrorList,
  HttpStatusBadge,
  InfoCallout,
  LatencyDistribution,
  LatencyLegendTable,
  LatencyPercentilesChart,
  MetaChip,
  MeterBar,
  MethodChip,
  ServiceStatusBadge,
  Sparkline,
  ThroughputChart,
  TypeChip,
  Waterfall,
} from '@/components/apm/ServiceVisuals'

const latencySeries = [
  {t: '10:00', p50: 10, p90: 20, p95: 30, p99: 50},
  {t: '10:05', p50: 12, p90: 24, p95: 34, p99: 60},
]

describe('ServiceVisuals', () => {
  it('renders service chips, gauges, heat cells, and status badges', () => {
    const {container} = render(
      <div>
        <TypeChip type="web" />
        <ServiceStatusBadge status="alerting" />
        <ServiceStatusBadge status="degraded" />
        <ServiceStatusBadge status="healthy" />
        <EnvPills pills={[{label: 'prod', chart: 1}, {label: 'unknown', chart: 99}]} />
        <MetaChip>v1.2.3</MetaChip>
        <InfoCallout>dependency insight</InfoCallout>
        <MeterBar pct={0} level="good" value="0%" />
        <MeterBar pct={150} level="bad" value="100%" />
        <ApdexHeat value="0.99" tone="success" />
        <BarGauge
          rows={[
            {label: 'GET /ok', valueText: '10ms', pct: 1, level: 'good'},
            {label: 'POST /slow', valueText: '320ms', pct: 52, level: 'warn'},
            {label: 'POST /fail', valueText: '1.2s', pct: 125, level: 'bad'},
          ]}
        />
        <MethodChip method="POST" />
        <HttpStatusBadge status={200} />
        <HttpStatusBadge status={500} />
      </div>,
    )

    expect(screen.getByText('web')).toBeInTheDocument()
    expect(screen.getByText('alerting')).toBeInTheDocument()
    expect(screen.getByText('degraded')).toBeInTheDocument()
    expect(screen.getByText('healthy')).toBeInTheDocument()
    expect(screen.getByText('prod')).toBeInTheDocument()
    expect(screen.getByText('unknown')).toBeInTheDocument()
    expect(screen.getByText('dependency insight')).toBeInTheDocument()
    expect(screen.getByText('GET /ok')).toBeInTheDocument()
    expect(screen.getByText('POST')).toBeInTheDocument()
    expect(screen.getByText('200')).toBeInTheDocument()
    expect(screen.getByText('500')).toBeInTheDocument()
    expect(container.querySelector('[style*="width: 2%"]')).not.toBeNull()
    expect(container.querySelector('[style*="width: 100%"]')).not.toBeNull()
  })

  it('renders sparklines, KPI cards, legends, and charts', () => {
    const {container} = render(
      <div>
        <Sparkline values={[7]} />
        <Sparkline values={[2, 8, 4]} />
        <ApmKpiRow
          kpis={[
            {label: 'p95', value: '120ms', valueTone: 'success', delta: {value: '-5%', direction: 'down'}},
            {label: 'errors', value: '0.2%'},
          ]}
        />
        <LatencyPercentilesChart
          series={latencySeries}
          thresholdMs={40}
          thresholdLabel="SLO"
          deployAt="10:05"
        />
        <LatencyLegendTable series={latencySeries} />
        <ThroughputChart points={[{t: '10:00', rps: 10}, {t: '10:05', rps: 15, errors: 2}]} deployAt="10:05" />
        <ThroughputChart
          points={[{t: '10:00', rps: 10}, {t: '10:05', rps: 15, errors: 2}]}
          deployAt="10:05"
          withErrors
        />
      </div>,
    )

    expect(screen.getAllByText('p95')).toHaveLength(2)
    expect(screen.getByText('errors')).toBeInTheDocument()
    expect(screen.getByText('p99')).toBeInTheDocument()
    const p50Row = screen.getByText('p50').closest('tr')
    if (p50Row === null) {
      throw new Error('Expected p50 row to render')
    }
    expect(within(p50Row).getByText('10')).toBeInTheDocument()
    expect(container.querySelectorAll('polyline')).toHaveLength(2)
    expect(screen.getAllByTestId('responsive-container')).toHaveLength(3)
  })

  it('renders error, distribution, and waterfall states', () => {
    const {container, rerender} = render(<ErrorList errors={[]} />)
    expect(screen.getByText('No active errors on this surface.')).toBeInTheDocument()

    rerender(
      <div>
        <ErrorBars bars={[{h: 20, level: 'warn'}, {h: 80, level: 'bad'}]} />
        <LatencyDistribution
          bars={[{h: 20, band: 'good'}, {h: 45, band: 'warn'}, {h: 80, band: 'bad'}]}
          markers={[{label: 'p95', left: 35}, {label: 'p99', left: 85, p99: true}]}
          axis={['0ms', '500ms', '1s']}
        />
        <Waterfall
          rows={[
            {op: 'GET', desc: '/checkout', left: 0, width: 60, label: '120ms', tone: 'root', selected: true},
            {op: 'db', desc: 'orders', left: 10, width: 20, label: '40ms', tone: 'db', indent: 1},
            {op: 'throw', desc: 'Timeout', left: 35, width: 10, label: '20ms', tone: 'error'},
          ]}
        />
        <ErrorList
          showUsers
          errors={[
            {
              severity: 'warn',
              title: 'Timeout warning',
              sub: 'checkout.ts',
              chips: ['v1', 'first seen'],
              events: '12',
            },
            {
              severity: 'fatal',
              title: 'Unhandled exception',
              sub: 'payment.ts',
              chips: ['v2'],
              events: '3',
              users: '2',
              unhandled: true,
            },
          ]}
        />
      </div>,
    )

    expect(screen.getByText('p95')).toBeInTheDocument()
    expect(screen.getByText('p99')).toBeInTheDocument()
    expect(screen.getByText('orders')).toBeInTheDocument()
    expect(screen.getByText('Timeout warning')).toBeInTheDocument()
    expect(screen.getByText('Unhandled exception')).toBeInTheDocument()
    expect(screen.getByText('unhandled')).toBeInTheDocument()
    expect(container.querySelectorAll('[style*="height: 80%"]')).toHaveLength(2)
  })
})
