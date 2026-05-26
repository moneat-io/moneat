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
import {FileText, Filter, RefreshCw, Search, Braces, Link2} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'

const config: FeaturePageConfig = {
  slug: 'log-management',
  title: 'Log Management',
  tagline: 'Structured logging at scale',
  description: 'Structured JSON logs with auto-refresh, full-text search, and powerful filtering. Unified with your errors and traces for faster root-cause analysis.',
  metaDescription: 'Log management with structured JSON, full-text search, real-time streaming, and powerful filtering. Start free with Moneat.',
  icon: FileText,
  iconColor: 'text-blue-400',
  iconBg: 'bg-blue-500/10',
  gradient: 'from-blue-500 to-indigo-400',
  accentColor: 'text-blue-400',
  screenshot: '/screenshots/log-management.png',
  screenshotAlt: 'Log management interface with real-time log viewer and filtering',
  subFeatures: [
    {icon: Search, title: 'Full-Text Search', description: 'Search across billions of log entries with sub-second response times.', iconColor: 'text-blue-400'},
    {icon: RefreshCw, title: 'Real-Time Streaming', description: 'Auto-refreshing log viewer that streams new entries as they arrive.', iconColor: 'text-cyan-400'},
    {icon: Filter, title: 'Powerful Filtering', description: 'Filter by severity, service, host, or any structured field. Save filters as views.', iconColor: 'text-indigo-400'},
    {icon: Braces, title: 'Structured JSON', description: 'First-class support for structured JSON logs with automatic field extraction.', iconColor: 'text-violet-400'},
    {icon: Link2, title: 'Correlated Signals', description: 'Jump from a log line to the related error, trace, or replay in one click.', iconColor: 'text-sky-400'},
    {icon: FileText, title: 'Log-Based Alerts', description: 'Set up alerts based on log patterns, error rates, or missing heartbeat logs.', iconColor: 'text-amber-400'},
  ],
  compatNote: 'Ingest logs via OpenTelemetry, compatible agents, or any source that sends structured JSON over HTTP.',
}

export const Route = createFileRoute('/log-management')({
  component: () => <FeaturePageTemplate config={config} />,
})
