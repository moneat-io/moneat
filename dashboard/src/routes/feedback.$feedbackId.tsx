import { createFileRoute, Link, redirect } from '@tanstack/react-router'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { formatRelativeTime } from '@/lib/utils'
import { useToast } from '@/hooks/use-toast'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import {
  MessageSquare,
  ChevronLeft,
  CheckCircle2,
  XCircle,
  Archive,
  ExternalLink,
  User,
  Mail,
  Globe,
  Tag,
  Code,
} from 'lucide-react'

export const Route = createFileRoute('/feedback/$feedbackId')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
    }
  },
  component: FeedbackDetailPage,
})

function FeedbackDetailPage() {
  const { feedbackId } = Route.useParams()
  const queryClient = useQueryClient()
  const { toast } = useToast()

  const { data: feedback, isLoading } = useQuery({
    queryKey: ['feedback-detail', feedbackId],
    queryFn: () => api.getFeedbackDetail(feedbackId),
  })

  const { data: issueForEvent } = useQuery({
    queryKey: ['event-issue', feedback?.associatedEventId],
    queryFn: () => api.getIssueIdForEvent(feedback!.associatedEventId!),
    enabled: !!feedback?.associatedEventId,
  })

  const updateMutation = useMutation({
    mutationFn: (status: string) => api.updateFeedback(feedbackId, { status }),
    onSuccess: (_, status) => {
      queryClient.invalidateQueries({ queryKey: ['feedback-detail', feedbackId] })
      queryClient.invalidateQueries({ queryKey: ['feedback'] })
      toast({
        title: status === 'resolved' ? 'Feedback resolved' : status === 'unresolved' ? 'Feedback unresolved' : 'Feedback archived',
        description: `Status set to ${status}.`,
      })
    },
    onError: () => {
      toast({
        title: 'Error',
        description: 'Failed to update feedback',
        variant: 'destructive',
      })
    },
  })

  if (isLoading) return <div className="p-8">Loading...</div>
  if (!feedback) return <div className="p-8">Feedback not found</div>

  return (
    <div className="min-h-screen bg-background">
      <div className="p-6 max-w-7xl mx-auto">
        <nav className="mb-6 flex items-center gap-2 text-sm text-muted-foreground">
          <Link to="/feedback" className="flex items-center gap-1 hover:text-foreground">
            <ChevronLeft className="h-4 w-4" />
            Feedback
          </Link>
          <span>/</span>
          <span className="text-foreground font-medium truncate max-w-[200px]" title={feedbackId}>
            {feedbackId.slice(0, 8)}…
          </span>
        </nav>

        <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-4 mb-6">
          <div className="flex items-center gap-3">
            <div className="rounded-lg bg-primary/10 p-2">
              <MessageSquare className="h-6 w-6 text-primary" />
            </div>
            <div>
              <h1 className="text-2xl font-bold">User Feedback</h1>
              <p className="text-sm text-muted-foreground">{formatRelativeTime(feedback.timestamp)}</p>
            </div>
          </div>
          <div className="flex flex-wrap gap-2">
            <Badge
              variant={feedback.status === 'resolved' ? 'default' : 'secondary'}
              className={feedback.status === 'resolved' ? 'bg-green-500' : ''}
            >
              {feedback.status}
            </Badge>
            {feedback.status !== 'resolved' && (
              <Button
                size="sm"
                className="bg-green-600 hover:bg-green-700"
                onClick={() => updateMutation.mutate('resolved')}
                disabled={updateMutation.isPending}
              >
                <CheckCircle2 className="h-4 w-4 mr-1" />
                Resolve
              </Button>
            )}
            {feedback.status === 'resolved' && (
              <Button
                size="sm"
                variant="outline"
                onClick={() => updateMutation.mutate('unresolved')}
                disabled={updateMutation.isPending}
              >
                <XCircle className="h-4 w-4 mr-1" />
                Unresolve
              </Button>
            )}
            {feedback.status !== 'archived' && (
              <Button
                size="sm"
                variant="outline"
                onClick={() => updateMutation.mutate('archived')}
                disabled={updateMutation.isPending}
              >
                <Archive className="h-4 w-4 mr-1" />
                Archive
              </Button>
            )}
          </div>
        </div>

        <div className="grid gap-6 md:grid-cols-2">
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Message</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="whitespace-pre-wrap text-sm">{feedback.message || '(No message)'}</p>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">Submitter</CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              {(feedback.name || feedback.contactEmail) && (
                <div className="flex items-center gap-2 text-sm">
                  <User className="h-4 w-4 text-muted-foreground" />
                  <span>{feedback.name || '—'}</span>
                </div>
              )}
              {feedback.contactEmail && (
                <div className="flex items-center gap-2 text-sm">
                  <Mail className="h-4 w-4 text-muted-foreground" />
                  <a href={`mailto:${feedback.contactEmail}`} className="text-primary hover:underline">
                    {feedback.contactEmail}
                  </a>
                </div>
              )}
              {feedback.url && (
                <div className="flex items-start gap-2 text-sm">
                  <Globe className="h-4 w-4 text-muted-foreground shrink-0 mt-0.5" />
                  <a
                    href={feedback.url}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-primary hover:underline break-all"
                  >
                    {feedback.url}
                  </a>
                </div>
              )}
              {!feedback.name && !feedback.contactEmail && !feedback.url && (
                <p className="text-sm text-muted-foreground">Anonymous</p>
              )}
            </CardContent>
          </Card>
        </div>

        <Card className="mt-6">
          <CardHeader>
            <CardTitle className="text-base">Metadata</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="flex flex-wrap gap-4 text-sm">
              {feedback.environment && (
                <div>
                  <span className="text-muted-foreground">Environment:</span>{' '}
                  <span>{feedback.environment}</span>
                </div>
              )}
              {feedback.release && (
                <div>
                  <span className="text-muted-foreground">Release:</span>{' '}
                  <span>{feedback.release}</span>
                </div>
              )}
              {feedback.platform && (
                <div>
                  <span className="text-muted-foreground">Platform:</span>{' '}
                  <span>{feedback.platform}</span>
                </div>
              )}
              {(feedback.sdkName || feedback.sdkVersion) && (
                <div className="flex items-center gap-1">
                  <Code className="h-4 w-4 text-muted-foreground" />
                  <span>
                    {feedback.sdkName} {feedback.sdkVersion}
                  </span>
                </div>
              )}
            </div>
            {Object.keys(feedback.tags ?? {}).length > 0 && (
              <div className="pt-2 border-t">
                <div className="flex items-center gap-2 text-sm text-muted-foreground mb-2">
                  <Tag className="h-4 w-4" />
                  Tags
                </div>
                <div className="flex flex-wrap gap-2">
                  {Object.entries(feedback.tags).map(([k, v]) => (
                    <Badge key={k} variant="outline">
                      {k}: {v}
                    </Badge>
                  ))}
                </div>
              </div>
            )}
          </CardContent>
        </Card>

        <Card className="mt-6">
          <CardHeader>
            <CardTitle className="text-base">Related</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-wrap gap-3">
            {feedback.associatedEventId && (
              <div>
                {issueForEvent?.issueId ? (
                  <Link
                    to="/issues/$issueId"
                    params={{ issueId: issueForEvent.issueId }}
                    className="inline-flex items-center gap-1 text-sm text-primary hover:underline"
                  >
                    <ExternalLink className="h-4 w-4" />
                    View related issue
                  </Link>
                ) : (
                  <span className="text-sm text-muted-foreground">
                    Event: {feedback.associatedEventId}
                  </span>
                )}
              </div>
            )}
            {feedback.replayId && (
              <Link
                to="/replays/$replayId"
                params={{ replayId: feedback.replayId }}
                className="inline-flex items-center gap-1 text-sm text-primary hover:underline"
              >
                <ExternalLink className="h-4 w-4" />
                View replay
              </Link>
            )}
            {!feedback.associatedEventId && !feedback.replayId && (
              <p className="text-sm text-muted-foreground">No linked event or replay</p>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
