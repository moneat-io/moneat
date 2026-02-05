import { createFileRoute, redirect } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { formatRelativeTime } from '@/lib/utils'

export const Route = createFileRoute('/')({
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
    }
  },
  component: DashboardPage,
})

function DashboardPage() {
  const { data: projects, isLoading } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const projectId = projects?.[0]?.id

  const { data: issues } = useQuery({
    queryKey: ['issues', projectId],
    queryFn: () => (projectId ? api.getIssues(projectId) : []),
    enabled: !!projectId,
  })

  if (isLoading) return <div className="p-8">Loading...</div>

  return (
    <div className="min-h-screen">
      <nav className="border-b bg-white px-6 py-4">
        <div className="flex items-center justify-between">
          <h1 className="text-2xl font-bold">Moneat</h1>
          <button
            onClick={() => {
              api.logout()
              window.location.href = '/login'
            }}
            className="text-sm text-gray-600 hover:text-gray-900"
          >
            Logout
          </button>
        </div>
      </nav>

      <div className="p-6">
        <div className="mb-6">
          <h2 className="text-xl font-bold">Issues</h2>
          {projects && projects.length > 0 && (
            <p className="text-sm text-gray-600">Project: {projects[0].name}</p>
          )}
        </div>

        {!projects || projects.length === 0 ? (
          <div className="rounded-lg border p-8 text-center">
            <p className="text-gray-600">No projects yet. Create your first project to get started.</p>
          </div>
        ) : !issues || issues.length === 0 ? (
          <div className="rounded-lg border p-8 text-center">
            <p className="text-gray-600">No issues found. Start sending errors to see them here.</p>
            <pre className="mt-4 text-left text-xs bg-gray-50 p-4 rounded">
              DSN: {projects[0].dsn}
            </pre>
          </div>
        ) : (
          <div className="space-y-2">
            {issues.map((issue) => (
              <div
                key={issue.id}
                className="rounded-lg border bg-white p-4 hover:border-gray-400 transition"
              >
                <div className="flex items-start justify-between">
                  <div>
                    <div className="font-semibold">{issue.title}</div>
                    <div className="text-sm text-gray-600">{issue.culprit}</div>
                  </div>
                  <div className="text-right text-sm text-gray-500">
                    <div>{issue.eventCount} events</div>
                    <div>{formatRelativeTime(issue.lastSeen)}</div>
                  </div>
                </div>
                <div className="mt-2 flex gap-2">
                  <span className="rounded bg-red-100 px-2 py-1 text-xs text-red-800">
                    {issue.level}
                  </span>
                  <span className="rounded bg-gray-100 px-2 py-1 text-xs text-gray-800">
                    {issue.platform}
                  </span>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
