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

import {createFileRoute, Link} from '@tanstack/react-router'

import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import type {ApmErrorGroup, ApmTimeRange, Issue, Project} from '@/lib/api'
import {trackEvent} from '@/lib/analytics'
import {serviceNamesForQuery} from '@/lib/service-facet-scope'
import {formatRelativeTime} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card} from '@/components/ui/card'
import {SearchFilterBar} from '@/components/filters/SearchFilterBar'
import {FacetRail} from '@/components/filters/FacetRail'
import {ExplorerShell} from '@/components/filters/ExplorerShell'
import {SegmentedTabs, type SegmentedOption} from '@/components/filters/SegmentedTabs'
import {TimeRangePicker} from '@/components/filters/TimeRangePicker'
import type {TimeRangePreset} from '@/lib/filters/time'
import type {FacetFilter, FacetSchema, FacetRailSection} from '@/lib/filters/types'
import {
  parseExplorerSearch,
  parseFacetFiltersOptionalParam,
  type ReadableFacetSearchValue,
} from '@/lib/filters/urlState'
import {levelBadgeVariant, levelBorderClass} from '@/lib/severity'
import {Checkbox} from '@/components/ui/checkbox'
import {DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger} from '@/components/ui/dropdown-menu'
import {
  AlertCircle,
  AlertTriangle,
  CheckCircle2,
  ChevronDown,
  Clock,
  Cpu,
  EyeOff,
  FolderKanban,
  Plus,
  Search,
  Server,
  Timer,
} from 'lucide-react'
import {useEffect, useMemo, useRef, useState} from 'react'
import type {ReactNode} from 'react'
import {useToast} from '@/hooks/useToast'
import {getNow} from '@/lib/demo'
import {parseDate} from '@/lib/date-format'
// Level → Badge variant and left-border indicator come from the shared severity
// helper (@/lib/severity) so every surface uses the same status language.

// Get freshness color based on how recently the last event occurred
function getLastSeenColor(lastSeen: string): string {
  const diff = getNow() - parseDate(lastSeen).getTime()
  const hours = diff / (1000 * 60 * 60)
  if (hours < 1) return 'text-danger-fg'
  if (hours < 24) return 'text-warning-fg'
  return 'text-muted-foreground'
}

// Check if an issue is new (first seen within last 24 hours)
function isNewIssue(firstSeen: string): boolean {
  const diff = getNow() - parseDate(firstSeen).getTime()
  return diff < 24 * 60 * 60 * 1000
}

// Format large numbers compactly
function formatCount(n: number): string {
  if (n >= 100000) return `${(n / 1000).toFixed(0)}k`
  if (n >= 10000) return `${(n / 1000).toFixed(1)}k`
  if (n >= 1000) return `${(n / 1000).toFixed(1)}k`
  return n.toLocaleString()
}

function normalizeApmTraceId(value: unknown): string | null {
  if (typeof value === 'number') {
    if (!Number.isInteger(value) || value < 0) return null
    return String(value)
  }
  if (typeof value !== 'string') return null
  const normalized = value.trim()
  return /^\d+$/.test(normalized) ? normalized : null
}

const ISSUE_TITLE_RENDER_MAX_CHARS = 240
const ISSUE_CULPRIT_RENDER_MAX_CHARS = 160
const ISSUE_SEARCH_TEXT_MAX_CHARS = 512
const ISSUE_ID_MAX_CHARS = 512
const ISSUES_FETCH_LIMIT = 500

type SafeIssue = {
  id: string
  projectId: string
  service: string
  title: string
  culprit: string
  level: string
  platform: string
  firstSeen: string
  lastSeen: string
  eventCount: number
  userCount: number
  status: string
}

type IssueUpdateTarget = {
  id: string
  projectId: string
}

type IssueActionStatus = 'resolved' | 'ignored' | 'resolvedInNextRelease'
type ToastFn = ReturnType<typeof useToast>['toast']
type QueryClient = ReturnType<typeof useQueryClient>

function issueSelectionKey(issue: Pick<SafeIssue, 'id' | 'projectId'>): string {
  return `${issue.projectId}:${issue.id}`
}

function toggledSelection(current: ReadonlySet<string>, key: string): Set<string> {
  const next = new Set(current)
  if (next.has(key)) {
    next.delete(key)
  } else {
    next.add(key)
  }
  return next
}

function toggledPageSelection(
  current: ReadonlySet<string>,
  issues: readonly SafeIssue[]
): Set<string> {
  const pageKeys = issues.map(issueSelectionKey)
  const next = new Set(current)
  const allPageIssuesSelected = pageKeys.every((key) => current.has(key))
  if (allPageIssuesSelected) {
    pageKeys.forEach((key) => next.delete(key))
  } else {
    pageKeys.forEach((key) => next.add(key))
  }
  return next
}

function issueTargetsFromSelection(
  keys: ReadonlySet<string>,
  byKey: ReadonlyMap<string, IssueUpdateTarget>
): IssueUpdateTarget[] {
  const targets: IssueUpdateTarget[] = []
  keys.forEach((key) => {
    const target = byKey.get(key)
    if (target) targets.push(target)
  })
  return targets
}

function matchesIssueFilters(
  issue: SafeIssue,
  normalizedSearchQuery: string,
  statusIncludes: readonly string[],
  statusExcludes: readonly string[],
  levelIncludes: readonly string[],
  levelExcludes: readonly string[]
): boolean {
  const matchesSearch =
    normalizedSearchQuery === '' ||
    issue.title.toLowerCase().includes(normalizedSearchQuery) ||
    issue.culprit.toLowerCase().includes(normalizedSearchQuery)
  const matchesStatus =
    (statusIncludes.length === 0 || statusIncludes.includes(issue.status)) &&
    !statusExcludes.includes(issue.status)
  const matchesLevel =
    (levelIncludes.length === 0 || levelIncludes.includes(issue.level)) &&
    !levelExcludes.includes(issue.level)
  return matchesSearch && matchesStatus && matchesLevel
}

function issueActionToast({
  submitted,
  successCount,
  successLabel,
}: Readonly<{
  submitted: number
  successCount: number
  successLabel: string
}>) {
  if (successCount === submitted) {
    return {
      title: 'Success',
      description: `${submitted} issue${submitted === 1 ? '' : 's'} ${successLabel}`,
    }
  }

  return {
    title: 'Partial Success',
    description: `${successCount}/${submitted} issues ${successLabel}`,
    variant: 'destructive' as const,
  }
}

function useIssueBulkMutation({
  status,
  eventName,
  successLabel,
  errorDescription,
  queryClient,
  toast,
  clearSelection,
}: Readonly<{
  status: IssueActionStatus
  eventName: string
  successLabel: string
  errorDescription: string
  queryClient: QueryClient
  toast: ToastFn
  clearSelection: () => void
}>) {
  return useMutation({
    mutationFn: async (issues: IssueUpdateTarget[]) => {
      const results = await Promise.allSettled(
        issues.map((issue) => api.updateIssue(issue.id, { status }, issue.projectId))
      )
      const successCount = results.filter(r => r.status === 'fulfilled').length
      if (successCount === 0) throw new Error('All requests failed')
      return { submitted: issues.length, successCount }
    },
    onSuccess: ({ submitted, successCount }) => {
      trackEvent(eventName, { count: String(successCount) })
      queryClient.invalidateQueries({ queryKey: ['issues'] })
      queryClient.invalidateQueries({ queryKey: ['stats'] })
      toast(issueActionToast({submitted, successCount, successLabel}))
      clearSelection()
    },
    onError: () => {
      toast({ title: 'Error', description: errorDescription, variant: 'destructive' })
    },
  })
}

function useIssueBulkMutations({
  queryClient,
  toast,
  clearSelection,
}: Readonly<{
  queryClient: QueryClient
  toast: ToastFn
  clearSelection: () => void
}>) {
  const resolveMutation = useIssueBulkMutation({
    status: 'resolved',
    eventName: 'Issue Resolve',
    successLabel: 'resolved',
    errorDescription: 'Failed to resolve issues',
    queryClient,
    toast,
    clearSelection,
  })
  const ignoreMutation = useIssueBulkMutation({
    status: 'ignored',
    eventName: 'Issue Ignore',
    successLabel: 'ignored',
    errorDescription: 'Failed to ignore issues',
    queryClient,
    toast,
    clearSelection,
  })
  const resolveNextReleaseMutation = useIssueBulkMutation({
    status: 'resolvedInNextRelease',
    eventName: 'Issue ResolveInNextRelease',
    successLabel: 'marked to resolve in next release',
    errorDescription: 'Failed to update issues',
    queryClient,
    toast,
    clearSelection,
  })

  return {resolveMutation, ignoreMutation, resolveNextReleaseMutation}
}

function clampText(value: string, maxChars: number): string {
  if (value.length <= maxChars) return value
  return `${value.slice(0, maxChars - 1)}…`
}

function toSafeString(value: unknown, fallback = '', maxChars = ISSUE_SEARCH_TEXT_MAX_CHARS): string {
  const raw = typeof value === 'string' ? value : fallback
  const trimmed = raw.trim()
  if (trimmed.length <= maxChars) return trimmed
  return trimmed.slice(0, maxChars)
}

function toSafeId(value: unknown): string {
  if (typeof value !== 'string' && typeof value !== 'number') return ''
  return toSafeString(String(value), '', ISSUE_ID_MAX_CHARS)
}

function toSafeCount(value: unknown): number {
  const n = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(n) || n < 0) return 0
  return Math.floor(n)
}

function facetValues(filters: readonly FacetFilter[], key: string, exclude: boolean): string[] {
  return filters
    .filter((filter) => filter.key === key && Boolean(filter.exclude) === exclude)
    .map((filter) => filter.value)
}

function serverStatusFilter(statusIncludes: readonly string[], statusExcludes: readonly string[]): string | undefined {
  return statusIncludes.length === 1 && statusExcludes.length === 0 ? statusIncludes[0] : undefined
}

function serviceNameMaps(services: readonly Project[]): {
  byResourceId: Map<string, string>
  byId: Map<string, string>
} {
  return {
    byResourceId: new Map(services.map((service) => [service.id, service.name])),
    byId: new Map(services.map((service) => [String(service.id), service.name])),
  }
}

function toSafeIssue(
  issue: Issue,
  servicesByResourceId: ReadonlyMap<string, string>,
  servicesById: ReadonlyMap<string, string>
): SafeIssue | null {
  const id = toSafeId(issue.id)
  if (!id) return null

  const projectId = toSafeId(issue.projectId)
  if (!projectId) return null

  const service =
    servicesByResourceId.get(projectId) ??
    servicesById.get(toSafeId(issue.projectId)) ??
    projectId

  return {
    id,
    projectId,
    service,
    title: toSafeString(issue.title, '', ISSUE_SEARCH_TEXT_MAX_CHARS),
    culprit: toSafeString(issue.culprit, '', ISSUE_SEARCH_TEXT_MAX_CHARS),
    level: toSafeString(issue.level, 'error', 16) || 'error',
    platform: toSafeString(issue.platform, 'unknown', 64) || 'unknown',
    firstSeen: toSafeString(issue.firstSeen, ''),
    lastSeen: toSafeString(issue.lastSeen, ''),
    eventCount: toSafeCount(issue.eventCount),
    userCount: toSafeCount(issue.userCount),
    status: toSafeString(issue.status, 'unresolved', 32) || 'unresolved',
  }
}

function getIssueDisplayTitle(issue: { title: string; culprit: string }): string {
  const title = clampText(issue.title?.trim() ?? '', ISSUE_TITLE_RENDER_MAX_CHARS)
  const culprit = clampText(issue.culprit?.trim() ?? '', ISSUE_CULPRIT_RENDER_MAX_CHARS)

  if (!title) return culprit || 'Unknown error'
  if (!culprit) return title

  const normalizedTitle = title.toLowerCase()
  const normalizedCulprit = culprit.toLowerCase()
  if (
    normalizedTitle.startsWith(`${normalizedCulprit}:`) ||
    normalizedTitle.startsWith(`${normalizedCulprit} `)
  ) {
    return title
  }

  return `${culprit}: ${title}`
}

// Simple sparkline component
function EventSparkline({ eventCount, eventSeries }: { eventCount: number; eventSeries?: number[] }) {
  let heights: number[]
  if (eventSeries && eventSeries.length > 0) {
    const max = Math.max(...eventSeries, 1)
    const padded = eventSeries.slice(-10)
    while (padded.length < 10) padded.unshift(0)
    heights = padded.map(v => Math.round((v / max) * 100))
  } else {
    // Static activity indicator — not a trend
    const bars = Math.min(Math.ceil(eventCount / 10), 10)
    heights = Array.from({ length: 10 }, (_, i) => (i < bars ? 40 : 0))
  }

  return (
    <div
      className="flex items-end gap-0.5 h-8 w-20"
      aria-label="activity indicator, not a trend"
      title="activity indicator, not a trend"
    >
      {heights.map((height, i) => (
        <div
          key={i}
          className="flex-1 bg-primary/60 rounded-sm transition-all"
          style={{ height: `${height}%` }}
        />
      ))}
    </div>
  )
}

/** Issues default to the unresolved status facet (also mirrored into the URL). */
export const DEFAULT_ISSUE_FACET_FILTERS: readonly FacetFilter[] = [
  {key: 'status', value: 'unresolved'},
]

const ISSUE_STATUS_ALL_URL_VALUE = 'all'
const ISSUE_FACET_KEYS = ['service', 'status', 'level'] as const
const APM_FACET_KEYS = ['service'] as const
const APM_FACET_PARAM_PREFIX = 'apm_'
type IssueFacetKey = (typeof ISSUE_FACET_KEYS)[number]
type IssueExcludeFacetKey = `exclude_${IssueFacetKey}`
const ISSUE_EXCLUDE_FACET_KEYS = ISSUE_FACET_KEYS.map((key) => `exclude_${key}` as const)
type ApmFacetKey = (typeof APM_FACET_KEYS)[number]
type ApmFacetParamKey = `${typeof APM_FACET_PARAM_PREFIX}${ApmFacetKey}`
type ApmExcludeFacetParamKey = `exclude_${ApmFacetParamKey}`

type IssueFacetSearch = Partial<Record<IssueFacetKey | IssueExcludeFacetKey, ReadableFacetSearchValue>>
type ApmFacetSearch = Partial<Record<ApmFacetParamKey | ApmExcludeFacetParamKey, ReadableFacetSearchValue>>

function apmFacetParamKey(key: ApmFacetKey): ApmFacetParamKey {
  return `${APM_FACET_PARAM_PREFIX}${key}` as ApmFacetParamKey
}

function apmExcludeFacetParamKey(key: ApmFacetKey): ApmExcludeFacetParamKey {
  return `exclude_${apmFacetParamKey(key)}` as ApmExcludeFacetParamKey
}

function searchParamValues(value: unknown): string[] {
  if (Array.isArray(value)) {
    return value.flatMap(searchParamValues)
  }
  if (typeof value !== 'string' && typeof value !== 'number' && typeof value !== 'boolean') return []
  return String(value)
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean)
}

function toSearchParamValue(values: readonly string[]): ReadableFacetSearchValue | undefined {
  if (values.length === 0) return undefined
  return values.length === 1 ? values[0] : [...values]
}

function issueFacetFiltersFromSearch(search: Partial<IssuesSearch> | Record<string, unknown>): FacetFilter[] | undefined {
  const filters: FacetFilter[] = []
  let hasCleanFacetParam = false

  for (const key of ISSUE_FACET_KEYS) {
    const includeValues = searchParamValues(search[key])
    if (includeValues.length > 0) {
      hasCleanFacetParam = true
      for (const value of includeValues) {
        if (key === 'status' && value === ISSUE_STATUS_ALL_URL_VALUE) continue
        filters.push({key, value})
      }
    }

    const excludeKey = `exclude_${key}` as const
    const excludeValues = searchParamValues(search[excludeKey])
    if (excludeValues.length > 0) {
      hasCleanFacetParam = true
      filters.push(...excludeValues.map((value) => ({key, value, exclude: true})))
    }
  }

  if (hasCleanFacetParam) {
    return filters
  }

  return parseFacetFiltersOptionalParam('facets' in search ? search.facets : undefined)
}

function serializeIssueFacetSearch(filters: readonly FacetFilter[], forceStatusParam: boolean): IssueFacetSearch {
  const search: IssueFacetSearch = {}

  for (const key of ISSUE_FACET_KEYS) {
    const includeValues = filters.filter((filter) => filter.key === key && !filter.exclude).map((filter) => filter.value)
    const excludeValues = filters.filter((filter) => filter.key === key && filter.exclude).map((filter) => filter.value)

    const includeParam = toSearchParamValue(includeValues)
    if (includeParam) {
      search[key] = includeParam
    }

    const excludeParam = toSearchParamValue(excludeValues)
    if (excludeParam) {
      search[`exclude_${key}` as const] = excludeParam
    }
  }

  if (forceStatusParam && !search.status && !search.exclude_status) {
    search.status = ISSUE_STATUS_ALL_URL_VALUE
  }

  return search
}

function issueFacetSearchKey(search: Partial<IssuesSearch>): string | undefined {
  const filters = issueFacetFiltersFromSearch(search)
  if (filters === undefined) return undefined
  return JSON.stringify(serializeIssueFacetSearch(filters, true))
}

function apmFacetFiltersFromSearch(search: Record<string, unknown>): FacetFilter[] | undefined {
  const filters: FacetFilter[] = []
  let hasCleanFacetParam = false

  for (const key of APM_FACET_KEYS) {
    const includeParamKey = apmFacetParamKey(key)
    const includeValues = searchParamValues(search[includeParamKey])
    if (includeValues.length > 0) {
      hasCleanFacetParam = true
      filters.push(...includeValues.map((value) => ({key, value})))
    }

    const excludeParamKey = apmExcludeFacetParamKey(key)
    const excludeValues = searchParamValues(search[excludeParamKey])
    if (excludeValues.length > 0) {
      hasCleanFacetParam = true
      filters.push(...excludeValues.map((value) => ({key, value, exclude: true})))
    }
  }

  if (hasCleanFacetParam) {
    return filters
  }

  return parseFacetFiltersOptionalParam('afacets' in search ? search.afacets : undefined)
}

function serializeApmFacetSearch(filters: readonly FacetFilter[]): ApmFacetSearch {
  const search: ApmFacetSearch = {}

  for (const key of APM_FACET_KEYS) {
    const includeValues = filters.filter((filter) => filter.key === key && !filter.exclude).map((filter) => filter.value)
    const excludeValues = filters.filter((filter) => filter.key === key && filter.exclude).map((filter) => filter.value)

    const includeParam = toSearchParamValue(includeValues)
    if (includeParam) {
      search[apmFacetParamKey(key)] = includeParam
    }

    const excludeParam = toSearchParamValue(excludeValues)
    if (excludeParam) {
      search[apmExcludeFacetParamKey(key)] = excludeParam
    }
  }

  return search
}

function apmFacetSearchKey(search: Record<string, unknown>): string | undefined {
  const filters = apmFacetFiltersFromSearch(search)
  if (filters === undefined) return undefined
  return JSON.stringify(serializeApmFacetSearch(filters))
}

function clearIssueFacetSearch(prev: Record<string, unknown>): Record<string, unknown> {
  const next = {...prev}
  for (const key of ISSUE_FACET_KEYS) {
    if (key in next) next[key] = undefined
  }
  for (const key of ISSUE_EXCLUDE_FACET_KEYS) {
    if (key in next) next[key] = undefined
  }
  if ('facets' in next) next.facets = undefined
  return next
}

function clearApmFacetSearch(prev: Record<string, unknown>): Record<string, unknown> {
  const next = {...prev}
  for (const key of APM_FACET_KEYS) {
    const includeKey = apmFacetParamKey(key)
    if (includeKey in next) next[includeKey] = undefined
    const excludeKey = apmExcludeFacetParamKey(key)
    if (excludeKey in next) next[excludeKey] = undefined
  }
  if ('afacets' in next) next.afacets = undefined
  return next
}

function hasLegacyIssueFacetParam(): boolean {
  if (globalThis.window === undefined) return false
  return new URLSearchParams(globalThis.window.location.search).has('facets')
}

function hasLegacyApmFacetParam(): boolean {
  if (globalThis.window === undefined) return false
  return new URLSearchParams(globalThis.window.location.search).has('afacets')
}

function releaseHydrationGuard(ref: {current: boolean}): () => void {
  const release = globalThis.setTimeout(() => {
    ref.current = false
  }, 0)
  return () => {
    globalThis.clearTimeout(release)
    ref.current = false
  }
}

/**
 * Resolve issue-explorer facet state from clean URL params. An absent facet
 * state falls back to the unresolved default; `status=all` means the user
 * explicitly cleared every status facet.
 */
export function resolveIssueFacetFilters(search: Partial<IssuesSearch> | undefined): FacetFilter[] {
  const filters = search ? issueFacetFiltersFromSearch(search) : undefined
  if (filters === undefined) {
    return DEFAULT_ISSUE_FACET_FILTERS.map((filter) => ({...filter}))
  }
  return filters
}

/** Issue explorer sub-views (tabs), persisted in the URL as `?view=`. */
export type IssuesView = 'issues' | 'apm-errors'
const ISSUES_VIEWS: readonly IssuesView[] = ['issues', 'apm-errors']

function parseIssuesView(value: unknown): IssuesView | undefined {
  return typeof value === 'string' && (ISSUES_VIEWS as readonly string[]).includes(value)
    ? (value as IssuesView)
    : undefined
}

/**
 * Issue explorer route search: the Issues tab's readable filters, the active
 * tab (`view`), and the APM Errors tab's own readable `apm_*` filters so each
 * tab's view persists independently without one tab's facets leaking into the
 * other. Legacy `facets`/`afacets` JSON is accepted by validateSearch only.
 */
export interface IssuesSearch {
  q?: string
  view?: IssuesView
  service?: ReadableFacetSearchValue
  status?: ReadableFacetSearchValue
  level?: ReadableFacetSearchValue
  exclude_service?: ReadableFacetSearchValue
  exclude_status?: ReadableFacetSearchValue
  exclude_level?: ReadableFacetSearchValue
  aq?: string
  apm_service?: ReadableFacetSearchValue
  exclude_apm_service?: ReadableFacetSearchValue
  afacets?: FacetFilter[]
}

export const Route = createFileRoute('/issues/')({
  validateSearch: (search: Record<string, unknown>): IssuesSearch => {
    const issueFacets = issueFacetFiltersFromSearch(search)
    const issueSearch = issueFacets ? serializeIssueFacetSearch(issueFacets, true) : {}
    const parsedExplorer = parseExplorerSearch(search)
    const apmFacets = apmFacetFiltersFromSearch(search)
    const apmSearch = apmFacets ? serializeApmFacetSearch(apmFacets) : {}
    const apmQuery = parseExplorerSearch({q: search.aq})
    const result: IssuesSearch = {
      ...issueSearch,
      ...apmSearch,
    }
    if (parsedExplorer.q) result.q = parsedExplorer.q
    const view = parseIssuesView(search.view)
    if (view) result.view = view
    if (apmQuery.q) result.aq = apmQuery.q
    return result
  },
  component: IndexPage,
})

function useIssueFacetUrlState() {
  const search = Route.useSearch()
  const navigate = Route.useNavigate()
  const urlQuery = search.q
  const urlFacetsKey = issueFacetSearchKey(search)
  const shouldNormalizeFacetsUrl = hasLegacyIssueFacetParam()
  const resolvedFacetFilters = useMemo(() => resolveIssueFacetFilters(search), [search])

  const [searchQuery, setSearchQuery] = useState(() => urlQuery ?? '')
  const [facetFilters, setFacetFilters] = useState<FacetFilter[]>(() => resolvedFacetFilters)
  const didMountRef = useRef(false)
  const isHydratingRef = useRef(false)

  // URL -> state: adopt external changes only (shared-link nav, back/forward).
  // The first run is skipped because state is already seeded from the URL above.
  useEffect(() => {
    if (!didMountRef.current) {
      didMountRef.current = true
      return undefined
    }
    if (isHydratingRef.current) return undefined
    isHydratingRef.current = true
    setSearchQuery(urlQuery ?? '')
    setFacetFilters(resolvedFacetFilters)
    return releaseHydrationGuard(isHydratingRef)
  }, [urlQuery, urlFacetsKey, resolvedFacetFilters])

  // state -> URL: reflect active filters in human-readable params such as
  // `status=unresolved&service=api`. Unrelated params persist.
  useEffect(() => {
    if (isHydratingRef.current) return undefined
    const nextQuery = searchQuery.trim() || undefined
    const nextFacetSearch = serializeIssueFacetSearch(facetFilters, true)
    const nextFacetsKey = JSON.stringify(nextFacetSearch)
    if (nextQuery === urlQuery && nextFacetsKey === urlFacetsKey && !shouldNormalizeFacetsUrl) return undefined
    isHydratingRef.current = true
    navigate({
      replace: true,
      search: (prev) => ({
        ...clearIssueFacetSearch(prev),
        q: nextQuery,
        ...nextFacetSearch,
      }),
    })
    return releaseHydrationGuard(isHydratingRef)
  }, [searchQuery, facetFilters, navigate, urlQuery, urlFacetsKey, shouldNormalizeFacetsUrl])

  return {searchQuery, setSearchQuery, facetFilters, setFacetFilters}
}

function isDoubleStringifiedFacetSearchParam(name: string): boolean {
  if (globalThis.window === undefined) return false
  return new URLSearchParams(globalThis.window.location.search).get(name)?.startsWith('"') ?? false
}

/**
 * Two-way bind an explorer's free-text query + facet filters to the route URL.
 * State stays the source of truth for the UI; the URL mirrors it in APM's
 * namespaced `aq`/`apm_*` params and seeds it on load / back-forward.
 * `isHydratingRef` keeps the two effects from echoing each other (same approach
 * as LogExplorer).
 */
function useApmFacetUrlState() {
  const search = Route.useSearch()
  const navigate = Route.useNavigate()
  const urlQuery = search.aq
  const urlFacetsKey = apmFacetSearchKey(search)
  const shouldNormalizeFacetsUrl =
    hasLegacyApmFacetParam() || isDoubleStringifiedFacetSearchParam('afacets')

  const [searchQuery, setSearchQuery] = useState(() => urlQuery ?? '')
  const [facetFilters, setFacetFilters] = useState<FacetFilter[]>(() => apmFacetFiltersFromSearch(search) ?? [])
  const didMountRef = useRef(false)
  const isHydratingRef = useRef(false)

  // URL -> state: adopt external changes only (shared-link nav, back/forward).
  // The first run is skipped because state is already seeded from the URL above.
  useEffect(() => {
    if (!didMountRef.current) {
      didMountRef.current = true
      return undefined
    }
    if (isHydratingRef.current) return undefined
    isHydratingRef.current = true
    setSearchQuery(urlQuery ?? '')
    setFacetFilters(apmFacetFiltersFromSearch(search) ?? [])
    return releaseHydrationGuard(isHydratingRef)
  }, [urlQuery, urlFacetsKey, search])

  // state -> URL: reflect active APM filters. Unrelated params persist.
  useEffect(() => {
    if (isHydratingRef.current) return undefined
    const nextQuery = searchQuery.trim() || undefined
    const nextFacetSearch = serializeApmFacetSearch(facetFilters)
    const nextFacetsKey = Object.keys(nextFacetSearch).length > 0 ? JSON.stringify(nextFacetSearch) : undefined
    if (nextQuery === urlQuery && nextFacetsKey === urlFacetsKey && !shouldNormalizeFacetsUrl) return undefined
    isHydratingRef.current = true
    navigate({
      replace: true,
      search: (prev) => ({
        ...clearApmFacetSearch(prev),
        aq: nextQuery,
        ...nextFacetSearch,
      }),
    })
    return releaseHydrationGuard(isHydratingRef)
  }, [
    searchQuery,
    facetFilters,
    navigate,
    urlQuery,
    urlFacetsKey,
    shouldNormalizeFacetsUrl,
  ])

  return {searchQuery, setSearchQuery, facetFilters, setFacetFilters}
}

const ISSUES_VIEW_TABS = [
  {value: 'issues', label: 'Issues', icon: AlertCircle},
  {value: 'apm-errors', label: 'APM Errors', icon: Cpu},
] as const satisfies ReadonlyArray<SegmentedOption<IssuesView>>

function IndexPage() {
  const search = Route.useSearch()
  const navigate = Route.useNavigate()
  // State stays the source of truth for the tab; the URL mirrors it (shareable
  // links) and seeds it on load. The default tab stays out of the URL to keep
  // links clean. Adopt external URL changes (back/forward) by adjusting state
  // during render — React's sanctioned alternative to a state-syncing effect.
  const [activeTab, setActiveTab] = useState<IssuesView>(() => search.view ?? 'issues')
  const [syncedView, setSyncedView] = useState(search.view)
  if (syncedView !== search.view) {
    setSyncedView(search.view)
    setActiveTab(search.view ?? 'issues')
  }

  const handleTabChange = (view: IssuesView) => {
    setActiveTab(view)
    navigate({
      replace: true,
      search: (prev) => ({...prev, view: view === 'issues' ? undefined : view}),
    })
  }

  const viewTabs = (
    <SegmentedTabs
      ariaLabel="Issues view"
      value={activeTab}
      onChange={handleTabChange}
      options={ISSUES_VIEW_TABS}
    />
  )

  // Render the active explorer directly so it inherits the height of the app
  // shell's fixed content region (see __root.tsx) and scrolls within it. A
  // per-page `100vh` + `overflow-hidden` wrapper clipped long lists instead of
  // letting them scroll.
  return activeTab === 'issues' ? (
    <DashboardPage tabs={viewTabs} />
  ) : (
    <ApmErrorsTab isActive tabs={viewTabs} />
  )
}

type DashboardPageProps = Readonly<{
  tabs?: ReactNode
}>

function DashboardPage({tabs}: DashboardPageProps) {
  // Issues filters round-trip through readable URL params such as
  // `status=unresolved`, defaulting a fresh visit to unresolved.
  const {searchQuery, setSearchQuery, facetFilters, setFacetFilters} = useIssueFacetUrlState()
  const [selectedIssueKeys, setSelectedIssueKeys] = useState<Set<string>>(new Set())
  const [page, setPage] = useState(1)
  const pageSize = 25
  const { toast } = useToast()
  const queryClient = useQueryClient()

  const { data: services, isLoading, isError: servicesError, error: servicesErrorObj } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })
  const hasServices = (services?.length ?? 0) > 0

  const effectiveFacetFilters = facetFilters
  const includedServices = useMemo(
    () => facetValues(effectiveFacetFilters, 'service', false),
    [effectiveFacetFilters]
  )
  const excludedServices = useMemo(
    () => facetValues(effectiveFacetFilters, 'service', true),
    [effectiveFacetFilters]
  )
  const statusIncludes = useMemo(
    () => facetValues(effectiveFacetFilters, 'status', false),
    [effectiveFacetFilters]
  )
  const statusExcludes = useMemo(
    () => facetValues(effectiveFacetFilters, 'status', true),
    [effectiveFacetFilters]
  )
  const levelIncludes = useMemo(
    () => facetValues(effectiveFacetFilters, 'level', false),
    [effectiveFacetFilters]
  )
  const levelExcludes = useMemo(
    () => facetValues(effectiveFacetFilters, 'level', true),
    [effectiveFacetFilters]
  )
  const serviceFiltersActive = includedServices.length > 0 || excludedServices.length > 0
  const selectedServices = useMemo(
    () => serviceNamesForQuery(services ?? [], includedServices, excludedServices),
    [excludedServices, includedServices, services]
  )
  const issuesQueryEnabled = hasServices && (!serviceFiltersActive || selectedServices.length > 0)
  const statusParam = serverStatusFilter(statusIncludes, statusExcludes)

  const {
    data: organizationIssues = [],
    isLoading: issuesLoading,
    isError: issuesError,
    error: issuesErrorObj,
  } = useQuery({
    queryKey: ['issues', 'organization', selectedServices, statusParam, serviceFiltersActive],
    queryFn: () => api.getOrganizationIssues({
      page: 1,
      limit: ISSUES_FETCH_LIMIT,
      status: statusParam,
      services: serviceFiltersActive ? selectedServices : undefined,
    }),
    enabled: issuesQueryEnabled,
  })

  const {
    resolveMutation,
    ignoreMutation,
    resolveNextReleaseMutation,
  } = useIssueBulkMutations({
    queryClient,
    toast,
    clearSelection: () => setSelectedIssueKeys(new Set()),
  })

  const bulkPending = resolveMutation.isPending || ignoreMutation.isPending || resolveNextReleaseMutation.isPending

  const handleToggleIssue = (issue: SafeIssue) => {
    const key = issueSelectionKey(issue)
    setSelectedIssueKeys((prev) => toggledSelection(prev, key))
  }

  const handleToggleAll = () => {
    setSelectedIssueKeys((prev) => toggledPageSelection(prev, pagedIssues))
  }

  const handleResolveSelected = () => {
    resolveMutation.mutate(selectedIssueTargets)
  }

  const handleIgnoreSelected = () => {
    ignoreMutation.mutate(selectedIssueTargets)
  }

  const handleResolveNextReleaseSelected = () => {
    resolveNextReleaseMutation.mutate(selectedIssueTargets)
  }

  const {byResourceId: serviceNameByResourceId, byId: serviceNameById} = useMemo(
    () => serviceNameMaps(services ?? []),
    [services]
  )
  const safeIssues = useMemo(
    () => organizationIssues
      .map((issue) => toSafeIssue(issue, serviceNameByResourceId, serviceNameById))
      .filter((issue): issue is SafeIssue => issue !== null),
    [organizationIssues, serviceNameById, serviceNameByResourceId]
  )

  const safeIssuesByKey = useMemo(() => {
    const byKey = new Map<string, IssueUpdateTarget>()
    safeIssues.forEach((issue) => {
      byKey.set(issueSelectionKey(issue), {
        id: issue.id,
        projectId: issue.projectId,
      })
    })
    return byKey
  }, [safeIssues])
  const selectedIssueTargets = issueTargetsFromSelection(selectedIssueKeys, safeIssuesByKey)

  const normalizedSearchQuery = searchQuery.trim().toLowerCase()

  // Service filters are resolved by the org-wide API. Status, level, and search
  // narrow the loaded issue set client-side so include/exclude semantics remain.
  const filteredIssues = safeIssues
    .filter((issue) => matchesIssueFilters(
      issue,
      normalizedSearchQuery,
      statusIncludes,
      statusExcludes,
      levelIncludes,
      levelExcludes
    ))
    .sort((a, b) => (b.lastSeen || '').localeCompare(a.lastSeen || ''))

  const totalPages = Math.max(1, Math.ceil(filteredIssues.length / pageSize))
  const currentPage = Math.min(page, totalPages)
  const pagedIssues = filteredIssues.slice((currentPage - 1) * pageSize, currentPage * pageSize)

  const hasActiveIssueFilters =
    Boolean(searchQuery) ||
    effectiveFacetFilters.length > 0

  function handleFacetFiltersChange(next: FacetFilter[]) {
    setFacetFilters(next)
    setSelectedIssueKeys(new Set())
    setPage(1)
  }

  const serviceNames = useMemo(() => (services ?? []).map((service) => service.name), [services])
  const issueSchema: FacetSchema = useMemo(
    () => [
      {key: 'service', suggestions: serviceNames},
      {
        key: 'status',
        suggestions: ['unresolved', 'resolved', 'ignored', 'resolvedInNextRelease'],
      },
      {key: 'level', suggestions: ['error', 'warning', 'fatal', 'info', 'debug']},
    ],
    [serviceNames]
  )
  const issueRailSections: FacetRailSection[] = useMemo(
    () => [
      {
        key: 'service',
        label: 'Service',
        color: 'bg-primary',
        options: (services ?? []).map((service) => ({value: service.name})),
      },
      {
        key: 'status',
        label: 'Status',
        color: 'bg-warning-solid',
        options: [
          {value: 'unresolved', label: 'Unresolved'},
          {value: 'resolved', label: 'Resolved'},
          {value: 'ignored', label: 'Ignored'},
          {value: 'resolvedInNextRelease', label: 'Next release'},
        ],
      },
      {
        key: 'level',
        label: 'Level',
        color: 'bg-danger-solid',
        options: [
          {value: 'error', label: 'Error'},
          {value: 'warning', label: 'Warning'},
          {value: 'fatal', label: 'Fatal'},
          {value: 'info', label: 'Info'},
          {value: 'debug', label: 'Debug'},
        ],
      },
    ],
    [services]
  )

  if (isLoading) return <div className="p-8">Loading...</div>

  if (servicesError) return (
    <div className="p-8 text-destructive">
      Failed to load services: {servicesErrorObj instanceof Error ? servicesErrorObj.message : 'Unknown error'}
    </div>
  )

  if (!hasServices) {
    return (
      <div className="px-6 py-4">
        <Card className="p-12 text-center border-primary/20 bg-gradient-to-b from-card to-primary/5">
          <div className="max-w-md mx-auto space-y-4">
            <div className="flex justify-center">
              <div className="rounded-full bg-primary/15 p-4 ring-2 ring-primary/20">
                <FolderKanban className="h-10 w-10 text-primary" />
              </div>
            </div>
            <div>
              <h3 className="text-lg font-semibold mb-2">No services yet</h3>
              <p className="text-muted-foreground mb-4">
                Create your first service to start tracking errors and monitoring your applications.
              </p>
            </div>
            <div className="flex justify-center">
              <Button
                size="lg"
                className="w-full max-w-sm sm:w-auto sm:mx-auto"
                onClick={() => globalThis.dispatchEvent(new CustomEvent('open-create-service-dialog'))}
              >
                <Plus className="h-4 w-4" />
                Create your first service
              </Button>
            </div>
          </div>
        </Card>
      </div>
    )
  }

  return (
    <ExplorerShell
      tabs={tabs}
      searchBar={
        <SearchFilterBar
          query={searchQuery}
          onQueryChange={(q) => { setSearchQuery(q); setPage(1); setSelectedIssueKeys(new Set()) }}
          facetFilters={effectiveFacetFilters}
          onFacetFiltersChange={handleFacetFiltersChange}
          schema={issueSchema}
          placeholder="Search issues..."
        />
      }
      rail={
        <FacetRail
          sections={issueRailSections}
          facetFilters={effectiveFacetFilters}
          onFacetFiltersChange={handleFacetFiltersChange}
        />
      }
      toolbar={
        <IssueExplorerToolbar
          pagedIssues={pagedIssues}
          selectedIssueKeys={selectedIssueKeys}
          selectedIssueCount={selectedIssueTargets.length}
          bulkPending={bulkPending}
          filteredIssueCount={filteredIssues.length}
          onToggleAll={handleToggleAll}
          onResolveSelected={handleResolveSelected}
          onIgnoreSelected={handleIgnoreSelected}
          onResolveNextReleaseSelected={handleResolveNextReleaseSelected}
        />
      }
    >
      <IssueExplorerContent
        issuesLoading={issuesLoading}
        issuesError={issuesError}
        issuesErrorObj={issuesErrorObj}
        filteredIssues={filteredIssues}
        pagedIssues={pagedIssues}
        selectedIssueKeys={selectedIssueKeys}
        totalPages={totalPages}
        currentPage={currentPage}
        hasActiveIssueFilters={hasActiveIssueFilters}
        onToggleIssue={handleToggleIssue}
        onPreviousPage={() => setPage(p => Math.max(1, p - 1))}
        onNextPage={() => setPage(p => p + 1)}
      />
    </ExplorerShell>
  )
}

type IssueExplorerToolbarProps = Readonly<{
  pagedIssues: readonly SafeIssue[]
  selectedIssueKeys: ReadonlySet<string>
  selectedIssueCount: number
  bulkPending: boolean
  filteredIssueCount: number
  onToggleAll: () => void
  onResolveSelected: () => void
  onIgnoreSelected: () => void
  onResolveNextReleaseSelected: () => void
}>

function IssueExplorerToolbar({
  pagedIssues,
  selectedIssueKeys,
  selectedIssueCount,
  bulkPending,
  filteredIssueCount,
  onToggleAll,
  onResolveSelected,
  onIgnoreSelected,
  onResolveNextReleaseSelected,
}: IssueExplorerToolbarProps) {
  const allPagedIssuesSelected = pagedIssues.every((issue) =>
    selectedIssueKeys.has(issueSelectionKey(issue))
  )

  return (
    <>
      {pagedIssues.length > 0 && (
        <div className="flex items-center gap-2">
          <Checkbox
            checked={allPagedIssuesSelected}
            onCheckedChange={onToggleAll}
            aria-label="Select all issues"
          />
          <span className="text-sm text-muted-foreground whitespace-nowrap hidden sm:inline">Select all</span>
        </div>
      )}
      {selectedIssueCount > 0 && (
        <div className="flex items-center gap-2 bg-primary/10 border border-primary/20 rounded-lg px-2 py-0.5">
          <CheckCircle2 className="h-4 w-4 text-primary" />
          <span className="text-sm font-medium whitespace-nowrap">{selectedIssueCount} selected</span>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button disabled={bulkPending} size="sm" className="h-7 ml-1">
                {bulkPending ? 'Updating...' : 'Actions'}
                <ChevronDown className="h-3 w-3 ml-1" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem onClick={onResolveSelected}>
                <CheckCircle2 className="h-4 w-4 mr-2" />
                Resolve
              </DropdownMenuItem>
              <DropdownMenuItem onClick={onIgnoreSelected}>
                <EyeOff className="h-4 w-4 mr-2" />
                Ignore
              </DropdownMenuItem>
              <DropdownMenuItem onClick={onResolveNextReleaseSelected}>
                <Timer className="h-4 w-4 mr-2" />
                Resolve in Next Release
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      )}
      <span className="ml-auto text-sm text-muted-foreground whitespace-nowrap hidden lg:inline">
        {filteredIssueCount} result{filteredIssueCount === 1 ? '' : 's'}
      </span>
    </>
  )
}

type IssueExplorerContentProps = Readonly<{
  issuesLoading: boolean
  issuesError: boolean
  issuesErrorObj: unknown
  filteredIssues: readonly SafeIssue[]
  pagedIssues: readonly SafeIssue[]
  selectedIssueKeys: ReadonlySet<string>
  totalPages: number
  currentPage: number
  hasActiveIssueFilters: boolean
  onToggleIssue: (issue: SafeIssue) => void
  onPreviousPage: () => void
  onNextPage: () => void
}>

function IssueExplorerContent({
  issuesLoading,
  issuesError,
  issuesErrorObj,
  filteredIssues,
  pagedIssues,
  selectedIssueKeys,
  totalPages,
  currentPage,
  hasActiveIssueFilters,
  onToggleIssue,
  onPreviousPage,
  onNextPage,
}: IssueExplorerContentProps) {
  if (issuesLoading) {
    return <div className="p-6 text-muted-foreground">Loading issues...</div>
  }

  if (issuesError) {
    return (
      <div className="p-6 text-destructive border border-destructive/30 rounded-lg bg-destructive/5">
        Failed to load issues: {issuesErrorObj instanceof Error ? issuesErrorObj.message : 'Unknown error'}
      </div>
    )
  }

  if (filteredIssues.length === 0) {
    return (
      <div className="p-4">
        <IssueEmptyState hasActiveIssueFilters={hasActiveIssueFilters} />
      </div>
    )
  }

  return (
    <div className="p-4">
      <IssueTable
        pagedIssues={pagedIssues}
        selectedIssueKeys={selectedIssueKeys}
        totalPages={totalPages}
        currentPage={currentPage}
        onToggleIssue={onToggleIssue}
        onPreviousPage={onPreviousPage}
        onNextPage={onNextPage}
      />
    </div>
  )
}

function IssueEmptyState({hasActiveIssueFilters}: Readonly<{hasActiveIssueFilters: boolean}>) {
  const Icon = hasActiveIssueFilters ? Search : AlertCircle
  const title = hasActiveIssueFilters ? 'No issues match your filters' : 'No issues yet'
  const description = hasActiveIssueFilters
    ? 'Try adjusting your search or filters.'
    : 'Start sending errors to your services to see them tracked here.'

  return (
    <Card className="p-12 text-center border-info-border/50 bg-gradient-to-b from-card to-info-bg">
      <div className="max-w-md mx-auto space-y-4">
        <div className="flex justify-center">
          <div className="rounded-full bg-info-bg p-4">
            <Icon className="h-10 w-10 text-info-fg" />
          </div>
        </div>
        <div>
          <h3 className="text-lg font-semibold mb-2">{title}</h3>
          <p className="text-muted-foreground">{description}</p>
        </div>
      </div>
    </Card>
  )
}

type IssueTableProps = Readonly<{
  pagedIssues: readonly SafeIssue[]
  selectedIssueKeys: ReadonlySet<string>
  totalPages: number
  currentPage: number
  onToggleIssue: (issue: SafeIssue) => void
  onPreviousPage: () => void
  onNextPage: () => void
}>

function IssueTable({
  pagedIssues,
  selectedIssueKeys,
  totalPages,
  currentPage,
  onToggleIssue,
  onPreviousPage,
  onNextPage,
}: IssueTableProps) {
  return (
    <div className="rounded-lg border border-border/60 bg-card overflow-hidden">
      <div className="hidden md:flex items-center gap-3 py-2 px-4 bg-muted/40 border-b border-border/40 text-[11px] font-medium text-muted-foreground uppercase tracking-wider select-none">
        <div className="w-4 shrink-0" />
        <div className="w-[4.5rem] shrink-0">Level</div>
        <div className="flex-1 min-w-0">Issue</div>
        <div className="hidden lg:block w-20 shrink-0">Platform</div>
        <div className="hidden lg:block w-20 shrink-0 text-center">Trend</div>
        <div className="w-[55px] shrink-0 text-right">Events</div>
        <div className="hidden sm:block w-[45px] shrink-0 text-right">Users</div>
        <div className="w-20 shrink-0 text-right">Last Seen</div>
      </div>
      <div className="divide-y divide-border/40">
        {pagedIssues.map((issue) => (
          <IssueRow
            key={issueSelectionKey(issue)}
            issue={issue}
            selected={selectedIssueKeys.has(issueSelectionKey(issue))}
            onToggle={onToggleIssue}
          />
        ))}
      </div>
      {totalPages > 1 && (
        <div className="flex justify-end gap-2 px-4 py-2 border-t border-border/40">
          <Button
            size="sm"
            variant="outline"
            disabled={currentPage <= 1}
            onClick={onPreviousPage}
          >
            Previous
          </Button>
          <span className="text-sm text-muted-foreground self-center">
            Page {currentPage} of {totalPages}
          </span>
          <Button
            size="sm"
            variant="outline"
            disabled={currentPage >= totalPages}
            onClick={onNextPage}
          >
            Next
          </Button>
        </div>
      )}
    </div>
  )
}

function IssueStatusBadges({issue}: Readonly<{issue: SafeIssue}>) {
  return (
    <div className="flex items-center gap-2 shrink-0">
      {isNewIssue(issue.firstSeen) && (
        <span className="text-[10px] font-bold text-success-fg bg-success-bg px-1.5 py-0.5 rounded uppercase">
          New
        </span>
      )}
      {issue.status === 'resolved' && (
        <Badge variant="success" className="text-[11px] px-1.5 py-0">
          Resolved
        </Badge>
      )}
      {issue.status === 'ignored' && (
        <Badge variant="secondary" className="text-[11px] px-1.5 py-0">
          <EyeOff className="h-3 w-3" />
          Ignored
        </Badge>
      )}
      {issue.status === 'resolvedInNextRelease' && (
        <Badge variant="info" className="text-[11px] px-1.5 py-0">
          <Timer className="h-3 w-3" />
          Next Release
        </Badge>
      )}
    </div>
  )
}

type IssueRowProps = Readonly<{
  issue: SafeIssue
  selected: boolean
  onToggle: (issue: SafeIssue) => void
}>

function IssueRow({issue, selected, onToggle}: IssueRowProps) {
  const title = getIssueDisplayTitle(issue)

  return (
    <div className={`hover:bg-accent/40 transition border-l-[3px] ${levelBorderClass(issue.level)}`}>
      <div className="flex items-center gap-2 sm:gap-3 py-2 sm:py-2.5 px-2 sm:px-4">
        <Checkbox
          checked={selected}
          onCheckedChange={() => onToggle(issue)}
          onClick={(e) => e.stopPropagation()}
          aria-label={`Select ${issue.title}`}
          className="shrink-0"
        />
        <Link
          to="/issues/$issueId"
          params={{ issueId: issue.id }}
          search={{ projectId: issue.projectId }}
          className="flex-1 flex items-center gap-2 sm:gap-3 min-w-0"
        >
          <div className="w-12 sm:w-[4.5rem] shrink-0">
            <Badge variant={levelBadgeVariant(issue.level)} className="text-[10px] sm:text-[11px] px-1.5 py-0">
              <span className="sm:hidden">{issue.level.toUpperCase().slice(0, 3)}</span>
              <span className="hidden sm:inline">{issue.level.toUpperCase()}</span>
            </Badge>
          </div>
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-1.5 sm:gap-2 min-w-0">
              <span className="font-semibold truncate flex-1 min-w-0 text-sm sm:text-base" title={title}>
                {title}
              </span>
              <IssueStatusBadges issue={issue} />
            </div>
            <div className="text-xs text-muted-foreground mt-0.5">
              <Clock className="inline h-3 w-3 mr-1 -mt-0.5" />
              First seen {formatRelativeTime(issue.firstSeen)}
            </div>
          </div>
          <div className="hidden lg:block w-20 shrink-0">
            <Badge variant="outline" className="text-[11px] px-1.5 py-0">{issue.platform}</Badge>
          </div>
          <div className="hidden lg:flex w-20 shrink-0 justify-center">
            <EventSparkline eventCount={issue.eventCount} />
          </div>
          <div className="w-[55px] shrink-0 text-right">
            <div className="font-semibold text-foreground">{formatCount(issue.eventCount)}</div>
            <div className="text-xs text-muted-foreground">events</div>
          </div>
          <div className="hidden sm:block w-[45px] shrink-0 text-right">
            <div className="font-semibold text-foreground">{issue.userCount ?? 0}</div>
            <div className="text-xs text-muted-foreground">users</div>
          </div>
          <div className="hidden md:block w-20 shrink-0 text-right">
            <span className={`text-xs font-medium ${getLastSeenColor(issue.lastSeen)}`}>
              {formatRelativeTime(issue.lastSeen)}
            </span>
          </div>
        </Link>
      </div>
    </div>
  )
}

const APM_ERRORS_PAGE_SIZE = 50
const APM_TIME_PRESETS: TimeRangePreset[] = [
  {label: '1h', value: '1h', minutes: 60},
  {label: '6h', value: '6h', minutes: 360},
  {label: '24h', value: '24h', minutes: 1440},
  {label: '7d', value: '7d', minutes: 10080},
  {label: '30d', value: '30d', minutes: 43200},
  {label: '90d', value: '90d', minutes: 129600},
]

function visibleApmErrors(errors: readonly ApmErrorGroup[], normalizedQuery: string): readonly ApmErrorGroup[] {
  if (!normalizedQuery) return errors
  return errors.filter((error) =>
    `${error.resource} ${error.errorMessage} ${error.errorType}`.toLowerCase().includes(normalizedQuery)
  )
}

type ApmErrorsTabProps = Readonly<{
  isActive: boolean
  tabs?: ReactNode
}>

function ApmErrorsTab({ isActive, tabs }: ApmErrorsTabProps) {
  // APM filters live in the URL under their own namespace (aq/apm_*) so the
  // Issues and APM tab views stay shareable and persist independently across tab
  // switches (replaces the old localStorage facet slice). No default facet.
  const {searchQuery: query, setSearchQuery: setQuery, facetFilters, setFacetFilters} =
    useApmFacetUrlState()
  const [timeRange, setTimeRange] = useState<ApmTimeRange>('24h')
  const [offset, setOffset] = useState(0)

  // The trace API filters by an explicit service list (no excludes), so only
  // include-mode service facets reach the query.
  const selectedServices = useMemo(
    () => facetFilters.filter((f) => f.key === 'service' && !f.exclude).map((f) => f.value),
    [facetFilters]
  )

  function handleFacetFiltersChange(next: FacetFilter[]) {
    setFacetFilters(next)
    setOffset(0)
  }

  const { data, isLoading } = useQuery({
    queryKey: ['apm-errors', selectedServices, timeRange, offset],
    queryFn: () => api.getApmErrors({
      services: selectedServices.length ? selectedServices : undefined,
      // Opt-in: with nothing selected we only need the facet counts (to populate
      // the rail), not the all-services error list — so request zero rows.
      limit: selectedServices.length ? APM_ERRORS_PAGE_SIZE : 0,
      offset: selectedServices.length ? offset : 0,
      timeRange,
    }),
    enabled: isActive && api.isAuthenticated(),
    retry: false,
    staleTime: 30000,
    refetchOnWindowFocus: false,
  })

  const errors = data?.errors ?? []
  const totalCount = data?.totalCount ?? 0
  const canPrev = offset > 0
  const canNext = offset + errors.length < totalCount

  const services = useMemo(() => {
    const countByService = new Map<string, number>()
    for (const facet of data?.serviceFacets ?? []) {
      countByService.set(facet.service, (countByService.get(facet.service) ?? 0) + facet.count)
    }
    return Array.from(countByService.entries())
      .map(([service, count]) => ({ service, count }))
      .sort((a, b) => b.count - a.count)
  }, [data?.serviceFacets])

  const hasServices = services.length > 0
  const hasSelection = selectedServices.length > 0

  // Free-text narrows the loaded page client-side (the trace API has no text query).
  const normalizedQuery = query.trim().toLowerCase()
  const visibleErrors = visibleApmErrors(errors, normalizedQuery)

  const schema: FacetSchema = useMemo(
    () => [{key: 'service', suggestions: services.map((s) => s.service)}],
    [services]
  )
  const railSections: FacetRailSection[] = useMemo(
    () => [
      {
        key: 'service',
        label: 'Service',
        color: 'bg-primary',
        allowExclude: false,
        options: services.map((s) => ({value: s.service, count: s.count})),
      },
    ],
    [services]
  )

  return (
    <ExplorerShell
      tabs={tabs}
      searchBar={
        <SearchFilterBar
          query={query}
          onQueryChange={(q) => { setQuery(q); setOffset(0) }}
          facetFilters={facetFilters}
          onFacetFiltersChange={handleFacetFiltersChange}
          schema={schema}
          placeholder="Filter by service..."
          trailing={
            <TimeRangePicker
              timePreset={timeRange}
              onTimePresetChange={(v) => { setTimeRange(v as ApmTimeRange); setOffset(0) }}
              customFrom=""
              customTo=""
              onCustomFromChange={() => {}}
              onCustomToChange={() => {}}
              presets={APM_TIME_PRESETS}
              allowCustom={false}
            />
          }
        />
      }
      rail={
        <FacetRail
          sections={railSections}
          facetFilters={facetFilters}
          onFacetFiltersChange={handleFacetFiltersChange}
        />
      }
      toolbar={
        hasSelection && !isLoading ? (
          <span className="ml-auto whitespace-nowrap text-xs tabular-nums text-muted-foreground">
            {totalCount.toLocaleString()} error{totalCount === 1 ? '' : 's'}
          </span>
        ) : null
      }
    >
      <ApmErrorsContent
        hasServices={hasServices}
        hasSelection={hasSelection}
        isLoading={isLoading}
        visibleErrors={visibleErrors}
        normalizedQuery={normalizedQuery}
        totalCount={totalCount}
        offset={offset}
        errorCount={errors.length}
        canPrev={canPrev}
        canNext={canNext}
        onPreviousPage={() => setOffset(Math.max(0, offset - APM_ERRORS_PAGE_SIZE))}
        onNextPage={() => setOffset(offset + APM_ERRORS_PAGE_SIZE)}
      />
    </ExplorerShell>
  )
}

type ApmErrorsContentProps = Readonly<{
  hasServices: boolean
  hasSelection: boolean
  isLoading: boolean
  visibleErrors: readonly ApmErrorGroup[]
  normalizedQuery: string
  totalCount: number
  offset: number
  errorCount: number
  canPrev: boolean
  canNext: boolean
  onPreviousPage: () => void
  onNextPage: () => void
}>

function ApmErrorsContent({
  hasServices,
  hasSelection,
  isLoading,
  visibleErrors,
  normalizedQuery,
  totalCount,
  offset,
  errorCount,
  canPrev,
  canNext,
  onPreviousPage,
  onNextPage,
}: ApmErrorsContentProps) {
  if (!hasServices) {
    return (
      <div className="p-4">
        {isLoading ? <ApmErrorsLoading /> : <NoApmErrorsState />}
      </div>
    )
  }

  if (!hasSelection) {
    return (
      <div className="p-4">
        <SelectApmServiceState />
      </div>
    )
  }

  if (isLoading) {
    return (
      <div className="p-4">
        <ApmErrorsLoading />
      </div>
    )
  }

  if (visibleErrors.length === 0) {
    return (
      <div className="p-4">
        <NoVisibleApmErrorsState hasSearch={Boolean(normalizedQuery)} />
      </div>
    )
  }

  return (
    <div className="p-4">
      <ApmErrorsTable
        visibleErrors={visibleErrors}
        totalCount={totalCount}
        offset={offset}
        errorCount={errorCount}
        showRange={totalCount > APM_ERRORS_PAGE_SIZE && !normalizedQuery}
        canPrev={canPrev}
        canNext={canNext}
        onPreviousPage={onPreviousPage}
        onNextPage={onNextPage}
      />
    </div>
  )
}

function ApmErrorsLoading() {
  return <div className="py-16 text-center text-muted-foreground">Loading APM errors...</div>
}

function NoApmErrorsState() {
  return (
    <Card className="p-12 text-center border-info-border/50 bg-gradient-to-b from-card to-info-bg">
      <div className="max-w-md mx-auto space-y-4">
        <div className="flex justify-center">
          <div className="rounded-full bg-info-bg p-4">
            <AlertTriangle className="h-10 w-10 text-info-fg" />
          </div>
        </div>
        <div>
          <h3 className="text-lg font-semibold mb-2">No APM errors found</h3>
          <p className="text-muted-foreground">
            Errors from application traces will appear here when spans report errors.
          </p>
        </div>
      </div>
    </Card>
  )
}

function SelectApmServiceState() {
  return (
    <Card className="p-12 text-center border-border/60">
      <div className="max-w-md mx-auto space-y-3">
        <div className="flex justify-center">
          <div className="rounded-full bg-muted p-4">
            <Server className="h-10 w-10 text-muted-foreground" />
          </div>
        </div>
        <div>
          <h3 className="text-lg font-semibold mb-1">Select a service</h3>
          <p className="text-muted-foreground">
            Pick one or more services from the search bar or the facet rail to view their errors.
          </p>
        </div>
      </div>
    </Card>
  )
}

function NoVisibleApmErrorsState({hasSearch}: Readonly<{hasSearch: boolean}>) {
  const title = hasSearch ? 'No errors match your search' : 'No errors for the selected services'
  const description = hasSearch
    ? 'Try a different search or clear it.'
    : 'Try a different service or widen the time range.'

  return (
    <Card className="p-12 text-center border-border/60">
      <div className="max-w-md mx-auto space-y-3">
        <div className="flex justify-center">
          <div className="rounded-full bg-muted p-4">
            <AlertTriangle className="h-10 w-10 text-muted-foreground" />
          </div>
        </div>
        <div>
          <h3 className="text-lg font-semibold mb-1">{title}</h3>
          <p className="text-muted-foreground">{description}</p>
        </div>
      </div>
    </Card>
  )
}

type ApmErrorsTableProps = Readonly<{
  visibleErrors: readonly ApmErrorGroup[]
  totalCount: number
  offset: number
  errorCount: number
  showRange: boolean
  canPrev: boolean
  canNext: boolean
  onPreviousPage: () => void
  onNextPage: () => void
}>

function ApmErrorsTable({
  visibleErrors,
  totalCount,
  offset,
  errorCount,
  showRange,
  canPrev,
  canNext,
  onPreviousPage,
  onNextPage,
}: ApmErrorsTableProps) {
  return (
    <div className="rounded-lg border border-border/60 bg-card overflow-hidden">
      {showRange && (
        <div className="px-4 py-2 text-xs text-muted-foreground border-b border-border/40">
          Showing {offset + 1}-{offset + errorCount} of {totalCount}
        </div>
      )}
      <div className="hidden md:grid md:grid-cols-[8rem_1fr_4rem_6rem_4rem] items-center gap-3 py-2 px-4 bg-muted/40 border-b border-border/40 text-[11px] font-medium text-muted-foreground uppercase tracking-wider select-none">
        <div>Service</div>
        <div>Error</div>
        <div className="text-right">Count</div>
        <div className="text-right">Last Seen</div>
        <div className="text-right">Trace</div>
      </div>
      <div className="divide-y divide-border/40">
        {visibleErrors.map((error) => (
          <ApmErrorRow key={apmErrorRowKey(error)} error={error} />
        ))}
      </div>
      {(canPrev || canNext) && (
        <div className="flex justify-end gap-2 px-4 py-2 border-t border-border/40">
          <Button
            size="sm"
            variant="outline"
            disabled={!canPrev}
            onClick={onPreviousPage}
          >
            Previous
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={!canNext}
            onClick={onNextPage}
          >
            Next
          </Button>
        </div>
      )}
    </div>
  )
}

function apmErrorRowKey(error: ApmErrorGroup): string {
  return `${error.service}|${error.resource}|${error.errorMessage}|${error.errorType}`
}

function ApmErrorRow({error}: Readonly<{error: ApmErrorGroup}>) {
  const traceId = normalizeApmTraceId(error.traceId)

  return (
    <div className="hover:bg-accent/40 transition border-l-[3px] border-l-red-500">
      <div className="grid grid-cols-[7rem_1fr_auto] md:grid-cols-[8rem_1fr_4rem_6rem_4rem] items-center gap-2 sm:gap-3 py-2 sm:py-2.5 px-2 sm:px-4">
        <Badge
          variant="outline"
          title={error.service}
          className="min-w-0 max-w-full w-fit text-[11px] px-1.5 py-0 gap-1"
        >
          <Server className="h-3 w-3 shrink-0" />
          <span className="min-w-0 truncate">{error.service}</span>
        </Badge>
        <div className="min-w-0">
          <div className="font-semibold truncate text-sm">
            {error.errorMessage || error.resource}
          </div>
          {error.errorType && (
            <div className="text-xs text-muted-foreground truncate">
              {error.errorType}
            </div>
          )}
          <div className="text-xs text-muted-foreground truncate mt-0.5">
            {error.resource}
          </div>
        </div>
        <div className="text-right">
          <div className="font-semibold text-foreground">
            {formatCount(error.count)}
          </div>
          <div className="text-xs text-muted-foreground">errors</div>
        </div>
        <div className="hidden md:block text-right text-xs text-muted-foreground">
          {error.lastSeen ? formatRelativeTime(error.lastSeen) : '-'}
        </div>
        <div className="hidden md:block text-right">
          {traceId && (
            <Link
              to="/performance/traces/$traceId"
              params={{ traceId }}
              className="text-xs text-primary hover:underline"
              onClick={(e) => e.stopPropagation()}
            >
              View trace
            </Link>
          )}
        </div>
      </div>
    </div>
  )
}
