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

import type {CustomDashboard, DashboardTemplateSummary} from '@/lib/api'

// Pure helpers + types behind the dashboard mini-viz thumbnails. Kept apart from
// DashboardThumb.tsx so that file only exports components (fast refresh).

export type ThumbKind = 'service' | 'host' | 'k8s' | 'db' | 'logs' | 'vitals'

export type TemplateCategory =
  | 'infrastructure'
  | 'kubernetes'
  | 'applications'
  | 'databases'
  | 'cloud'
  | 'logs'
export type TemplateFilter = 'all' | TemplateCategory

export const TEMPLATE_FILTERS: ReadonlyArray<{key: TemplateFilter; label: string}> = [
  {key: 'all', label: 'All'},
  {key: 'infrastructure', label: 'Infrastructure'},
  {key: 'kubernetes', label: 'Kubernetes'},
  {key: 'applications', label: 'Applications'},
  {key: 'databases', label: 'Databases'},
  {key: 'cloud', label: 'Cloud'},
  {key: 'logs', label: 'Logs'},
]

export function normalizeCategory(category: string): TemplateCategory | null {
  const normalized = category.toLowerCase()
  switch (normalized) {
    case 'infrastructure':
    case 'kubernetes':
    case 'applications':
    case 'databases':
    case 'cloud':
    case 'logs':
      return normalized
    default:
      return null
  }
}

export function getTemplateThumb(template: DashboardTemplateSummary): ThumbKind {
  const category = normalizeCategory(template.category)
  if (category === 'kubernetes') return 'k8s'
  if (category === 'databases') return 'db'
  if (category === 'logs') return 'logs'
  if (category === 'applications') return 'service'

  const tags = new Set(template.tags.map((tag) => tag.toLowerCase()))
  if (tags.has('kubernetes')) return 'k8s'
  if (tags.has('database') || tags.has('databases')) return 'db'
  if (tags.has('logs')) return 'logs'
  if (tags.has('browser') || tags.has('rum')) return 'vitals'
  return 'host'
}

// Distinct data sources a dashboard reads from, derived from its widget queries
// (e.g. "metrics", "traces", "logs"). Used as the source chips on list rows and
// grid cards; empty when a board has no queries yet.
export function getDashboardSources(dashboard: CustomDashboard): string[] {
  const seen = new Set<string>()
  for (const widget of dashboard.widgets) {
    for (const query of widget.query_configs) {
      const source = query.dataSource?.trim().toLowerCase()
      if (source) seen.add(source)
    }
  }
  return [...seen]
}

// Representative thumbnail shape for an existing dashboard, derived from its
// name, folder and queried sources. Deterministic so a board keeps the same
// shape between renders; replace with a real rendered preview once the backend
// snapshots widgets.
export function getDashboardThumb(dashboard: CustomDashboard, folderName?: string): ThumbKind {
  const haystack =
    `${dashboard.title} ${folderName ?? ''} ${dashboard.description ?? ''} ${getDashboardSources(dashboard).join(' ')}`.toLowerCase()
  if (/(kubernetes|k8s|cluster|\bpod|\bnode)/.test(haystack)) return 'k8s'
  if (/(postgre|mysql|redis|mongo|database|cache|queue|\bsql\b)/.test(haystack)) return 'db'
  if (/(\blog|error triage|trace)/.test(haystack)) return 'logs'
  if (/(vital|web|browser|\brum\b|frontend|mobile|render)/.test(haystack)) return 'vitals'
  if (/(host|fleet|node exporter|\bcpu\b|infra|edge|\bcdn\b|disk)/.test(haystack)) return 'host'
  return 'service'
}
