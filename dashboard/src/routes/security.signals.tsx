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
import {api, formatErrorForLogging, type SignalResponse, type TriageRequest} from '@/lib/api'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Sheet, SheetContent, SheetHeader, SheetTitle} from '@/components/ui/sheet'
import {useToast} from '@/hooks/useToast'
import {SignalFilterBar, type SignalFilters} from '@/components/security/SignalFilterBar'
import {SignalsTable} from '@/components/security/SignalsTable'
import {SignalDetailContent} from '@/components/security/SignalDetailContent'

export const Route = createFileRoute('/security/signals')({
  component: SignalsTab,
})

function SignalsTab() {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [filters, setFilters] = useState<SignalFilters>({})
  const [selected, setSelected] = useState<SignalResponse | null>(null)

  const listKey = ['security-signals', filters] as const
  const {data, isLoading} = useQuery({
    queryKey: listKey,
    queryFn: () => api.listSignals({...filters, limit: 100}),
  })

  const signals = useMemo(() => data?.signals ?? [], [data])
  const sources = useMemo(
    () => Array.from(new Set(signals.map((s) => s.source))).sort(),
    [signals]
  )

  const detailQuery = useQuery({
    queryKey: ['security-signal', selected?.id],
    queryFn: () => api.getSignal(selected!.id),
    enabled: selected != null,
  })

  const triage = useMutation({
    mutationFn: (request: TriageRequest) => api.triageSignal(selected!.id, request),
    onSuccess: (updated) => {
      setSelected(updated)
      void queryClient.invalidateQueries({queryKey: ['security-signals']})
      void queryClient.invalidateQueries({queryKey: ['security-signal', updated.id]})
      toast({title: 'Signal updated'})
    },
    onError: (error) => {
      toast({title: 'Triage failed', description: formatErrorForLogging(error), variant: 'destructive'})
    },
  })

  return (
    <div className="space-y-3">
      <SignalFilterBar filters={filters} onChange={setFilters} sources={sources} />

      {isLoading ? (
        <div className="flex justify-center py-8">
          <div className="h-6 w-6 animate-spin rounded-full border-2 border-muted border-t-primary" />
        </div>
      ) : (
        <Card>
          <CardHeader className="px-2.5 py-1.5">
            <CardTitle className="text-xs">Signals ({data?.total_count ?? signals.length})</CardTitle>
          </CardHeader>
          <CardContent className="p-2.5 pt-0">
            <SignalsTable signals={signals} selectedId={selected?.id} onSelect={setSelected} />
          </CardContent>
        </Card>
      )}

      <Sheet open={selected != null} onOpenChange={(open) => !open && setSelected(null)}>
        <SheetContent className="w-full overflow-y-auto sm:max-w-md">
          <SheetHeader>
            <SheetTitle className="text-sm">{selected?.rule_name}</SheetTitle>
          </SheetHeader>
          <div className="mt-4">
            {detailQuery.data ? (
              <SignalDetailContent
                detail={detailQuery.data}
                onTriage={(request) => triage.mutateAsync(request)}
                isSubmitting={triage.isPending}
              />
            ) : (
              <div className="flex justify-center py-8">
                <div className="h-6 w-6 animate-spin rounded-full border-2 border-muted border-t-primary" />
              </div>
            )}
          </div>
        </SheetContent>
      </Sheet>
    </div>
  )
}
