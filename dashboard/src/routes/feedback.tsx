import {createFileRoute, Outlet, redirect, useMatches, useNavigate} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {useProject} from '@/contexts/project-context'
import {formatRelativeTime} from '@/lib/utils'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Card} from '@/components/ui/card'
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
import {useMemo, useState} from 'react'
import {useToast} from '@/hooks/use-toast'

export const Route = createFileRoute('/feedback')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
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

function getAvatarColor(name?: string, email?: string): string {
  const str = name || email || ''
  let hash = 0
  for (let i = 0; i < str.length; i++) {
    hash = str.charCodeAt(i) + ((hash << 5) - hash)
  }
  const colors = [
    'bg-violet-500/20 text-violet-700 dark:text-violet-300',
    'bg-blue-500/20 text-blue-700 dark:text-blue-300',
    'bg-emerald-500/20 text-emerald-700 dark:text-emerald-300',
    'bg-amber-500/20 text-amber-700 dark:text-amber-300',
    'bg-rose-500/20 text-rose-700 dark:text-rose-300',
    'bg-cyan-500/20 text-cyan-700 dark:text-cyan-300',
    'bg-pink-500/20 text-pink-700 dark:text-pink-300',
    'bg-orange-500/20 text-orange-700 dark:text-orange-300',
  ]
  return colors[Math.abs(hash) % colors.length]
}

const statusConfig = {
  unresolved: {
    color: 'bg-amber-500/15 text-amber-700 dark:text-amber-400 border-amber-500/30',
    dot: 'bg-amber-500',
    border: 'border-l-amber-500',
    icon: CircleDot,
    label: 'Unresolved',
  },
  resolved: {
    color: 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-400 border-emerald-500/30',
    dot: 'bg-emerald-500',
    border: 'border-l-emerald-500',
    icon: CheckCircle2,
    label: 'Resolved',
  },
  archived: {
    color: 'bg-slate-500/15 text-slate-600 dark:text-slate-400 border-slate-500/30',
    dot: 'bg-slate-400',
    border: 'border-l-slate-400',
    icon: Archive,
    label: 'Archived',
  },
} as const

function FeedbackPage() {
  const [searchQuery, setSearchQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<string>('unresolved')
  const [selectedFeedback, setSelectedFeedback] = useState<Set<string>>(new Set())
  const { selectedProjectId, setSelectedProjectId } = useProject()
  const { toast } = useToast()
  const queryClient = useQueryClient()
  const navigate = useNavigate()

  const { data: projects, isLoading } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const projectId = selectedProjectId || projects?.[0]?.id

  if (!selectedProjectId && projects && projects.length > 0 && projects[0]?.id) {
    setSelectedProjectId(projects[0].id)
  }

  // Fetch all feedback for stats (unfiltered)
  const { data: allFeedback = [] } = useQuery({
    queryKey: ['feedback', projectId, 'all'],
    queryFn: () =>
      projectId
        ? api.getFeedback(projectId, { page: 1, limit: 500 })
        : [],
    enabled: !!projectId,
  })

  const { data: feedbackList = [] } = useQuery({
    queryKey: ['feedback', projectId, statusFilter],
    queryFn: () =>
      projectId
        ? api.getFeedback(projectId, {
            page: 1,
            limit: 100,
            status: statusFilter === 'all' ? undefined : statusFilter,
          })
        : [],
    enabled: !!projectId,
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
      queryClient.invalidateQueries({ queryKey: ['feedback', projectId] })
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

  return (
    <div className="min-h-screen bg-gradient-to-br from-background via-background to-primary/5">
      <div className="container mx-auto px-4 py-6">
        {/* Header */}
        <div className="mb-6 flex items-center gap-3">
          <div className="rounded-xl bg-violet-500/15 p-2.5 ring-1 ring-violet-500/20">
            <MessageSquare className="h-6 w-6 text-violet-600 dark:text-violet-400" />
          </div>
          <div>
            <h2 className="text-2xl font-bold">User Feedback</h2>
            <p className="text-sm text-muted-foreground">Review and manage feedback from your users</p>
          </div>
        </div>

        {!projects || projects.length === 0 ? (
          <Card className="p-12 text-center border-primary/20 bg-gradient-to-b from-card to-primary/5">
            <div className="max-w-md mx-auto space-y-4">
              <div className="flex justify-center">
                <div className="rounded-full bg-violet-500/15 p-4 ring-2 ring-violet-500/20">
                  <MessageSquare className="h-10 w-10 text-violet-600 dark:text-violet-400" />
                </div>
              </div>
              <div>
                <h3 className="text-lg font-semibold mb-2">No projects yet</h3>
                <p className="text-muted-foreground">
                  Create a project and integrate the Sentry feedback widget to see user feedback here.
                </p>
              </div>
            </div>
          </Card>
        ) : (
          <>
            {/* Stats Cards */}
            {allFeedback.length > 0 && (
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-6">
                <button
                  onClick={() => setStatusFilter('all')}
                  className={`rounded-xl border p-4 text-left transition-all hover:shadow-md ${
                    statusFilter === 'all'
                      ? 'border-violet-500/40 bg-violet-500/10 shadow-sm ring-1 ring-violet-500/20'
                      : 'border-border/60 bg-card hover:border-violet-500/20'
                  }`}
                >
                  <div className="flex items-center gap-2 mb-2">
                    <Inbox className="h-4 w-4 text-violet-500" />
                    <span className="text-xs font-medium text-muted-foreground uppercase tracking-wide">Total</span>
                  </div>
                  <div className="text-2xl font-bold text-violet-600 dark:text-violet-400">{stats.total}</div>
                </button>
                <button
                  onClick={() => setStatusFilter('unresolved')}
                  className={`rounded-xl border p-4 text-left transition-all hover:shadow-md ${
                    statusFilter === 'unresolved'
                      ? 'border-amber-500/40 bg-amber-500/10 shadow-sm ring-1 ring-amber-500/20'
                      : 'border-border/60 bg-card hover:border-amber-500/20'
                  }`}
                >
                  <div className="flex items-center gap-2 mb-2">
                    <CircleDot className="h-4 w-4 text-amber-500" />
                    <span className="text-xs font-medium text-muted-foreground uppercase tracking-wide">Unresolved</span>
                  </div>
                  <div className="text-2xl font-bold text-amber-600 dark:text-amber-400">{stats.unresolved}</div>
                </button>
                <button
                  onClick={() => setStatusFilter('resolved')}
                  className={`rounded-xl border p-4 text-left transition-all hover:shadow-md ${
                    statusFilter === 'resolved'
                      ? 'border-emerald-500/40 bg-emerald-500/10 shadow-sm ring-1 ring-emerald-500/20'
                      : 'border-border/60 bg-card hover:border-emerald-500/20'
                  }`}
                >
                  <div className="flex items-center gap-2 mb-2">
                    <CheckCircle2 className="h-4 w-4 text-emerald-500" />
                    <span className="text-xs font-medium text-muted-foreground uppercase tracking-wide">Resolved</span>
                  </div>
                  <div className="text-2xl font-bold text-emerald-600 dark:text-emerald-400">{stats.resolved}</div>
                </button>
                <button
                  onClick={() => setStatusFilter('archived')}
                  className={`rounded-xl border p-4 text-left transition-all hover:shadow-md ${
                    statusFilter === 'archived'
                      ? 'border-slate-500/40 bg-slate-500/10 shadow-sm ring-1 ring-slate-500/20'
                      : 'border-border/60 bg-card hover:border-slate-500/20'
                  }`}
                >
                  <div className="flex items-center gap-2 mb-2">
                    <Archive className="h-4 w-4 text-slate-500" />
                    <span className="text-xs font-medium text-muted-foreground uppercase tracking-wide">Archived</span>
                  </div>
                  <div className="text-2xl font-bold text-slate-600 dark:text-slate-400">{stats.archived}</div>
                </button>
              </div>
            )}

            {/* Toolbar */}
            <div className="mb-4 flex gap-3 items-center flex-wrap">
              {filteredFeedback.length > 0 && (
                <div className="flex items-center gap-2">
                  <Checkbox
                    checked={selectedFeedback.size === filteredFeedback.length && filteredFeedback.length > 0}
                    onCheckedChange={handleToggleAll}
                    aria-label="Select all feedback"
                  />
                  <span className="text-sm text-muted-foreground whitespace-nowrap">Select all</span>
                </div>
              )}
              {selectedFeedback.size > 0 && (
                <div className="flex items-center gap-2 bg-emerald-500/10 border border-emerald-500/20 rounded-lg px-3 py-1.5">
                  <CheckCircle2 className="h-4 w-4 text-emerald-600 dark:text-emerald-400" />
                  <span className="text-sm font-medium whitespace-nowrap">
                    {selectedFeedback.size} selected
                  </span>
                  <Button
                    onClick={handleResolveSelected}
                    disabled={resolveMutation.isPending}
                    size="sm"
                    className="bg-emerald-600 hover:bg-emerald-700 h-7 ml-2"
                  >
                    {resolveMutation.isPending ? 'Resolving...' : 'Resolve'}
                  </Button>
                </div>
              )}
              <div className="relative flex-1 min-w-[200px]">
                <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                <Input
                  placeholder="Search by message, name, or email..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="pl-10"
                />
              </div>
            </div>

            {filteredFeedback.length === 0 ? (
              <Card className="p-12 text-center border-blue-500/20 bg-gradient-to-b from-card to-blue-500/5">
                <div className="max-w-md mx-auto space-y-4">
                  <div className="flex justify-center">
                    <div className="rounded-full bg-blue-500/10 p-4">
                      {searchQuery ? (
                        <Search className="h-10 w-10 text-blue-600 dark:text-blue-400" />
                      ) : (
                        <MessageSquare className="h-10 w-10 text-blue-600 dark:text-blue-400" />
                      )}
                    </div>
                  </div>
                  <div>
                    <h3 className="text-lg font-semibold mb-2">
                      {searchQuery ? 'No feedback match your search' : 'No feedback yet'}
                    </h3>
                    <p className="text-muted-foreground">
                      {searchQuery
                        ? 'Try adjusting your search or changing the status filter.'
                        : 'Use the Sentry User Feedback widget in your app to collect user feedback. It will appear here.'}
                    </p>
                  </div>
                </div>
              </Card>
            ) : (
              <TooltipProvider>
                <div className="space-y-2">
                  {filteredFeedback.map((f) => {
                    const config = statusConfig[f.status as keyof typeof statusConfig] || statusConfig.unresolved
                    const displayName = f.name || f.contactEmail || 'Anonymous'
                    const initials = getInitials(f.name, f.contactEmail)
                    const avatarColor = getAvatarColor(f.name, f.contactEmail)

                    return (
                      <div
                        key={f.feedbackId}
                        onClick={() => navigate({ to: '/feedback/$feedbackId', params: { feedbackId: f.feedbackId } })}
                        className={`cursor-pointer rounded-xl border border-border/60 bg-card hover:bg-accent/50 hover:border-primary/20 transition-all hover:shadow-md border-l-[3px] ${config.border}`}
                      >
                        <div className="flex items-start gap-4 p-4">
                          {/* Checkbox */}
                          <div className="flex items-center pt-1">
                            <Checkbox
                              checked={selectedFeedback.has(f.feedbackId)}
                              onCheckedChange={() => handleToggleFeedback(f.feedbackId)}
                              onClick={(e) => e.stopPropagation()}
                              aria-label="Select feedback"
                            />
                          </div>

                          {/* Avatar */}
                          <Avatar className="h-9 w-9 shrink-0 mt-0.5">
                            <AvatarFallback className={`text-xs font-semibold ${avatarColor}`}>
                              {initials}
                            </AvatarFallback>
                          </Avatar>

                          {/* Content */}
                          <div className="flex-1 min-w-0">
                            {/* Top row: name + time */}
                            <div className="flex items-center gap-2 mb-1">
                              <span className="font-semibold text-sm truncate">{displayName}</span>
                              {f.contactEmail && f.name && (
                                <Tooltip>
                                  <TooltipTrigger asChild>
                                    <Mail className="h-3.5 w-3.5 text-muted-foreground/60 shrink-0" />
                                  </TooltipTrigger>
                                  <TooltipContent>{f.contactEmail}</TooltipContent>
                                </Tooltip>
                              )}
                              <span className="text-xs text-muted-foreground ml-auto shrink-0 flex items-center gap-1">
                                <Clock className="h-3 w-3" />
                                {formatRelativeTime(f.timestamp)}
                              </span>
                            </div>

                            {/* Message */}
                            <p className="text-sm text-foreground/80 line-clamp-2 mb-2">
                              {f.message || '(No message)'}
                            </p>

                            {/* Bottom row: badges */}
                            <div className="flex flex-wrap items-center gap-2">
                              <Badge
                                variant="outline"
                                className={`text-xs font-medium ${config.color}`}
                              >
                                <span className={`inline-block h-1.5 w-1.5 rounded-full mr-1.5 ${config.dot}`} />
                                {config.label}
                              </Badge>
                              {f.environment && (
                                <Badge variant="outline" className="text-xs font-normal text-muted-foreground">
                                  {f.environment}
                                </Badge>
                              )}
                              {f.platform && (
                                <Badge variant="outline" className="text-xs font-normal gap-1">
                                  <Monitor className="h-3 w-3" />
                                  {f.platform}
                                </Badge>
                              )}
                              {f.url && (
                                <Tooltip>
                                  <TooltipTrigger asChild>
                                    <Badge variant="outline" className="text-xs font-normal gap-1 max-w-[200px] truncate">
                                      <Globe className="h-3 w-3 shrink-0" />
                                      <span className="truncate">{f.url.replace(/^https?:\/\//, '')}</span>
                                    </Badge>
                                  </TooltipTrigger>
                                  <TooltipContent>{f.url}</TooltipContent>
                                </Tooltip>
                              )}
                              {f.associatedEventId && (
                                <Badge variant="outline" className="text-xs font-normal gap-1 text-orange-600 dark:text-orange-400 border-orange-500/30">
                                  <AlertCircle className="h-3 w-3" />
                                  Event linked
                                </Badge>
                              )}
                              {f.replayId && (
                                <span
                                  onClick={(e) => {
                                    e.stopPropagation()
                                    navigate({ to: '/replays/$replayId', params: { replayId: f.replayId! } })
                                  }}
                                  className="inline-flex items-center gap-1 text-xs font-medium text-blue-600 dark:text-blue-400 hover:underline cursor-pointer bg-blue-500/10 border border-blue-500/20 rounded-full px-2.5 py-0.5"
                                >
                                  <Video className="h-3 w-3" />
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
              <div className="mt-4 text-center">
                <p className="text-xs text-muted-foreground">
                  Showing {filteredFeedback.length} feedback item{filteredFeedback.length === 1 ? '' : 's'}
                  {searchQuery && ' matching your search'}
                </p>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}
