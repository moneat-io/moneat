import { createFileRoute, redirect, Link } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { formatRelativeTime } from '@/lib/utils'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Card, CardContent } from '@/components/ui/card'
import { Plus, Search } from 'lucide-react'
import { useState } from 'react'

export const Route = createFileRoute('/')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
    }
    
    // Check if user needs to complete onboarding
    try {
      const user = await api.getCurrentUser()
      if (!user.onboardingCompleted) {
        throw redirect({ to: '/onboarding' })
      }
    } catch (error) {
      // If we can't fetch user, let them continue (auth will handle it)
      console.error('Failed to fetch user:', error)
    }
  },
  component: DashboardPage,
})

function DashboardPage() {
  const [selectedProjectId, setSelectedProjectId] = useState<number | null>(null)
  const [searchQuery, setSearchQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<string>('unresolved')
  const queryClient = useQueryClient()

  const { data: projects, isLoading } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const projectId = selectedProjectId || projects?.[0]?.id

  const { data: issues = [] } = useQuery({
    queryKey: ['issues', projectId, statusFilter],
    queryFn: () => (projectId ? api.getIssues(projectId) : []),
    enabled: !!projectId,
  })

  const [showCreateProject, setShowCreateProject] = useState(false)
  const [newProjectName, setNewProjectName] = useState('')

  const createProjectMutation = useMutation({
    mutationFn: (name: string) => api.createProject(name),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['projects'] })
      setShowCreateProject(false)
      setNewProjectName('')
    },
  })

  if (isLoading) return <div className="p-8">Loading...</div>

  const filteredIssues = issues.filter((issue) => {
    const matchesSearch =
      searchQuery === '' ||
      issue.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
      issue.culprit.toLowerCase().includes(searchQuery.toLowerCase())
    const matchesStatus = statusFilter === 'all' || issue.status === statusFilter
    return matchesSearch && matchesStatus
  })

  const currentProject = projects?.find((p) => p.id === projectId)

  return (
    <div className="min-h-screen bg-background">
      <nav className="border-b bg-card px-6 py-4">
        <div className="flex items-center justify-between">
          <h1 className="text-2xl font-bold">Moneat</h1>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              api.logout()
              window.location.href = '/login'
            }}
          >
            Logout
          </Button>
        </div>
      </nav>

      <div className="p-6 max-w-7xl mx-auto">
        <div className="mb-6 flex items-center justify-between">
          <div className="flex items-center gap-4">
            <h2 className="text-xl font-bold">Issues</h2>
            {projects && projects.length > 0 && (
              <Select
                value={projectId?.toString() || ''}
                onValueChange={(val) => setSelectedProjectId(Number(val))}
              >
                <SelectTrigger className="w-[200px]">
                  <SelectValue placeholder="Select project" />
                </SelectTrigger>
                <SelectContent>
                  {projects.map((project) => (
                    <SelectItem key={project.id} value={project.id.toString()}>
                      {project.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
          </div>
          <Button onClick={() => setShowCreateProject(true)} size="sm">
            <Plus className="h-4 w-4 mr-2" />
            New Project
          </Button>
        </div>

        {showCreateProject && (
          <Card className="mb-6">
            <CardContent className="pt-6">
              <div className="flex gap-2">
                <Input
                  placeholder="Project name"
                  value={newProjectName}
                  onChange={(e) => setNewProjectName(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && newProjectName) {
                      createProjectMutation.mutate(newProjectName)
                    }
                  }}
                />
                <Button
                  onClick={() => newProjectName && createProjectMutation.mutate(newProjectName)}
                  disabled={!newProjectName || createProjectMutation.isPending}
                >
                  Create
                </Button>
                <Button variant="outline" onClick={() => setShowCreateProject(false)}>
                  Cancel
                </Button>
              </div>
            </CardContent>
          </Card>
        )}

        {!projects || projects.length === 0 ? (
          <div className="rounded-lg border bg-card p-8 text-center">
            <p className="text-muted-foreground">No projects yet. Create your first project to get started.</p>
          </div>
        ) : (
          <>
            {currentProject && (
              <div className="mb-4 p-4 bg-muted rounded-lg border">
                <div className="text-sm text-muted-foreground">
                  <strong>DSN:</strong>{' '}
                  <code className="bg-background px-2 py-1 rounded text-xs">{currentProject.dsn}</code>
                </div>
              </div>
            )}

            <div className="mb-4 flex gap-4">
              <div className="relative flex-1">
                <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                <Input
                  placeholder="Search issues..."
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  className="pl-10"
                />
              </div>
              <Select value={statusFilter} onValueChange={setStatusFilter}>
                <SelectTrigger className="w-[180px]">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">All Issues</SelectItem>
                  <SelectItem value="unresolved">Unresolved</SelectItem>
                  <SelectItem value="resolved">Resolved</SelectItem>
                </SelectContent>
              </Select>
            </div>

            {filteredIssues.length === 0 ? (
              <div className="rounded-lg border bg-card p-8 text-center">
                <p className="text-muted-foreground">
                  {searchQuery || statusFilter !== 'all'
                    ? 'No issues match your filters.'
                    : 'No issues found. Start sending errors to see them here.'}
                </p>
              </div>
            ) : (
              <div className="space-y-2">
                {filteredIssues.map((issue) => (
                  <Link
                    key={issue.id}
                    to="/issues/$issueId"
                    params={{ issueId: issue.id }}
                    className="block rounded-lg border bg-card p-4 hover:bg-accent transition"
                  >
                    <div className="flex items-start justify-between">
                      <div className="flex-1">
                        <div className="font-semibold">{issue.title}</div>
                        <div className="text-sm text-muted-foreground">{issue.culprit}</div>
                      </div>
                      <div className="text-right text-sm text-muted-foreground">
                        <div className="font-semibold">{issue.eventCount} events</div>
                        <div>{formatRelativeTime(issue.lastSeen)}</div>
                      </div>
                    </div>
                    <div className="mt-2 flex gap-2">
                      <Badge variant={issue.level === 'error' ? 'destructive' : 'secondary'}>
                        {issue.level}
                      </Badge>
                      <Badge variant="outline">{issue.platform}</Badge>
                      {issue.status === 'resolved' && (
                        <Badge variant="default" className="bg-green-500">
                          Resolved
                        </Badge>
                      )}
                    </div>
                  </Link>
                ))}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}
