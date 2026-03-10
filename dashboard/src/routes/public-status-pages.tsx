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
import {GitBranch, Globe, Palette, RefreshCw, Bell, Shield} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'

const config: FeaturePageConfig = {
  slug: 'public-status-pages',
  title: 'Status Pages',
  tagline: 'Keep your users informed',
  description: 'Beautiful public status pages with custom domains, automated from your monitors. Show your customers real-time service health with zero manual effort. Free on all plans.',
  metaDescription: 'Public status pages with custom domains, automated from uptime monitors. Free on all plans. Start free with Moneat.',
  icon: GitBranch,
  iconColor: 'text-cyan-400',
  iconBg: 'bg-cyan-500/10',
  gradient: 'from-cyan-500 to-teal-400',
  accentColor: 'text-cyan-400',
  screenshot: '/screenshots/status-pages.png',
  screenshotAlt: 'Public status page showing service health and uptime history',
  subFeatures: [
    {icon: Globe, title: 'Custom Domains', description: 'Host your status page on your own domain like status.yourapp.com with automatic SSL.', iconColor: 'text-cyan-400'},
    {icon: RefreshCw, title: 'Auto-Updated', description: 'Status pages update automatically based on your uptime monitors — no manual toggling.', iconColor: 'text-green-400'},
    {icon: Palette, title: 'Customizable', description: 'Match your brand with custom logos, colors, and component grouping.', iconColor: 'text-violet-400'},
    {icon: Bell, title: 'Subscriber Notifications', description: 'Let customers subscribe to status updates via email for the components they care about.', iconColor: 'text-amber-400'},
    {icon: GitBranch, title: 'Component Groups', description: 'Organize services into logical groups to give customers a clear view of what affects them.', iconColor: 'text-blue-400'},
    {icon: Shield, title: 'Free on All Plans', description: 'Status pages are included free on every plan, including the free tier.', iconColor: 'text-emerald-400'},
  ],
}

export const Route = createFileRoute('/public-status-pages')({
  component: () => <FeaturePageTemplate config={config} />,
})
