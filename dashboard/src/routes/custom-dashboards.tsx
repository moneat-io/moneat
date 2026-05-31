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
import {LayoutDashboard, Database, LineChart, Grid3x3, Share2, Palette} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'
import {getFeaturePageSeoInput} from '@/lib/seo/routes'

const pageSeo = getFeaturePageSeoInput('custom-dashboards')

const config: FeaturePageConfig = {
  slug: pageSeo.slug,
  title: pageSeo.title,
  tagline: 'Visualize anything',
  description: 'Build custom dashboards with drag-and-drop widgets powered by any data source. Connect PostgreSQL, ClickHouse, BigQuery, or use built-in Moneat metrics to create the views your team needs.',
  metaDescription: pageSeo.metaDescription,
  icon: LayoutDashboard,
  iconColor: 'text-sky-400',
  iconBg: 'bg-sky-500/10',
  gradient: 'from-sky-500 to-blue-400',
  accentColor: 'text-sky-400',
  screenshot: pageSeo.image,
  screenshotAlt: 'Custom dashboard with multiple widgets showing metrics and charts',
  subFeatures: [
    {icon: Grid3x3, title: 'Drag & Drop', description: 'Arrange widgets on a flexible grid. Resize, reorder, and organize your dashboard layout visually.', iconColor: 'text-sky-400'},
    {icon: Database, title: 'Custom Data Sources', description: 'Connect PostgreSQL, ClickHouse, BigQuery, or any SQL database to power your dashboard widgets.', iconColor: 'text-blue-400'},
    {icon: LineChart, title: 'Rich Visualizations', description: 'Time series, bar charts, tables, stat cards, and more — all with auto-refreshing data.', iconColor: 'text-violet-400'},
    {icon: LayoutDashboard, title: 'Built-in Metrics', description: 'Use Moneat\'s built-in error, log, uptime, and infrastructure metrics without any extra config.', iconColor: 'text-amber-400'},
    {icon: Share2, title: 'Shareable', description: 'Share dashboards across your team or embed them in internal tools and documentation.', iconColor: 'text-green-400'},
    {icon: Palette, title: 'Theming', description: 'Dashboards respect your workspace theme and look great in both light and dark mode.', iconColor: 'text-rose-400'},
  ],
}

export const Route = createFileRoute('/custom-dashboards')({
  component: () => <FeaturePageTemplate config={config} />,
})
