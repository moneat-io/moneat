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
import {api, type CustomDataSourceResponse} from '@/lib/api'
import {Database, Pencil, Plus, Power, PowerOff, Trash2} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
import {PageHeader} from '@/components/ui/page-header'
import {EmptyState} from '@/components/ui/empty-state'
import {StatusDot} from '@/components/ui/status-dot'
import {useState} from 'react'
import {DATA_SOURCE_TYPES} from '@/components/dashboards/DataSourceTypes'
import {DataSourceConnectDialog} from '@/components/dashboards/DataSourceConnectDialog'

type DataSourcesSearch = Readonly<{
  new?: number
  edit?: string
}>

export const Route = createFileRoute('/dashboards/datasources')({
  validateSearch: (search: Record<string, unknown>): DataSourcesSearch => ({
    new: searchFlag(search.new),
    edit: searchResourceId(search.edit) ?? undefined,
  }),
  component: DataSourcesPage,
})

function searchFlag(value: unknown): number | undefined {
  if (value === true) return 1
  return searchResourceId(value) || value === 1 || value === '1' ? 1 : undefined
}

function searchResourceId(value: unknown): string | null {
  if (typeof value !== 'string' || !value) return null
  return /^[0-9a-fA-F-]{36}$/.test(value) ? value : null
}

interface DialogState {
  open: boolean
  mode: 'create' | 'edit'
  editId?: string
}

function DataSourcesPage() {
  const search = Route.useSearch()
  const navigate = Route.useNavigate()
  const queryClient = useQueryClient()

  // Open state is seeded once from the URL (?new / ?edit) then driven locally.
  const [dialog, setDialog] = useState<DialogState>(() => {
    if (search.edit) return {open: true, mode: 'edit', editId: search.edit}
    if (search.new) return {open: true, mode: 'create'}
    return {open: false, mode: 'create'}
  })

  const {data: dataSources, isLoading} = useQuery({
    queryKey: ['custom-datasources'],
    queryFn: () => api.listCustomDataSources(),
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api.deleteCustomDataSource(id),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['custom-datasources']})
      queryClient.invalidateQueries({queryKey: ['datasources']})
    },
  })

  const toggleMutation = useMutation({
    mutationFn: ({id, enabled}: {id: string; enabled: boolean}) =>
      api.updateCustomDataSource(id, {enabled}),
    onSuccess: () => queryClient.invalidateQueries({queryKey: ['custom-datasources']}),
  })

  const openCreate = () => setDialog({open: true, mode: 'create'})
  const openEdit = (ds: CustomDataSourceResponse) =>
    setDialog({open: true, mode: 'edit', editId: ds.id})
  const closeDialog = () => {
    setDialog((d) => ({...d, open: false}))
    if (search.new || search.edit) {
      navigate({search: {}, replace: true}).catch(() => {})
    }
  }

  const editSource =
    dialog.mode === 'edit' ? dataSources?.find((d) => d.id === dialog.editId) : undefined
  const dialogReady = dialog.open && (dialog.mode === 'create' || editSource != null)

  return (
    <div className="mx-auto max-w-4xl space-y-4 p-4">
      <PageHeader
        icon={Database}
        title="Data Sources"
        description="Connect external databases and metrics backends to query from your dashboards"
        actions={
          <Button size="sm" onClick={openCreate} className="gap-1.5 shrink-0">
            <Plus className="h-3.5 w-3.5" />
            Add data source
          </Button>
        }
      />

      {isLoading ? (
        <div className="text-muted-foreground py-12 text-center">Loading data sources...</div>
      ) : !dataSources?.length ? (
        <EmptyState
          icon={Database}
          title="No data sources yet"
          description="Connect a database, a metrics backend like Prometheus, or a cloud source to query it from your dashboards."
          action={
            <Button size="sm" onClick={openCreate} className="gap-1.5">
              <Plus className="h-3.5 w-3.5" />
              Add data source
            </Button>
          }
        />
      ) : (
        <div className="space-y-3">
          {dataSources.map((ds) => (
            <div
              key={ds.id}
              className="flex items-center justify-between rounded-lg border p-4 hover:bg-accent/40 transition-colors"
            >
              <div className="flex items-center gap-3">
                <div
                  className={`flex items-center justify-center rounded-md p-2 ${ds.enabled ? 'bg-[hsl(var(--primary)/0.12)] text-primary' : 'bg-muted text-muted-foreground'}`}
                >
                  {DATA_SOURCE_TYPES.find((t) => t.value === ds.source_type)?.logo || (
                    <Database className="h-5 w-5" />
                  )}
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <StatusDot tone={ds.enabled ? 'success' : 'neutral'} size="sm" />
                    <span className="font-medium">{ds.name}</span>
                    <Badge variant="neutral" size="sm">{ds.source_type}</Badge>
                    {!ds.enabled && <Badge variant="warning" size="sm">Disabled</Badge>}
                  </div>
                  <div className="text-muted-foreground text-sm font-mono">
                    {ds.host}
                    {ds.port ? `:${ds.port}` : ''}
                    {ds.database_name && ` / ${ds.database_name}`}
                  </div>
                  {ds.description && (
                    <div className="text-muted-foreground text-xs">{ds.description}</div>
                  )}
                </div>
              </div>
              <div className="flex items-center gap-1">
                <Button variant="ghost" size="sm" onClick={() => openEdit(ds)} title="Edit">
                  <Pencil className="h-4 w-4" />
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => toggleMutation.mutate({id: ds.id, enabled: !ds.enabled})}
                  title={ds.enabled ? 'Disable' : 'Enable'}
                >
                  {ds.enabled ? <PowerOff className="h-4 w-4" /> : <Power className="h-4 w-4" />}
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    if (confirm(`Delete data source "${ds.name}"?`)) {
                      deleteMutation.mutate(ds.id)
                    }
                  }}
                  title="Delete"
                  className="text-destructive hover:text-destructive"
                >
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            </div>
          ))}
        </div>
      )}

      {dialogReady && (
        <DataSourceConnectDialog
          key={dialog.mode === 'edit' ? `edit-${dialog.editId}` : 'create'}
          mode={dialog.mode}
          initial={editSource}
          onClose={closeDialog}
        />
      )}
    </div>
  )
}
