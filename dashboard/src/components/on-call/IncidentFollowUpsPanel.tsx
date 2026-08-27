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
import {CheckCircle2, ListChecks} from 'lucide-react'
import {api} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {SectionCard} from '@/components/ui/section-card'
import {useToast} from '@/hooks/useToast'
import type {OnCallIncidentFollowUp} from '@/lib/api/types'

export function IncidentFollowUpsPanel({incidentId}: Readonly<{incidentId: string}>) {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const queryKey = ['incident-follow-ups', incidentId]
  const {data: followUps, isLoading} = useQuery({
    queryKey,
    queryFn: () => api.getIncidentFollowUps(incidentId),
  })

  const transitionMutation = useMutation({
    mutationFn: ({followUp, action}: {followUp: OnCallIncidentFollowUp; action: 'accept' | 'complete'}) =>
      action === 'accept'
        ? api.acceptIncidentFollowUp(incidentId, followUp.id)
        : api.completeIncidentFollowUp(incidentId, followUp.id),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey})
      queryClient.invalidateQueries({queryKey: ['on-call-follow-ups', 'queue']})
    },
    onError: (error: Error) => toast({title: 'Follow-up update failed', description: error.message, variant: 'destructive'}),
  })

  return (
    <SectionCard
      title="Follow-ups"
      icon={ListChecks}
      iconTone="accent"
      count={followUps?.filter((followUp) => followUp.status !== 'COMPLETED' && followUp.status !== 'CANCELLED').length}
      bodyClassName="space-y-2"
    >
      {isLoading && <p className="text-sm text-muted-foreground">Loading follow-ups…</p>}
      {!isLoading && (!followUps || followUps.length === 0) && (
        <p className="text-sm text-muted-foreground">No follow-ups have been recorded for this incident.</p>
      )}
      {followUps?.map((followUp) => (
        <div key={followUp.id} className="rounded-lg border p-3">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <p className="text-sm font-medium">{followUp.title}</p>
              <p className="mt-0.5 text-xs text-muted-foreground">{followUp.description}</p>
              <div className="mt-2 flex flex-wrap items-center gap-1.5 text-[11px] text-muted-foreground">
                <Badge variant={followUp.priority === 'P0' || followUp.priority === 'P1' ? 'danger' : 'neutral'} size="sm">
                  {followUp.priority}
                </Badge>
                <Badge variant={followUp.status === 'COMPLETED' ? 'success' : 'info'} size="sm">
                  {followUp.status}
                </Badge>
                <span>{followUp.ownerUserName ?? followUp.ownerTeamName}</span>
                {followUp.dueAt && <span>Due {new Date(followUp.dueAt).toLocaleDateString()}</span>}
                {followUp.labels.map((label) => <span key={label}>#{label}</span>)}
              </div>
            </div>
            <div className="flex shrink-0 gap-1.5">
              {followUp.status === 'OPEN' && (
                <Button
                  size="sm"
                  variant="outline"
                  disabled={transitionMutation.isPending}
                  onClick={() => transitionMutation.mutate({followUp, action: 'accept'})}
                >
                  Accept
                </Button>
              )}
              {followUp.status === 'ACCEPTED' && (
                <Button
                  size="sm"
                  disabled={transitionMutation.isPending}
                  onClick={() => transitionMutation.mutate({followUp, action: 'complete'})}
                >
                  <CheckCircle2 className="mr-1.5 h-3.5 w-3.5" />
                  Complete
                </Button>
              )}
            </div>
          </div>
        </div>
      ))}
    </SectionCard>
  )
}
