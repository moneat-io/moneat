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

import type {OverviewResponse} from '@/lib/api/types'

export const overviewTestData: OverviewResponse = {
  systemStatus: {
    state: 'Action needed',
    severity: 'bad',
    counts: {incidents: 0, alerts: 3, degraded: 2, hostsOffline: 1},
    ai: {
      summary: 'checkout-api is degraded after deploy v2.4.1.',
      incidentId: null,
    },
  },
  kpis: [
    {
      id: 'errors',
      label: 'Errors · 24h',
      value: '48.3k',
      delta: {value: '312%', direction: 'up', tone: 'bad'},
      status: 'bad',
      spark: [6, 7, 5, 8, 6, 7, 9, 12, 18, 25, 27],
    },
    {
      id: 'latency',
      label: 'p95 latency',
      value: '412',
      unit: 'ms',
      delta: {value: '38%', direction: 'up', tone: 'bad'},
      status: 'warn',
      spark: [9, 8, 10, 9, 11, 10, 12, 14, 17, 21, 23],
    },
    {
      id: 'uptime',
      label: 'Uptime · 24h',
      value: '99.2',
      unit: '%',
      delta: {value: '0.4%', direction: 'down', tone: 'warn'},
      status: 'warn',
      spark: [100, 100, 99, 100, 99, 99, 98, 99, 99, 99, 99],
    },
  ],
  serviceHealth: [
    {
      name: 'checkout-api',
      env: 'prod',
      status: 'bad',
      reqPerMin: 3108,
      errorPct: 6.8,
      p95Ms: 920,
      apdex: 0.71,
      trend: [4, 5, 4, 6, 7, 9, 14, 19, 24, 30],
      issues: 14,
      deploy: {version: 'v2.4.1', ageLabel: '14m', tone: 'bad'},
    },
  ],
  telemetry: {
    errors: [6, 7, 5, 8, 6, 7, 9, 12, 18, 25, 27],
    latency: [9, 8, 10, 9, 11, 10, 12, 14, 17, 21, 23],
    throughput: [18, 19, 17, 20, 18, 19, 17, 18, 15, 13, 11],
    logs: [16, 15, 16, 18, 21, 20, 25, 31, 28, 35, 39],
    deployAtPct: 73,
    deployLabel: 'v2.4.1',
  },
  triage: {
    incidents: [],
    alerts: [{
      title: 'Elevated 5xx on checkout-api',
      detail: 'checkout-api reported 1.2k server errors',
      level: 'error',
      ageLabel: '6m',
    }],
    issues: [{
      level: 'error',
      title: 'Payment capture failed',
      detail: '1.2k events',
      ageLabel: '8m',
    }],
    security: [],
  },
  infra: {
    gauges: [
      {label: 'CPU', pct: 82, tone: 'warn'},
      {label: 'Mem', pct: 76, tone: 'warn'},
    ],
    containers: 142,
    pods: 38,
    upLabel: '23/24 up',
    offlineNode: 'node-use1-04',
  },
  uptime: {
    monitors: [{
      name: 'checkout flow',
      bars: ['up', 'up', 'warn', 'down'],
      uptimeLabel: 'DOWN',
      down: true,
    }],
    upLabel: '18/19 up',
    syntheticFailing: 'checkout flow',
    statusPages: '2 status pages',
  },
  deploys: [{
    version: 'v2.4.1',
    service: 'checkout-api',
    status: 'bad',
    label: 'regressing',
    ageLabel: '14m',
  }],
  activity: [{
    kind: 'deploy',
    text: 'v2.4.1 released to checkout-api',
    meta: '14m ago',
  }],
}
