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
import {fireEvent, render, screen} from '@testing-library/react'
import {SystemStatusWidget} from '../widgets/SystemStatusWidget'
import {KpiWidget} from '../widgets/KpiWidget'
import {ServiceHealthWidget} from '../widgets/ServiceHealthWidget'
import {TelemetryWidget} from '../widgets/TelemetryWidget'
import {TriageWidget} from '../widgets/TriageWidget'
import {InfraSummaryWidget} from '../widgets/InfraSummaryWidget'
import {UptimeSummaryWidget} from '../widgets/UptimeSummaryWidget'
import {DeploysWidget} from '../widgets/DeploysWidget'
import {ActivityWidget} from '../widgets/ActivityWidget'

describe('SystemStatusWidget', () => {
  it('renders the triage line and actions', () => {
    render(<SystemStatusWidget />)
    expect(screen.getByTestId('widget-system_status')).toBeInTheDocument()
    expect(screen.getByText('Action needed')).toBeInTheDocument()
    expect(screen.getByText('View incident')).toBeInTheDocument()
  })
})

describe('KpiWidget', () => {
  it('renders the first KPI by default', () => {
    render(<KpiWidget />)
    expect(screen.getByTestId('widget-kpi')).toBeInTheDocument()
    expect(screen.getByText('Errors · 24h')).toBeInTheDocument()
    expect(screen.getByText('48.3k')).toBeInTheDocument()
  })
  it('selects a KPI by display_config.kpiId', () => {
    render(<KpiWidget displayConfig={{kpiId: 'latency'}} />)
    expect(screen.getByText('p95 latency')).toBeInTheDocument()
    expect(screen.getByText('412')).toBeInTheDocument()
  })
})

describe('ServiceHealthWidget', () => {
  it('renders services from mock data', () => {
    render(<ServiceHealthWidget />)
    expect(screen.getByTestId('widget-service_health')).toBeInTheDocument()
    expect(screen.getByText('checkout-api')).toBeInTheDocument()
    expect(screen.getByText('View all')).toBeInTheDocument()
  })
})

describe('TelemetryWidget', () => {
  it('switches series tabs', () => {
    render(<TelemetryWidget />)
    expect(screen.getByTestId('widget-telemetry')).toBeInTheDocument()
    expect(screen.getByText('error rate')).toBeInTheDocument()
    fireEvent.click(screen.getByText('Logs'))
    expect(screen.getByText('log volume')).toBeInTheDocument()
  })
})

describe('TriageWidget', () => {
  it('renders the needs-attention sections', () => {
    render(<TriageWidget />)
    expect(screen.getByTestId('widget-triage')).toBeInTheDocument()
    expect(screen.getByText('Needs attention')).toBeInTheDocument()
    expect(screen.getByText('Elevated 5xx on checkout-api')).toBeInTheDocument()
  })
})

describe('InfraSummaryWidget', () => {
  it('renders resource gauges', () => {
    render(<InfraSummaryWidget />)
    expect(screen.getByTestId('widget-infra_summary')).toBeInTheDocument()
    expect(screen.getByText('Infrastructure')).toBeInTheDocument()
    expect(screen.getByText('82%')).toBeInTheDocument()
  })
})

describe('UptimeSummaryWidget', () => {
  it('renders monitor heartbeats', () => {
    render(<UptimeSummaryWidget />)
    expect(screen.getByTestId('widget-uptime_summary')).toBeInTheDocument()
    expect(screen.getByText('checkout flow')).toBeInTheDocument()
    expect(screen.getByText('DOWN')).toBeInTheDocument()
  })
})

describe('DeploysWidget', () => {
  it('renders recent deploys', () => {
    render(<DeploysWidget />)
    expect(screen.getByTestId('widget-deploys')).toBeInTheDocument()
    expect(screen.getByText('v2.4.1')).toBeInTheDocument()
    expect(screen.getByText('regressing')).toBeInTheDocument()
  })
})

describe('ActivityWidget', () => {
  it('renders the activity feed', () => {
    render(<ActivityWidget />)
    expect(screen.getByTestId('widget-activity')).toBeInTheDocument()
    expect(screen.getByText('Alert 5xx>2% triggered INC-204')).toBeInTheDocument()
  })
})
