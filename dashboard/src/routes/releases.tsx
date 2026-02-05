import { createFileRoute, Link, redirect } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { useProject } from '@/contexts/project-context'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Activity, AlertCircle, Users, Package } from 'lucide-react'

export const Route = createFileRoute('/releases')({
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
  component: ReleasesPage,
})

function ReleasesPage() {
  const { selectedProjectId } = useProject()

  const { data: projects } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const projectId = selectedProjectId || projects?.[0]?.id

  const { data: releases, isLoading, error } = useQuery({
    queryKey: ['releases', projectId],
    queryFn: async () => {
      if (!projectId) return []
      const data = await api.getReleases(projectId)
      console.log('Releases data:', data)
      return data
    },
    enabled: !!projectId,
  })
  
  if (error) {
    console.error('Error loading releases:', error)
  }

  const formatDate = (isoString: string) => {
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

  return (
    <div className="min-h-screen bg-background">
      <div className="p-6 max-w-7xl mx-auto">
        <div className="mb-6">
          <h2 className="text-2xl font-bold">Releases</h2>
          <p className="text-muted-foreground mt-1">
            Track release health, new issues, and crash-free rates
          </p>
        </div>

        {!projects || projects.length === 0 ? (
          <Card className="p-12 text-center">
            <div className="max-w-md mx-auto space-y-4">
              <p className="text-muted-foreground">
                No projects yet. Create a project to view releases.
              </p>
            </div>
          </Card>
        ) : isLoading ? (
          <div className="p-8 text-center">Loading releases...</div>
        ) : !releases || releases.length === 0 ? (
          <Card className="p-12 text-center">
            <div className="max-w-md mx-auto space-y-4">
              <Package className="h-12 w-12 mx-auto text-muted-foreground" />
              <h3 className="text-lg font-semibold">No releases detected</h3>
              <p className="text-muted-foreground">
                Releases are auto-detected when events include a release version.
                Configure your SDK with a release version to start tracking.
              </p>
            </div>
          </Card>
        ) : (
          <div className="space-y-4">
            {releases.map((release, index) => (
              <Link
                key={release.version}
                to="/releases/$version"
                params={{ version: encodeURIComponent(release.version) }}
                className="block"
              >
                <Card className="hover:bg-accent/50 transition-colors cursor-pointer">
                  <CardContent className="p-4">
                    <div className="flex items-center justify-between">
                      <div className="flex items-center gap-4">
                        <div className="flex items-center justify-center w-10 h-10 rounded-lg bg-primary/10">
                          <Package className="h-5 w-5 text-primary" />
                        </div>
                        <div>
                          <div className="flex items-center gap-2">
                            <span className="font-semibold">{release.version}</span>
                            {index === 0 && (
                              <span className="text-xs px-2 py-0.5 rounded-full bg-primary/20 text-primary font-medium">
                                Latest
                              </span>
                            )}
                          </div>
                          <div className="text-sm text-muted-foreground">
                            {formatDate(release.firstSeen)}
                            {release.firstSeen !== release.lastSeen && (
                              <> – {formatDate(release.lastSeen)}</>
                            )}
                          </div>
                        </div>
                      </div>
                      <div className="flex items-center gap-6">
                        <div className="flex items-center gap-2 text-sm">
                          <Activity className="h-4 w-4 text-muted-foreground" />
                          <span>{release.eventCount.toLocaleString()} events</span>
                        </div>
                        <div className="flex items-center gap-2 text-sm">
                          <AlertCircle className="h-4 w-4 text-muted-foreground" />
                          <span>{release.newIssueCount} new issues</span>
                        </div>
                        {release.crashFreeRate != null && (
                          <div className="flex items-center gap-2 text-sm">
                            <span
                              className={
                                release.crashFreeRate >= 99
                                  ? 'text-emerald-600'
                                  : release.crashFreeRate >= 95
                                    ? 'text-amber-600'
                                    : 'text-red-600'
                              }
                            >
                              {release.crashFreeRate.toFixed(1)}% crash-free
                            </span>
                          </div>
                        )}
                        <div className="flex items-center gap-2 text-sm">
                          <Users className="h-4 w-4 text-muted-foreground" />
                          <span>{release.userCount.toLocaleString()} users</span>
                        </div>
                      </div>
                    </div>
                  </CardContent>
                </Card>
              </Link>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
