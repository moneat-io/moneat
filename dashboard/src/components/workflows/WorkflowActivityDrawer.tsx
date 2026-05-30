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

import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {History, Loader2, Share2} from 'lucide-react'
import {api} from '@/lib/api'
import type {WorkflowImportRequest, WorkflowResponse} from '@/lib/api'
import {Sheet, SheetContent, SheetHeader, SheetTitle} from '@/components/ui/sheet'
import {useToast} from '@/hooks/useToast'
import {WorkflowAuditTimeline} from './WorkflowAuditTimeline'
import {WorkflowExportImport} from './WorkflowExportImport'

const ACTIVITY_AUDIT_LIMIT = 20

interface WorkflowActivityDrawerProps {
  workflow: WorkflowResponse | null
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function WorkflowActivityDrawer({workflow, open, onOpenChange}: WorkflowActivityDrawerProps) {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const workflowId = workflow?.id ?? null
  const enabled = open && workflowId !== null

  const exportQuery = useQuery({
    queryKey: ['workflow-export', workflowId],
    queryFn: () => api.exportWorkflow(workflowId ?? 0),
    enabled,
  })
  const auditQuery = useQuery({
    queryKey: ['workflow-audit', workflowId, ACTIVITY_AUDIT_LIMIT],
    queryFn: () => api.getWorkflowAuditForWorkflow(workflowId ?? 0, ACTIVITY_AUDIT_LIMIT),
    enabled,
  })

  const importMutation = useMutation({
    mutationFn: (request: WorkflowImportRequest) => api.importWorkflow(request),
    onSuccess: (imported) => {
      queryClient.invalidateQueries({queryKey: ['workflows']})
      toast({title: 'Workflow imported', description: imported.name})
    },
    onError: (error: Error) => {
      toast({title: 'Import failed', description: error.message, variant: 'destructive'})
    },
  })

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="w-full overflow-y-auto sm:max-w-xl">
        <SheetHeader>
          <SheetTitle className="flex items-center gap-2">
            <Share2 className="h-4 w-4" />
            Share &amp; activity
          </SheetTitle>
        </SheetHeader>
        {!workflow ? (
          <p className="mt-4 text-sm text-muted-foreground">Select a workflow first.</p>
        ) : (
          <div className="mt-4 space-y-5">
            <WorkflowExportImport
              exportData={exportQuery.data}
              exporting={exportQuery.isFetching}
              onExport={() => exportQuery.refetch()}
              importing={importMutation.isPending}
              onImport={(request) => importMutation.mutate(request)}
            />
            <div className="space-y-2">
              <h3 className="flex items-center gap-2 text-sm font-semibold">
                <History className="h-4 w-4" />
                Activity
              </h3>
              {auditQuery.isLoading ? (
                <div className="flex h-24 items-center justify-center rounded-md border">
                  <Loader2 className="h-5 w-5 animate-spin" />
                </div>
              ) : (
                <WorkflowAuditTimeline entries={auditQuery.data ?? []} />
              )}
            </div>
          </div>
        )}
      </SheetContent>
    </Sheet>
  )
}
