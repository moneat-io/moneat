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

import {createFileRoute, Link, redirect} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {formatRelativeTime} from '@/lib/utils'
import {useToast} from '@/hooks/useToast'
import {Badge, type BadgeProps} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card} from '@/components/ui/card'
import {SectionCard} from '@/components/ui/section-card'
import {EmptyState} from '@/components/ui/empty-state'
import {StatusDot, type StatusTone} from '@/components/ui/status-dot'
import {Avatar, AvatarFallback} from '@/components/ui/avatar'
import {Separator} from '@/components/ui/separator'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger,} from '@/components/ui/tooltip'
import {
  AlertCircle,
  Archive,
  Check,
  CheckCircle2,
  ChevronLeft,
  CircleDot,
  Clock,
  Code,
  Copy,
  ExternalLink,
  Globe,
  Mail,
  MessageSquare,
  Monitor,
  Package,
  Server,
  Tag,
  User,
  Video,
  XCircle,
} from 'lucide-react'
import {useState} from 'react'

export const Route = createFileRoute('/feedback/$feedbackId')({
  beforeLoad: async ({ location }) => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login', search: { redirect: location.href } })
    }
  },
  component: FeedbackDetailPage,
})

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

// Environment badge tone in the shared status language.
function environmentBadgeVariant(env: string): BadgeProps['variant'] {
  if (env === 'production') return 'danger'
  if (env === 'staging') return 'warning'
  return 'success'
}

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false)

  const handleCopy = () => {
    navigator.clipboard.writeText(text)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          onClick={handleCopy}
          className="text-muted-foreground/60 hover:text-foreground transition p-1 rounded"
        >
          {copied ? <Check className="h-3.5 w-3.5 text-success-fg" /> : <Copy className="h-3.5 w-3.5" />}
        </button>
      </TooltipTrigger>
      <TooltipContent>{copied ? 'Copied!' : 'Copy'}</TooltipContent>
    </Tooltip>
  )
}

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
  if (!feedback) return (
    <div className="p-8">
      <EmptyState icon={MessageSquare} title="Feedback not found" description="This feedback item could not be found." />
    </div>
  )

  const config = statusConfig[feedback.status as keyof typeof statusConfig] || statusConfig.unresolved
  const displayName = feedback.name || feedback.contactEmail || 'Anonymous'
  const initials = getInitials(feedback.name, feedback.contactEmail)
  const avatarColor = getAvatarColor(feedback.name, feedback.contactEmail)
  const hasSubmitter = feedback.name || feedback.contactEmail || feedback.url
  const hasMetadata = feedback.environment || feedback.release || feedback.platform || feedback.sdkName || feedback.sdkVersion
  const hasTags = Object.keys(feedback.tags ?? {}).length > 0
  const hasRelated = feedback.associatedEventId || feedback.replayId

  return (
    <TooltipProvider>
      <div className="min-h-screen">
        <div className="p-6 max-w-5xl mx-auto">
          {/* Breadcrumb */}
          <nav className="mb-6 flex items-center gap-2 text-sm text-muted-foreground">
            <Link to="/feedback" className="flex items-center gap-1 hover:text-foreground transition-colors">
              <ChevronLeft className="h-4 w-4" />
              Feedback
            </Link>
            <span className="text-muted-foreground/40">/</span>
            <span className="text-foreground font-medium font-mono truncate max-w-[200px] flex items-center gap-1" title={feedbackId}>
              {feedbackId.slice(0, 8)}…
            </span>
          </nav>

          {/* Header Card */}
          <Card className={`mb-6 overflow-hidden border-l-[3px] ${config.border}`}>
            <div className="p-6">
              <div className="flex flex-col sm:flex-row sm:items-start sm:justify-between gap-4">
                <div className="flex items-center gap-4">
                  <Avatar className="h-12 w-12">
                    <AvatarFallback className={`text-base font-bold ${avatarColor}`}>
                      {initials}
                    </AvatarFallback>
                  </Avatar>
                  <div>
                    <div className="flex items-center gap-2 mb-1">
                      <h1 className="text-xl font-bold">{displayName}</h1>
                      <Badge variant={config.badge} size="sm" className="font-medium">
                        <StatusDot tone={config.tone} size="sm" className="mr-0.5" />
                        {config.label}
                      </Badge>
                    </div>
                    <div className="flex items-center gap-3 text-sm text-muted-foreground">
                      <span className="flex items-center gap-1">
                        <Clock className="h-3.5 w-3.5" />
                        {formatRelativeTime(feedback.timestamp)}
                      </span>
                      {feedback.contactEmail && (
                        <span className="flex items-center gap-1">
                          <Mail className="h-3.5 w-3.5" />
                          {feedback.contactEmail}
                        </span>
                      )}
                    </div>
                  </div>
                </div>
                <div className="flex flex-wrap gap-2 shrink-0">
                  {feedback.status !== 'resolved' && (
                    <Button
                      size="sm"
                      onClick={() => updateMutation.mutate('resolved')}
                      disabled={updateMutation.isPending}
                    >
                      <CheckCircle2 className="h-4 w-4 mr-1.5" />
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
                      <XCircle className="h-4 w-4 mr-1.5" />
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
                      <Archive className="h-4 w-4 mr-1.5" />
                      Archive
                    </Button>
                  )}
                </div>
              </div>
            </div>
          </Card>

          {/* Message Card - Hero section */}
          <SectionCard title="Feedback Message" icon={MessageSquare} iconTone="accent" className="mb-6">
              <div className="rounded-xl bg-muted/50 border border-border/60 p-5 relative">
                {/* Quote accent */}
                <div className="absolute left-0 top-0 bottom-0 w-1 rounded-l-xl bg-primary" />
                <p className="whitespace-pre-wrap text-sm leading-relaxed pl-4">
                  {feedback.message || '(No message provided)'}
                </p>
              </div>
          </SectionCard>

          <div className="grid gap-6 md:grid-cols-2">
            {/* Submitter Card */}
            <SectionCard title="Submitter Details" icon={User} iconTone="info">
                {hasSubmitter ? (
                  <div className="space-y-3">
                    {feedback.name && (
                      <div className="flex items-center justify-between group">
                        <div className="flex items-center gap-3">
                          <div className="rounded-md bg-muted p-2">
                            <User className="h-4 w-4 text-muted-foreground" />
                          </div>
                          <div>
                            <p className="text-xs text-muted-foreground">Name</p>
                            <p className="text-sm font-medium">{feedback.name}</p>
                          </div>
                        </div>
                        <div className="opacity-0 group-hover:opacity-100 transition">
                          <CopyButton text={feedback.name} />
                        </div>
                      </div>
                    )}
                    {feedback.contactEmail && (
                      <div className="flex items-center justify-between group">
                        <div className="flex items-center gap-3">
                          <div className="rounded-md bg-muted p-2">
                            <Mail className="h-4 w-4 text-muted-foreground" />
                          </div>
                          <div>
                            <p className="text-xs text-muted-foreground">Email</p>
                            <a
                              href={`mailto:${feedback.contactEmail}`}
                              className="text-sm font-medium text-primary hover:underline"
                            >
                              {feedback.contactEmail}
                            </a>
                          </div>
                        </div>
                        <div className="opacity-0 group-hover:opacity-100 transition">
                          <CopyButton text={feedback.contactEmail} />
                        </div>
                      </div>
                    )}
                    {feedback.url && (
                      <div className="flex items-center justify-between group">
                        <div className="flex items-center gap-3 min-w-0">
                          <div className="rounded-md bg-muted p-2 shrink-0">
                            <Globe className="h-4 w-4 text-muted-foreground" />
                          </div>
                          <div className="min-w-0">
                            <p className="text-xs text-muted-foreground">Page URL</p>
                            <a
                              href={feedback.url}
                              target="_blank"
                              rel="noopener noreferrer"
                              className="text-sm font-medium text-primary hover:underline break-all line-clamp-2"
                            >
                              {feedback.url}
                            </a>
                          </div>
                        </div>
                        <div className="opacity-0 group-hover:opacity-100 transition shrink-0">
                          <CopyButton text={feedback.url} />
                        </div>
                      </div>
                    )}
                    {feedback.user && (feedback.user.id || feedback.user.username) && (
                      <>
                        <Separator />
                        <div className="text-xs text-muted-foreground">Identified User</div>
                        <div className="flex flex-wrap gap-2">
                          {feedback.user.username && (
                            <Badge variant="outline" className="text-xs">
                              @{feedback.user.username}
                            </Badge>
                          )}
                          {feedback.user.id && (
                            <Badge variant="outline" className="text-xs font-mono">
                              ID: {feedback.user.id}
                            </Badge>
                          )}
                        </div>
                      </>
                    )}
                  </div>
                ) : (
                  <div className="text-center py-4 text-muted-foreground">
                    <User className="h-8 w-8 mx-auto mb-2 opacity-30" />
                    <p className="text-sm">Anonymous feedback</p>
                  </div>
                )}
            </SectionCard>

            {/* Metadata Card */}
            <SectionCard title="Environment & SDK" icon={Server}>
                {hasMetadata ? (
                  <div className="space-y-3">
                    {feedback.environment && (
                      <div className="flex items-center gap-3">
                        <div className="rounded-md bg-muted p-2">
                          <Server className="h-4 w-4 text-muted-foreground" />
                        </div>
                        <div>
                          <p className="text-xs text-muted-foreground">Environment</p>
                          <p className="text-sm font-medium">
                            <Badge variant={environmentBadgeVariant(feedback.environment)} className="font-medium">
                              {feedback.environment}
                            </Badge>
                          </p>
                        </div>
                      </div>
                    )}
                    {feedback.release && (
                      <div className="flex items-center gap-3 group">
                        <div className="rounded-md bg-muted p-2">
                          <Package className="h-4 w-4 text-muted-foreground" />
                        </div>
                        <div className="min-w-0 flex-1">
                          <p className="text-xs text-muted-foreground">Release</p>
                          <p className="text-sm font-medium font-mono truncate">{feedback.release}</p>
                        </div>
                        <div className="opacity-0 group-hover:opacity-100 transition shrink-0">
                          <CopyButton text={feedback.release} />
                        </div>
                      </div>
                    )}
                    {feedback.platform && (
                      <div className="flex items-center gap-3">
                        <div className="rounded-md bg-muted p-2">
                          <Monitor className="h-4 w-4 text-muted-foreground" />
                        </div>
                        <div>
                          <p className="text-xs text-muted-foreground">Platform</p>
                          <p className="text-sm font-medium">{feedback.platform}</p>
                        </div>
                      </div>
                    )}
                    {(feedback.sdkName || feedback.sdkVersion) && (
                      <div className="flex items-center gap-3">
                        <div className="rounded-md bg-muted p-2">
                          <Code className="h-4 w-4 text-muted-foreground" />
                        </div>
                        <div>
                          <p className="text-xs text-muted-foreground">SDK</p>
                          <p className="text-sm font-medium">
                            {feedback.sdkName}{feedback.sdkVersion ? ` v${feedback.sdkVersion}` : ''}
                          </p>
                        </div>
                      </div>
                    )}
                  </div>
                ) : (
                  <div className="text-center py-4 text-muted-foreground">
                    <Server className="h-8 w-8 mx-auto mb-2 opacity-30" />
                    <p className="text-sm">No metadata available</p>
                  </div>
                )}
            </SectionCard>
          </div>

          {/* Tags Card */}
          {hasTags && (
            <SectionCard title="Tags" icon={Tag} count={Object.keys(feedback.tags).length} className="mt-6">
                <div className="flex flex-wrap gap-2">
                  {Object.entries(feedback.tags).map(([k, v]) => (
                    <div
                      key={k}
                      className="inline-flex items-center rounded-lg border border-border/60 bg-muted/50 px-3 py-1.5 text-sm"
                    >
                      <span className="text-muted-foreground font-medium">{k}</span>
                      <span className="mx-1.5 text-muted-foreground/40">=</span>
                      <span className="font-mono text-xs">{v}</span>
                    </div>
                  ))}
                </div>
            </SectionCard>
          )}

          {/* Related Items Card */}
          <SectionCard title="Related Items" icon={ExternalLink} className="mt-6">
              {hasRelated ? (
                <div className="grid gap-3 sm:grid-cols-2">
                  {feedback.associatedEventId && (
                    <div className="rounded-xl border border-warning-border bg-warning-bg/40 p-4 hover:bg-warning-bg/70 transition">
                      <div className="flex items-start gap-3">
                        <div className="rounded-lg bg-warning-bg p-2 shrink-0">
                          <AlertCircle className="h-5 w-5 text-warning-fg" />
                        </div>
                        <div className="min-w-0">
                          <p className="text-sm font-medium mb-1">Linked Event</p>
                          {issueForEvent ? (
                            <Link
                              to="/issues/$issueId"
                              params={{ issueId: issueForEvent }}
                              search={{ projectId: undefined }}
                              className="inline-flex items-center gap-1 text-sm text-primary hover:underline font-medium"
                            >
                              View related issue
                              <ExternalLink className="h-3.5 w-3.5" />
                            </Link>
                          ) : (
                            <p className="text-xs text-muted-foreground font-mono truncate" title={feedback.associatedEventId}>
                              {feedback.associatedEventId}
                            </p>
                          )}
                        </div>
                      </div>
                    </div>
                  )}
                  {feedback.replayId && (
                    <Link
                      to="/replays/$replayId"
                      params={{ replayId: feedback.replayId }}
                      className="block rounded-xl border border-info-border bg-info-bg/40 p-4 hover:bg-info-bg/70 transition"
                    >
                      <div className="flex items-start gap-3">
                        <div className="rounded-lg bg-info-bg p-2 shrink-0">
                          <Video className="h-5 w-5 text-info-fg" />
                        </div>
                        <div className="min-w-0">
                          <p className="text-sm font-medium mb-1">Session Replay</p>
                          <span className="inline-flex items-center gap-1 text-sm text-primary hover:underline font-medium">
                            Watch replay
                            <ExternalLink className="h-3.5 w-3.5" />
                          </span>
                        </div>
                      </div>
                    </Link>
                  )}
                </div>
              ) : (
                <div className="text-center py-6 text-muted-foreground">
                  <ExternalLink className="h-8 w-8 mx-auto mb-2 opacity-30" />
                  <p className="text-sm">No linked events or replays</p>
                </div>
              )}
          </SectionCard>

          {/* Feedback ID footer */}
          <div className="mt-6 flex items-center justify-center gap-2 text-xs text-muted-foreground/60">
            <span>Feedback ID:</span>
            <code className="font-mono bg-muted px-2 py-0.5 rounded">{feedbackId}</code>
            <CopyButton text={feedbackId} />
          </div>
        </div>
      </div>
    </TooltipProvider>
  )
}
