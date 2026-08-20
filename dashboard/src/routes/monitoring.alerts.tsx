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

// ─────────────────────────────────────────────────────────────────────────────
// Infrastructure alert rules. Replaces the Alerts tab that used to hang off the
// (now removed) host detail page, so threshold configuration is reachable on its
// own instead of being buried three clicks into one host.
//
// Two profiles exist: org-wide shared defaults, and per-host overrides. A host
// follows one or the other — switching a host to "custom" seeds its rules from
// the shared defaults and then diverges.
//
// The write API is keyed by host (`/monitor/hosts/{id}/alerts?scope=global`),
// so editing shared defaults still needs some host to carry the request; the
// first known host acts as that carrier.
// ─────────────────────────────────────────────────────────────────────────────

import {useMemo, useState} from 'react'
import {createFileRoute, Link, useNavigate} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {
  BellRing,
  Check,
  Globe2,
  Pencil,
  Plus,
  Server,
  Settings2,
  ShieldCheck,
  SlidersHorizontal,
  Trash2,
} from 'lucide-react'

import {api, type DdHostResponse, type HostAlert, type HostAlertConfig} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
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
import {EmptyState} from '@/components/ui/empty-state'
import {PageHeader} from '@/components/ui/page-header'
import {SectionCard} from '@/components/ui/section-card'
import {StatCard} from '@/components/ui/stat-card'
import {StatusDot} from '@/components/ui/status-dot'
import {Switch} from '@/components/ui/switch'
import {cn, formatRelativeTime} from '@/lib/utils'
import {
  AlertRuleDialog,
  type AlertRuleFormValues,
} from '@/components/monitoring/alerts/AlertRuleDialog'
import {
  alertMetricLabel,
  alertMetricTone,
  formatAlertDuration,
  formatAlertThreshold,
} from '@/components/monitoring/alerts/alertMetrics'

type AlertScope = 'global' | 'host'

type AlertsSearch = Readonly<{
  /** Selected host profile; absent means the shared defaults. */
  host?: string
}>

export const Route = createFileRoute('/monitoring/alerts')({
  validateSearch: (search: Record<string, unknown>): AlertsSearch => ({
    host: typeof search.host === 'string' && search.host ? search.host : undefined,
  }),
  component: AlertRulesPage,
})

const hostAlertConfigQueryKey = (hostId: string) => ['host-alert-config', hostId] as const

function AlertRulesPage() {
  const navigate = useNavigate({from: Route.fullPath})
  const {host: selectedHostId} = Route.useSearch()
  const queryClient = useQueryClient()

  const [isRuleDialogOpen, setIsRuleDialogOpen] = useState(false)
  const [editingRule, setEditingRule] = useState<HostAlert | null>(null)
  const [pendingDelete, setPendingDelete] = useState<HostAlert | null>(null)

  const {data: hostsData, isLoading: hostsLoading} = useQuery({
    queryKey: ['hosts'],
    queryFn: () => api.getHosts(),
    enabled: api.isAuthenticated(),
  })

  const hosts: DdHostResponse[] = useMemo(
    () => [...(hostsData?.hosts ?? [])].sort((a, b) => a.hostname.localeCompare(b.hostname)),
    [hostsData?.hosts]
  )

  // Shared defaults still travel over a host-scoped endpoint, so fall back to
  // the first host as the carrier when no host is explicitly selected.
  const carrierHostId = selectedHostId ?? hosts[0]?.id
  const viewingGlobal = !selectedHostId
  const selectedHost = hosts.find((host) => host.id === selectedHostId)

  const {data: alertConfig, isLoading: configLoading} = useQuery({
    queryKey: hostAlertConfigQueryKey(carrierHostId ?? ''),
    queryFn: () => api.getHostAlertConfig(carrierHostId as string),
    enabled: api.isAuthenticated() && Boolean(carrierHostId),
  })

  const hostScope: AlertScope = alertConfig?.scope === 'global' ? 'global' : 'host'
  // A host that follows the shared defaults shows those rules, read-only.
  const followsShared = !viewingGlobal && hostScope === 'global'
  const editScope: AlertScope = viewingGlobal ? 'global' : 'host'

  const rules = useMemo(() => {
    if (!alertConfig) return []
    const source =
      viewingGlobal || followsShared ? alertConfig.globalAlerts : alertConfig.hostAlerts
    return [...source].sort(
      (a, b) => a.metric.localeCompare(b.metric) || a.threshold - b.threshold
    )
  }, [alertConfig, viewingGlobal, followsShared])

  const invalidate = () => {
    if (carrierHostId) {
      queryClient.invalidateQueries({queryKey: hostAlertConfigQueryKey(carrierHostId)})
    }
  }

  const scopeMutation = useMutation({
    mutationFn: (scope: AlertScope) => api.updateHostAlertScope(carrierHostId as string, scope),
    onSuccess: invalidate,
  })

  const createMutation = useMutation({
    mutationFn: (values: AlertRuleFormValues) =>
      api.createHostAlert(
        carrierHostId as string,
        {
          metric: values.metric,
          condition: values.condition,
          threshold: values.threshold,
          durationSeconds: values.durationSeconds,
          enabled: values.enabled,
          alertPriority: values.alertPriority ?? undefined,
        },
        editScope
      ),
    onSuccess: () => {
      invalidate()
      setIsRuleDialogOpen(false)
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({rule, values}: {rule: HostAlert; values: AlertRuleFormValues}) =>
      api.updateHostAlert(
        carrierHostId as string,
        rule.id,
        {
          metric: values.metric,
          condition: values.condition,
          threshold: values.threshold,
          durationSeconds: values.durationSeconds,
          enabled: values.enabled,
          alertPriority: values.alertPriority,
        },
        rule.scope as AlertScope
      ),
    onSuccess: () => {
      invalidate()
      setIsRuleDialogOpen(false)
      setEditingRule(null)
    },
  })

  const toggleMutation = useMutation({
    mutationFn: ({rule, enabled}: {rule: HostAlert; enabled: boolean}) =>
      api.updateHostAlert(
        carrierHostId as string,
        rule.id,
        {enabled},
        rule.scope as AlertScope
      ),
    // Reflect the switch immediately; the list is otherwise a full refetch away.
    onMutate: ({rule, enabled}) => {
      const key = hostAlertConfigQueryKey(carrierHostId ?? '')
      const previous = queryClient.getQueryData<HostAlertConfig>(key)
      queryClient.setQueryData<HostAlertConfig>(key, (current) =>
        current ? applyRuleUpdate(current, {...rule, enabled}) : current
      )
      return {previous, key}
    },
    onError: (_error, _variables, context) => {
      if (context?.previous) queryClient.setQueryData(context.key, context.previous)
    },
    onSettled: invalidate,
  })

  const deleteMutation = useMutation({
    mutationFn: (rule: HostAlert) =>
      api.deleteHostAlert(carrierHostId as string, rule.id, rule.scope as AlertScope),
    onSuccess: () => {
      invalidate()
      setPendingDelete(null)
    },
  })

  const enabledCount = rules.filter((rule) => rule.enabled).length
  const scopeLabel = viewingGlobal
    ? 'shared defaults'
    : (selectedHost?.hostname ?? 'this host')

  const isLoading = hostsLoading || (Boolean(carrierHostId) && configLoading)
  const canEdit = viewingGlobal || !followsShared

  const openCreate = () => {
    setEditingRule(null)
    setIsRuleDialogOpen(true)
  }

  const openEdit = (rule: HostAlert) => {
    setEditingRule(rule)
    setIsRuleDialogOpen(true)
  }

  return (
    <div className="container mx-auto space-y-4 px-4 py-4">
      <PageHeader
        icon={BellRing}
        eyebrow="Infrastructure"
        title="Alert rules"
        description="Thresholds that open an on-call alert when a host metric crosses a limit."
        actions={
          <>
            <Button variant="outline" size="sm" className="gap-1.5" asChild>
              <Link to="/settings" search={{tab: 'notifications'}}>
                <Settings2 className="h-3.5 w-3.5" />
                Notification channels
              </Link>
            </Button>
            <Button size="sm" className="gap-1.5" onClick={openCreate} disabled={!canEdit}>
              <Plus className="h-3.5 w-3.5" />
              New rule
            </Button>
          </>
        }
      />

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="Active rules"
          value={isLoading ? '—' : enabledCount}
          icon={ShieldCheck}
          tone="success"
          subtitle={`of ${rules.length} in ${viewingGlobal ? 'shared defaults' : 'this profile'}`}
        />
        <StatCard
          label="Hosts"
          value={hostsLoading ? '—' : hosts.length}
          icon={Server}
          tone="info"
          subtitle="reporting to this org"
        />
        <StatCard
          label="Profile"
          value={viewingGlobal ? 'Shared' : followsShared ? 'Shared' : 'Custom'}
          icon={viewingGlobal ? Globe2 : SlidersHorizontal}
          tone={viewingGlobal || followsShared ? 'accent' : 'warning'}
          subtitle={viewingGlobal ? 'org-wide defaults' : scopeLabel}
        />
        <StatCard
          label="Paging rules"
          value={isLoading ? '—' : rules.filter((rule) => isPagingPriority(rule.alertPriority)).length}
          icon={BellRing}
          tone="danger"
          subtitle="P0–P2 override set"
        />
      </div>

      <div className="grid gap-3 lg:grid-cols-[16rem_minmax(0,1fr)]">
        <SectionCard title="Profiles" icon={Globe2} iconTone="info" flushBody>
          <nav className="max-h-[28rem] overflow-y-auto py-1">
            <ProfileRow
              active={viewingGlobal}
              icon={Globe2}
              label="Shared defaults"
              detail="Applies to every host set to shared"
              onSelect={() => navigate({search: {}})}
            />
            {hosts.length > 0 && (
              <p className="px-3 pb-1 pt-2.5 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
                Hosts
              </p>
            )}
            {hostsLoading &&
              Array.from({length: 3}).map((_, index) => (
                <div key={index} className="px-3 py-2">
                  <div className="h-4 w-full animate-pulse rounded bg-muted" />
                </div>
              ))}
            {hosts.map((host) => (
              <ProfileRow
                key={host.id}
                active={host.id === selectedHostId}
                icon={Server}
                tone={host.isOnline ? 'success' : 'neutral'}
                label={host.hostname}
                detail={host.platform || host.os}
                onSelect={() => navigate({search: {host: host.id}})}
              />
            ))}
            {!hostsLoading && hosts.length === 0 && (
              <p className="px-3 py-6 text-center text-xs text-muted-foreground">
                No hosts are reporting yet.
              </p>
            )}
          </nav>
        </SectionCard>

        <SectionCard
          title={viewingGlobal ? 'Shared defaults' : scopeLabel}
          icon={viewingGlobal ? Globe2 : Server}
          iconTone={viewingGlobal ? 'info' : 'accent'}
          count={isLoading ? undefined : rules.length}
          actions={
            !viewingGlobal &&
            carrierHostId && (
              <div className="flex items-center rounded-md border p-0.5">
                <ScopeButton
                  active={followsShared}
                  pending={scopeMutation.isPending}
                  onClick={() => scopeMutation.mutate('global')}
                >
                  Shared
                </ScopeButton>
                <ScopeButton
                  active={!followsShared}
                  pending={scopeMutation.isPending}
                  onClick={() => scopeMutation.mutate('host')}
                >
                  Custom
                </ScopeButton>
              </div>
            )
          }
          flushBody
        >
          {followsShared && (
            <p className="flex items-start gap-2 border-b bg-muted/40 px-4 py-2.5 text-xs text-muted-foreground">
              <Globe2 className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              <span>
                {scopeLabel} follows the shared defaults. Switch to{' '}
                <span className="font-medium text-foreground">Custom</span> to give it its own
                thresholds.
              </span>
            </p>
          )}

          {isLoading && (
            <div className="space-y-2 px-4 py-3">
              {Array.from({length: 4}).map((_, index) => (
                <div key={index} className="h-12 w-full animate-pulse rounded bg-muted" />
              ))}
            </div>
          )}

          {!isLoading && rules.length === 0 && (
            <div className="px-4 py-4">
              <EmptyState
                icon={BellRing}
                title="No rules yet"
                description={
                  hosts.length === 0
                    ? 'Connect a host with the agent to start configuring threshold alerts.'
                    : 'Add a threshold rule to open an on-call alert when this metric drifts.'
                }
                action={
                  canEdit && hosts.length > 0 ? (
                    <Button size="sm" className="gap-1.5" onClick={openCreate}>
                      <Plus className="h-3.5 w-3.5" />
                      New rule
                    </Button>
                  ) : undefined
                }
              />
            </div>
          )}

          {!isLoading && rules.length > 0 && (
            <ul className="divide-y">
              {rules.map((rule) => (
                <RuleRow
                  key={`${rule.scope}-${rule.id}`}
                  rule={rule}
                  readOnly={!canEdit}
                  togglePending={toggleMutation.isPending}
                  onToggle={(enabled) => toggleMutation.mutate({rule, enabled})}
                  onEdit={() => openEdit(rule)}
                  onDelete={() => setPendingDelete(rule)}
                />
              ))}
            </ul>
          )}
        </SectionCard>
      </div>

      <AlertRuleDialog
        open={isRuleDialogOpen}
        onOpenChange={(open) => {
          setIsRuleDialogOpen(open)
          if (!open) setEditingRule(null)
        }}
        rule={editingRule}
        scopeLabel={scopeLabel}
        pending={createMutation.isPending || updateMutation.isPending}
        onSubmit={(values) => {
          if (editingRule) updateMutation.mutate({rule: editingRule, values})
          else createMutation.mutate(values)
        }}
      />

      <AlertDialog
        open={Boolean(pendingDelete)}
        onOpenChange={(open) => !open && setPendingDelete(null)}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete this rule?</AlertDialogTitle>
            <AlertDialogDescription>
              {pendingDelete
                ? `${alertMetricLabel(pendingDelete.metric)} ${pendingDelete.condition} ${formatAlertThreshold(pendingDelete.metric, pendingDelete.threshold)} will stop being evaluated for ${scopeLabel}.`
                : null}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              disabled={deleteMutation.isPending}
              onClick={(event) => {
                event.preventDefault()
                if (pendingDelete) deleteMutation.mutate(pendingDelete)
              }}
            >
              {deleteMutation.isPending ? 'Deleting…' : 'Delete rule'}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

function isPagingPriority(priority?: string | null): boolean {
  return priority === 'P0' || priority === 'P1' || priority === 'P2'
}

/** Swap one rule wherever it appears in a cached config. */
function applyRuleUpdate(config: HostAlertConfig, updated: HostAlert): HostAlertConfig {
  const swap = (list: HostAlert[]) =>
    list.map((rule) =>
      rule.id === updated.id && rule.scope === updated.scope ? updated : rule
    )
  return {
    ...config,
    globalAlerts: swap(config.globalAlerts),
    hostAlerts: swap(config.hostAlerts),
    effectiveAlerts: swap(config.effectiveAlerts),
  }
}

type ProfileRowProps = Readonly<{
  active: boolean
  icon: React.ComponentType<{className?: string}>
  label: string
  detail?: string
  tone?: 'success' | 'neutral'
  onSelect: () => void
}>

function ProfileRow({active, icon: Icon, label, detail, tone, onSelect}: ProfileRowProps) {
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-current={active ? 'true' : undefined}
      className={cn(
        'flex w-full items-center gap-2 px-3 py-2 text-left transition-colors',
        active ? 'bg-muted' : 'hover:bg-muted/50'
      )}
    >
      {tone ? (
        <StatusDot tone={tone} className="ml-0.5 mr-0.5" />
      ) : (
        <Icon className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />
      )}
      <span className="min-w-0 flex-1">
        <span className={cn('block truncate text-xs', active ? 'font-semibold' : 'font-medium')}>
          {label}
        </span>
        {detail && (
          <span className="block truncate text-[11px] text-muted-foreground">{detail}</span>
        )}
      </span>
      {active && <Check className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />}
    </button>
  )
}

type ScopeButtonProps = Readonly<{
  active: boolean
  pending: boolean
  onClick: () => void
  children: React.ReactNode
}>

function ScopeButton({active, pending, onClick, children}: ScopeButtonProps) {
  return (
    <Button
      type="button"
      size="sm"
      variant={active ? 'secondary' : 'ghost'}
      className="h-6 px-2 text-[11px]"
      disabled={pending || active}
      onClick={onClick}
    >
      {children}
    </Button>
  )
}

type RuleRowProps = Readonly<{
  rule: HostAlert
  readOnly: boolean
  togglePending: boolean
  onToggle: (enabled: boolean) => void
  onEdit: () => void
  onDelete: () => void
}>

function RuleRow({rule, readOnly, togglePending, onToggle, onEdit, onDelete}: RuleRowProps) {
  const tone = alertMetricTone(rule.metric)
  return (
    <li
      className={cn(
        'group flex items-center gap-3 px-4 py-2.5 transition-colors hover:bg-muted/40',
        !rule.enabled && 'opacity-60'
      )}
    >
      <Switch
        aria-label={`${rule.enabled ? 'Disable' : 'Enable'} ${alertMetricLabel(rule.metric)} rule`}
        checked={rule.enabled}
        disabled={readOnly || togglePending}
        onCheckedChange={onToggle}
        className="shrink-0"
      />

      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-1.5">
          <StatusDot tone={tone} size="sm" />
          <span className="text-xs font-medium">{alertMetricLabel(rule.metric)}</span>
          <Badge variant="outline" className="px-1.5 py-0 font-mono text-[10px]">
            {rule.condition} {formatAlertThreshold(rule.metric, rule.threshold)}
          </Badge>
          <Badge variant="neutral" className="px-1.5 py-0 text-[10px]">
            {formatAlertDuration(rule.durationSeconds)}
          </Badge>
          {rule.alertPriority && (
            <Badge
              variant={isPagingPriority(rule.alertPriority) ? 'danger' : 'info'}
              className="px-1.5 py-0 text-[10px]"
            >
              {rule.alertPriority}
            </Badge>
          )}
        </div>
        <p className="mt-0.5 text-[11px] text-muted-foreground">
          {rule.lastTriggeredAt
            ? `Last fired ${formatRelativeTime(rule.lastTriggeredAt)}`
            : 'Never fired'}
        </p>
      </div>

      {!readOnly && (
        <div className="flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100">
          <Button
            size="sm"
            variant="ghost"
            className="h-7 w-7 p-0"
            aria-label={`Edit ${alertMetricLabel(rule.metric)} rule`}
            onClick={onEdit}
          >
            <Pencil className="h-3.5 w-3.5" />
          </Button>
          <Button
            size="sm"
            variant="ghost"
            className="h-7 w-7 p-0"
            aria-label={`Delete ${alertMetricLabel(rule.metric)} rule`}
            onClick={onDelete}
          >
            <Trash2 className="h-3.5 w-3.5 text-danger-fg" />
          </Button>
        </div>
      )}
    </li>
  )
}
