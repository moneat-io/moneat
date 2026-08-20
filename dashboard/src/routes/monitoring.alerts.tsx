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
// the shared defaults and then diverges. See useAlertRules for the data layer.
// ─────────────────────────────────────────────────────────────────────────────

import {useState, type ComponentType, type ReactNode} from 'react'
import {createFileRoute, Link, useNavigate} from '@tanstack/react-router'
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

import type {DdHostResponse, HostAlert} from '@/lib/api'
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
import {AlertRuleDialog} from '@/components/monitoring/alerts/AlertRuleDialog'
import {isPagingPriority, useAlertRules} from '@/components/monitoring/alerts/useAlertRules'
import {
  alertMetricLabel,
  alertMetricTone,
  formatAlertDuration,
  formatAlertThreshold,
} from '@/components/monitoring/alerts/alertMetrics'

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

const SKELETON_ROWS = ['a', 'b', 'c', 'd'] as const

function AlertRulesPage() {
  const navigate = useNavigate({from: Route.fullPath})
  const {host: selectedHostId} = Route.useSearch()

  const [isRuleDialogOpen, setIsRuleDialogOpen] = useState(false)
  const [editingRule, setEditingRule] = useState<HostAlert | null>(null)
  const [pendingDelete, setPendingDelete] = useState<HostAlert | null>(null)

  const alerts = useAlertRules(selectedHostId)
  const {rules, viewingGlobal, followsShared, canEdit, isLoading} = alerts

  const scopeLabel = viewingGlobal
    ? 'shared defaults'
    : (alerts.selectedHost?.hostname ?? 'this host')
  const panelTitle = viewingGlobal ? 'Shared defaults' : scopeLabel

  const openCreate = () => {
    setEditingRule(null)
    setIsRuleDialogOpen(true)
  }

  const closeRuleDialog = (open: boolean) => {
    setIsRuleDialogOpen(open)
    if (!open) setEditingRule(null)
  }

  const submitRule = (values: Parameters<typeof alerts.createMutation.mutate>[0]) => {
    if (editingRule) {
      alerts.updateMutation.mutate(
        {rule: editingRule, values},
        {onSuccess: () => closeRuleDialog(false)}
      )
      return
    }
    alerts.createMutation.mutate(values, {onSuccess: () => closeRuleDialog(false)})
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
          value={isLoading ? '—' : alerts.enabledCount}
          icon={ShieldCheck}
          tone="success"
          subtitle={`of ${rules.length} in ${viewingGlobal ? 'shared defaults' : 'this profile'}`}
        />
        <StatCard
          label="Hosts"
          value={alerts.hostsLoading ? '—' : alerts.hosts.length}
          icon={Server}
          tone="info"
          subtitle="reporting to this org"
        />
        <StatCard
          label="Profile"
          value={followsShared || viewingGlobal ? 'Shared' : 'Custom'}
          icon={viewingGlobal ? Globe2 : SlidersHorizontal}
          tone={followsShared || viewingGlobal ? 'accent' : 'warning'}
          subtitle={viewingGlobal ? 'org-wide defaults' : scopeLabel}
        />
        <StatCard
          label="Paging rules"
          value={isLoading ? '—' : alerts.pagingCount}
          icon={BellRing}
          tone="danger"
          subtitle="P0–P2 override set"
        />
      </div>

      <div className="grid gap-3 lg:grid-cols-[16rem_minmax(0,1fr)]">
        <SectionCard title="Profiles" icon={Globe2} iconTone="info" flushBody>
          <ProfileRail
            hosts={alerts.hosts}
            loading={alerts.hostsLoading}
            selectedHostId={selectedHostId}
            onSelectShared={() => navigate({search: {}})}
            onSelectHost={(hostId) => navigate({search: {host: hostId}})}
          />
        </SectionCard>

        <SectionCard
          title={panelTitle}
          icon={viewingGlobal ? Globe2 : Server}
          iconTone={viewingGlobal ? 'info' : 'accent'}
          count={isLoading ? undefined : rules.length}
          actions={
            !viewingGlobal && alerts.hasCarrier ? (
              <div className="flex items-center rounded-md border p-0.5">
                <ScopeButton
                  active={followsShared}
                  pending={alerts.scopeMutation.isPending}
                  onClick={() => alerts.scopeMutation.mutate('global')}
                >
                  Shared
                </ScopeButton>
                <ScopeButton
                  active={!followsShared}
                  pending={alerts.scopeMutation.isPending}
                  onClick={() => alerts.scopeMutation.mutate('host')}
                >
                  Custom
                </ScopeButton>
              </div>
            ) : undefined
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

          <RulesPanelBody
            isLoading={isLoading}
            rules={rules}
            canEdit={canEdit}
            hasHosts={alerts.hosts.length > 0}
            togglePending={alerts.toggleMutation.isPending}
            onToggle={(rule, enabled) => alerts.toggleMutation.mutate({rule, enabled})}
            onEdit={(rule) => {
              setEditingRule(rule)
              setIsRuleDialogOpen(true)
            }}
            onDelete={setPendingDelete}
            onCreate={openCreate}
          />
        </SectionCard>
      </div>

      <AlertRuleDialog
        open={isRuleDialogOpen}
        onOpenChange={closeRuleDialog}
        rule={editingRule}
        scopeLabel={scopeLabel}
        pending={alerts.createMutation.isPending || alerts.updateMutation.isPending}
        onSubmit={submitRule}
      />

      <DeleteRuleDialog
        rule={pendingDelete}
        scopeLabel={scopeLabel}
        pending={alerts.deleteMutation.isPending}
        onCancel={() => setPendingDelete(null)}
        onConfirm={(rule) =>
          alerts.deleteMutation.mutate(rule, {onSuccess: () => setPendingDelete(null)})
        }
      />
    </div>
  )
}

type ProfileRailProps = Readonly<{
  hosts: readonly DdHostResponse[]
  loading: boolean
  selectedHostId?: string
  onSelectShared: () => void
  onSelectHost: (hostId: string) => void
}>

function ProfileRail({
  hosts,
  loading,
  selectedHostId,
  onSelectShared,
  onSelectHost,
}: ProfileRailProps) {
  return (
    <nav className="max-h-[28rem] overflow-y-auto py-1">
      <ProfileRow
        active={!selectedHostId}
        icon={Globe2}
        label="Shared defaults"
        detail="Applies to every host set to shared"
        onSelect={onSelectShared}
      />
      {hosts.length > 0 && (
        <p className="px-3 pb-1 pt-2.5 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
          Hosts
        </p>
      )}
      {loading &&
        SKELETON_ROWS.slice(0, 3).map((key) => (
          <div key={key} className="px-3 py-2">
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
          onSelect={() => onSelectHost(host.id)}
        />
      ))}
      {!loading && hosts.length === 0 && (
        <p className="px-3 py-6 text-center text-xs text-muted-foreground">
          No hosts are reporting yet.
        </p>
      )}
    </nav>
  )
}

type RulesPanelBodyProps = Readonly<{
  isLoading: boolean
  rules: readonly HostAlert[]
  canEdit: boolean
  hasHosts: boolean
  togglePending: boolean
  onToggle: (rule: HostAlert, enabled: boolean) => void
  onEdit: (rule: HostAlert) => void
  onDelete: (rule: HostAlert) => void
  onCreate: () => void
}>

function RulesPanelBody({
  isLoading,
  rules,
  canEdit,
  hasHosts,
  togglePending,
  onToggle,
  onEdit,
  onDelete,
  onCreate,
}: RulesPanelBodyProps) {
  if (isLoading) {
    return (
      <div className="space-y-2 px-4 py-3">
        {SKELETON_ROWS.map((key) => (
          <div key={key} className="h-12 w-full animate-pulse rounded bg-muted" />
        ))}
      </div>
    )
  }

  if (rules.length === 0) {
    return (
      <div className="px-4 py-4">
        <EmptyState
          icon={BellRing}
          title="No rules yet"
          description={
            hasHosts
              ? 'Add a threshold rule to open an on-call alert when this metric drifts.'
              : 'Connect a host with the agent to start configuring threshold alerts.'
          }
          action={
            canEdit && hasHosts ? (
              <Button size="sm" className="gap-1.5" onClick={onCreate}>
                <Plus className="h-3.5 w-3.5" />
                New rule
              </Button>
            ) : undefined
          }
        />
      </div>
    )
  }

  return (
    <ul className="divide-y">
      {rules.map((rule) => (
        <RuleRow
          key={`${rule.scope}-${rule.id}`}
          rule={rule}
          readOnly={!canEdit}
          togglePending={togglePending}
          onToggle={(enabled) => onToggle(rule, enabled)}
          onEdit={() => onEdit(rule)}
          onDelete={() => onDelete(rule)}
        />
      ))}
    </ul>
  )
}

type DeleteRuleDialogProps = Readonly<{
  rule: HostAlert | null
  scopeLabel: string
  pending: boolean
  onCancel: () => void
  onConfirm: (rule: HostAlert) => void
}>

function DeleteRuleDialog({
  rule,
  scopeLabel,
  pending,
  onCancel,
  onConfirm,
}: DeleteRuleDialogProps) {
  return (
    <AlertDialog open={Boolean(rule)} onOpenChange={(open) => !open && onCancel()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Delete this rule?</AlertDialogTitle>
          <AlertDialogDescription>
            {rule
              ? `${alertMetricLabel(rule.metric)} ${rule.condition} ${formatAlertThreshold(rule.metric, rule.threshold)} will stop being evaluated for ${scopeLabel}.`
              : null}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction
            disabled={pending}
            onClick={(event) => {
              event.preventDefault()
              if (rule) onConfirm(rule)
            }}
          >
            {pending ? 'Deleting…' : 'Delete rule'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

type ProfileRowProps = Readonly<{
  active: boolean
  icon: ComponentType<{className?: string}>
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
  children: ReactNode
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
  const label = alertMetricLabel(rule.metric)
  return (
    <li
      className={cn(
        'group flex items-center gap-3 px-4 py-2.5 transition-colors hover:bg-muted/40',
        !rule.enabled && 'opacity-60'
      )}
    >
      <Switch
        aria-label={`${rule.enabled ? 'Disable' : 'Enable'} ${label} rule`}
        checked={rule.enabled}
        disabled={readOnly || togglePending}
        onCheckedChange={onToggle}
        className="shrink-0"
      />

      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-1.5">
          <StatusDot tone={alertMetricTone(rule.metric)} size="sm" />
          <span className="text-xs font-medium">{label}</span>
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
          {rule.lastTriggeredAt ? `Last fired ${formatRelativeTime(rule.lastTriggeredAt)}` : 'Never fired'}
        </p>
      </div>

      {!readOnly && (
        <div className="flex shrink-0 items-center gap-0.5 opacity-0 transition-opacity focus-within:opacity-100 group-hover:opacity-100">
          <Button
            size="sm"
            variant="ghost"
            className="h-7 w-7 p-0"
            aria-label={`Edit ${label} rule`}
            onClick={onEdit}
          >
            <Pencil className="h-3.5 w-3.5" />
          </Button>
          <Button
            size="sm"
            variant="ghost"
            className="h-7 w-7 p-0"
            aria-label={`Delete ${label} rule`}
            onClick={onDelete}
          >
            <Trash2 className="h-3.5 w-3.5 text-danger-fg" />
          </Button>
        </div>
      )}
    </li>
  )
}
