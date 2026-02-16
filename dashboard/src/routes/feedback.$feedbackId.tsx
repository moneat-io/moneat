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
import {useToast} from '@/hooks/use-toast'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
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
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({ to: '/login' })
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
    gradient: 'from-amber-500/10 via-transparent to-transparent',
    icon: CircleDot,
    label: 'Unresolved',
    badgeBg: 'bg-amber-500/15 text-amber-700 dark:text-amber-400',
  },
  resolved: {
    color: 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-400 border-emerald-500/30',
    dot: 'bg-emerald-500',
    gradient: 'from-emerald-500/10 via-transparent to-transparent',
    icon: CheckCircle2,
    label: 'Resolved',
    badgeBg: 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-400',
  },
  archived: {
    color: 'bg-slate-500/15 text-slate-600 dark:text-slate-400 border-slate-500/30',
    dot: 'bg-slate-400',
    gradient: 'from-slate-500/10 via-transparent to-transparent',
    icon: Archive,
    label: 'Archived',
    badgeBg: 'bg-slate-500/15 text-slate-600 dark:text-slate-400',
  },
} as const

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
          {copied ? <Check className="h-3.5 w-3.5 text-emerald-500" /> : <Copy className="h-3.5 w-3.5" />}
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
  if (!feedback) return <div className="p-8">Feedback not found</div>

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
      <div className="min-h-screen bg-gradient-to-br from-background via-background to-primary/5">
        {/* Top gradient accent */}
        <div className={`h-1 bg-gradient-to-r ${config.gradient}`} />

        <div className="p-6 max-w-5xl mx-auto">
          {/* Breadcrumb */}
          <nav className="mb-6 flex items-center gap-2 text-sm text-muted-foreground">
            <Link to="/feedback" className="flex items-center gap-1 hover:text-foreground transition-colors">
              <ChevronLeft className="h-4 w-4" />
              Feedback
            </Link>
            <span className="text-muted-foreground/40">/</span>
            <span className="text-foreground font-medium truncate max-w-[200px] flex items-center gap-1" title={feedbackId}>
              {feedbackId.slice(0, 8)}...
            </span>
          </nav>

          {/* Header Card */}
          <Card className={`mb-6 overflow-hidden border-l-[3px] ${
            feedback.status === 'resolved' ? 'border-l-emerald-500' :
            feedback.status === 'archived' ? 'border-l-slate-400' :
            'border-l-amber-500'
          }`}>
            <div className={`bg-gradient-to-r ${config.gradient} p-6`}>
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
                      <Badge
                        variant="outline"
                        className={`text-xs font-medium ${config.color}`}
                      >
                        <span className={`inline-block h-1.5 w-1.5 rounded-full mr-1.5 ${config.dot}`} />
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
                      className="bg-emerald-600 hover:bg-emerald-700 shadow-sm"
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
          <Card className="mb-6 overflow-hidden">
            <CardHeader className="pb-3">
              <CardTitle className="text-base flex items-center gap-2">
                <div className="rounded-lg bg-violet-500/15 p-1.5">
                  <MessageSquare className="h-4 w-4 text-violet-600 dark:text-violet-400" />
                </div>
                Feedback Message
              </CardTitle>
            </CardHeader>
            <CardContent>
              <div className="rounded-xl bg-muted/50 border border-border/60 p-5 relative">
                {/* Quote accent */}
                <div className="absolute left-0 top-0 bottom-0 w-1 rounded-l-xl bg-gradient-to-b from-violet-500 to-blue-500" />
                <p className="whitespace-pre-wrap text-sm leading-relaxed pl-4">
                  {feedback.message || '(No message provided)'}
                </p>
              </div>
            </CardContent>
          </Card>

          <div className="grid gap-6 md:grid-cols-2">
            {/* Submitter Card */}
            <Card>
              <CardHeader className="pb-3">
                <CardTitle className="text-base flex items-center gap-2">
                  <div className="rounded-lg bg-blue-500/15 p-1.5">
                    <User className="h-4 w-4 text-blue-600 dark:text-blue-400" />
                  </div>
                  Submitter Details
                </CardTitle>
              </CardHeader>
              <CardContent>
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
                          <div className="rounded-md bg-blue-500/10 p-2">
                            <Mail className="h-4 w-4 text-blue-600 dark:text-blue-400" />
                          </div>
                          <div>
                            <p className="text-xs text-muted-foreground">Email</p>
                            <a
                              href={`mailto:${feedback.contactEmail}`}
                              className="text-sm font-medium text-blue-600 dark:text-blue-400 hover:underline"
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
                          <div className="rounded-md bg-emerald-500/10 p-2 shrink-0">
                            <Globe className="h-4 w-4 text-emerald-600 dark:text-emerald-400" />
                          </div>
                          <div className="min-w-0">
                            <p className="text-xs text-muted-foreground">Page URL</p>
                            <a
                              href={feedback.url}
                              target="_blank"
                              rel="noopener noreferrer"
                              className="text-sm font-medium text-emerald-600 dark:text-emerald-400 hover:underline break-all line-clamp-2"
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
              </CardContent>
            </Card>

            {/* Metadata Card */}
            <Card>
              <CardHeader className="pb-3">
                <CardTitle className="text-base flex items-center gap-2">
                  <div className="rounded-lg bg-orange-500/15 p-1.5">
                    <Server className="h-4 w-4 text-orange-600 dark:text-orange-400" />
                  </div>
                  Environment & SDK
                </CardTitle>
              </CardHeader>
              <CardContent>
                {hasMetadata ? (
                  <div className="space-y-3">
                    {feedback.environment && (
                      <div className="flex items-center gap-3">
                        <div className="rounded-md bg-orange-500/10 p-2">
                          <Server className="h-4 w-4 text-orange-600 dark:text-orange-400" />
                        </div>
                        <div>
                          <p className="text-xs text-muted-foreground">Environment</p>
                          <p className="text-sm font-medium">
                            <Badge variant="outline" className={`font-medium ${
                              feedback.environment === 'production'
                                ? 'border-red-500/30 text-red-600 dark:text-red-400 bg-red-500/10'
                                : feedback.environment === 'staging'
                                ? 'border-amber-500/30 text-amber-600 dark:text-amber-400 bg-amber-500/10'
                                : 'border-emerald-500/30 text-emerald-600 dark:text-emerald-400 bg-emerald-500/10'
                            }`}>
                              {feedback.environment}
                            </Badge>
                          </p>
                        </div>
                      </div>
                    )}
                    {feedback.release && (
                      <div className="flex items-center gap-3 group">
                        <div className="rounded-md bg-purple-500/10 p-2">
                          <Package className="h-4 w-4 text-purple-600 dark:text-purple-400" />
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
                        <div className="rounded-md bg-cyan-500/10 p-2">
                          <Monitor className="h-4 w-4 text-cyan-600 dark:text-cyan-400" />
                        </div>
                        <div>
                          <p className="text-xs text-muted-foreground">Platform</p>
                          <p className="text-sm font-medium">{feedback.platform}</p>
                        </div>
                      </div>
                    )}
                    {(feedback.sdkName || feedback.sdkVersion) && (
                      <div className="flex items-center gap-3">
                        <div className="rounded-md bg-indigo-500/10 p-2">
                          <Code className="h-4 w-4 text-indigo-600 dark:text-indigo-400" />
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
              </CardContent>
            </Card>
          </div>

          {/* Tags Card */}
          {hasTags && (
            <Card className="mt-6">
              <CardHeader className="pb-3">
                <CardTitle className="text-base flex items-center gap-2">
                  <div className="rounded-lg bg-pink-500/15 p-1.5">
                    <Tag className="h-4 w-4 text-pink-600 dark:text-pink-400" />
                  </div>
                  Tags
                  <Badge variant="secondary" className="text-xs font-normal ml-1">
                    {Object.keys(feedback.tags).length}
                  </Badge>
                </CardTitle>
              </CardHeader>
              <CardContent>
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
              </CardContent>
            </Card>
          )}

          {/* Related Items Card */}
          <Card className="mt-6">
            <CardHeader className="pb-3">
              <CardTitle className="text-base flex items-center gap-2">
                <div className="rounded-lg bg-cyan-500/15 p-1.5">
                  <ExternalLink className="h-4 w-4 text-cyan-600 dark:text-cyan-400" />
                </div>
                Related Items
              </CardTitle>
            </CardHeader>
            <CardContent>
              {hasRelated ? (
                <div className="grid gap-3 sm:grid-cols-2">
                  {feedback.associatedEventId && (
                    <div className="rounded-xl border border-orange-500/20 bg-orange-500/5 p-4 hover:bg-orange-500/10 transition">
                      <div className="flex items-start gap-3">
                        <div className="rounded-lg bg-orange-500/15 p-2 shrink-0">
                          <AlertCircle className="h-5 w-5 text-orange-600 dark:text-orange-400" />
                        </div>
                        <div className="min-w-0">
                          <p className="text-sm font-medium mb-1">Linked Event</p>
                          {issueForEvent?.issueId ? (
                            <Link
                              to="/issues/$issueId"
                              params={{ issueId: issueForEvent.issueId }}
                              className="inline-flex items-center gap-1 text-sm text-orange-600 dark:text-orange-400 hover:underline font-medium"
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
                      className="block rounded-xl border border-blue-500/20 bg-blue-500/5 p-4 hover:bg-blue-500/10 transition"
                    >
                      <div className="flex items-start gap-3">
                        <div className="rounded-lg bg-blue-500/15 p-2 shrink-0">
                          <Video className="h-5 w-5 text-blue-600 dark:text-blue-400" />
                        </div>
                        <div className="min-w-0">
                          <p className="text-sm font-medium mb-1">Session Replay</p>
                          <span className="inline-flex items-center gap-1 text-sm text-blue-600 dark:text-blue-400 hover:underline font-medium">
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
            </CardContent>
          </Card>

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
