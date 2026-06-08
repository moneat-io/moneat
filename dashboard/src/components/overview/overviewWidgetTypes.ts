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

import type {ComponentType} from 'react'
import {
  Activity,
  AlertTriangle,
  Boxes,
  Clock,
  Gauge,
  LineChart,
  ListChecks,
  type LucideIcon,
  Rocket,
  Server,
} from 'lucide-react'
import {SystemStatusWidget} from './widgets/SystemStatusWidget'
import {KpiWidget} from './widgets/KpiWidget'
import {ServiceHealthWidget} from './widgets/ServiceHealthWidget'
import {TelemetryWidget} from './widgets/TelemetryWidget'
import {TriageWidget} from './widgets/TriageWidget'
import {InfraSummaryWidget} from './widgets/InfraSummaryWidget'
import {UptimeSummaryWidget} from './widgets/UptimeSummaryWidget'
import {DeploysWidget} from './widgets/DeploysWidget'
import {ActivityWidget} from './widgets/ActivityWidget'

export interface OverviewWidgetDef {
  label: string
  description: string
  icon: LucideIcon
  /** Default grid footprint in the 12-column dashboard grid. */
  defaultSize: {w: number; h: number}
  minH: number
  component: ComponentType<{displayConfig?: Record<string, string>}>
}

export const OVERVIEW_WIDGETS: Record<string, OverviewWidgetDef> = {
  system_status: {
    label: 'System status',
    description: 'Hero triage line with the AI incident summary',
    icon: AlertTriangle,
    defaultSize: {w: 12, h: 3},
    minH: 2,
    component: SystemStatusWidget,
  },
  kpi: {
    label: 'KPI',
    description: 'A single key metric with trend and sparkline',
    icon: Gauge,
    defaultSize: {w: 2, h: 3},
    minH: 2,
    component: KpiWidget,
  },
  service_health: {
    label: 'Service health',
    description: 'Per-service request rate, errors, latency and deploys',
    icon: Server,
    defaultSize: {w: 8, h: 10},
    minH: 6,
    component: ServiceHealthWidget,
  },
  telemetry: {
    label: 'Telemetry',
    description: 'Errors, latency, throughput and log volume over time',
    icon: LineChart,
    defaultSize: {w: 8, h: 7},
    minH: 5,
    component: TelemetryWidget,
  },
  triage: {
    label: 'Needs attention',
    description: 'Incidents, firing alerts, new issues and security signals',
    icon: ListChecks,
    defaultSize: {w: 4, h: 17},
    minH: 6,
    component: TriageWidget,
  },
  infra_summary: {
    label: 'Infrastructure',
    description: 'Resource gauges with container and pod counts',
    icon: Boxes,
    defaultSize: {w: 3, h: 7},
    minH: 5,
    component: InfraSummaryWidget,
  },
  uptime_summary: {
    label: 'Uptime & Synthetics',
    description: 'Monitor heartbeats and synthetic checks',
    icon: Activity,
    defaultSize: {w: 3, h: 7},
    minH: 5,
    component: UptimeSummaryWidget,
  },
  deploys: {
    label: 'Deploys',
    description: 'Recent deploys with post-deploy status',
    icon: Rocket,
    defaultSize: {w: 3, h: 7},
    minH: 5,
    component: DeploysWidget,
  },
  activity: {
    label: 'Activity',
    description: 'Recent workspace events',
    icon: Clock,
    defaultSize: {w: 3, h: 7},
    minH: 5,
    component: ActivityWidget,
  },
}

export function overviewWidgetDef(widgetType: string): OverviewWidgetDef | undefined {
  return OVERVIEW_WIDGETS[widgetType]
}
