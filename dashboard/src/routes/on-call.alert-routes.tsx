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

import {createFileRoute} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {useState} from 'react'
import {
  ArrowDown,
  ArrowUp,
  Bell,
  GitBranch,
  Layers,
  Pencil,
  Plus,
  Route as RouteIcon,
  ShieldAlert,
  Trash2,
} from 'lucide-react'
import {api} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent} from '@/components/ui/card'
import {EmptyState} from '@/components/ui/empty-state'
import {PageHeader} from '@/components/ui/page-header'
import {Switch} from '@/components/ui/switch'
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
import {
  nativeIncidentUnavailableCopy,
  useNativeIncidentRollout,
} from '@/hooks/useNativeIncidentRollout'
import {useOnCallAdmin} from '@/hooks/useOnCallAdmin'
import type {AlertRoute} from '@/lib/api/types'
import {
  alertRouteToForm,
  describeCondition,
  formToRequest,
  formatWindow,
  isConflictError,
  pagingModeLabel,
} from '@/components/on-call/alert-routes/alertRouteModel'
import {ALERT_ROUTES_QUERY_KEY} from '@/components/on-call/alert-routes/alertRouteQueries'
import {AlertRouteEditorSheet} from '@/components/on-call/alert-routes/AlertRouteEditorSheet'
import type {IncidentTypeOption} from '@/components/on-call/alert-routes/AlertRouteIncidentSection'

export const Route = createFileRoute('/on-call/alert-routes')({
  component: AlertRoutesPage,
})

const STALE_ROUTE_MESSAGE =
  'This route changed since the page loaded. The latest routes have been reloaded — try again.'

function AlertRoutesPage() {
  const rollout = useNativeIncidentRollout()
  const {isAdmin} = useOnCallAdmin()
  const queryClient = useQueryClient()
  const {toast} = useToast()

  const [editor, setEditor] = useState<{open: boolean; route?: AlertRoute}>({open: false})
  const [pendingDelete, setPendingDelete] = useState<AlertRoute | null>(null)

  const routesQuery = useQuery({
    queryKey: ALERT_ROUTES_QUERY_KEY,
    queryFn: () => api.getAlertRoutes(),
    enabled: rollout.enabled,
  })
  const escalationPoliciesQuery = useQuery({
    queryKey: ['escalation-policies'],
    queryFn: () => api.getEscalationPolicies(),
    enabled: rollout.enabled,
  })
  const teamsQuery = useQuery({
    queryKey: ['organization-teams'],
    queryFn: () => api.getOrganizationTeams(),
    enabled: rollout.enabled,
  })
  const incidentTypesQuery = useQuery({
    queryKey: ['incident-type-definitions'],
    queryFn: () => api.getIncidentTypes(),
    enabled: rollout.enabled,
  })

  const routes = [...(routesQuery.data ?? [])].sort((a, b) => a.position - b.position)
  const escalationPolicies = (escalationPoliciesQuery.data ?? []).map((policy) => ({
    id: policy.id,
    name: policy.name,
  }))
  const teams = (teamsQuery.data ?? []).map((team) => ({id: team.id, name: team.name}))
  const incidentTypeOptions: IncidentTypeOption[] = (incidentTypesQuery.data ?? [])
    .filter((type) => type.enabled)
    .map((type) => ({value: type.key, label: type.name}))

  const invalidateRoutes = () => queryClient.invalidateQueries({queryKey: ALERT_ROUTES_QUERY_KEY})

  const onMutationError = (error: Error) => {
    if (isConflictError(error)) {
      invalidateRoutes()
      toast({title: 'Routes changed', description: STALE_ROUTE_MESSAGE, variant: 'destructive'})
      return
    }
    toast({title: 'Error', description: error.message, variant: 'destructive'})
  }

  const toggleMutation = useMutation({
    mutationFn: (route: AlertRoute) =>
      api.updateAlertRoute(
        route.id,
        formToRequest({...alertRouteToForm(route), enabled: !route.enabled}, route.revision)
      ),
    onSuccess: (updated) => {
      invalidateRoutes()
      toast({
        title: updated.enabled ? 'Route enabled' : 'Route disabled',
        description: `“${updated.name}” is now ${updated.enabled ? 'active' : 'skipped'}.`,
      })
    },
    onError: onMutationError,
  })

  const reorderMutation = useMutation({
    mutationFn: (orderedIds: string[]) => {
      const byId = new Map(routes.map((route) => [route.id, route]))
      return api.reorderAlertRoutes({
        routes: orderedIds.map((id) => ({id, expectedRevision: byId.get(id)!.revision})),
      })
    },
    onSuccess: (updated) => {
      queryClient.setQueryData(ALERT_ROUTES_QUERY_KEY, updated)
      invalidateRoutes()
    },
    onError: onMutationError,
  })

  const deleteMutation = useMutation({
    mutationFn: (route: AlertRoute) => api.deleteAlertRoute(route.id, route.revision),
    onSuccess: (_result, route) => {
      setPendingDelete(null)
      invalidateRoutes()
      toast({title: 'Route deleted', description: `“${route.name}” has been removed.`})
    },
    onError: (error: Error) => {
      setPendingDelete(null)
      onMutationError(error)
    },
  })

  const move = (index: number, direction: -1 | 1) => {
    const target = index + direction
    if (target < 0 || target >= routes.length) return
    const orderedIds = routes.map((route) => route.id)
    ;[orderedIds[index], orderedIds[target]] = [orderedIds[target], orderedIds[index]]
    reorderMutation.mutate(orderedIds)
  }

  const busy = toggleMutation.isPending || reorderMutation.isPending || deleteMutation.isPending

  if (rollout.isLoading) {
    return (
      <div className="flex items-center justify-center py-16">
        <div className="h-8 w-8 animate-spin rounded-full border-2 border-muted border-t-primary" />
      </div>
    )
  }

  if (!rollout.enabled) {
    const copy = nativeIncidentUnavailableCopy(rollout)
    return (
      <div className="space-y-4">
        <PageHeader icon={RouteIcon} title="Alert routes" description="Route alerts to responders and incidents" />
        <EmptyState icon={ShieldAlert} title={copy.title} description={copy.description} />
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <PageHeader
        icon={RouteIcon}
        title="Alert routes"
        description="Route alerts to responders and incidents, in priority order"
        actions={
          isAdmin ? (
            <Button size="sm" onClick={() => setEditor({open: true, route: undefined})}>
              <Plus className="mr-1.5 h-4 w-4" />
              New route
            </Button>
          ) : undefined
        }
      />

      {!isAdmin && (
        <div className="rounded-lg border bg-muted/30 px-3 py-2 text-xs text-muted-foreground">
          You can review alert routes here. Editing routes requires an administrator.
        </div>
      )}

      {routesQuery.isLoading && (
        <div className="flex items-center justify-center py-10">
          <div className="h-8 w-8 animate-spin rounded-full border-2 border-muted border-t-primary" />
        </div>
      )}

      {!routesQuery.isLoading && routes.length === 0 && (
        <EmptyState
          icon={RouteIcon}
          title="No alert routes yet"
          description={
            isAdmin
              ? 'Create a route to page responders and open incidents when alerts match your conditions.'
              : 'Alert routes will appear here once an administrator creates them.'
          }
          action={
            isAdmin ? (
              <Button size="sm" onClick={() => setEditor({open: true, route: undefined})}>
                <Plus className="mr-1.5 h-4 w-4" />
                New route
              </Button>
            ) : undefined
          }
        />
      )}

      {routes.length > 0 && (
        <div className="space-y-2">
          {routes.map((route, index) => (
            <AlertRouteRow
              key={route.id}
              route={route}
              index={index}
              total={routes.length}
              isAdmin={isAdmin}
              busy={busy}
              onEdit={() => setEditor({open: true, route})}
              onToggle={() => toggleMutation.mutate(route)}
              onMoveUp={() => move(index, -1)}
              onMoveDown={() => move(index, 1)}
              onDelete={() => setPendingDelete(route)}
            />
          ))}
        </div>
      )}

      <AlertRouteEditorSheet
        open={editor.open}
        onOpenChange={(open) => setEditor((prev) => ({...prev, open}))}
        route={editor.route}
        escalationPolicies={escalationPolicies}
        teams={teams}
        incidentTypeOptions={incidentTypeOptions}
        onSaved={() => setEditor({open: false})}
      />

      <AlertDialog open={pendingDelete !== null} onOpenChange={(open) => !open && setPendingDelete(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete this alert route?</AlertDialogTitle>
            <AlertDialogDescription>
              {pendingDelete
                ? `“${pendingDelete.name}” will stop routing alerts. This cannot be undone.`
                : ''}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleteMutation.isPending}>Cancel</AlertDialogCancel>
            <AlertDialogAction
              onClick={(event) => {
                event.preventDefault()
                if (pendingDelete) deleteMutation.mutate(pendingDelete)
              }}
              disabled={deleteMutation.isPending}
            >
              {deleteMutation.isPending ? 'Deleting…' : 'Delete route'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

interface AlertRouteRowProps {
  route: AlertRoute
  index: number
  total: number
  isAdmin: boolean
  busy: boolean
  onEdit: () => void
  onToggle: () => void
  onMoveUp: () => void
  onMoveDown: () => void
  onDelete: () => void
}

function AlertRouteRow({
  route,
  index,
  total,
  isAdmin,
  busy,
  onEdit,
  onToggle,
  onMoveUp,
  onMoveDown,
  onDelete,
}: Readonly<AlertRouteRowProps>) {
  return (
    <Card className={route.enabled ? undefined : 'opacity-70'}>
      <CardContent className="flex items-start gap-3 p-3">
        {isAdmin && (
          <div className="flex flex-col">
            <Button
              variant="ghost"
              size="icon"
              className="h-6 w-6"
              disabled={busy || index === 0}
              onClick={onMoveUp}
              aria-label={`Move ${route.name} up`}
            >
              <ArrowUp className="h-3.5 w-3.5" />
            </Button>
            <span className="text-center text-[11px] font-medium tabular-nums text-muted-foreground">
              {index + 1}
            </span>
            <Button
              variant="ghost"
              size="icon"
              className="h-6 w-6"
              disabled={busy || index === total - 1}
              onClick={onMoveDown}
              aria-label={`Move ${route.name} down`}
            >
              <ArrowDown className="h-3.5 w-3.5" />
            </Button>
          </div>
        )}

        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="text-sm font-semibold">{route.name}</h3>
            <code className="rounded bg-muted px-1.5 py-0.5 text-[11px] text-muted-foreground">{route.key}</code>
            <Badge variant={route.enabled ? 'success' : 'neutral'} size="sm">
              {route.enabled ? 'Enabled' : 'Disabled'}
            </Badge>
          </div>
          {route.description && (
            <p className="mt-0.5 line-clamp-1 text-xs text-muted-foreground">{route.description}</p>
          )}
          <RouteSummary route={route} />
        </div>

        {isAdmin && (
          <div className="flex shrink-0 items-center gap-1">
            <Switch
              checked={route.enabled}
              onCheckedChange={onToggle}
              disabled={busy}
              aria-label={`Toggle ${route.name}`}
            />
            <Button variant="ghost" size="icon" className="h-8 w-8" onClick={onEdit} aria-label={`Edit ${route.name}`}>
              <Pencil className="h-3.5 w-3.5" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              className="h-8 w-8"
              onClick={onDelete}
              disabled={busy}
              aria-label={`Delete ${route.name}`}
            >
              <Trash2 className="h-3.5 w-3.5" />
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function RouteSummary({route}: Readonly<{route: AlertRoute}>) {
  const firstGroup = route.conditionGroups[0]
  const conditionText = firstGroup?.conditions.map(describeCondition).join(' and ') ?? 'No conditions'
  const extraGroups = route.conditionGroups.length - 1
  const incidentLabel = route.incident.mode === 'ACTIVE' ? 'active' : 'triage'

  return (
    <div className="mt-1.5 space-y-1 text-xs text-muted-foreground">
      <p className="line-clamp-1">
        <span className="font-medium text-foreground/70">When</span> {conditionText}
        {extraGroups > 0 && ` (+${extraGroups} more group${extraGroups > 1 ? 's' : ''})`}
      </p>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="inline-flex items-center gap-1">
          <Bell className="h-3 w-3" />
          {pagingModeLabel(route.paging.mode)}
          {route.paging.mode !== 'NONE' && route.paging.targets.length > 0 &&
            ` · ${route.paging.targets.length} target${route.paging.targets.length > 1 ? 's' : ''}`}
        </span>
        <span className="inline-flex items-center gap-1">
          <Layers className="h-3 w-3" />
          {route.grouping.behavior === 'AUTOMATIC' ? 'Auto-group' : 'Suggested grouping'}
          {' · '}
          {formatWindow(route.grouping.windowSeconds)}
        </span>
        <span className="inline-flex items-center gap-1">
          <GitBranch className="h-3 w-3" />
          {route.incident.create ? `Incident (${incidentLabel})` : 'No incident'}
        </span>
      </div>
    </div>
  )
}
