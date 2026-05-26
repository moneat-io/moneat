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

import {createFileRoute} from '@tanstack/react-router'
import {Server, Cpu, HardDrive, Box, Network, Database} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'

const config: FeaturePageConfig = {
  slug: 'infrastructure-monitoring',
  title: 'Infrastructure Monitoring',
  tagline: 'Infrastructure telemetry',
  description:
    'Monitor hosts, containers, Kubernetes clusters, and databases with real-time infrastructure telemetry. ' +
    'Point compatible agents at Moneat and get full visibility without changing your applications.',
  metaDescription: 'Infrastructure monitoring for hosts, containers, Kubernetes, and databases. Compatible with the Datadog Agent. Start free with Moneat.',
  icon: Server,
  iconColor: 'text-orange-400',
  iconBg: 'bg-orange-500/10',
  gradient: 'from-orange-500 to-amber-400',
  accentColor: 'text-orange-400',
  screenshot: '/screenshots/containers.png',
  screenshotAlt: 'Infrastructure monitoring showing host and container metrics',
  subFeatures: [
    {icon: Cpu, title: 'Host Metrics', description: 'CPU, memory, disk, network, and load metrics from every host in your fleet.', iconColor: 'text-orange-400'},
    {icon: Box, title: 'Container Monitoring', description: 'Real-time Docker container metrics including CPU, memory, I/O, and network per container.', iconColor: 'text-blue-400'},
    {icon: Network, title: 'Kubernetes', description: 'Cluster, node, pod, and deployment metrics with automatic discovery and labeling.', iconColor: 'text-cyan-400'},
    {icon: Database, title: 'Database Monitoring', description: 'Track query performance, connections, and resource usage for PostgreSQL, MySQL, and more.', iconColor: 'text-violet-400'},
    {icon: HardDrive, title: 'Process Monitoring', description: 'Track individual processes, resource consumption, and detect runaway processes.', iconColor: 'text-amber-400'},
    {
      icon: Server,
      title: 'Custom Metrics',
      description:
        'Send custom metrics via OpenTelemetry, DogStatsD, or compatible agents and visualize them ' +
        'on dashboards.',
      iconColor: 'text-green-400',
    },
  ],
  compatNote:
    'Works with compatible agents, including the Datadog Agent. Point the intake URL at your Moneat instance ' +
    'and infrastructure data flows through.',
}

export const Route = createFileRoute('/infrastructure-monitoring')({
  component: () => <FeaturePageTemplate config={config} />,
})
