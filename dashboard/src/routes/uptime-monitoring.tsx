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
import {Globe, Clock, Bell, BarChart3, GitBranch, Phone} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'

const config: FeaturePageConfig = {
  slug: 'uptime-monitoring',
  title: 'Uptime Monitoring',
  tagline: 'Never miss downtime',
  description: 'Monitor your services 24/7 with customizable check intervals. Get alerted instantly via phone, SMS, Slack, or Discord when something goes down. Automatic public status pages included.',
  metaDescription: 'Uptime monitoring with instant alerts via phone, SMS, Slack. Public status pages included. Start free with Moneat.',
  icon: Globe,
  iconColor: 'text-green-400',
  iconBg: 'bg-green-500/10',
  gradient: 'from-green-500 to-emerald-400',
  accentColor: 'text-green-400',
  screenshot: '/screenshots/uptime.png',
  screenshotAlt: 'Uptime monitoring dashboard with status checks and availability metrics',
  subFeatures: [
    {icon: Clock, title: 'Configurable Intervals', description: 'Check every 30 seconds to every 30 minutes. Choose the cadence that matches your SLA.', iconColor: 'text-green-400'},
    {icon: Bell, title: 'Multi-Channel Alerts', description: 'Get notified via phone calls, SMS, Slack, Discord, or email when monitors go down.', iconColor: 'text-rose-400'},
    {icon: BarChart3, title: 'Availability Reports', description: 'Track uptime percentages, response times, and SLA compliance over any time range.', iconColor: 'text-cyan-400'},
    {icon: GitBranch, title: 'Status Pages', description: 'Public status pages with custom domains, automated from your monitors, free on all tiers.', iconColor: 'text-violet-400'},
    {icon: Phone, title: 'On-Call Integration', description: 'Route alerts through on-call schedules and escalation policies to reach the right person.', iconColor: 'text-orange-400'},
    {icon: Globe, title: 'Global Checks', description: 'Monitor from multiple regions to detect localized outages and latency issues.', iconColor: 'text-sky-400'},
  ],
}

export const Route = createFileRoute('/uptime-monitoring')({
  component: () => <FeaturePageTemplate config={config} />,
})
