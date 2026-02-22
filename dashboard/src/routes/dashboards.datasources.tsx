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
import {useQuery, useMutation, useQueryClient} from '@tanstack/react-query'
import {
  api,
  type CustomDataSourceResponse,
  type CreateCustomDataSourceRequest,
  type TestConnectionResult,
} from '@/lib/api'
import {Plus, Database, Trash2, Power, PowerOff, FlaskConical, Check, X, Pencil} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {useState} from 'react'

export const Route = createFileRoute('/dashboards/datasources')({
  component: DataSourcesPage,
})

function DataSourcesPage() {
  const queryClient = useQueryClient()
  const [showForm, setShowForm] = useState(false)
  const [editingId, setEditingId] = useState<number | null>(null)
  const [testResult, setTestResult] = useState<TestConnectionResult | null>(null)
  const [formData, setFormData] = useState<CreateCustomDataSourceRequest>({
    name: '',
    source_type: 'postgresql',
    host: '',
    port: 5432,
    database_name: '',
    username: '',
    password: '',
  })

  const {data: dataSources, isLoading} = useQuery({
    queryKey: ['custom-datasources'],
    queryFn: () => api.listCustomDataSources(),
  })

  const createMutation = useMutation({
    mutationFn: (req: CreateCustomDataSourceRequest) => api.createCustomDataSource(req),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['custom-datasources']})
      queryClient.invalidateQueries({queryKey: ['datasources']})
      resetForm()
    },
  })

  const updateMutation = useMutation({
    mutationFn: ({id, req}: {id: number; req: CreateCustomDataSourceRequest}) =>
      api.updateCustomDataSource(id, req),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['custom-datasources']})
      queryClient.invalidateQueries({queryKey: ['datasources']})
      resetForm()
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (id: number) => api.deleteCustomDataSource(id),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['custom-datasources']})
      queryClient.invalidateQueries({queryKey: ['datasources']})
    },
  })

  const toggleMutation = useMutation({
    mutationFn: ({id, enabled}: {id: number; enabled: boolean}) =>
      api.updateCustomDataSource(id, {enabled}),
    onSuccess: () => queryClient.invalidateQueries({queryKey: ['custom-datasources']}),
  })

  const testMutation = useMutation({
    mutationFn: () =>
      api.testDataSourceConnection({
        source_type: formData.source_type,
        host: formData.host,
        port: formData.port,
        database_name: formData.database_name,
        username: formData.username,
        password: formData.password,
        api_key: formData.api_key,
      }),
    onSuccess: (result) => setTestResult(result),
    onError: () => setTestResult({success: false, message: 'Connection test failed'}),
  })

  function resetForm() {
    setShowForm(false)
    setEditingId(null)
    setTestResult(null)
    setFormData({
      name: '',
      source_type: 'postgresql',
      host: '',
      port: 5432,
      database_name: '',
      username: '',
      password: '',
    })
  }

  function startEdit(ds: CustomDataSourceResponse) {
    setEditingId(ds.id)
    setShowForm(true)
    setTestResult(null)
    setFormData({
      name: ds.name,
      source_type: ds.source_type,
      host: ds.host,
      port: ds.port ?? undefined,
      database_name: ds.database_name ?? undefined,
      description: ds.description ?? undefined,
    })
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (editingId) {
      updateMutation.mutate({id: editingId, req: formData})
    } else {
      createMutation.mutate(formData)
    }
  }

  function updateField(field: string, value: string | number | undefined) {
    setFormData((prev) => ({...prev, [field]: value}))
  }

  const isPostgres = formData.source_type === 'postgresql'

  return (
    <div className="mx-auto max-w-4xl space-y-6 p-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Custom Data Sources</h1>
          <p className="text-muted-foreground text-sm">
            Connect external databases and metrics sources to your dashboards
          </p>
        </div>
        <Button onClick={() => setShowForm(true)} disabled={showForm}>
          <Plus className="mr-2 h-4 w-4" />
          Add Data Source
        </Button>
      </div>

      {showForm && (
        <div className="rounded-lg border p-6">
          <h2 className="mb-4 text-lg font-semibold">{editingId ? 'Edit' : 'New'} Data Source</h2>
          <form onSubmit={handleSubmit} className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="text-sm font-medium">Name</label>
                <input
                  type="text"
                  required
                  className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 text-sm"
                  placeholder="My PostgreSQL"
                  value={formData.name}
                  onChange={(e) => updateField('name', e.target.value)}
                />
              </div>
              <div>
                <label className="text-sm font-medium">Type</label>
                <select
                  className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 text-sm"
                  value={formData.source_type}
                  onChange={(e) => {
                    updateField('source_type', e.target.value)
                    updateField('port', e.target.value === 'postgresql' ? 5432 : 9090)
                  }}
                  disabled={!!editingId}
                >
                  <option value="postgresql">PostgreSQL</option>
                  <option value="prometheus">Prometheus</option>
                </select>
              </div>
            </div>

            <div>
              <label className="text-sm font-medium">Description</label>
              <input
                type="text"
                className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 text-sm"
                placeholder="Optional description"
                value={formData.description ?? ''}
                onChange={(e) => updateField('description', e.target.value || undefined)}
              />
            </div>

            <div className="grid grid-cols-3 gap-4">
              <div className="col-span-2">
                <label className="text-sm font-medium">Host</label>
                <input
                  type="text"
                  required
                  className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 text-sm"
                  placeholder={isPostgres ? 'db.example.com' : 'prometheus.example.com'}
                  value={formData.host}
                  onChange={(e) => updateField('host', e.target.value)}
                />
              </div>
              <div>
                <label className="text-sm font-medium">Port</label>
                <input
                  type="number"
                  className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 text-sm"
                  value={formData.port ?? ''}
                  onChange={(e) => updateField('port', parseInt(e.target.value) || undefined)}
                />
              </div>
            </div>

            {isPostgres && (
              <div>
                <label className="text-sm font-medium">Database Name</label>
                <input
                  type="text"
                  className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 text-sm"
                  placeholder="postgres"
                  value={formData.database_name ?? ''}
                  onChange={(e) => updateField('database_name', e.target.value || undefined)}
                />
              </div>
            )}

            <div className="border-t pt-4">
              <h3 className="mb-3 text-sm font-semibold">Credentials</h3>
              {isPostgres ? (
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="text-sm font-medium">Username</label>
                    <input
                      type="text"
                      className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 text-sm"
                      placeholder="postgres"
                      value={formData.username ?? ''}
                      onChange={(e) => updateField('username', e.target.value || undefined)}
                    />
                  </div>
                  <div>
                    <label className="text-sm font-medium">Password</label>
                    <input
                      type="password"
                      className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 text-sm"
                      placeholder={editingId ? '(unchanged)' : 'Enter password'}
                      value={formData.password ?? ''}
                      onChange={(e) => updateField('password', e.target.value || undefined)}
                    />
                  </div>
                </div>
              ) : (
                <div>
                  <label className="text-sm font-medium">API Key / Bearer Token</label>
                  <input
                    type="password"
                    className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 text-sm"
                    placeholder={editingId ? '(unchanged)' : 'Optional bearer token'}
                    value={formData.api_key ?? ''}
                    onChange={(e) => updateField('api_key', e.target.value || undefined)}
                  />
                </div>
              )}
              <p className="text-muted-foreground mt-2 text-xs">
                Credentials are encrypted at rest using AES-256-GCM and never returned in API responses.
              </p>
            </div>

            {testResult && (
              <div
                className={`rounded-md p-3 text-sm ${testResult.success ? 'bg-green-500/10 text-green-600' : 'bg-red-500/10 text-red-600'}`}
              >
                <div className="flex items-center gap-2">
                  {testResult.success ? <Check className="h-4 w-4" /> : <X className="h-4 w-4" />}
                  {testResult.message}
                </div>
                {testResult.tables && testResult.tables.length > 0 && (
                  <div className="mt-2">
                    <span className="font-medium">Tables: </span>
                    {testResult.tables.slice(0, 10).join(', ')}
                    {testResult.tables.length > 10 && ` (+${testResult.tables.length - 10} more)`}
                  </div>
                )}
                {testResult.metrics && testResult.metrics.length > 0 && (
                  <div className="mt-2">
                    <span className="font-medium">Metrics: </span>
                    {testResult.metrics.slice(0, 10).join(', ')}
                    {testResult.metrics.length > 10 && ` (+${testResult.metrics.length - 10} more)`}
                  </div>
                )}
              </div>
            )}

            <div className="flex gap-2">
              <Button
                type="button"
                variant="outline"
                onClick={() => testMutation.mutate()}
                disabled={!formData.host || testMutation.isPending}
              >
                <FlaskConical className="mr-2 h-4 w-4" />
                {testMutation.isPending ? 'Testing...' : 'Test Connection'}
              </Button>
              <div className="flex-1" />
              <Button type="button" variant="ghost" onClick={resetForm}>
                Cancel
              </Button>
              <Button
                type="submit"
                disabled={createMutation.isPending || updateMutation.isPending || !formData.name || !formData.host}
              >
                {editingId ? 'Update' : 'Create'} Data Source
              </Button>
            </div>
          </form>
        </div>
      )}

      {isLoading ? (
        <div className="text-muted-foreground py-12 text-center">Loading data sources...</div>
      ) : !dataSources?.length ? (
        <div className="rounded-lg border border-dashed py-12 text-center">
          <Database className="text-muted-foreground mx-auto mb-3 h-12 w-12" />
          <h3 className="text-lg font-medium">No custom data sources</h3>
          <p className="text-muted-foreground text-sm">
            Add a PostgreSQL or Prometheus data source to query from your dashboards.
          </p>
        </div>
      ) : (
        <div className="space-y-3">
          {dataSources.map((ds) => (
            <div
              key={ds.id}
              className="flex items-center justify-between rounded-lg border p-4"
            >
              <div className="flex items-center gap-3">
                <div
                  className={`rounded-md p-2 ${ds.enabled ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground'}`}
                >
                  <Database className="h-5 w-5" />
                </div>
                <div>
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{ds.name}</span>
                    <span className="bg-muted rounded px-1.5 py-0.5 text-xs">{ds.source_type}</span>
                    {!ds.enabled && (
                      <span className="rounded bg-yellow-500/10 px-1.5 py-0.5 text-xs text-yellow-600">
                        Disabled
                      </span>
                    )}
                  </div>
                  <div className="text-muted-foreground text-sm">
                    {ds.host}:{ds.port}
                    {ds.database_name && ` / ${ds.database_name}`}
                  </div>
                  {ds.description && (
                    <div className="text-muted-foreground text-xs">{ds.description}</div>
                  )}
                </div>
              </div>
              <div className="flex items-center gap-1">
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => startEdit(ds)}
                  title="Edit"
                >
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
    </div>
  )
}
