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

import {useMemo, useState} from 'react'
import {ArrowRight, FileJson, Plus} from 'lucide-react'
import {Badge} from '@/components/ui/badge'
import type {DashboardTemplateSummary} from '@/lib/api'
import {cn} from '@/lib/utils'
import {DashboardThumb} from './DashboardThumb'
import {
  getTemplateThumb,
  normalizeCategory,
  TEMPLATE_FILTERS,
  type TemplateCategory,
  type TemplateFilter,
  type ThumbKind,
} from './dashboardThumbHelpers'

// Templates tab of the Dashboards hub: a permanent, filterable gallery of
// prebuilt panel sets. Unlike the old first-run gallery this is always reachable,
// at any dashboard count. Category chips on the left, blank/import start tiles on
// the right, then a grid of preview cards.

const SOURCE_BADGE_LIMIT = 2

type TemplateCardModel = Readonly<{
  template: DashboardTemplateSummary
  category: TemplateCategory | null
  thumb: ThumbKind
}>

type DashboardsTemplatesPanelProps = Readonly<{
  templates: readonly DashboardTemplateSummary[]
  isLoading: boolean
  searchQuery: string
  onUseTemplate: (templateId: string) => void
  onCreateBlank: () => void
  onImport: () => void
}>

export function DashboardsTemplatesPanel({
  templates,
  isLoading,
  searchQuery,
  onUseTemplate,
  onCreateBlank,
  onImport,
}: DashboardsTemplatesPanelProps) {
  const [filter, setFilter] = useState<TemplateFilter>('all')

  const models = useMemo<readonly TemplateCardModel[]>(
    () =>
      templates.map((template) => ({
        template,
        category: normalizeCategory(template.category),
        thumb: getTemplateThumb(template),
      })),
    [templates],
  )

  const query = searchQuery.trim().toLowerCase()
  const visible = models.filter((model) => {
    if (filter !== 'all' && model.category !== filter) return false
    if (!query) return true
    return matchesTemplate(model.template, query)
  })

  return (
    <section>
      {/* toolbar: category chips + start tiles */}
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <fieldset className="inline-flex flex-wrap items-center gap-0.5 rounded-md border bg-card p-[3px]">
          <legend className="sr-only">Filter templates by category</legend>
          {TEMPLATE_FILTERS.map((option) => {
            const active = filter === option.key
            return (
              <button
                key={option.key}
                type="button"
                aria-pressed={active}
                onClick={() => setFilter(option.key)}
                className={cn(
                  'rounded-sm px-2.5 py-1 text-xs font-medium transition-colors',
                  active
                    ? 'bg-primary/10 text-primary'
                    : 'text-muted-foreground hover:text-foreground',
                )}
              >
                {option.label}
              </button>
            )
          })}
        </fieldset>
        <div className="ml-auto flex items-center gap-2">
          <StartTile icon={Plus} label="Blank dashboard" onClick={onCreateBlank} />
          <StartTile icon={FileJson} label="Import JSON" onClick={onImport} />
        </div>
      </div>

      <TemplateGallery templates={visible} isLoading={isLoading} onUseTemplate={onUseTemplate} />
    </section>
  )
}

function matchesTemplate(template: DashboardTemplateSummary, query: string): boolean {
  const haystack = [
    template.title,
    template.description ?? '',
    template.category,
    ...template.tags,
    ...template.required_sources,
  ]
    .join(' ')
    .toLowerCase()
  return haystack.includes(query)
}

function StartTile({
  icon: Icon,
  label,
  onClick,
}: Readonly<{icon: typeof Plus; label: string; onClick: () => void}>) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="group inline-flex h-[30px] items-center gap-2 rounded-md border border-dashed border-border bg-card px-3 text-sm font-medium text-foreground transition-colors hover:border-primary hover:text-primary"
    >
      <Icon className="h-4 w-4 text-muted-foreground transition-colors group-hover:text-primary" />
      {label}
    </button>
  )
}

function TemplateGallery({
  templates,
  isLoading,
  onUseTemplate,
}: Readonly<{
  templates: readonly TemplateCardModel[]
  isLoading: boolean
  onUseTemplate: (templateId: string) => void
}>) {
  if (isLoading) {
    return <TemplateGallerySkeleton />
  }

  if (templates.length === 0) {
    return (
      <div className="rounded-lg border bg-card p-6 text-sm text-muted-foreground">
        No templates match this category.
      </div>
    )
  }

  return (
    <div className="grid grid-cols-[repeat(auto-fill,minmax(256px,1fr))] gap-3">
      {templates.map((model) => (
        <TemplateCard
          key={model.template.id}
          model={model}
          onUse={() => onUseTemplate(model.template.id)}
        />
      ))}
    </div>
  )
}

function TemplateCard({
  model,
  onUse,
}: Readonly<{model: TemplateCardModel; onUse: () => void}>) {
  const {template, thumb} = model
  const sources = template.required_sources.slice(0, SOURCE_BADGE_LIMIT)
  const extraSourceCount = template.required_sources.length - sources.length
  const sourceSet = new Set(template.required_sources.map((source) => source.toLowerCase()))
  const tags = template.tags.filter((tag) => !sourceSet.has(tag.toLowerCase()))

  return (
    <button
      type="button"
      onClick={onUse}
      aria-label={`Use the ${template.title} template`}
      className="group flex flex-col overflow-hidden rounded-lg border bg-card text-left shadow-xs transition-all hover:-translate-y-px hover:border-primary hover:shadow-sm"
    >
      <div className="relative h-[116px] overflow-hidden border-b bg-[hsl(var(--viz-surface))] p-2">
        <DashboardThumb kind={thumb} />
        <span className="pointer-events-none absolute bottom-2 right-2 inline-flex items-center gap-1 rounded-full bg-primary px-2 py-0.5 text-[10px] font-semibold text-primary-foreground opacity-0 transition-opacity group-hover:opacity-100">
          Use template
          <ArrowRight className="h-2.5 w-2.5" />
        </span>
      </div>
      <div className="flex flex-col gap-1.5 px-3 pb-3 pt-2.5">
        <div className="flex items-center gap-2">
          <span className="text-sm font-semibold text-foreground transition-colors group-hover:text-primary">
            {template.title}
          </span>
          <span className="ml-auto font-mono text-[10px] tabular-nums text-muted-foreground/80">
            {template.widget_count} widgets
          </span>
        </div>
        <p className="line-clamp-2 min-h-8 text-xs leading-snug text-muted-foreground">
          {template.description}
        </p>
        <div className="mt-0.5 flex flex-wrap gap-1.5">
          {sources.map((source) => (
            <Badge
              key={source}
              variant="neutral"
              className="rounded-full px-2 py-0 text-[10px] font-medium leading-4"
            >
              {source}
            </Badge>
          ))}
          {extraSourceCount > 0 && (
            <Badge
              variant="neutral"
              className="rounded-full px-2 py-0 text-[10px] font-medium leading-4"
            >
              +{extraSourceCount}
            </Badge>
          )}
          {tags.map((tag) => (
            <Badge
              key={tag}
              variant="neutral"
              className="rounded-full px-2 py-0 text-[10px] font-medium leading-4"
            >
              {tag}
            </Badge>
          ))}
        </div>
      </div>
    </button>
  )
}

function TemplateGallerySkeleton() {
  return (
    <div className="grid grid-cols-[repeat(auto-fill,minmax(256px,1fr))] gap-3">
      {[1, 2, 3, 4, 5, 6].map((i) => (
        <div key={i} className="overflow-hidden rounded-lg border bg-card">
          <div className="h-[116px] animate-pulse border-b bg-muted" />
          <div className="space-y-2 px-3 pb-3 pt-2.5">
            <div className="h-4 w-2/3 animate-pulse rounded bg-muted" />
            <div className="h-3 w-full animate-pulse rounded bg-muted" />
            <div className="flex gap-1.5 pt-1">
              <div className="h-4 w-14 animate-pulse rounded-full bg-muted" />
              <div className="h-4 w-20 animate-pulse rounded-full bg-muted" />
            </div>
          </div>
        </div>
      ))}
    </div>
  )
}
