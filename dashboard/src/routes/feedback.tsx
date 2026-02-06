import { createFileRoute, Link, redirect } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { useProject } from '@/contexts/project-context'
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
import { Card } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { MessageSquare, Search, CheckCircle2, ExternalLink } from 'lucide-react'
import { useState } from 'react'
import { useToast } from '@/hooks/use-toast'

export const Route = createFileRoute('/feedback')({
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
  component: FeedbackPage,
})

function FeedbackPage() {
  const [searchQuery, setSearchQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<string>('unresolved')
  const [selectedFeedback, setSelectedFeedback] = useState<Set<string>>(new Set())
  const { selectedProjectId, setSelectedProjectId } = useProject()
  const { toast } = useToast()
  const queryClient = useQueryClient()

  const { data: projects, isLoading } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
  })

  const projectId = selectedProjectId || projects?.[0]?.id

  if (!selectedProjectId && projects && projects.length > 0 && projects[0]?.id) {
    setSelectedProjectId(projects[0].id)
  }

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
      <div className="p-6 max-w-7xl mx-auto">
        <div className="mb-6 flex items-center justify-between">
          <h2 className="text-2xl font-bold">Feedback</h2>
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
            <div className="mb-4 flex gap-4 items-center flex-wrap">
              {filteredFeedback.length > 0 && (
                <div className="flex items-center gap-2">
                  <Checkbox
                    checked={selectedFeedback.size === filteredFeedback.length}
                    onCheckedChange={handleToggleAll}
                    aria-label="Select all feedback"
                  />
                  <span className="text-sm text-muted-foreground whitespace-nowrap">Select all</span>
                </div>
              )}
              {selectedFeedback.size > 0 && (
                <div className="flex items-center gap-2 bg-primary/10 border border-primary/20 rounded-lg px-3 py-1.5">
                  <CheckCircle2 className="h-4 w-4 text-primary" />
                  <span className="text-sm font-medium whitespace-nowrap">
                    {selectedFeedback.size} selected
                  </span>
                  <Button
                    onClick={handleResolveSelected}
                    disabled={resolveMutation.isPending}
                    size="sm"
                    className="bg-green-600 hover:bg-green-700 h-7 ml-2"
                  >
                    {resolveMutation.isPending ? 'Resolving...' : 'Resolve'}
                  </Button>
                </div>
              )}
              <div className="relative flex-1 min-w-[200px]">
                <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 h-4 w-4 text-muted-foreground" />
                <Input
                  placeholder="Search feedback..."
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
                  <SelectItem value="all">All</SelectItem>
                  <SelectItem value="unresolved">Unresolved</SelectItem>
                  <SelectItem value="resolved">Resolved</SelectItem>
                  <SelectItem value="archived">Archived</SelectItem>
                </SelectContent>
              </Select>
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
                        ? 'Try adjusting your search.'
                        : 'Use the Sentry User Feedback widget in your app to collect user feedback. It will appear here.'}
                    </p>
                  </div>
                </div>
              </Card>
            ) : (
              <div className="space-y-2">
                {filteredFeedback.map((f) => (
                  <div
                    key={f.feedbackId}
                    className="rounded-lg border border-border/80 bg-card hover:bg-accent hover:border-primary/20 transition"
                  >
                    <div className="flex items-center gap-4 p-4">
                      <div className="flex items-center">
                        <Checkbox
                          checked={selectedFeedback.has(f.feedbackId)}
                          onCheckedChange={() => handleToggleFeedback(f.feedbackId)}
                          onClick={(e) => e.stopPropagation()}
                          aria-label={`Select feedback`}
                        />
                      </div>
                      <Link
                        to="/feedback/$feedbackId"
                        params={{ feedbackId: f.feedbackId }}
                        className="flex-1 flex items-start gap-4 min-w-0"
                      >
                        <div className="flex-1 min-w-0">
                          <div className="font-medium line-clamp-2">{f.message || '(No message)'}</div>
                          <div className="text-sm text-muted-foreground mt-1">
                            {f.name || f.contactEmail || 'Anonymous'}
                            {f.url && (
                              <span className="truncate ml-2 text-xs block sm:inline" title={f.url}>
                                {f.url}
                              </span>
                            )}
                          </div>
                          <div className="mt-2 flex flex-wrap gap-2">
                            <Badge
                              variant={f.status === 'resolved' ? 'default' : 'secondary'}
                              className={f.status === 'resolved' ? 'bg-green-500' : ''}
                            >
                              {f.status}
                            </Badge>
                            {f.platform && (
                              <Badge variant="outline">{f.platform}</Badge>
                            )}
                            {f.associatedEventId && (
                              <Badge variant="outline" className="text-xs font-normal">
                                Event linked
                              </Badge>
                            )}
                            {f.replayId && (
                              <Link
                                to="/replays/$replayId"
                                params={{ replayId: f.replayId }}
                                onClick={(e) => e.stopPropagation()}
                                className="inline-flex items-center gap-1 text-xs text-primary hover:underline"
                              >
                                <ExternalLink className="h-3 w-3" />
                                Replay
                              </Link>
                            )}
                          </div>
                        </div>
                        <div className="text-right text-sm text-muted-foreground shrink-0">
                          {formatRelativeTime(f.timestamp)}
                        </div>
                      </Link>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}
