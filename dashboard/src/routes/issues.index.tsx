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
import {formatRelativeTime, cn} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card} from '@/components/ui/card'
import {SearchFilterBar} from '@/components/filters/SearchFilterBar'
import {FacetRail} from '@/components/filters/FacetRail'
import {ExplorerShell} from '@/components/filters/ExplorerShell'
import {TimeRangePicker} from '@/components/filters/TimeRangePicker'
import type {TimeRangePreset} from '@/lib/filters/time'
import type {FacetFilter, FacetSchema, FacetRailSection} from '@/lib/filters/types'
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
import {useEffect, useMemo, useState} from 'react'
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
  projectResourceId: string
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
  projectResourceId: string
}

function issueSelectionKey(issue: Pick<SafeIssue, 'id' | 'projectResourceId'>): string {
  return `${issue.projectResourceId}:${issue.id}`
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
    byResourceId: new Map(services.map((service) => [service.resourceId, service.name])),
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

  const projectResourceId = toSafeId(issue.projectResourceId) || toSafeId(issue.projectId)
  if (!projectResourceId) return null

  const service =
    servicesByResourceId.get(projectResourceId) ??
    servicesById.get(toSafeId(issue.projectId)) ??
    projectResourceId

  return {
    id,
    projectResourceId,
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

export const Route = createFileRoute('/issues/')({
  component: IndexPage,
})

function IndexPage() {
  const [activeTab, setActiveTab] = useState<'issues' | 'apm-errors'>('issues')

  return (
    <div className="flex h-[calc(100vh-var(--header-height,0px))] flex-col overflow-hidden">
      <div className="shrink-0 border-b px-6">
        <div className="flex gap-4">
          <button
            type="button"
            onClick={() => setActiveTab('issues')}
            className={cn(
              'border-b-2 px-1 py-3 text-sm font-medium inline-flex items-center',
              activeTab === 'issues'
                ? 'border-primary text-foreground'
                : 'border-transparent text-muted-foreground hover:text-foreground'
            )}
          >
            <AlertCircle className="h-4 w-4 mr-1.5" />
            Issues
          </button>
          <button
            type="button"
            onClick={() => setActiveTab('apm-errors')}
            className={cn(
              'border-b-2 px-1 py-3 text-sm font-medium inline-flex items-center',
              activeTab === 'apm-errors'
                ? 'border-primary text-foreground'
                : 'border-transparent text-muted-foreground hover:text-foreground'
            )}
          >
            <Cpu className="h-4 w-4 mr-1.5" />
            APM Errors
          </button>
        </div>
      </div>
      <div className="min-h-0 flex-1">
        {activeTab === 'issues' ? (
          <DashboardPage />
        ) : (
          <ApmErrorsTab isActive />
        )}
      </div>
    </div>
  )
}

function DashboardPage() {
  const [searchQuery, setSearchQuery] = useState('')
  const [facetFilters, setFacetFilters] = useState<FacetFilter[]>([])
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
    queryKey: ['issues', 'organization', selectedServices, statusParam],
    queryFn: () => api.getOrganizationIssues({
      page: 1,
      limit: ISSUES_FETCH_LIMIT,
      status: statusParam,
      services: serviceFiltersActive ? selectedServices : undefined,
    }),
    enabled: issuesQueryEnabled,
  })

  const resolveMutation = useMutation({
    mutationFn: async (issues: IssueUpdateTarget[]) => {
      const results = await Promise.allSettled(
        issues.map((issue) => api.updateIssue(issue.id, { status: 'resolved' }, issue.projectResourceId))
      )
      const successCount = results.filter(r => r.status === 'fulfilled').length
      if (successCount === 0) throw new Error('All requests failed')
      return { submitted: issues.length, successCount }
    },
    onSuccess: ({ submitted, successCount }) => {
      trackEvent('Issue Resolve', { count: String(successCount) })
      queryClient.invalidateQueries({ queryKey: ['issues'] })
      queryClient.invalidateQueries({ queryKey: ['stats'] })
      if (successCount === submitted) {
        toast({
          title: 'Success',
          description: `${submitted} issue${submitted === 1 ? '' : 's'} resolved`,
        })
      } else {
        toast({
          title: 'Partial Success',
          description: `${successCount}/${submitted} issues resolved`,
          variant: 'destructive',
        })
      }
      setSelectedIssueKeys(new Set())
    },
    onError: () => {
      toast({
        title: 'Error',
        description: 'Failed to resolve issues',
        variant: 'destructive',
      })
    },
  })

  const ignoreMutation = useMutation({
    mutationFn: async (issues: IssueUpdateTarget[]) => {
      const results = await Promise.allSettled(
        issues.map((issue) => api.updateIssue(issue.id, { status: 'ignored' }, issue.projectResourceId))
      )
      const successCount = results.filter(r => r.status === 'fulfilled').length
      if (successCount === 0) throw new Error('All requests failed')
      return { submitted: issues.length, successCount }
    },
    onSuccess: ({ submitted, successCount }) => {
      trackEvent('Issue Ignore', { count: String(successCount) })
      queryClient.invalidateQueries({ queryKey: ['issues'] })
      queryClient.invalidateQueries({ queryKey: ['stats'] })
      if (successCount === submitted) {
        toast({
          title: 'Success',
          description: `${submitted} issue${submitted === 1 ? '' : 's'} ignored`,
        })
      } else {
        toast({
          title: 'Partial Success',
          description: `${successCount}/${submitted} issues ignored`,
          variant: 'destructive',
        })
      }
      setSelectedIssueKeys(new Set())
    },
    onError: () => {
      toast({ title: 'Error', description: 'Failed to ignore issues', variant: 'destructive' })
    },
  })

  const resolveNextReleaseMutation = useMutation({
    mutationFn: async (issues: IssueUpdateTarget[]) => {
      const results = await Promise.allSettled(
        issues.map((issue) =>
          api.updateIssue(issue.id, { status: 'resolvedInNextRelease' }, issue.projectResourceId)
        )
      )
      const successCount = results.filter(r => r.status === 'fulfilled').length
      if (successCount === 0) throw new Error('All requests failed')
      return { submitted: issues.length, successCount }
    },
    onSuccess: ({ submitted, successCount }) => {
      trackEvent('Issue ResolveInNextRelease', { count: String(successCount) })
      queryClient.invalidateQueries({ queryKey: ['issues'] })
      queryClient.invalidateQueries({ queryKey: ['stats'] })
      if (successCount === submitted) {
        toast({
          title: 'Success',
          description: `${submitted} issue${submitted === 1 ? '' : 's'} marked to resolve in next release`,
        })
      } else {
        toast({
          title: 'Partial Success',
          description: `${successCount}/${submitted} issues marked to resolve in next release`,
          variant: 'destructive',
        })
      }
      setSelectedIssueKeys(new Set())
    },
    onError: () => {
      toast({ title: 'Error', description: 'Failed to update issues', variant: 'destructive' })
    },
  })

  const bulkPending = resolveMutation.isPending || ignoreMutation.isPending || resolveNextReleaseMutation.isPending

  const handleToggleIssue = (issue: SafeIssue) => {
    const key = issueSelectionKey(issue)
    const newSelected = new Set(selectedIssueKeys)
    if (newSelected.has(key)) {
      newSelected.delete(key)
    } else {
      newSelected.add(key)
    }
    setSelectedIssueKeys(newSelected)
  }

  const handleToggleAll = () => {
    const pageKeys = pagedIssues.map(issueSelectionKey)
    const allPageIssuesSelected = pageKeys.every((key) => selectedIssueKeys.has(key))
    const nextSelected = new Set(selectedIssueKeys)
    if (allPageIssuesSelected) {
      pageKeys.forEach((key) => nextSelected.delete(key))
    } else {
      pageKeys.forEach((key) => nextSelected.add(key))
    }
    setSelectedIssueKeys(nextSelected)
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
        projectResourceId: issue.projectResourceId,
      })
    })
    return byKey
  }, [safeIssues])
  const selectedIssueTargets: IssueUpdateTarget[] = []
  selectedIssueKeys.forEach((key) => {
    const target = safeIssuesByKey.get(key)
    if (target) selectedIssueTargets.push(target)
  })

  const normalizedSearchQuery = searchQuery.trim().toLowerCase()

  // Service filters are resolved by the org-wide API. Status, level, and search
  // narrow the loaded issue set client-side so include/exclude semantics remain.
  const filteredIssues = safeIssues
    .filter((issue) => {
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
    })
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
        <>
          {pagedIssues.length > 0 && (
            <div className="flex items-center gap-2">
              <Checkbox
                checked={pagedIssues.length > 0 && pagedIssues.every((issue) =>
                  selectedIssueKeys.has(issueSelectionKey(issue))
                )}
                onCheckedChange={handleToggleAll}
                aria-label="Select all issues"
              />
              <span className="text-sm text-muted-foreground whitespace-nowrap hidden sm:inline">Select all</span>
            </div>
          )}
          {selectedIssueTargets.length > 0 && (
            <div className="flex items-center gap-2 bg-primary/10 border border-primary/20 rounded-lg px-2 py-0.5">
              <CheckCircle2 className="h-4 w-4 text-primary" />
              <span className="text-sm font-medium whitespace-nowrap">{selectedIssueTargets.length} selected</span>
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button disabled={bulkPending} size="sm" className="h-7 ml-1">
                    {bulkPending ? 'Updating...' : 'Actions'}
                    <ChevronDown className="h-3 w-3 ml-1" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end">
                  <DropdownMenuItem onClick={handleResolveSelected}>
                    <CheckCircle2 className="h-4 w-4 mr-2" />
                    Resolve
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={handleIgnoreSelected}>
                    <EyeOff className="h-4 w-4 mr-2" />
                    Ignore
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={handleResolveNextReleaseSelected}>
                    <Timer className="h-4 w-4 mr-2" />
                    Resolve in Next Release
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>
            </div>
          )}
          <span className="ml-auto text-sm text-muted-foreground whitespace-nowrap hidden lg:inline">
            {filteredIssues.length} result{filteredIssues.length !== 1 ? 's' : ''}
          </span>
        </>
      }
    >
      <div className="p-4">
        {issuesLoading ? (
          <div className="p-6 text-muted-foreground">Loading issues...</div>
        ) : issuesError ? (
          <div className="p-6 text-destructive border border-destructive/30 rounded-lg bg-destructive/5">
            Failed to load issues: {issuesErrorObj instanceof Error ? issuesErrorObj.message : 'Unknown error'}
          </div>
        ) : filteredIssues.length === 0 ? (
          <Card className="p-12 text-center border-info-border/50 bg-gradient-to-b from-card to-info-bg">
            <div className="max-w-md mx-auto space-y-4">
              <div className="flex justify-center">
                <div className="rounded-full bg-info-bg p-4">
                  {hasActiveIssueFilters ? (
                    <Search className="h-10 w-10 text-info-fg" />
                  ) : (
                    <AlertCircle className="h-10 w-10 text-info-fg" />
                  )}
                </div>
              </div>
              <div>
                <h3 className="text-lg font-semibold mb-2">
                  {hasActiveIssueFilters ? 'No issues match your filters' : 'No issues yet'}
                </h3>
                <p className="text-muted-foreground">
                  {hasActiveIssueFilters
                    ? 'Try adjusting your search or filters.'
                    : 'Start sending errors to your services to see them tracked here.'}
                </p>
              </div>
            </div>
          </Card>
        ) : (
          <div className="rounded-lg border border-border/60 bg-card overflow-hidden">
            {/* Table header */}
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
            {/* Issue rows */}
            <div className="divide-y divide-border/40">
              {pagedIssues.map((issue) => (
                <div
                  key={issueSelectionKey(issue)}
                  className={`hover:bg-accent/40 transition border-l-[3px] ${levelBorderClass(issue.level)}`}
                >
                  <div className="flex items-center gap-2 sm:gap-3 py-2 sm:py-2.5 px-2 sm:px-4">
                    <Checkbox
                      checked={selectedIssueKeys.has(issueSelectionKey(issue))}
                      onCheckedChange={() => handleToggleIssue(issue)}
                      onClick={(e) => e.stopPropagation()}
                      aria-label={`Select ${issue.title}`}
                      className="shrink-0"
                    />
                    <Link
                      to="/issues/$issueId"
                      params={{ issueId: issue.id }}
                      search={{ projectId: issue.projectResourceId }}
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
                          <span className="font-semibold truncate flex-1 min-w-0 text-sm sm:text-base" title={getIssueDisplayTitle(issue)}>
                            {getIssueDisplayTitle(issue)}
                          </span>
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
              ))}
            </div>
            {totalPages > 1 && (
              <div className="flex justify-end gap-2 px-4 py-2 border-t border-border/40">
                <Button
                  size="sm"
                  variant="outline"
                  disabled={currentPage <= 1}
                  onClick={() => setPage(p => Math.max(1, p - 1))}
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
                  onClick={() => setPage(p => p + 1)}
                >
                  Next
                </Button>
              </div>
            )}
          </div>
        )}
      </div>
    </ExplorerShell>
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

function ApmErrorsTab({ isActive }: { isActive: boolean }) {
  const [facetFilters, setFacetFilters] = useState<FacetFilter[]>(() => {
    try {
      const raw = globalThis.localStorage?.getItem('apmErrors.facetFilters')
      const parsed = raw ? JSON.parse(raw) : null
      return Array.isArray(parsed)
        ? parsed.filter(
            (f): f is FacetFilter =>
              !!f && typeof f.key === 'string' && typeof f.value === 'string'
          )
        : []
    } catch {
      return []
    }
  })
  const [query, setQuery] = useState('')
  const [timeRange, setTimeRange] = useState<ApmTimeRange>('24h')
  const [offset, setOffset] = useState(0)

  // Persist the selected facets so the slice survives reloads (service-first
  // navigation — replaces the old global "active project" mode).
  useEffect(() => {
    try {
      globalThis.localStorage?.setItem('apmErrors.facetFilters', JSON.stringify(facetFilters))
    } catch {
      /* ignore persistence errors */
    }
  }, [facetFilters])

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
  const visibleErrors = normalizedQuery
    ? errors.filter((e: ApmErrorGroup) =>
        `${e.resource} ${e.errorMessage} ${e.errorType}`.toLowerCase().includes(normalizedQuery)
      )
    : errors

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
      <div className="p-4">
        {!hasServices ? (
          isLoading ? (
            <div className="py-16 text-center text-muted-foreground">Loading APM errors...</div>
          ) : (
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
        ) : !hasSelection ? (
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
        ) : isLoading ? (
          <div className="py-16 text-center text-muted-foreground">Loading APM errors...</div>
        ) : visibleErrors.length === 0 ? (
          <Card className="p-12 text-center border-border/60">
            <div className="max-w-md mx-auto space-y-3">
              <div className="flex justify-center">
                <div className="rounded-full bg-muted p-4">
                  <AlertTriangle className="h-10 w-10 text-muted-foreground" />
                </div>
              </div>
              <div>
                <h3 className="text-lg font-semibold mb-1">
                  {normalizedQuery ? 'No errors match your search' : 'No errors for the selected services'}
                </h3>
                <p className="text-muted-foreground">
                  {normalizedQuery
                    ? 'Try a different search or clear it.'
                    : 'Try a different service or widen the time range.'}
                </p>
              </div>
            </div>
          </Card>
        ) : (
          <div className="rounded-lg border border-border/60 bg-card overflow-hidden">
            {totalCount > APM_ERRORS_PAGE_SIZE && !normalizedQuery && (
              <div className="px-4 py-2 text-xs text-muted-foreground border-b border-border/40">
                Showing {offset + 1}–{offset + errors.length} of {totalCount}
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
              {visibleErrors.map((error: ApmErrorGroup) => {
                const traceId = normalizeApmTraceId(error.traceId)
                const stableKey = `${error.service}|${error.resource}|${error.errorMessage}|${error.errorType}`
                return (
                  <div
                    key={stableKey}
                    className="hover:bg-accent/40 transition border-l-[3px] border-l-red-500"
                  >
                    <div className="grid grid-cols-[auto_1fr_auto] md:grid-cols-[8rem_1fr_4rem_6rem_4rem] items-center gap-2 sm:gap-3 py-2 sm:py-2.5 px-2 sm:px-4">
                      <Badge
                        variant="outline"
                        className="shrink-0 text-[11px] px-1.5 py-0 gap-1 w-fit"
                      >
                        <Server className="h-3 w-3" />
                        {error.service}
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
                        {error.lastSeen ? formatRelativeTime(error.lastSeen) : '—'}
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
              })}
            </div>
            {(canPrev || canNext) && (
              <div className="flex justify-end gap-2 px-4 py-2 border-t border-border/40">
                <Button
                  size="sm"
                  variant="outline"
                  disabled={!canPrev}
                  onClick={() => setOffset(Math.max(0, offset - APM_ERRORS_PAGE_SIZE))}
                >
                  Previous
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={!canNext}
                  onClick={() => setOffset(offset + APM_ERRORS_PAGE_SIZE)}
                >
                  Next
                </Button>
              </div>
            )}
          </div>
        )}
      </div>
    </ExplorerShell>
  )
}
