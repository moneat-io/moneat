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
  OVERVIEW_FIXTURES,
  useActivity,
  useDeploys,
  useInfraSummary,
  useKpis,
  useServiceHealth,
  useSystemStatus,
  useTelemetry,
  useTriage,
  useUptimeSummary,
} from '../overviewMockData'

describe('overview fixtures', () => {
  it('has six KPIs with distinct ids', () => {
    const kpis = OVERVIEW_FIXTURES.kpis
    expect(kpis).toHaveLength(6)
    expect(new Set(kpis.map((k) => k.id)).size).toBe(6)
  })

  it('includes a degraded (bad) service', () => {
    expect(OVERVIEW_FIXTURES.serviceHealth.some((s) => s.status === 'bad')).toBe(true)
    expect(OVERVIEW_FIXTURES.serviceHealth.find((s) => s.name === 'checkout-api')).toBeDefined()
  })

  it('telemetry series share the same length', () => {
    const t = OVERVIEW_FIXTURES.telemetry
    const len = t.errors.length
    expect(t.latency).toHaveLength(len)
    expect(t.throughput).toHaveLength(len)
    expect(t.logs).toHaveLength(len)
    expect(t.deployAtPct).toBeGreaterThan(0)
    expect(t.deployAtPct).toBeLessThanOrEqual(100)
  })

  it('triage references the active incident', () => {
    expect(OVERVIEW_FIXTURES.triage.incidents[0].id).toBe('INC-204')
  })
})

describe('overview hooks return their fixtures', () => {
  it('expose each section', () => {
    expect(useSystemStatus()).toBe(OVERVIEW_FIXTURES.systemStatus)
    expect(useKpis()).toBe(OVERVIEW_FIXTURES.kpis)
    expect(useServiceHealth()).toBe(OVERVIEW_FIXTURES.serviceHealth)
    expect(useTelemetry()).toBe(OVERVIEW_FIXTURES.telemetry)
    expect(useTriage()).toBe(OVERVIEW_FIXTURES.triage)
    expect(useInfraSummary()).toBe(OVERVIEW_FIXTURES.infra)
    expect(useUptimeSummary()).toBe(OVERVIEW_FIXTURES.uptime)
    expect(useDeploys()).toBe(OVERVIEW_FIXTURES.deploys)
    expect(useActivity()).toBe(OVERVIEW_FIXTURES.activity)
  })
})
