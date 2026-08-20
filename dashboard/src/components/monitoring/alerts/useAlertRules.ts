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
// Data layer for the alert rules page: which profile is in view, the rules it
// holds, and the writes against it. Kept apart from the page so the rendering
// stays a straight read of this state.
//
// The write API is keyed by host (`/monitor/hosts/{id}/alerts?scope=global`),
// so editing org-wide defaults still needs a host to carry the request; the
// first known host acts as that carrier.
// ─────────────────────────────────────────────────────────────────────────────

import {useMemo} from 'react'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'

import {api, type DdHostResponse, type HostAlert, type HostAlertConfig} from '@/lib/api'
import type {AlertRuleFormValues} from './AlertRuleDialog'

export type AlertScope = 'global' | 'host'

export const hostAlertConfigQueryKey = (hostId: string) =>
  ['host-alert-config', hostId] as const

/** Swap one rule wherever it appears in a cached config. */
export function applyRuleUpdate(config: HostAlertConfig, updated: HostAlert): HostAlertConfig {
  const swap = (list: HostAlert[]) =>
    list.map((rule) => (rule.id === updated.id && rule.scope === updated.scope ? updated : rule))
  return {
    ...config,
    globalAlerts: swap(config.globalAlerts),
    hostAlerts: swap(config.hostAlerts),
    effectiveAlerts: swap(config.effectiveAlerts),
  }
}

export function isPagingPriority(priority?: string | null): boolean {
  return priority === 'P0' || priority === 'P1' || priority === 'P2'
}

function toWritePayload(values: AlertRuleFormValues) {
  return {
    metric: values.metric,
    condition: values.condition,
    threshold: values.threshold,
    durationSeconds: values.durationSeconds,
    enabled: values.enabled,
  }
}

export function useAlertRules(selectedHostId?: string) {
  const queryClient = useQueryClient()

  const {data: hostsData, isLoading: hostsLoading} = useQuery({
    queryKey: ['hosts'],
    queryFn: () => api.getHosts(),
    enabled: api.isAuthenticated(),
  })

  const hosts: DdHostResponse[] = useMemo(
    () => [...(hostsData?.hosts ?? [])].sort((a, b) => a.hostname.localeCompare(b.hostname)),
    [hostsData?.hosts]
  )

  const carrierHostId = selectedHostId ?? hosts[0]?.id ?? ''
  const viewingGlobal = !selectedHostId
  const selectedHost = hosts.find((host) => host.id === selectedHostId)

  const {data: alertConfig, isLoading: configLoading} = useQuery({
    queryKey: hostAlertConfigQueryKey(carrierHostId),
    queryFn: () => api.getHostAlertConfig(carrierHostId),
    enabled: api.isAuthenticated() && carrierHostId !== '',
  })

  // A host set to the global scope follows the shared defaults, read-only.
  const followsShared = !viewingGlobal && alertConfig?.scope === 'global'
  const editScope: AlertScope = viewingGlobal ? 'global' : 'host'

  const rules = useMemo(() => {
    if (!alertConfig) return []
    const source = viewingGlobal || followsShared ? alertConfig.globalAlerts : alertConfig.hostAlerts
    return [...source].sort((a, b) => a.metric.localeCompare(b.metric) || a.threshold - b.threshold)
  }, [alertConfig, viewingGlobal, followsShared])

  const invalidate = () => {
    if (carrierHostId !== '') {
      queryClient.invalidateQueries({queryKey: hostAlertConfigQueryKey(carrierHostId)})
    }
  }

  const scopeMutation = useMutation({
    mutationFn: (scope: AlertScope) => api.updateHostAlertScope(carrierHostId, scope),
    onSuccess: invalidate,
  })

  const createMutation = useMutation({
    mutationFn: (values: AlertRuleFormValues) =>
      api.createHostAlert(
        carrierHostId,
        {...toWritePayload(values), alertPriority: values.alertPriority ?? undefined},
        editScope
      ),
    onSuccess: invalidate,
  })

  const updateMutation = useMutation({
    mutationFn: ({rule, values}: {rule: HostAlert; values: AlertRuleFormValues}) =>
      api.updateHostAlert(
        carrierHostId,
        rule.id,
        {...toWritePayload(values), alertPriority: values.alertPriority},
        rule.scope as AlertScope
      ),
    onSuccess: invalidate,
  })

  const toggleMutation = useMutation({
    mutationFn: ({rule, enabled}: {rule: HostAlert; enabled: boolean}) =>
      api.updateHostAlert(carrierHostId, rule.id, {enabled}, rule.scope as AlertScope),
    // Reflect the switch immediately; the list is otherwise a refetch away.
    onMutate: ({rule, enabled}) => {
      const key = hostAlertConfigQueryKey(carrierHostId)
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
      api.deleteHostAlert(carrierHostId, rule.id, rule.scope as AlertScope),
    onSuccess: invalidate,
  })

  return {
    hosts,
    hostsLoading,
    isLoading: hostsLoading || (carrierHostId !== '' && configLoading),
    rules,
    selectedHost,
    viewingGlobal,
    followsShared,
    hasCarrier: carrierHostId !== '',
    // Shared defaults are always editable; a host is only editable on its own profile.
    canEdit: viewingGlobal || !followsShared,
    enabledCount: rules.filter((rule) => rule.enabled).length,
    pagingCount: rules.filter((rule) => isPagingPriority(rule.alertPriority)).length,
    scopeMutation,
    createMutation,
    updateMutation,
    toggleMutation,
    deleteMutation,
  }
}
