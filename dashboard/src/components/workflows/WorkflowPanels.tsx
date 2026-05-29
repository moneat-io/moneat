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

import {Bug, CheckCircle2, ExternalLink, Loader2, PauseCircle, PlayCircle, XCircle} from 'lucide-react'
import type {WorkflowRunResponse} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Sheet, SheetContent, SheetHeader, SheetTitle} from '@/components/ui/sheet'
import type {WorkflowValidationIssue} from './workflowGraph'

interface PublishToggleProps {
  published: boolean
  pending: boolean
  onPublish: () => void
  onUnpublish: () => void
}

export function PublishToggle({published, pending, onPublish, onUnpublish}: PublishToggleProps) {
  return (
    <Button
      type="button"
      size="sm"
      variant={published ? 'outline' : 'default'}
      disabled={pending}
      onClick={published ? onUnpublish : onPublish}
      className="gap-1.5"
    >
      {pending ? (
        <Loader2 className="h-3.5 w-3.5 animate-spin" />
      ) : published ? (
        <PauseCircle className="h-3.5 w-3.5" />
      ) : (
        <PlayCircle className="h-3.5 w-3.5" />
      )}
      {published ? 'Unpublish' : 'Publish'}
    </Button>
  )
}

export function ValidationPanel({issues}: {issues: WorkflowValidationIssue[]}) {
  const errors = issues.filter((issue) => issue.level === 'error')
  return (
    <div className="rounded-md border bg-muted/20 p-3">
      <div className="mb-2 flex items-center gap-2">
        {errors.length === 0 ? (
          <CheckCircle2 className="h-4 w-4 text-emerald-500" />
        ) : (
          <XCircle className="h-4 w-4 text-destructive" />
        )}
        <h3 className="text-sm font-semibold">Validation</h3>
      </div>
      {issues.length === 0 ? (
        <p className="text-sm text-muted-foreground">Ready to save or publish.</p>
      ) : (
        <div className="space-y-1.5">
          {issues.map((issue) => (
            <div key={issue.message} className="flex items-start gap-2 text-sm">
              <Badge variant={issue.level === 'error' ? 'destructive' : 'secondary'} className="mt-0.5">
                {issue.level}
              </Badge>
              <span className="text-muted-foreground">{issue.message}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

export function RunDebugDrawer({
  run,
  open,
  showTemporalLink,
  onOpenChange,
}: {
  run: WorkflowRunResponse | null
  open: boolean
  showTemporalLink: boolean
  onOpenChange: (open: boolean) => void
}) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="w-full overflow-y-auto sm:max-w-xl">
        <SheetHeader>
          <SheetTitle>Run debug</SheetTitle>
        </SheetHeader>
        {!run ? (
          <p className="mt-4 text-sm text-muted-foreground">Select a run to inspect node status.</p>
        ) : (
          <div className="mt-4 space-y-3">
            <div className="rounded-md border p-3">
              <div className="flex items-center justify-between gap-2">
                <Badge variant="outline">{run.status}</Badge>
                <span className="text-xs text-muted-foreground">{formatDate(run.created_at)}</span>
              </div>
              {run.error_message && <p className="mt-2 text-sm text-destructive">{run.error_message}</p>}
              {showTemporalLink && (
                <Button type="button" variant="outline" size="sm" className="mt-3 gap-1.5">
                  <ExternalLink className="h-3.5 w-3.5" />
                  Open in Temporal
                </Button>
              )}
            </div>
            <div className="space-y-2">
              {run.steps.length === 0 ? (
                <p className="rounded-md border bg-muted/20 px-3 py-2 text-sm text-muted-foreground">
                  Node-level debug entries have not been recorded for this run.
                </p>
              ) : (
                run.steps.map((step) => (
                  <div key={step.id} className="rounded-md border p-3">
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex min-w-0 items-center gap-2">
                        <Bug className="h-4 w-4 shrink-0 text-muted-foreground" />
                        <p className="truncate text-sm font-medium">{step.node_id}</p>
                      </div>
                      <Badge variant="outline">{step.status}</Badge>
                    </div>
                    {step.error_message && <p className="mt-2 text-sm text-destructive">{step.error_message}</p>}
                    <pre className="mt-2 max-h-40 overflow-auto rounded bg-muted/40 p-2 text-[11px]">
                      {JSON.stringify(step.output, null, 2)}
                    </pre>
                  </div>
                ))
              )}
            </div>
          </div>
        )}
      </SheetContent>
    </Sheet>
  )
}

function formatDate(value?: string | null): string {
  if (!value) return 'Never'
  return new Date(value).toLocaleString()
}
