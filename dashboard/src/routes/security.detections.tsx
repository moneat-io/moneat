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

import {useMemo, useState} from 'react'
import {createFileRoute} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {ListFilter, Plus, ShieldCheck} from 'lucide-react'
import {
  api,
  formatErrorForLogging,
  type CreateDetectionRuleRequest,
  type DetectionCoverageResponse,
  type DetectionRuleResponse,
} from '@/lib/api'
import {SectionCard} from '@/components/ui/section-card'
import {EmptyState} from '@/components/ui/empty-state'
import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
import {Sheet, SheetContent, SheetHeader, SheetTitle} from '@/components/ui/sheet'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import {useToast} from '@/hooks/useToast'
import {RuleList} from '@/components/security/RuleList'
import {RuleForm} from '@/components/security/RuleForm'
import {SecurityError} from '@/components/security/SecurityError'

export const Route = createFileRoute('/security/detections')({
  component: DetectionsTab,
})

type Editing = {mode: 'create'} | {mode: 'edit'; rule: DetectionRuleResponse} | null

function DetectionsTab() {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [editing, setEditing] = useState<Editing>(null)
  const [pendingDelete, setPendingDelete] = useState<DetectionRuleResponse | null>(null)

  const {data, isLoading, isError, error} = useQuery({
    queryKey: ['detection-rules'],
    queryFn: () => api.listDetectionRules(),
  })
  const {data: coverage} = useQuery({
    queryKey: ['detection-coverage'],
    queryFn: () => api.getDetectionCoverage(),
  })
  const rules = useMemo(() => data?.rules ?? [], [data])

  const invalidate = () => {
    void queryClient.invalidateQueries({queryKey: ['detection-rules']})
    void queryClient.invalidateQueries({queryKey: ['detection-coverage']})
  }

  const save = useMutation({
    mutationFn: (vars: {ruleId?: number; request: CreateDetectionRuleRequest}) =>
      vars.ruleId
        ? api.updateDetectionRule(vars.ruleId, vars.request)
        : api.createDetectionRule(vars.request),
    onSuccess: (saved) => {
      void invalidate()
      toast({title: 'Rule saved', description: saved.name})
    },
    onError: (error) => {
      toast({title: 'Save failed', description: formatErrorForLogging(error), variant: 'destructive'})
    },
  })

  const toggle = useMutation({
    mutationFn: (vars: {rule: DetectionRuleResponse; enabled: boolean}) =>
      api.updateDetectionRule(vars.rule.id, {enabled: vars.enabled}),
    onSuccess: () => void invalidate(),
    onError: (error) => {
      toast({title: 'Update failed', description: formatErrorForLogging(error), variant: 'destructive'})
    },
  })

  const remove = useMutation({
    mutationFn: (ruleId: number) => api.deleteDetectionRule(ruleId),
    onSuccess: () => {
      void invalidate()
      toast({title: 'Rule deleted'})
    },
    onError: (error) => {
      toast({title: 'Delete failed', description: formatErrorForLogging(error), variant: 'destructive'})
    },
    onSettled: () => setPendingDelete(null),
  })

  const editingRule = editing?.mode === 'edit' ? editing.rule : undefined

  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between gap-2">
        <p className="text-sm text-muted-foreground">Rules that turn matching logs into signals.</p>
        <Button size="sm" onClick={() => setEditing({mode: 'create'})}>
          <Plus className="h-4 w-4" />
          New rule
        </Button>
      </div>

      {coverage && <CoveragePanel coverage={coverage} />}

      {isLoading ? (
        <div className="flex justify-center py-8">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-muted border-t-primary" />
        </div>
      ) : isError ? (
        <SecurityError title="Couldn’t load detection rules" error={error} />
      ) : (
        <SectionCard
          title="Detection rules"
          icon={ListFilter}
          iconTone="accent"
          count={data?.total_count ?? rules.length}
          flushBody
        >
          {rules.length === 0 ? (
            <div className="p-4">
              <EmptyState
                icon={ListFilter}
                title="No detection rules yet"
                description="Create a rule to turn matching logs into security signals."
                action={
                  <Button size="sm" onClick={() => setEditing({mode: 'create'})}>
                    <Plus className="h-4 w-4" />
                    New rule
                  </Button>
                }
              />
            </div>
          ) : (
            <RuleList
              rules={rules}
              onEdit={(rule) => setEditing({mode: 'edit', rule})}
              onToggle={(rule, enabled) => toggle.mutate({rule, enabled})}
              onDelete={(rule) => setPendingDelete(rule)}
              togglingId={toggle.isPending ? toggle.variables?.rule.id : undefined}
            />
          )}
        </SectionCard>
      )}

      <Sheet open={editing != null} onOpenChange={(open) => !open && setEditing(null)}>
        <SheetContent className="w-full overflow-y-auto sm:max-w-lg">
          <SheetHeader>
            <SheetTitle className="text-sm">{editingRule ? 'Edit rule' : 'New rule'}</SheetTitle>
          </SheetHeader>
          {editing && (
            <div className="mt-4">
              <RuleForm
                rule={editingRule}
                isSubmitting={save.isPending}
                onCancel={() => setEditing(null)}
                onSubmit={(request, ruleId) =>
                  save.mutateAsync({ruleId: ruleId ?? editingRule?.id, request})
                }
                onPreview={(ruleId) => api.previewDetectionRule(ruleId)}
              />
            </div>
          )}
        </SheetContent>
      </Sheet>

      <AlertDialog open={pendingDelete != null} onOpenChange={(open) => !open && setPendingDelete(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete rule</AlertDialogTitle>
            <AlertDialogDescription>
              Delete “{pendingDelete?.name}”? This stops it from producing new signals.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              onClick={() => pendingDelete && remove.mutate(pendingDelete.id)}>
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

function CoveragePanel({coverage}: {coverage: DetectionCoverageResponse}) {
  const topTechniques = coverage.techniques.slice(0, 6)
  const topTactics = coverage.tactics.slice(0, 8)
  return (
    <SectionCard
      title="MITRE ATT&CK coverage"
      icon={ShieldCheck}
      iconTone="info"
      count={`${coverage.techniques.length} techniques`}
      bodyClassName="space-y-2"
    >
      <div className="flex flex-wrap gap-1">
        {topTactics.length === 0 ? (
          <span className="text-xs text-muted-foreground">No enabled rules with ATT&CK tags</span>
        ) : (
          topTactics.map((tactic) => (
            <Badge key={tactic.tactic} variant="neutral" size="sm">
              {tactic.tactic.replace(/-/g, ' ')} - {tactic.rule_count}
            </Badge>
          ))
        )}
      </div>
      {topTechniques.length > 0 && (
        <div className="grid gap-1 md:grid-cols-2">
          {topTechniques.map((technique) => (
            <div key={technique.technique_id} className="rounded-md border p-2">
              <div className="flex items-center justify-between gap-2">
                <span className="font-mono text-xs">{technique.technique_id}</span>
                <Badge variant="neutral" size="sm">{technique.rule_count} rules</Badge>
              </div>
              <div className="mt-1 truncate text-[10px] text-muted-foreground">
                {technique.tactics.map((tactic) => tactic.replace(/-/g, ' ')).join(', ')}
              </div>
            </div>
          ))}
        </div>
      )}
    </SectionCard>
  )
}
