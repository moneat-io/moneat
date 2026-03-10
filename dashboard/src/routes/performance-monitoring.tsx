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
import {Zap, Activity, Timer, GitBranch, BarChart3, Search} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'

const config: FeaturePageConfig = {
  slug: 'performance-monitoring',
  title: 'APM & Traces',
  tagline: 'Find slow endpoints fast',
  description: 'Track transactions and spans across your services. Find slow endpoints, database queries, and external calls before your users notice. Distributed tracing with full context.',
  metaDescription: 'Application performance monitoring with distributed tracing, transaction tracking, and span analysis. Start free with Moneat.',
  icon: Zap,
  iconColor: 'text-amber-400',
  iconBg: 'bg-amber-500/10',
  gradient: 'from-amber-500 to-orange-400',
  accentColor: 'text-amber-400',
  screenshot: '/screenshots/performance.png',
  screenshotAlt: 'Performance monitoring dashboard with transaction timings and span waterfall',
  subFeatures: [
    {icon: Activity, title: 'Transaction Tracking', description: 'Automatically capture every HTTP request, background job, and queue consumer as a transaction.', iconColor: 'text-amber-400'},
    {icon: Timer, title: 'Span Waterfall', description: 'See the full breakdown of every transaction with database queries, HTTP calls, and custom spans.', iconColor: 'text-orange-400'},
    {icon: GitBranch, title: 'Distributed Tracing', description: 'Follow requests across services with trace context propagation and correlated spans.', iconColor: 'text-blue-400'},
    {icon: BarChart3, title: 'P50/P95/P99 Latency', description: 'Track percentile latencies over time. Spot regressions before they become incidents.', iconColor: 'text-cyan-400'},
    {icon: Search, title: 'Trace Search', description: 'Search traces by duration, status, tags, or any custom attribute attached to spans.', iconColor: 'text-violet-400'},
    {icon: Zap, title: 'Apdex Scoring', description: 'Measure user satisfaction with Apdex scores based on configurable thresholds.', iconColor: 'text-green-400'},
  ],
  compatNote: 'Compatible with Sentry SDKs and the Datadog Agent. Ingest traces from either or both simultaneously.',
}

export const Route = createFileRoute('/performance-monitoring')({
  component: () => <FeaturePageTemplate config={config} />,
})
