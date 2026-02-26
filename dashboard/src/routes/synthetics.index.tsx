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
import {api, type SyntheticResultListResponse, type SyntheticResultResponse, type SyntheticTestResponse} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {cn} from '@/lib/utils'
import {Play, Trash2} from 'lucide-react'
import {useToast} from '@/hooks/use-toast'

export const Route = createFileRoute('/synthetics/')({
  component: SyntheticResults,
})

const resultStatusColors: Record<string, string> = {
  passed: 'bg-green-500/15 text-green-500 border-green-500/30',
  failed: 'bg-red-500/15 text-red-500 border-red-500/30',
  skipped: 'bg-slate-500/15 text-slate-400 border-slate-500/30',
}

const testStatusColors: Record<string, string> = {
  pending: 'bg-slate-500/15 text-slate-400 border-slate-500/30',
  running: 'bg-blue-500/15 text-blue-500 border-blue-500/30',
  passing: 'bg-green-500/15 text-green-500 border-green-500/30',
  failing: 'bg-red-500/15 text-red-500 border-red-500/30',
}

const testTypeColors: Record<string, string> = {
  api: 'bg-violet-500/15 text-violet-500 border-violet-500/30',
  multistep: 'bg-blue-500/15 text-blue-500 border-blue-500/30',
}

function formatLastRun(timestamp: number | null | undefined): string {
  if (!timestamp) return 'Never'
  return new Date(timestamp).toLocaleString()
}

function SyntheticResults() {
  const {toast} = useToast()
  const queryClient = useQueryClient()

  const {data: testsData, isLoading: testsLoading} = useQuery({
    queryKey: ['synthetic-tests'],
    queryFn: () => api.listSyntheticTests(),
  })

  const {data: resultsData, isLoading: resultsLoading} = useQuery({
    queryKey: ['synthetic-results'],
    queryFn: () => api.get<SyntheticResultListResponse>('/v1/synthetics?limit=50'),
  })

  const runMutation = useMutation({
    mutationFn: (testId: string) => api.runSyntheticTest(testId),
    onSuccess: () => {
      toast({title: 'Test run triggered'})
      queryClient.invalidateQueries({queryKey: ['synthetic-tests']})
      queryClient.invalidateQueries({queryKey: ['synthetic-results']})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to run test', description: error.message, variant: 'destructive'})
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (testId: string) => api.deleteSyntheticTest(testId),
    onSuccess: () => {
      toast({title: 'Test deleted'})
      queryClient.invalidateQueries({queryKey: ['synthetic-tests']})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to delete test', description: error.message, variant: 'destructive'})
    },
  })

  const tests: SyntheticTestResponse[] = testsData ?? []
  const results: SyntheticResultResponse[] = resultsData?.results ?? []

  const handleDelete = (testId: string) => {
    if (window.confirm('Are you sure you want to delete this test?')) {
      deleteMutation.mutate(testId)
    }
  }

  if (testsLoading || resultsLoading) {
    return (
      <div className="flex justify-center py-12">
        <div className="animate-spin rounded-full h-8 w-8 border-2 border-muted border-t-primary" />
      </div>
    )
  }

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader><CardTitle>Tests</CardTitle></CardHeader>
        <CardContent>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-muted-foreground">
                  <th className="pb-2 pr-4 font-medium">Name</th>
                  <th className="pb-2 pr-4 font-medium">Type</th>
                  <th className="pb-2 pr-4 font-medium">Status</th>
                  <th className="pb-2 pr-4 font-medium">Last Run</th>
                  <th className="pb-2 pr-4 font-medium">Interval</th>
                  <th className="pb-2 font-medium">Actions</th>
                </tr>
              </thead>
              <tbody>
                {tests.map((t) => (
                  <tr key={t.id} className="border-b last:border-0 hover:bg-muted/30">
                    <td className="py-2 pr-4 font-medium">{t.name}</td>
                    <td className="py-2 pr-4">
                      <Badge variant="outline" className={cn('text-xs', testTypeColors[t.testType] || '')}>
                        {t.testType}
                      </Badge>
                    </td>
                    <td className="py-2 pr-4">
                      <Badge variant="outline" className={cn('text-xs', testStatusColors[t.status] || '')}>
                        {t.status || 'pending'}
                      </Badge>
                    </td>
                    <td className="py-2 pr-4 text-muted-foreground text-xs">{formatLastRun(t.lastRunAt)}</td>
                    <td className="py-2 pr-4 text-muted-foreground">Every {Math.round((t.intervalSeconds ?? 0) / 60)} min</td>
                    <td className="py-2">
                      <div className="flex items-center gap-1">
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-7 w-7"
                          onClick={() => runMutation.mutate(t.id)}
                          disabled={runMutation.isPending}
                        >
                          <Play className="h-3.5 w-3.5" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-7 w-7 text-destructive hover:text-destructive"
                          onClick={() => handleDelete(t.id)}
                          disabled={deleteMutation.isPending}
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))}
                {tests.length === 0 && (
                  <tr>
                    <td colSpan={6} className="py-8 text-center text-muted-foreground">
                      No synthetic tests configured. Click "New Test" to create one.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader><CardTitle>Test Results</CardTitle></CardHeader>
        <CardContent>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-muted-foreground">
                  <th className="pb-2 pr-4 font-medium">Test Name</th>
                  <th className="pb-2 pr-4 font-medium">Type</th>
                  <th className="pb-2 pr-4 font-medium">Status</th>
                  <th className="pb-2 pr-4 font-medium">Duration</th>
                  <th className="pb-2 pr-4 font-medium">Probe</th>
                  <th className="pb-2 font-medium">Time</th>
                </tr>
              </thead>
              <tbody>
                {results.map((r) => (
                  <tr key={r.resultId} className="border-b last:border-0 hover:bg-muted/30">
                    <td className="py-2 pr-4 font-medium">{r.testName}</td>
                    <td className="py-2 pr-4"><Badge variant="outline" className="text-xs">{r.testType}</Badge></td>
                    <td className="py-2 pr-4">
                      <Badge variant="outline" className={cn('text-xs', resultStatusColors[r.status] || '')}>
                        {r.status}
                      </Badge>
                    </td>
                    <td className="py-2 pr-4">{r.durationMs}ms</td>
                    <td className="py-2 pr-4">{r.probeDc}</td>
                    <td className="py-2 text-muted-foreground text-xs">{r.timestamp}</td>
                  </tr>
                ))}
                {results.length === 0 && (
                  <tr>
                    <td colSpan={6} className="py-8 text-center text-muted-foreground">No synthetic test results</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
