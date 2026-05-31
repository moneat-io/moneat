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
import {Bell, MessageSquare, Mail, Filter, Workflow, Phone} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'
import {getFeaturePageSeoInput} from '@/lib/seo/routes'

const pageSeo = getFeaturePageSeoInput('alerting')

const config: FeaturePageConfig = {
  slug: pageSeo.slug,
  title: pageSeo.title,
  tagline: 'Never miss what matters',
  description: 'Multi-channel alerts with Slack, Discord, email, phone, and SMS integrations. Route alerts to the right teams instantly with flexible rules and escalation policies.',
  metaDescription: pageSeo.metaDescription,
  icon: Bell,
  iconColor: 'text-rose-400',
  iconBg: 'bg-rose-500/10',
  gradient: 'from-rose-500 to-pink-400',
  accentColor: 'text-rose-400',
  screenshot: pageSeo.image,
  screenshotAlt: 'Alerting configuration with multi-channel notification settings',
  subFeatures: [
    {icon: MessageSquare, title: 'Slack & Discord', description: 'Rich alert notifications in Slack and Discord with actionable buttons to acknowledge or resolve.', iconColor: 'text-rose-400'},
    {icon: Mail, title: 'Email Alerts', description: 'Detailed email notifications with error context, stack traces, and direct links to investigate.', iconColor: 'text-blue-400'},
    {icon: Phone, title: 'Phone & SMS', description: 'Critical alerts that call or text the on-call responder when immediate attention is needed.', iconColor: 'text-amber-400'},
    {icon: Filter, title: 'Alert Rules', description: 'Configure alert thresholds on error rates, log patterns, uptime, or any metric you track.', iconColor: 'text-violet-400'},
    {icon: Workflow, title: 'Routing Rules', description: 'Route different alert types to different channels and teams based on project, severity, or tags.', iconColor: 'text-cyan-400'},
    {icon: Bell, title: 'Digest & Dedup', description: 'Group related alerts together to avoid notification fatigue during incident storms.', iconColor: 'text-green-400'},
  ],
}

export const Route = createFileRoute('/alerting')({
  component: () => <FeaturePageTemplate config={config} />,
})
