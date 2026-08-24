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

import {useState} from 'react'
import {useMutation, useQueryClient} from '@tanstack/react-query'
import {AlertTriangle, Loader2, WandSparkles} from 'lucide-react'
import {api} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Switch} from '@/components/ui/switch'
import {Textarea} from '@/components/ui/textarea'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import {useToast} from '@/hooks/useToast'
import type {
  AlertRoute,
  AlertRoutePreviewRequest,
} from '@/lib/api/types'
import {
  alertRouteToForm,
  applyP0P2TriagePreset,
  createEmptyAlertRouteForm,
  formToRequest,
  isConflictError,
  validateAlertRouteForm,
  type AlertRouteFormState,
} from './alertRouteModel'
import {AlertRouteConditionsSection} from './AlertRouteConditionsSection'
import {AlertRoutePagingSection, type PagingReference} from './AlertRoutePagingSection'
import {AlertRouteGroupingSection} from './AlertRouteGroupingSection'
import {AlertRouteIncidentSection, type IncidentTypeOption} from './AlertRouteIncidentSection'
import {AlertRoutePreviewPanel} from './AlertRoutePreviewPanel'
import {AlertRouteRevisionHistory} from './AlertRouteRevisionHistory'
import {ALERT_ROUTES_QUERY_KEY} from './alertRouteQueries'

interface AlertRouteEditorSheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The route to edit, or undefined to create a new route. */
  route?: AlertRoute
  escalationPolicies: PagingReference[]
  teams: PagingReference[]
  incidentTypeOptions: IncidentTypeOption[]
  onSaved: (route: AlertRoute) => void
}

export function AlertRouteEditorSheet({
  open,
  onOpenChange,
  route,
  escalationPolicies,
  teams,
  incidentTypeOptions,
  onSaved,
}: Readonly<AlertRouteEditorSheetProps>) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent
        side="right"
        className="flex w-full flex-col gap-0 overflow-hidden p-0 sm:max-w-2xl lg:max-w-3xl"
      >
        {open && (
          <AlertRouteEditorForm
            key={route?.id ?? 'new'}
            route={route}
            escalationPolicies={escalationPolicies}
            teams={teams}
            incidentTypeOptions={incidentTypeOptions}
            onClose={() => onOpenChange(false)}
            onSaved={onSaved}
          />
        )}
      </SheetContent>
    </Sheet>
  )
}

interface AlertRouteEditorFormProps {
  route?: AlertRoute
  escalationPolicies: PagingReference[]
  teams: PagingReference[]
  incidentTypeOptions: IncidentTypeOption[]
  onClose: () => void
  onSaved: (route: AlertRoute) => void
}

function AlertRouteEditorForm({
  route,
  escalationPolicies,
  teams,
  incidentTypeOptions,
  onClose,
  onSaved,
}: Readonly<AlertRouteEditorFormProps>) {
  const isEditing = route !== undefined
  const queryClient = useQueryClient()
  const {toast} = useToast()

  const [form, setForm] = useState<AlertRouteFormState>(() =>
    route ? alertRouteToForm(route) : createEmptyAlertRouteForm()
  )
  // Tracked separately from the form so a 409 recovery can rebase edits onto the
  // latest revision without discarding the author's in-progress changes.
  const [expectedRevision, setExpectedRevision] = useState<number | undefined>(route?.revision)
  const [conflictRoute, setConflictRoute] = useState<AlertRoute | null>(null)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [historyToken, setHistoryToken] = useState(0)

  const validationErrors = validateAlertRouteForm(form)
  const patch = (partial: Partial<AlertRouteFormState>) => setForm((prev) => ({...prev, ...partial}))

  const previewMutation = useMutation({
    mutationFn: (request: AlertRoutePreviewRequest) => api.previewAlertRoutes(request),
  })

  const saveMutation = useMutation({
    // The variable is the expectedRevision to submit — the tracked value for a
    // normal save, or the freshly fetched revision when overwriting a conflict.
    mutationFn: (revision: number | undefined) => {
      const request = formToRequest(form, isEditing ? revision : undefined)
      return isEditing && route ? api.updateAlertRoute(route.id, request) : api.createAlertRoute(request)
    },
    onSuccess: (saved) => {
      queryClient.invalidateQueries({queryKey: ALERT_ROUTES_QUERY_KEY})
      setConflictRoute(null)
      setSaveError(null)
      toast({
        title: isEditing ? 'Route updated' : 'Route created',
        description: `“${saved.name}” has been saved.`,
      })
      onSaved(saved)
      onClose()
    },
    onError: async (error: Error) => {
      if (isEditing && route && isConflictError(error)) {
        // Someone else changed this route since it was opened. Fetch the latest so
        // the author can rebase or overwrite — their edits stay in the form.
        try {
          const latest = await api.getAlertRoute(route.id)
          setConflictRoute(latest)
          setSaveError(null)
        } catch {
          setSaveError(
            'This route changed since you opened it, and the latest version could not be loaded. Reopen the editor to continue.'
          )
        }
        return
      }
      setSaveError(error.message)
    },
  })

  const submit = () => {
    if (validationErrors.length > 0) return
    setSaveError(null)
    saveMutation.mutate(expectedRevision)
  }

  const reloadLatest = () => {
    if (!conflictRoute) return
    setForm(alertRouteToForm(conflictRoute))
    setExpectedRevision(conflictRoute.revision)
    setConflictRoute(null)
    setHistoryToken((token) => token + 1)
  }

  const overwriteLatest = () => {
    if (!conflictRoute) return
    setExpectedRevision(conflictRoute.revision)
    saveMutation.mutate(conflictRoute.revision)
  }

  const busy = saveMutation.isPending
  const incidentSummary = describeIncidentSummary(form)

  return (
    <>
      <SheetHeader className="space-y-1 border-b px-6 py-4 text-left">
        <SheetTitle>{isEditing ? 'Edit alert route' : 'New alert route'}</SheetTitle>
        <SheetDescription>
          Routes are evaluated in order; the first route whose conditions match handles the alert.
        </SheetDescription>
      </SheetHeader>

      <div className="min-h-0 flex-1 space-y-6 overflow-y-auto px-6 py-5">
        {conflictRoute && (
          <ConflictBanner
            latestName={conflictRoute.name}
            latestRevision={conflictRoute.revision}
            busy={busy}
            onReload={reloadLatest}
            onOverwrite={overwriteLatest}
          />
        )}

        <section className="space-y-3">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="route-name">Name</Label>
              <Input
                id="route-name"
                className="h-8"
                value={form.name}
                placeholder="Payments — page platform"
                onChange={(event) => patch({name: event.target.value})}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="route-key">Key</Label>
              <Input
                id="route-key"
                className="h-8 font-mono"
                value={form.key}
                placeholder="payments-platform"
                onChange={(event) => patch({key: event.target.value})}
              />
            </div>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="route-description">Description (optional)</Label>
            <Textarea
              id="route-description"
              rows={2}
              value={form.description ?? ''}
              placeholder="What this route is for"
              onChange={(event) => patch({description: event.target.value})}
            />
          </div>
          <label className="flex items-center justify-between gap-4 rounded-lg border p-3">
            <span className="min-w-0">
              <span className="block text-sm font-medium">Route enabled</span>
              <span className="mt-0.5 block text-xs text-muted-foreground">
                Disabled routes are skipped during evaluation.
              </span>
            </span>
            <Switch
              checked={form.enabled}
              onCheckedChange={(enabled) => patch({enabled})}
              aria-label="Route enabled"
            />
          </label>
        </section>

        <EditorSection title="Conditions" description="OR across groups, AND within a group.">
          <div className="flex items-start justify-between gap-3 rounded-lg border border-info-border bg-info-bg p-3 text-xs">
            <div>
              <p className="font-medium text-info-fg">Need a standard high-severity path?</p>
              <p className="mt-0.5 text-info-fg/80">
                Opt in to page once per group and create triage incidents for P0–P2 alerts.
              </p>
            </div>
            <Button
              type="button"
              variant="outline"
              size="sm"
              className="h-8 shrink-0"
              onClick={() => setForm((current) => applyP0P2TriagePreset(current))}
            >
              <WandSparkles className="mr-1.5 h-3.5 w-3.5" />
              Use P0–P2 preset
            </Button>
          </div>
          <AlertRouteConditionsSection
            groups={form.conditionGroups}
            onChange={(conditionGroups) => patch({conditionGroups})}
          />
        </EditorSection>

        <EditorSection title="Paging" description="Who gets paged when this route matches.">
          <AlertRoutePagingSection
            paging={form.paging}
            escalationPolicies={escalationPolicies}
            teams={teams}
            onChange={(paging) => patch({paging})}
          />
        </EditorSection>

        {form.paging.mode !== 'NONE' && !form.incident.create && (
          <div className="flex items-start gap-2 rounded-lg border border-warning-border bg-warning-bg p-3 text-xs text-warning-fg">
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            <p>
              This is a paging-only route. It will page the configured targets and will not create an
              incident. Because routes use first match wins, a matching alert will not continue to a later route.
            </p>
          </div>
        )}

        <EditorSection title="Grouping" description="How matched alerts fold together.">
          <AlertRouteGroupingSection
            grouping={form.grouping}
            onChange={(grouping) => patch({grouping})}
          />
        </EditorSection>

        <EditorSection title="Incident & recovery" description="Incident creation and recovery automation.">
          <AlertRouteIncidentSection
            incident={form.incident}
            recovery={form.recovery}
            incidentTypeOptions={incidentTypeOptions}
            onIncidentChange={(incident) => patch({incident})}
            onRecoveryChange={(recovery) => patch({recovery})}
          />
        </EditorSection>

        <div className="rounded-lg border bg-muted/30 p-3 text-xs">
          <p className="font-medium">Save summary</p>
          <p className="mt-1 text-muted-foreground">
            {form.enabled ? 'Enabled' : 'Disabled'} route · {form.paging.mode === 'NONE' ? 'no paging' : 'pages responders'} ·{' '}
            {incidentSummary}
          </p>
        </div>

        <details className="rounded-lg border">
          <summary className="cursor-pointer px-4 py-2.5 text-sm font-semibold">Preview</summary>
          <div className="border-t px-4 py-3">
            <p className="mb-3 text-xs text-muted-foreground">
              Preview runs against the currently saved routes to show which one handles a sample alert
              and where routes overlap. Save to include your edits.
            </p>
            <AlertRoutePreviewPanel
              onRun={(request) => previewMutation.mutate(request)}
              result={previewMutation.data}
              isLoading={previewMutation.isPending}
              error={previewMutation.error?.message}
              editingRouteId={route?.id}
            />
          </div>
        </details>

        {isEditing && route && (
          <details className="rounded-lg border">
            <summary className="cursor-pointer px-4 py-2.5 text-sm font-semibold">Revision history</summary>
            <div className="border-t px-4 py-3">
              <AlertRouteRevisionHistory routeId={route.id} refreshToken={historyToken} />
            </div>
          </details>
        )}

        {validationErrors.length > 0 && (
          <div className="rounded-lg border border-warning-border bg-warning-bg p-3 text-xs text-warning-fg">
            <p className="mb-1 font-medium">Resolve these before saving:</p>
            <ul className="list-disc space-y-0.5 pl-4">
              {validationErrors.map((error) => (
                <li key={error}>{error}</li>
              ))}
            </ul>
          </div>
        )}

        {saveError && (
          <div className="flex items-start gap-2 rounded-lg border border-danger-border bg-danger-bg p-3 text-xs text-danger-fg">
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            <span>{saveError}</span>
          </div>
        )}
      </div>

      <div className="flex shrink-0 items-center justify-end gap-2 border-t px-6 py-3">
        <Button variant="outline" size="sm" onClick={onClose} disabled={busy}>
          Cancel
        </Button>
        <Button size="sm" onClick={submit} disabled={busy || validationErrors.length > 0}>
          {busy && <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />}
          {isEditing ? 'Save changes' : 'Create route'}
        </Button>
      </div>
    </>
  )
}

function EditorSection({
  title,
  description,
  children,
}: Readonly<{title: string; description: string; children: React.ReactNode}>) {
  return (
    <section className="space-y-3">
      <div>
        <h3 className="text-sm font-semibold">{title}</h3>
        <p className="text-xs text-muted-foreground">{description}</p>
      </div>
      {children}
    </section>
  )
}

function describeIncidentSummary(form: AlertRouteFormState): string {
  if (!form.incident.create) return 'does not create incidents'
  if (form.incident.mode === 'ACTIVE') {
    const severity = form.incident.severity ? ` at ${form.incident.severity}` : ''
    return `creates an active incident${severity}`
  }
  if (form.incident.severity === 'ALERT_PRIORITY') {
    return 'creates triage incidents with severity derived from P0–P2 priority'
  }
  return 'creates a triage incident'
}

interface ConflictBannerProps {
  latestName: string
  latestRevision: number
  busy: boolean
  onReload: () => void
  onOverwrite: () => void
}

function ConflictBanner({latestName, latestRevision, busy, onReload, onOverwrite}: Readonly<ConflictBannerProps>) {
  return (
    <div className="space-y-2 rounded-lg border border-warning-border bg-warning-bg p-3 text-xs text-warning-fg">
      <div className="flex items-start gap-2">
        <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
        <div>
          <p className="font-medium">This route changed since you opened it.</p>
          <p className="mt-0.5">
            “{latestName}” is now at revision {latestRevision}. Your edits are still here. Reload the
            latest version to start from it (discards your edits), or save over it to keep yours.
          </p>
        </div>
      </div>
      <div className="flex gap-2 pl-6">
        <Button variant="outline" size="sm" className="h-7" onClick={onReload} disabled={busy}>
          Reload latest
        </Button>
        <Button size="sm" className="h-7" onClick={onOverwrite} disabled={busy}>
          Save over their changes
        </Button>
      </div>
    </div>
  )
}
