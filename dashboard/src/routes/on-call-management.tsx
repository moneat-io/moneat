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
import {Phone, Calendar, ArrowUpRight, MessageSquare, Clock, Users} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'
import {getFeaturePageSeoInput} from '@/lib/seo/routes'

const pageSeo = getFeaturePageSeoInput('on-call-management')

const config: FeaturePageConfig = {
  slug: pageSeo.slug,
  title: pageSeo.title,
  tagline: 'The right person, every time',
  description: 'Manage on-call rotations and escalation policies. When things break, Moneat notifies the right person via phone call, SMS, Slack, or email — with automatic escalation if they don\'t respond.',
  metaDescription: pageSeo.metaDescription,
  icon: Phone,
  iconColor: 'text-orange-400',
  iconBg: 'bg-orange-500/10',
  gradient: 'from-orange-500 to-amber-400',
  accentColor: 'text-orange-400',
  screenshot: pageSeo.image,
  screenshotAlt: 'On-call escalation policies with rotation schedules',
  subFeatures: [
    {icon: Calendar, title: 'On-Call Schedules', description: 'Create rotating schedules with daily, weekly, or custom rotation patterns for your team.', iconColor: 'text-orange-400'},
    {icon: ArrowUpRight, title: 'Escalation Policies', description: 'Define multi-level escalation chains so incidents never go unacknowledged.', iconColor: 'text-red-400'},
    {icon: Phone, title: 'Phone & SMS Alerts', description: 'Wake the right person with phone calls and SMS when critical incidents fire.', iconColor: 'text-amber-400'},
    {icon: MessageSquare, title: 'Slack Integration', description: 'Create incident channels, acknowledge alerts, and resolve incidents directly from Slack.', iconColor: 'text-blue-400'},
    {icon: Clock, title: 'Incident Timeline', description: 'Track every acknowledgment, escalation, and resolution with a full audit timeline.', iconColor: 'text-violet-400'},
    {icon: Users, title: 'Team Management', description: 'Organize responders into teams and assign escalation policies per team or service.', iconColor: 'text-green-400'},
  ],
}

export const Route = createFileRoute('/on-call-management')({
  component: () => <FeaturePageTemplate config={config} />,
})
