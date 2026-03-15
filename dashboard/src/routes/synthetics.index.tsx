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

import {createFileRoute, Link} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api, type SyntheticResultResponse, type SyntheticTestResponse} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Card, CardContent, CardHeader, CardTitle} from '@/components/ui/card'
import {Input} from '@/components/ui/input'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Checkbox} from '@/components/ui/checkbox'
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
import {cn} from '@/lib/utils'
import {Play, Trash2, Pause, Search, Filter} from 'lucide-react'
import {useToast} from '@/hooks/useToast'
import {useState, useMemo} from 'react'

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
  passed: 'bg-green-500/15 text-green-500 border-green-500/30',
  passing: 'bg-green-500/15 text-green-500 border-green-500/30',
  failed: 'bg-red-500/15 text-red-500 border-red-500/30',
  failing: 'bg-red-500/15 text-red-500 border-red-500/30',
}

const testTypeColors: Record<string, string> = {
  api: 'bg-violet-500/15 text-violet-500 border-violet-500/30',
  multistep: 'bg-blue-500/15 text-blue-500 border-blue-500/30',
  ssl: 'bg-amber-500/15 text-amber-500 border-amber-500/30',
  dns: 'bg-cyan-500/15 text-cyan-500 border-cyan-500/30',
  tcp: 'bg-emerald-500/15 text-emerald-500 border-emerald-500/30',
  udp: 'bg-emerald-500/15 text-emerald-500 border-emerald-500/30',
}

function formatLastRun(timestamp: number | null | undefined): string {
  if (!timestamp) return 'Never'
  return new Date(timestamp).toLocaleString()
}

function Sparkline({results, testId}: {results: SyntheticResultResponse[]; testId: string}) {
  const testResults = results.filter((r) => r.testId === testId).slice(0, 20).reverse()
  if (testResults.length === 0) return <span className="text-muted-foreground text-xs">—</span>
  const max = Math.max(...testResults.map((r) => r.durationMs), 1)
  return (
    <div className="flex gap-px items-end h-5 w-20">
      {testResults.map((r, i) => (
        <div
          key={i}
          className={cn('flex-1 rounded-sm min-w-[2px]', r.status === 'passed' ? 'bg-green-500' : 'bg-red-500')}
          style={{height: `${Math.max((r.durationMs / max) * 100, 10)}%`}}
          title={`${r.durationMs}ms`}
        />
      ))}
    </div>
  )
}

function computeUptime(results: SyntheticResultResponse[], testId: string): string {
  const testResults = results.filter((r) => r.testId === testId)
  if (testResults.length === 0) return '—'
  const passed = testResults.filter((r) => r.status === 'passed').length
  return `${((passed / testResults.length) * 100).toFixed(1)}%`
}

function SyntheticResults() {
  const {toast} = useToast()
  const queryClient = useQueryClient()
  const [searchQuery, setSearchQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<string>('all')
  const [typeFilter, setTypeFilter] = useState<string>('all')
  const [tagFilter, setTagFilter] = useState<string>('all')
  const [selectedTests, setSelectedTests] = useState<Set<string>>(new Set())
  const [deleteConfirm, setDeleteConfirm] = useState<string | null>(null)
  const [bulkDeleteConfirm, setBulkDeleteConfirm] = useState(false)

  const {data: testsData, isLoading: testsLoading} = useQuery({
    queryKey: ['synthetic-tests'],
    queryFn: () => api.listSyntheticTests(),
  })

  const {data: resultsData, isLoading: resultsLoading} = useQuery({
    queryKey: ['synthetic-results'],
    queryFn: () => api.listSyntheticResults(200),
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
      setDeleteConfirm(null)
    },
    onError: (error: Error) => {
      toast({title: 'Failed to delete test', description: error.message, variant: 'destructive'})
    },
  })

  const togglePauseMutation = useMutation({
    mutationFn: ({testId, active}: {testId: string; active: boolean}) =>
      api.updateSyntheticTest(testId, {active}),
    onSuccess: () => {
      toast({title: 'Test updated'})
      queryClient.invalidateQueries({queryKey: ['synthetic-tests']})
    },
    onError: (error: Error) => {
      toast({title: 'Failed to update test', description: error.message, variant: 'destructive'})
      queryClient.invalidateQueries({queryKey: ['synthetic-tests']})
    },
  })

  const tests: SyntheticTestResponse[] = testsData ?? []
  const results: SyntheticResultResponse[] = resultsData?.results ?? []

  const allTags = useMemo(() => {
    const tags = new Set<string>()
    tests.forEach((t) => t.tags?.forEach((tag) => tags.add(tag)))
    return Array.from(tags).sort()
  }, [tests])

  const filteredTests = useMemo(() => {
    return tests.filter((t) => {
      if (searchQuery && !t.name.toLowerCase().includes(searchQuery.toLowerCase())) return false
      if (statusFilter !== 'all' && t.lastStatus !== statusFilter && t.status !== statusFilter) return false
      if (typeFilter !== 'all' && t.testType !== typeFilter) return false
      if (tagFilter !== 'all' && !(t.tags ?? []).includes(tagFilter)) return false
      return true
    })
  }, [tests, searchQuery, statusFilter, typeFilter, tagFilter])

  const toggleSelect = (testId: string) => {
    setSelectedTests((prev) => {
      const next = new Set(prev)
      if (next.has(testId)) next.delete(testId)
      else next.add(testId)
      return next
    })
  }

  const toggleSelectAll = () => {
    if (selectedTests.size === filteredTests.length) {
      setSelectedTests(new Set())
    } else {
      setSelectedTests(new Set(filteredTests.map((t) => t.id)))
    }
  }

  const handleBulkDelete = async () => {
    const total = selectedTests.size
    let succeeded = 0
    const failed: string[] = []
    for (const testId of selectedTests) {
      try {
        await api.deleteSyntheticTest(testId)
        succeeded++
      } catch {
        failed.push(testId)
      }
    }
    setSelectedTests(new Set())
    setBulkDeleteConfirm(false)
    queryClient.invalidateQueries({queryKey: ['synthetic-tests']})
    if (failed.length > 0) {
      toast({title: `Deleted ${succeeded}/${total} tests`, description: `${failed.length} failed`, variant: 'destructive'})
    } else {
      toast({title: `${succeeded} tests deleted`})
    }
  }

  const handleBulkPause = async () => {
    const total = selectedTests.size
    let succeeded = 0
    const failed: string[] = []
    for (const testId of selectedTests) {
      try {
        await api.updateSyntheticTest(testId, {active: false})
        succeeded++
      } catch {
        failed.push(testId)
      }
    }
    setSelectedTests(new Set())
    queryClient.invalidateQueries({queryKey: ['synthetic-tests']})
    if (failed.length > 0) {
      toast({title: `Paused ${succeeded}/${total} tests`, description: `${failed.length} failed`, variant: 'destructive'})
    } else {
      toast({title: `${succeeded} tests paused`})
    }
  }

  const handleBulkResume = async () => {
    const total = selectedTests.size
    let succeeded = 0
    const failed: string[] = []
    for (const testId of selectedTests) {
      try {
        await api.updateSyntheticTest(testId, {active: true})
        succeeded++
      } catch {
        failed.push(testId)
      }
    }
    setSelectedTests(new Set())
    queryClient.invalidateQueries({queryKey: ['synthetic-tests']})
    if (failed.length > 0) {
      toast({title: `Resumed ${succeeded}/${total} tests`, description: `${failed.length} failed`, variant: 'destructive'})
    } else {
      toast({title: `${succeeded} tests resumed`})
    }
  }

  if (testsLoading || resultsLoading) {
    return (
      <div className="flex justify-center py-8">
        <div className="animate-spin rounded-full h-6 w-6 border-2 border-muted border-t-primary" />
      </div>
    )
  }

  return (
    <div className="space-y-2">
      <Card>
        <CardHeader className="py-2 px-3">
          <div className="flex items-center justify-between">
            <CardTitle className="text-sm">Tests</CardTitle>
            {selectedTests.size > 0 && (
              <div className="flex items-center gap-2">
                <span className="text-sm text-muted-foreground">{selectedTests.size} selected</span>
                <Button variant="outline" size="sm" onClick={handleBulkPause}>
                  <Pause className="h-3.5 w-3.5 mr-1" />Pause
                </Button>
                <Button variant="outline" size="sm" onClick={handleBulkResume}>
                  <Play className="h-3.5 w-3.5 mr-1" />Resume
                </Button>
                <Button variant="destructive" size="sm" onClick={() => setBulkDeleteConfirm(true)}>
                  <Trash2 className="h-3.5 w-3.5 mr-1" />Delete
                </Button>
              </div>
            )}
          </div>
        </CardHeader>
        <CardContent className="px-3 pb-3 pt-0">
          {/* Filter bar */}
          <div className="flex items-center gap-1.5 mb-2 flex-wrap">
            <div className="relative flex-1 min-w-40">
              <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground" />
              <Input
                placeholder="Search tests..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="pl-8 h-7 text-xs"
              />
            </div>
            <Select value={statusFilter} onValueChange={setStatusFilter}>
              <SelectTrigger className="w-28 h-7 text-xs">
                <Filter className="h-3.5 w-3.5 mr-1.5" />
                <SelectValue placeholder="Status" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">All Status</SelectItem>
                <SelectItem value="passed">Passing</SelectItem>
                <SelectItem value="failed">Failing</SelectItem>
                <SelectItem value="pending">Pending</SelectItem>
              </SelectContent>
            </Select>
            <Select value={typeFilter} onValueChange={setTypeFilter}>
              <SelectTrigger className="w-28 h-7 text-xs">
                <SelectValue placeholder="Type" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="all">All Types</SelectItem>
                <SelectItem value="api">API</SelectItem>
                <SelectItem value="multistep">Multistep</SelectItem>
                <SelectItem value="ssl">SSL</SelectItem>
                <SelectItem value="dns">DNS</SelectItem>
                <SelectItem value="tcp">TCP</SelectItem>
                <SelectItem value="udp">UDP</SelectItem>
              </SelectContent>
            </Select>
            {allTags.length > 0 && (
              <Select value={tagFilter} onValueChange={setTagFilter}>
                <SelectTrigger className="w-28 h-7 text-xs">
                  <SelectValue placeholder="Tag" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="all">All Tags</SelectItem>
                  {allTags.map((tag) => (
                    <SelectItem key={tag} value={tag}>{tag}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b text-left text-muted-foreground">
                  <th className="pb-1.5 pr-2 w-7">
                    <Checkbox
                      checked={selectedTests.size === filteredTests.length && filteredTests.length > 0}
                      onCheckedChange={toggleSelectAll}
                    />
                  </th>
                  <th className="pb-1.5 pr-2 font-medium">Name</th>
                  <th className="pb-1.5 pr-2 font-medium">Type</th>
                  <th className="pb-1.5 pr-2 font-medium">Status</th>
                  <th className="pb-1.5 pr-2 font-medium">Uptime</th>
                  <th className="pb-1.5 pr-2 font-medium">Response</th>
                  <th className="pb-1.5 pr-2 font-medium">Last Run</th>
                  <th className="pb-1.5 pr-2 font-medium">Interval</th>
                  <th className="pb-1.5 font-medium">Actions</th>
                </tr>
              </thead>
              <tbody>
                {filteredTests.map((t) => (
                  <tr key={t.id} className="border-b last:border-0 hover:bg-muted/30">
                    <td className="py-1.5 pr-2">
                      <Checkbox
                        checked={selectedTests.has(t.id)}
                        onCheckedChange={() => toggleSelect(t.id)}
                      />
                    </td>
                    <td className="py-1.5 pr-2">
                      <Link
                        to="/synthetics/$testId"
                        params={{testId: t.id}}
                        className="font-medium hover:text-primary hover:underline"
                      >
                        {t.name}
                      </Link>
                      {t.tags && t.tags.length > 0 && (
                        <div className="flex gap-1 mt-0.5">
                          {t.tags.map((tag) => (
                            <Badge key={tag} variant="secondary" className="text-[10px] px-1 py-0">{tag}</Badge>
                          ))}
                        </div>
                      )}
                    </td>
                    <td className="py-1.5 pr-2">
                      <Badge variant="outline" className={cn('text-[10px]', testTypeColors[t.testType] || '')}>
                        {t.testType}
                      </Badge>
                    </td>
                    <td className="py-1.5 pr-2">
                      <Badge variant="outline" className={cn('text-[10px]', testStatusColors[t.lastStatus ?? t.status] || '')}>
                        {t.lastStatus || t.status || 'pending'}
                      </Badge>
                    </td>
                    <td className="py-1.5 pr-2 font-medium">
                      {computeUptime(results, t.id)}
                    </td>
                    <td className="py-1.5 pr-2">
                      <Sparkline results={results} testId={t.id} />
                    </td>
                    <td className="py-1.5 pr-2 text-muted-foreground">{formatLastRun(t.lastRunAt)}</td>
                    <td className="py-1.5 pr-2 text-muted-foreground">Every {Math.round((t.intervalSeconds ?? 0) / 60)} min</td>
                    <td className="py-1.5">
                      <div className="flex items-center gap-1">
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-6 w-6"
                          onClick={() => runMutation.mutate(t.id)}
                          disabled={runMutation.isPending}
                          title="Run now"
                        >
                          <Play className="h-3.5 w-3.5" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-6 w-6"
                          onClick={() => togglePauseMutation.mutate({testId: t.id, active: !t.active})}
                          title={t.active ? 'Pause' : 'Resume'}
                        >
                          <Pause className={cn('h-3.5 w-3.5', !t.active && 'text-amber-500')} />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-7 w-7 text-destructive hover:text-destructive"
                          onClick={() => setDeleteConfirm(t.id)}
                          disabled={deleteMutation.isPending}
                          title="Delete"
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </Button>
                      </div>
                    </td>
                  </tr>
                ))}
                {filteredTests.length === 0 && (
                  <tr>
                    <td colSpan={9} className="py-6 text-center text-muted-foreground">
                      {tests.length === 0
                        ? 'No synthetic tests configured. Click "New Test" to create one.'
                        : 'No tests match your filters.'}
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="py-2 px-3"><CardTitle className="text-sm">Recent Results</CardTitle></CardHeader>
        <CardContent className="px-3 pb-3 pt-0">
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b text-left text-muted-foreground">
                  <th className="pb-1.5 pr-2 font-medium">Test Name</th>
                  <th className="pb-1.5 pr-2 font-medium">Type</th>
                  <th className="pb-1.5 pr-2 font-medium">Status</th>
                  <th className="pb-1.5 pr-2 font-medium">Duration</th>
                  <th className="pb-1.5 pr-2 font-medium">Probe</th>
                  <th className="pb-1.5 font-medium">Time</th>
                </tr>
              </thead>
              <tbody>
                {results.slice(0, 50).map((r) => (
                  <tr key={r.resultId} className="border-b last:border-0 hover:bg-muted/30">
                    <td className="py-1.5 pr-2 font-medium">{r.testName}</td>
                    <td className="py-1.5 pr-2"><Badge variant="outline" className="text-[10px]">{r.testType}</Badge></td>
                    <td className="py-1.5 pr-2">
                      <Badge variant="outline" className={cn('text-[10px]', resultStatusColors[r.status] || '')}>
                        {r.status}
                      </Badge>
                    </td>
                    <td className="py-1.5 pr-2">{r.durationMs}ms</td>
                    <td className="py-1.5 pr-2">{r.probeDc}</td>
                    <td className="py-1.5 text-muted-foreground">{r.timestamp}</td>
                  </tr>
                ))}
                {results.length === 0 && (
                  <tr>
                    <td colSpan={6} className="py-6 text-center text-muted-foreground">No synthetic test results</td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>

      {/* Delete confirmation dialog */}
      <AlertDialog open={deleteConfirm !== null} onOpenChange={() => setDeleteConfirm(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete synthetic test?</AlertDialogTitle>
            <AlertDialogDescription>
              This action cannot be undone. The test and its configuration will be permanently removed.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              onClick={() => deleteConfirm && deleteMutation.mutate(deleteConfirm)}
            >
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Bulk delete confirmation dialog */}
      <AlertDialog open={bulkDeleteConfirm} onOpenChange={setBulkDeleteConfirm}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete {selectedTests.size} tests?</AlertDialogTitle>
            <AlertDialogDescription>
              This action cannot be undone. All selected tests will be permanently removed.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              onClick={handleBulkDelete}
            >
              Delete All
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
