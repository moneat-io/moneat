import { createFileRoute, Outlet, redirect, useMatches, useNavigate } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { useProject } from '@/contexts/project-context'
import { useState } from 'react'
import { Card } from '@/components/ui/card'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Play, AlertCircle, Globe, User, Monitor } from 'lucide-react'

export const Route = createFileRoute('/replays')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
    }
    try {
      const user = await api.getCurrentUser()
      if (!user.onboardingCompleted) {
        throw redirect({ to: '/onboarding' })
      }
    } catch (error) {
      console.error('Failed to fetch user:', error)
    }
  },
  component: ReplaysLayout,
})

function ReplaysLayout() {
  const matches = useMatches()
  const showingChildRoute = matches.some((match) => match.id.includes('/replays/$replayId'))

  if (showingChildRoute) {
    return <Outlet />
  }

  return <ReplaysPage />
}

function formatDuration(ms: number) {
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)}s`
  return `${ms.toFixed(0)}ms`
}

function formatDate(isoString: string) {
  if (!isoString) return 'N/A'
  const date = new Date(isoString)
  if (isNaN(date.getTime())) return 'Invalid Date'
  return date.toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function ReplaysPage() {
  const navigate = useNavigate()
  const { selectedProjectId } = useProject()
  const [period, setPeriod] = useState<'24h' | '7d' | '30d'>('7d')
  const [environment, setEnvironment] = useState('all')
  const [page, setPage] = useState(1)

  const { data: projects } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const projectId = selectedProjectId || projects?.[0]?.id

  const { data: replays = [], isLoading } = useQuery({
    queryKey: ['replays', projectId, period, environment, page],
    queryFn: () =>
      projectId
        ? api.getReplays(projectId, {
            page,
            limit: 25,
            period,
            environment: environment === 'all' ? undefined : environment,
          })
        : [],
    enabled: !!projectId,
  })

  if (!projects || projects.length === 0) {
    return (
      <div className="min-h-screen bg-background p-6">
        <Card className="p-12 text-center">
          <p className="text-muted-foreground">No projects yet. Create a project to view replays.</p>
        </Card>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-background">
      <div className="mx-auto max-w-7xl p-6">
        <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-2xl font-bold">Replays</h2>
          <div className="flex flex-wrap items-center gap-2">
            <Select value={period} onValueChange={(value) => setPeriod(value as '24h' | '7d' | '30d')}>
              <SelectTrigger className="w-[140px]">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="24h">Last 24h</SelectItem>
                <SelectItem value="7d">Last 7d</SelectItem>
                <SelectItem value="30d">Last 30d</SelectItem>
              </SelectContent>
            </Select>
            <Select value={environment} onValueChange={setEnvironment}>
              <SelectTrigger className="w-[150px]">
                <SelectValue placeholder="Environment" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">All Environments</SelectItem>
                <SelectItem value="production">Production</SelectItem>
                <SelectItem value="staging">Staging</SelectItem>
                <SelectItem value="development">Development</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>

        {isLoading ? (
          <div className="p-8 text-center">Loading replays...</div>
        ) : !replays.length ? (
          <Card className="p-12 text-center">
            <Play className="h-12 w-12 mx-auto text-muted-foreground mb-4" />
            <h3 className="text-lg font-semibold mb-2">No replays yet</h3>
            <p className="text-muted-foreground max-w-md mx-auto">
              Session replays are recorded when you enable the Sentry Replay integration in your SDK.
              Configure replays in your project setup to start capturing user sessions.
            </p>
          </Card>
        ) : (
          <Card>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>User</TableHead>
                  <TableHead>Duration</TableHead>
                  <TableHead className="text-right">Activity</TableHead>
                  <TableHead className="text-right">Errors</TableHead>
                  <TableHead>URLs</TableHead>
                  <TableHead>Browser / OS</TableHead>
                  <TableHead>Timestamp</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {replays.map((replay) => (
                  <TableRow
                    key={replay.replayId}
                    className="cursor-pointer hover:bg-accent/50"
                    onClick={() => navigate({ to: '/replays/$replayId', params: { replayId: replay.replayId } })}
                  >
                      <TableCell>
                        <div className="flex items-center gap-2">
                          <User className="h-4 w-4 text-muted-foreground" />
                          {replay.user?.email || replay.user?.username || replay.user?.id || 'Anonymous'}
                        </div>
                      </TableCell>
                      <TableCell>{formatDuration(replay.durationMs)}</TableCell>
                      <TableCell className="text-right">{replay.activity}</TableCell>
                      <TableCell className="text-right">
                        {replay.errorCount > 0 ? (
                          <span className="flex items-center justify-end gap-1 text-destructive">
                            <AlertCircle className="h-4 w-4" />
                            {replay.errorCount}
                          </span>
                        ) : (
                          '0'
                        )}
                      </TableCell>
                      <TableCell className="max-w-[200px] truncate" title={replay.urls?.join(', ')}>
                        {replay.urls?.length ? (
                          <span className="flex items-center gap-1">
                            <Globe className="h-4 w-4 text-muted-foreground flex-shrink-0" />
                            {replay.urls[0] || '-'}
                            {replay.urls.length > 1 && ` (+${replay.urls.length - 1})`}
                          </span>
                        ) : (
                          '-'
                        )}
                      </TableCell>
                      <TableCell>
                        <div className="flex items-center gap-1 text-sm text-muted-foreground">
                          <Monitor className="h-4 w-4 flex-shrink-0" />
                          {[replay.browserName, replay.browserVersion, replay.osName]
                            .filter(Boolean)
                            .join(' / ') || '-'}
                        </div>
                      </TableCell>
                      <TableCell>{formatDate(replay.startedAt)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            {replays.length >= 25 && (
              <div className="p-3 flex justify-center gap-2">
                <button
                  type="button"
                  onClick={() => setPage((p) => Math.max(1, p - 1))}
                  disabled={page <= 1}
                  className="px-3 py-1 text-sm rounded border hover:bg-accent disabled:opacity-50"
                >
                  Previous
                </button>
                <span className="px-3 py-1 text-sm text-muted-foreground">Page {page}</span>
                <button
                  type="button"
                  onClick={() => setPage((p) => p + 1)}
                  className="px-3 py-1 text-sm rounded border hover:bg-accent"
                >
                  Next
                </button>
              </div>
            )}
          </Card>
        )}
      </div>
    </div>
  )
}
