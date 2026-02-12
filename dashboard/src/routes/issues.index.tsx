import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api, formatErrorForLogging} from '@/lib/api'
import {useProject} from '@/contexts/project-context'
import {formatRelativeTime} from '@/lib/utils'
import {useMemo, useState} from 'react'
import {AlertCircle, Search} from 'lucide-react'
import {Card} from '@/components/ui/card'
import {Badge} from '@/components/ui/badge'
import {Input} from '@/components/ui/input'
import {Button} from '@/components/ui/button'

export const Route = createFileRoute('/issues/')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
    try {
      const user = await api.getCurrentUser()
      if (!user.onboardingCompleted) {
        throw redirect({to: '/onboarding'})
      }
    } catch (error) {
      console.error('Failed to fetch user:', formatErrorForLogging(error))
    }
  },
  component: IssuesPage,
})

function getLevelBadge(level: string): string {
  switch (level.toLowerCase()) {
    case 'fatal':
      return 'border-red-300 bg-red-50 text-red-800 dark:border-red-800 dark:bg-red-950/50 dark:text-red-300'
    case 'error':
      return 'border-red-200 bg-red-50/80 text-red-700 dark:border-red-800/60 dark:bg-red-950/30 dark:text-red-400'
    case 'warning':
      return 'border-amber-200 bg-amber-50/80 text-amber-700 dark:border-amber-800/60 dark:bg-amber-950/30 dark:text-amber-400'
    case 'info':
      return 'border-blue-200 bg-blue-50/80 text-blue-700 dark:border-blue-800/60 dark:bg-blue-950/30 dark:text-blue-400'
    case 'debug':
      return 'border-zinc-200 bg-zinc-50/80 text-zinc-600 dark:border-zinc-700 dark:bg-zinc-800/30 dark:text-zinc-400'
    default:
      return 'border-border bg-muted text-muted-foreground'
  }
}

function getStatusBadge(status: string): string {
  switch (status.toLowerCase()) {
    case 'unresolved':
      return 'border-amber-200 bg-amber-50/80 text-amber-700 dark:border-amber-800/60 dark:bg-amber-950/30 dark:text-amber-400'
    case 'resolved':
      return 'border-emerald-200 bg-emerald-50/80 text-emerald-700 dark:border-emerald-800/60 dark:bg-emerald-950/30 dark:text-emerald-400'
    default:
      return 'border-border bg-muted text-muted-foreground'
  }
}

function IssuesPage() {
  const [searchQuery, setSearchQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<'all' | 'unresolved' | 'resolved'>('unresolved')
  const {selectedProjectId, setSelectedProjectId} = useProject()

  const {data: projects, isLoading: projectsLoading} = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const projectId = selectedProjectId || projects?.[0]?.id

  if (!selectedProjectId && projects && projects.length > 0 && projects[0]?.id) {
    setSelectedProjectId(projects[0].id)
  }

  const {data: issues = [], isLoading: issuesLoading} = useQuery({
    queryKey: ['issues', projectId, 'all'],
    queryFn: () => (projectId ? api.getIssues(projectId, 1, 200) : []),
    enabled: !!projectId,
  })

  const stats = useMemo(() => {
    const unresolved = issues.filter((issue) => issue.status === 'unresolved').length
    const resolved = issues.filter((issue) => issue.status === 'resolved').length
    return {total: issues.length, unresolved, resolved}
  }, [issues])

  const filteredIssues = useMemo(() => {
    const query = searchQuery.trim().toLowerCase()
    return issues
      .filter((issue) => statusFilter === 'all' || issue.status === statusFilter)
      .filter((issue) => {
        if (!query) {
          return true
        }
        return (
          issue.title.toLowerCase().includes(query) ||
          issue.culprit.toLowerCase().includes(query) ||
          issue.id.toLowerCase().includes(query)
        )
      })
      .sort((a, b) => new Date(b.lastSeen).getTime() - new Date(a.lastSeen).getTime())
  }, [issues, searchQuery, statusFilter])

  if (projectsLoading) {
    return (
      <div className="p-6 space-y-4">
        <div className="h-8 w-48 rounded bg-muted animate-pulse" />
        <div className="h-28 rounded-lg bg-muted animate-pulse" />
        <div className="h-96 rounded-lg bg-muted animate-pulse" />
      </div>
    )
  }

  if (!projects || projects.length === 0) {
    return (
      <div className="p-6">
        <Card className="p-10 text-center">
          <div className="mx-auto max-w-md space-y-3">
            <AlertCircle className="mx-auto h-8 w-8 text-muted-foreground" />
            <h2 className="text-lg font-semibold">No projects found</h2>
            <p className="text-sm text-muted-foreground">Create a project to start capturing and viewing issues.</p>
          </div>
        </Card>
      </div>
    )
  }

  return (
    <div className="p-6 space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Issues</h1>
        <p className="text-sm text-muted-foreground mt-1">Inspect and triage project errors.</p>
      </div>

      <div className="grid gap-4 md:grid-cols-3">
        <Card className="p-4">
          <p className="text-xs text-muted-foreground">Total</p>
          <p className="mt-2 text-2xl font-semibold tabular-nums">{stats.total}</p>
        </Card>
        <Card className="p-4">
          <p className="text-xs text-muted-foreground">Unresolved</p>
          <p className="mt-2 text-2xl font-semibold tabular-nums">{stats.unresolved}</p>
        </Card>
        <Card className="p-4">
          <p className="text-xs text-muted-foreground">Resolved</p>
          <p className="mt-2 text-2xl font-semibold tabular-nums">{stats.resolved}</p>
        </Card>
      </div>

      <Card className="p-4 space-y-4">
        <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
          <div className="relative w-full md:max-w-sm">
            <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              placeholder="Search by title, culprit, or issue ID"
              className="pl-9"
            />
          </div>
          <div className="flex flex-wrap gap-2">
            <Button variant={statusFilter === 'unresolved' ? 'default' : 'outline'} size="sm" onClick={() => setStatusFilter('unresolved')}>
              Unresolved
            </Button>
            <Button variant={statusFilter === 'resolved' ? 'default' : 'outline'} size="sm" onClick={() => setStatusFilter('resolved')}>
              Resolved
            </Button>
            <Button variant={statusFilter === 'all' ? 'default' : 'outline'} size="sm" onClick={() => setStatusFilter('all')}>
              All
            </Button>
          </div>
        </div>

        {issuesLoading ? (
          <div className="space-y-2">
            <div className="h-16 rounded-md bg-muted animate-pulse" />
            <div className="h-16 rounded-md bg-muted animate-pulse" />
            <div className="h-16 rounded-md bg-muted animate-pulse" />
          </div>
        ) : filteredIssues.length === 0 ? (
          <div className="py-12 text-center text-sm text-muted-foreground">No issues match the current filters.</div>
        ) : (
          <div className="space-y-1">
            {filteredIssues.map((issue) => (
              <Link
                key={issue.id}
                to="/issues/$issueId"
                params={{issueId: issue.id}}
                className="flex items-center gap-3 rounded-md border border-border/40 p-3 transition hover:bg-muted/50"
              >
                <Badge variant="outline" className={`${getLevelBadge(issue.level)} w-16 justify-center`}>
                  {issue.level.toUpperCase()}
                </Badge>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">{issue.title || issue.culprit}</p>
                  <p className="truncate text-xs text-muted-foreground">{issue.culprit}</p>
                </div>
                <Badge variant="outline" className={getStatusBadge(issue.status)}>
                  {issue.status}
                </Badge>
                <div className="hidden text-right text-xs text-muted-foreground sm:block">
                  <p className="tabular-nums">{issue.eventCount.toLocaleString()} events</p>
                  <p className="tabular-nums">{issue.userCount.toLocaleString()} users</p>
                </div>
                <div className="hidden text-right text-xs text-muted-foreground md:block">
                  <p>Last seen</p>
                  <p>{formatRelativeTime(issue.lastSeen)}</p>
                </div>
              </Link>
            ))}
          </div>
        )}
      </Card>
    </div>
  )
}
