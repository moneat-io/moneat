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

import {useState, useEffect} from 'react'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api, type LogIndex, type CreateLogIndexRequest, type UpdateLogIndexRequest} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Label} from '@/components/ui/label'
import {Card, CardContent, CardDescription, CardHeader, CardTitle} from '@/components/ui/card'
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from '@/components/ui/table'
import {Badge} from '@/components/ui/badge'
import {Switch} from '@/components/ui/switch'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {useToast} from '@/hooks/useToast'
import {ArrowDown, ArrowUp, Loader2, Plus, Trash2, Pencil, FlaskConical} from 'lucide-react'

export function LogIndexesTab() {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [showCreate, setShowCreate] = useState(false)
  const [editIndex, setEditIndex] = useState<LogIndex | null>(null)
  const [deleteIndex, setDeleteIndex] = useState<LogIndex | null>(null)

  const {data: indexData, isLoading} = useQuery({
    queryKey: ['log-indexes'],
    queryFn: () => api.getLogIndexes(),
    enabled: api.isAuthenticated(),
  })
  const indexes = indexData?.indexes ?? []

  const deleteMutation = useMutation({
    mutationFn: (id: number) => api.deleteLogIndex(id),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['log-indexes']})
      toast({title: 'Index deleted'})
      setDeleteIndex(null)
    },
    onError: () => toast({title: 'Failed to delete index', variant: 'destructive'}),
  })

  const updatePriority = useMutation({
    mutationFn: ({id, priority}: {id: number; priority: number}) =>
      api.updateLogIndex(id, {priority}),
    onSuccess: () => queryClient.invalidateQueries({queryKey: ['log-indexes']}),
  })

  const toggleActive = useMutation({
    mutationFn: ({id, is_active}: {id: number; is_active: boolean}) =>
      api.updateLogIndex(id, {is_active}),
    onSuccess: () => queryClient.invalidateQueries({queryKey: ['log-indexes']}),
  })

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div>
              <CardTitle>Log Indexes</CardTitle>
              <CardDescription>
                Control which logs get stored using filter-based indexes. Logs matching an index are tagged with its
                name for faster queries. Use <code className="text-xs bg-muted px-1 rounded">@index:name</code> in
                the search bar to filter by index.
              </CardDescription>
            </div>
            <Button onClick={() => setShowCreate(true)} size="sm">
              <Plus className="h-4 w-4 mr-1" /> Create Index
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          {isLoading ? (
            <div className="flex items-center justify-center py-8">
              <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
            </div>
          ) : indexes.length === 0 ? (
            <p className="text-sm text-muted-foreground py-4">
              No log indexes configured. All logs are stored with default settings.
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-12">Priority</TableHead>
                  <TableHead>Name</TableHead>
                  <TableHead>Filter</TableHead>
                  <TableHead className="w-24">Sampling</TableHead>
                  <TableHead className="w-28">Retention</TableHead>
                  <TableHead className="w-20">Active</TableHead>
                  <TableHead className="w-28" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {indexes.map((idx, i) => (
                  <TableRow key={idx.id}>
                    <TableCell>
                      <div className="flex items-center gap-1">
                        <span className="text-xs text-muted-foreground w-4">{idx.priority}</span>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-6 w-6"
                          disabled={i === 0}
                          aria-label={`Move ${idx.name} up`}
                          onClick={() => {
                            const prev = indexes[i - 1]
                            if (prev) {
                              updatePriority.mutate({id: idx.id, priority: prev.priority - 1})
                            }
                          }}
                        >
                          <ArrowUp className="h-3 w-3" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-6 w-6"
                          disabled={i === indexes.length - 1}
                          aria-label={`Move ${idx.name} down`}
                          onClick={() => {
                            const next = indexes[i + 1]
                            if (next) {
                              updatePriority.mutate({id: idx.id, priority: next.priority + 1})
                            }
                          }}
                        >
                          <ArrowDown className="h-3 w-3" />
                        </Button>
                      </div>
                    </TableCell>
                    <TableCell className="font-medium">{idx.name}</TableCell>
                    <TableCell>
                      {idx.filter_query ? (
                        <code className="text-xs bg-muted px-1.5 py-0.5 rounded">{idx.filter_query}</code>
                      ) : (
                        <span className="text-xs text-muted-foreground">catch-all</span>
                      )}
                    </TableCell>
                    <TableCell>
                      {idx.sampling_rate < 1 ? (
                        <Badge variant="secondary">{Math.round(idx.sampling_rate * 100)}%</Badge>
                      ) : (
                        <span className="text-xs text-muted-foreground">100%</span>
                      )}
                    </TableCell>
                    <TableCell className="text-sm">{idx.retention_days}d</TableCell>
                    <TableCell>
                      <Switch
                        checked={idx.is_active}
                        onCheckedChange={(checked) =>
                          toggleActive.mutate({id: idx.id, is_active: checked})
                        }
                      />
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-1">
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-7 w-7"
                          aria-label={`Edit ${idx.name}`}
                          onClick={() => setEditIndex(idx)}
                        >
                          <Pencil className="h-3.5 w-3.5" />
                        </Button>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-7 w-7 text-destructive"
                          aria-label={`Delete ${idx.name}`}
                          onClick={() => setDeleteIndex(idx)}
                        >
                          <Trash2 className="h-3.5 w-3.5" />
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <LogIndexFormDialog
        open={showCreate}
        onClose={() => setShowCreate(false)}
        mode="create"
      />

      {editIndex && (
        <LogIndexFormDialog
          open={!!editIndex}
          onClose={() => setEditIndex(null)}
          mode="edit"
          initial={editIndex}
        />
      )}

      <Dialog open={!!deleteIndex} onOpenChange={() => setDeleteIndex(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Index</DialogTitle>
            <DialogDescription>
              Are you sure you want to delete the index &ldquo;{deleteIndex?.name}&rdquo;?
              Existing logs tagged with this index will remain but new logs will no longer match.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDeleteIndex(null)}>Cancel</Button>
            <Button
              variant="destructive"
              disabled={deleteMutation.isPending}
              onClick={() => deleteIndex && deleteMutation.mutate(deleteIndex.id)}
            >
              {deleteMutation.isPending ? <Loader2 className="h-4 w-4 animate-spin mr-1" /> : null}
              Delete
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

function LogIndexFormDialog({
  open,
  onClose,
  mode,
  initial,
}: {
  open: boolean
  onClose: () => void
  mode: 'create' | 'edit'
  initial?: LogIndex
}) {
  const queryClient = useQueryClient()
  const {toast} = useToast()
  const [name, setName] = useState(initial?.name ?? '')
  const [filterQuery, setFilterQuery] = useState(initial?.filter_query ?? '')
  const [retentionDays, setRetentionDays] = useState(initial?.retention_days ?? 30)
  const [samplingRate, setSamplingRate] = useState(initial?.sampling_rate ?? 1.0)
  const [priority, setPriority] = useState(initial?.priority ?? 0)
  const [testResult, setTestResult] = useState<{match: number; total: number} | null>(null)

  useEffect(() => {
    if (open) {
      // Reset form state when dialog opens so stale values are not shown.
      // eslint-disable-next-line react-hooks/set-state-in-effect
      setName(initial?.name ?? '')
      setFilterQuery(initial?.filter_query ?? '')
      setRetentionDays(initial?.retention_days ?? 30)
      setSamplingRate(initial?.sampling_rate ?? 1.0)
      setPriority(initial?.priority ?? 0)
      setTestResult(null)
    }
  }, [open])

  const createMutation = useMutation({
    mutationFn: (req: CreateLogIndexRequest) => api.createLogIndex(req),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['log-indexes']})
      toast({title: 'Index created'})
      onClose()
    },
    onError: () => toast({title: 'Failed to create index', variant: 'destructive'}),
  })

  const updateMutation = useMutation({
    mutationFn: (req: UpdateLogIndexRequest) => api.updateLogIndex(initial!.id, req),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['log-indexes']})
      toast({title: 'Index updated'})
      onClose()
    },
    onError: () => toast({title: 'Failed to update index', variant: 'destructive'}),
  })

  const testMutation = useMutation({
    mutationFn: (q: string) => api.testLogIndexFilter(q),
    onSuccess: (data) => setTestResult({match: data.match_count, total: data.total_count}),
    onError: () => toast({title: 'Failed to test filter', variant: 'destructive'}),
  })

  const handleSubmit = () => {
    if (mode === 'create') {
      createMutation.mutate({
        name,
        filter_query: filterQuery,
        retention_days: retentionDays,
        sampling_rate: samplingRate,
        priority,
      })
    } else {
      updateMutation.mutate({
        name,
        filter_query: filterQuery,
        retention_days: retentionDays,
        sampling_rate: samplingRate,
        priority,
      })
    }
  }

  const isPending = createMutation.isPending || updateMutation.isPending

  return (
    <Dialog open={open} onOpenChange={onClose}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{mode === 'create' ? 'Create' : 'Edit'} Log Index</DialogTitle>
          <DialogDescription>
            Define a filter to match logs for this index.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-2">
            <Label>Name</Label>
            <Input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g. production-errors"
            />
          </div>
          <div className="space-y-2">
            <Label>Filter Query</Label>
            <div className="flex gap-2">
              <Input
                value={filterQuery}
                onChange={(e) => {
                  setFilterQuery(e.target.value)
                  setTestResult(null)
                }}
                placeholder="e.g. service:api level:error"
                className="flex-1"
              />
              <Button
                variant="outline"
                size="sm"
                disabled={testMutation.isPending}
                onClick={() => testMutation.mutate(filterQuery)}
              >
                {testMutation.isPending ? (
                  <Loader2 className="h-4 w-4 animate-spin" />
                ) : (
                  <FlaskConical className="h-4 w-4" />
                )}
                <span className="ml-1">Test</span>
              </Button>
            </div>
            {testResult && (
              <p className="text-xs text-muted-foreground">
                Matched <strong>{testResult.match.toLocaleString()}</strong> of{' '}
                {testResult.total.toLocaleString()} logs in the last hour
                {testResult.total > 0 && (
                  <> ({Math.round((testResult.match / testResult.total) * 100)}%)</>
                )}
              </p>
            )}
            <p className="text-xs text-muted-foreground">
              Leave empty for a catch-all index. Uses the same syntax as the log search bar.
            </p>
          </div>
          <div className="grid grid-cols-3 gap-4">
            <div className="space-y-2">
              <Label>Retention (days)</Label>
              <Input
                type="number"
                min={1}
                max={365}
                value={retentionDays}
                onChange={(e) => setRetentionDays(parseInt(e.target.value) || 30)}
              />
            </div>
            <div className="space-y-2">
              <Label>Sampling Rate</Label>
              <Input
                type="number"
                min={0}
                max={1}
                step={0.01}
                value={samplingRate}
                onChange={(e) => {
                  const parsed = parseFloat(e.target.value)
                  setSamplingRate(Number.isNaN(parsed) ? 1.0 : parsed)
                }}
              />
            </div>
            <div className="space-y-2">
              <Label>Priority</Label>
              <Input
                type="number"
                value={priority}
                onChange={(e) => setPriority(parseInt(e.target.value) || 0)}
              />
            </div>
          </div>
          <p className="text-xs text-muted-foreground">
            Lower priority numbers are evaluated first. Sampling rate 1.0 = keep all, 0.5 = keep 50%.
          </p>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>Cancel</Button>
          <Button onClick={handleSubmit} disabled={isPending || !name.trim()}>
            {isPending && <Loader2 className="h-4 w-4 animate-spin mr-1" />}
            {mode === 'create' ? 'Create' : 'Save'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
