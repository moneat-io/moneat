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

import {createFileRoute, Outlet, redirect, useMatches, useNavigate} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {formatRelativeTime, cn} from '@/lib/utils'
import {FacetRail} from '@/components/filters/FacetRail'
import {Badge, type BadgeProps} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {PageHeader} from '@/components/ui/page-header'
import {StatCard} from '@/components/ui/stat-card'
import {EmptyState} from '@/components/ui/empty-state'
import {StatusDot, type StatusTone} from '@/components/ui/status-dot'
import {Checkbox} from '@/components/ui/checkbox'
import {Avatar, AvatarFallback} from '@/components/ui/avatar'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger,} from '@/components/ui/tooltip'
import {
  AlertCircle,
  Archive,
  CheckCircle2,
  CircleDot,
  Clock,
  Globe,
  Inbox,
  Mail,
  MessageSquare,
  Monitor,
  Search,
  Video,
} from 'lucide-react'
import {useMemo, useState, type ReactNode} from 'react'
import {useToast} from '@/hooks/useToast'
import {
  facetValues,
  serviceNamesForQuery,
  serviceRailSections,
  serviceScopeKey,
} from '@/lib/service-facet-scope'
import {feedbackSourceLabel} from '@/lib/telemetry-sources'
import type {FacetFilter} from '@/lib/filters/types'

export const Route = createFileRoute('/feedback')({
  beforeLoad: async ({ location }) => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login', search: { redirect: location.href } })
    }
  },
  component: FeedbackLayout,
})

function FeedbackLayout() {
  const matches = useMatches()
  const showingChildRoute = matches.some((match) => match.id.includes('/feedback/$feedbackId'))

  if (showingChildRoute) {
    return <Outlet />
  }

  return <FeedbackPage />
}

function getInitials(name?: string, email?: string): string {
  if (name) {
    const parts = name.trim().split(/\s+/)
    if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase()
    return name.slice(0, 2).toUpperCase()
  }
  if (email) return email.slice(0, 2).toUpperCase()
  return '?'
}

// Deterministic avatar tint from the categorical chart palette (literal classes
// so Tailwind emits them); encodes identity, not status.
function getAvatarColor(name?: string, email?: string): string {
  const str = name || email || ''
  let hash = 0
  for (let i = 0; i < str.length; i++) {
    hash = str.charCodeAt(i) + ((hash << 5) - hash)
  }
  const colors = [
    'bg-chart-1/20 text-chart-1',
    'bg-chart-2/20 text-chart-2',
    'bg-chart-3/20 text-chart-3',
    'bg-chart-4/20 text-chart-4',
    'bg-chart-5/20 text-chart-5',
    'bg-chart-6/20 text-chart-6',
    'bg-chart-7/20 text-chart-7',
    'bg-chart-8/20 text-chart-8',
  ]
  return colors[Math.abs(hash) % colors.length]
}

const statusConfig = {
  unresolved: {
    tone: 'warning' as StatusTone,
    badge: 'warning' as BadgeProps['variant'],
    border: 'border-l-warning-solid',
    icon: CircleDot,
    label: 'Unresolved',
  },
  resolved: {
    tone: 'success' as StatusTone,
    badge: 'success' as BadgeProps['variant'],
    border: 'border-l-success-solid',
    icon: CheckCircle2,
    label: 'Resolved',
  },
  archived: {
    tone: 'neutral' as StatusTone,
    badge: 'neutral' as BadgeProps['variant'],
    border: 'border-l-border',
    icon: Archive,
    label: 'Archived',
  },
} as const

function FeedbackStatCard({
  label,
  value,
  icon,
  tone,
  active,
  onClick,
}: {
  readonly label: string
  readonly value: number
  readonly icon: React.ComponentType<{className?: string}>
  readonly tone: StatusTone
  readonly active: boolean
  readonly onClick: () => void
}) {
  return (
    <button type="button" onClick={onClick} className="text-left">
      <StatCard
        label={label}
        value={value}
        icon={icon}
        tone={tone}
        className={cn(
          'transition-colors hover:border-primary/40',
          active && 'border-primary ring-1 ring-primary/30'
        )}
      />
    </button>
  )
}

function FeedbackPage() {
  const [searchQuery, setSearchQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<string>('unresolved')
  const [selectedFeedback, setSelectedFeedback] = useState<Set<string>>(new Set())
  const [facetFilters, setFacetFilters] = useState<FacetFilter[]>([])
  const { toast } = useToast()
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  const { data: projects, isLoading } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const projectList = projects ?? []
  const includedServices = useMemo(
    () => facetValues(facetFilters, 'service', false),
    [facetFilters]
  )
  const excludedServices = useMemo(
    () => facetValues(facetFilters, 'service', true),
    [facetFilters]
  )
  const hasServiceFilters = includedServices.length > 0 || excludedServices.length > 0
  const serviceNames = useMemo(
    () => serviceNamesForQuery(projectList, includedServices, excludedServices),
    [projectList, includedServices, excludedServices]
  )
  const scopeKey = serviceScopeKey(serviceNames, hasServiceFilters)
  const hasFeedbackScope = projectList.length > 0 && (!hasServiceFilters || serviceNames.length > 0)
  const serviceScopeParams = useMemo(() => (
    serviceNames.length > 0 ? {services: [...serviceNames]} : {}
  ), [serviceNames])
  const railSections = useMemo(() => serviceRailSections(projectList), [projectList])
  const handleFacetFiltersChange = (nextFilters: FacetFilter[]) => {
    setFacetFilters(nextFilters)
    setSelectedFeedback(new Set())
  }

  // Fetch all feedback for stats (unfiltered)
  const { data: allFeedback = [] } = useQuery({
    queryKey: ['feedback', 'organization', scopeKey, 'all', serviceScopeParams],
    queryFn: () => api.getOrganizationFeedback({ page: 1, limit: 500, ...serviceScopeParams }),
    enabled: hasFeedbackScope,
  })

  const { data: feedbackList = [] } = useQuery({
    queryKey: ['feedback', 'organization', scopeKey, statusFilter, serviceScopeParams],
    queryFn: () => api.getOrganizationFeedback({
      page: 1,
      limit: 100,
      status: statusFilter === 'all' ? undefined : statusFilter,
      ...serviceScopeParams,
    }),
    enabled: hasFeedbackScope,
  })

  const stats = useMemo(() => {
    const total = allFeedback.length
    const unresolved = allFeedback.filter((f) => f.status === 'unresolved').length
    const resolved = allFeedback.filter((f) => f.status === 'resolved').length
    const archived = allFeedback.filter((f) => f.status === 'archived').length
    return { total, unresolved, resolved, archived }
  }, [allFeedback])

  const resolveMutation = useMutation({
    mutationFn: async (feedbackIds: string[]) => {
      await Promise.all(
        feedbackIds.map((id) => api.updateFeedback(id, { status: 'resolved' }))
      )
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['feedback'] })
      toast({
        title: 'Success',
        description: `${selectedFeedback.size} feedback item${selectedFeedback.size === 1 ? '' : 's'} resolved`,
      })
      setSelectedFeedback(new Set())
    },
    onError: () => {
      toast({
        title: 'Error',
        description: 'Failed to resolve feedback',
        variant: 'destructive',
      })
    },
  })

  const handleToggleFeedback = (feedbackId: string) => {
    const next = new Set(selectedFeedback)
    if (next.has(feedbackId)) next.delete(feedbackId)
    else next.add(feedbackId)
    setSelectedFeedback(next)
  }

  const handleToggleAll = () => {
    if (selectedFeedback.size === filteredFeedback.length) {
      setSelectedFeedback(new Set())
    } else {
      setSelectedFeedback(new Set(filteredFeedback.map((f) => f.feedbackId)))
    }
  }

  const handleResolveSelected = () => {
    resolveMutation.mutate(Array.from(selectedFeedback))
  }

  if (isLoading) return <div className="p-8">Loading...</div>

  const filteredFeedback = feedbackList.filter((f) => {
    const matchesSearch =
      searchQuery === '' ||
      f.message?.toLowerCase().includes(searchQuery.toLowerCase()) ||
      f.name?.toLowerCase().includes(searchQuery.toLowerCase()) ||
      f.contactEmail?.toLowerCase().includes(searchQuery.toLowerCase())
    return matchesSearch
  })

  let feedbackContent: ReactNode
  if (projectList.length === 0) {
    feedbackContent = (
      <EmptyState
        icon={MessageSquare}
        title="No services yet"
        description="Create a service and integrate the feedback widget to see user feedback here."
      />
    )
  } else if (!hasFeedbackScope) {
    feedbackContent = (
      <EmptyState
        icon={MessageSquare}
        title="No services match filters"
        description="Adjust the selected services to view feedback."
      />
    )
  } else {
    feedbackContent = (
      <>
        {/* Stats Cards (also act as status filters) */}
        {allFeedback.length > 0 && (
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            <FeedbackStatCard
              label="Total"
              value={stats.total}
              icon={Inbox}
              tone="accent"
              active={statusFilter === 'all'}
              onClick={() => setStatusFilter('all')}
            />
            <FeedbackStatCard
              label="Unresolved"
              value={stats.unresolved}
              icon={CircleDot}
              tone="warning"
              active={statusFilter === 'unresolved'}
              onClick={() => setStatusFilter('unresolved')}
            />
            <FeedbackStatCard
              label="Resolved"
              value={stats.resolved}
              icon={CheckCircle2}
              tone="success"
              active={statusFilter === 'resolved'}
              onClick={() => setStatusFilter('resolved')}
            />
            <FeedbackStatCard
              label="Archived"
              value={stats.archived}
              icon={Archive}
              tone="neutral"
              active={statusFilter === 'archived'}
              onClick={() => setStatusFilter('archived')}
            />
          </div>
        )}

        {/* Toolbar */}
        <div className="mb-3 flex gap-2 items-center flex-wrap">
          {filteredFeedback.length > 0 && (
            <div className="flex items-center gap-2">
              <Checkbox
                checked={selectedFeedback.size === filteredFeedback.length && filteredFeedback.length > 0}
                onCheckedChange={handleToggleAll}
                aria-label="Select all feedback"
              />
              <span className="text-xs text-muted-foreground whitespace-nowrap">Select all</span>
            </div>
          )}
          {selectedFeedback.size > 0 && (
            <div className="flex items-center gap-1.5 bg-success-bg border border-success-border rounded-lg px-2.5 py-1">
              <CheckCircle2 className="h-3 w-3 text-success-fg" />
              <span className="text-xs font-medium whitespace-nowrap">
                {selectedFeedback.size} selected
              </span>
              <Button
                onClick={handleResolveSelected}
                disabled={resolveMutation.isPending}
                size="sm"
                className="h-6 text-xs ml-1.5"
              >
                {resolveMutation.isPending ? 'Resolving...' : 'Resolve'}
              </Button>
            </div>
          )}
          <div className="relative flex-1 min-w-[180px]">
            <Search className="absolute left-2.5 top-1/2 transform -translate-y-1/2 h-3 w-3 text-muted-foreground" />
            <Input
              placeholder="Search by message, name, or email..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="pl-8 h-8 text-xs"
            />
          </div>
        </div>

        {filteredFeedback.length === 0 ? (
          <EmptyState
            icon={searchQuery ? Search : MessageSquare}
            title={searchQuery ? 'No feedback match your search' : 'No feedback yet'}
            description={
              searchQuery
                ? 'Try adjusting your search or changing the status filter.'
                : 'Use the feedback widget in your app to collect user feedback. It will appear here.'
            }
          />
        ) : (
          <TooltipProvider>
            <div className="space-y-1.5">
              {filteredFeedback.map((f) => {
                const config = statusConfig[f.status as keyof typeof statusConfig] || statusConfig.unresolved
                const displayName = f.name || f.contactEmail || 'Anonymous'
                const initials = getInitials(f.name, f.contactEmail)
                const avatarColor = getAvatarColor(f.name, f.contactEmail)
                const sourceLabel = feedbackSourceLabel(f.sourceType, f.sourceName)

                return (
                  <div
                    key={f.feedbackId}
                    onClick={() => navigate({ to: '/feedback/$feedbackId', params: { feedbackId: f.feedbackId } })}
                    className={`cursor-pointer rounded-lg border border-border/60 bg-card hover:bg-accent/50 hover:border-primary/20 transition-all border-l-[3px] ${config.border}`}
                  >
                    <div className="flex items-start gap-2 p-2">
                      {/* Checkbox */}
                      <div className="flex items-center pt-0.5">
                        <Checkbox
                          checked={selectedFeedback.has(f.feedbackId)}
                          onCheckedChange={() => handleToggleFeedback(f.feedbackId)}
                          onClick={(e) => e.stopPropagation()}
                          aria-label="Select feedback"
                        />
                      </div>

                      {/* Avatar */}
                      <Avatar className="h-6 w-6 shrink-0 mt-0.5">
                        <AvatarFallback className={`text-[10px] font-semibold ${avatarColor}`}>
                          {initials}
                        </AvatarFallback>
                      </Avatar>

                      {/* Content */}
                      <div className="flex-1 min-w-0">
                        {/* Top row: name + time */}
                        <div className="flex items-center gap-1.5 mb-0.5">
                          <span className="font-semibold text-xs truncate">{displayName}</span>
                          {f.contactEmail && f.name && (
                            <Tooltip>
                              <TooltipTrigger asChild>
                                <Mail className="h-3 w-3 text-muted-foreground/60 shrink-0" />
                              </TooltipTrigger>
                              <TooltipContent>{f.contactEmail}</TooltipContent>
                            </Tooltip>
                          )}
                          <span className="text-[11px] text-muted-foreground ml-auto shrink-0 flex items-center gap-0.5">
                            <Clock className="h-2.5 w-2.5" />
                            {formatRelativeTime(f.timestamp)}
                          </span>
                        </div>

                        {/* Message */}
                        <p className="text-xs text-foreground/80 line-clamp-2 mb-1.5">
                          {f.message || '(No message)'}
                        </p>

                        {/* Bottom row: badges */}
                        <div className="flex flex-wrap items-center gap-1.5">
                          <Badge variant={config.badge} size="sm" className="font-medium">
                            <StatusDot tone={config.tone} size="sm" className="mr-0.5" />
                            {config.label}
                          </Badge>
                          {f.environment && (
                            <Badge variant="outline" size="sm" className="font-normal text-muted-foreground">
                              {f.environment}
                            </Badge>
                          )}
                          <Badge variant="outline" size="sm" className="font-normal text-muted-foreground">
                            {sourceLabel}
                          </Badge>
                          {f.platform && (
                            <Badge variant="outline" size="sm" className="font-normal gap-0.5">
                              <Monitor className="h-2.5 w-2.5" />
                              {f.platform}
                            </Badge>
                          )}
                          {f.url && (
                            <Tooltip>
                              <TooltipTrigger asChild>
                                <Badge variant="outline" size="sm" className="font-normal gap-0.5 max-w-[180px] truncate">
                                  <Globe className="h-2.5 w-2.5 shrink-0" />
                                  <span className="truncate">{f.url.replace(/^https?:\/\//, '')}</span>
                                </Badge>
                              </TooltipTrigger>
                              <TooltipContent>{f.url}</TooltipContent>
                            </Tooltip>
                          )}
                          {f.associatedEventId && (
                            <Badge variant="warning" size="sm" className="font-normal gap-0.5">
                              <AlertCircle className="h-2.5 w-2.5" />
                              Event linked
                            </Badge>
                          )}
                          {f.replayId && (
                            <span
                              onClick={(e) => {
                                e.stopPropagation()
                                navigate({ to: '/replays/$replayId', params: { replayId: f.replayId! } })
                              }}
                              className="inline-flex items-center gap-0.5 text-[11px] font-medium text-primary hover:underline cursor-pointer bg-[hsl(var(--primary)/0.12)] border border-[hsl(var(--primary)/0.3)] rounded-full px-2 py-0.5"
                            >
                              <Video className="h-2.5 w-2.5" />
                              Replay
                            </span>
                          )}
                        </div>
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>
          </TooltipProvider>
        )}

        {/* Footer count */}
        {filteredFeedback.length > 0 && (
          <div className="mt-3 text-center">
            <p className="text-xs text-muted-foreground">
              Showing {filteredFeedback.length} feedback item{filteredFeedback.length === 1 ? '' : 's'}
              {searchQuery && ' matching your search'}
            </p>
          </div>
        )}
      </>
    )
  }

  return (
    <div className="min-h-screen">
      <div className="container mx-auto px-4 py-4 space-y-3">
        <PageHeader
          icon={MessageSquare}
          title="User Feedback"
          description="Review and manage feedback from your users"
        />

        <div className="grid gap-2 lg:grid-cols-[220px_minmax(0,1fr)]">
          <FacetRail
            sections={railSections}
            facetFilters={facetFilters}
            onFacetFiltersChange={handleFacetFiltersChange}
            title="Feedback"
            className="h-auto max-h-[340px] rounded-md border lg:sticky lg:top-2 lg:h-[calc(100vh-8rem)] lg:max-h-none"
          />

          <div className="min-w-0">
            {feedbackContent}
          </div>
        </div>
      </div>
    </div>
  )
}
