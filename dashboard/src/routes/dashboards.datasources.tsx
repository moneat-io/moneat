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
import {Plus, Database, Trash2, Power, PowerOff, FlaskConical, Check, X, Pencil, Link2} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
import {PageHeader} from '@/components/ui/page-header'
import {SectionCard} from '@/components/ui/section-card'
import {EmptyState} from '@/components/ui/empty-state'
import {StatusDot} from '@/components/ui/status-dot'
import {useState, useCallback} from 'react'
import {DataSourceTypePicker} from '@/components/dashboards/DataSourceTypePicker'
import {DATA_SOURCE_TYPES} from '@/components/dashboards/DataSourceTypes'

export const Route = createFileRoute('/dashboards/datasources')({
  component: DataSourcesPage,
})

function DataSourcesPage() {
  const queryClient = useQueryClient()
  const [showForm, setShowForm] = useState(false)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [testResult, setTestResult] = useState<TestConnectionResult | null>(null)
  const [connectionMode, setConnectionMode] = useState<'fields' | 'url'>('fields')
  const [connectionUrl, setConnectionUrl] = useState('')
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
    mutationFn: ({id, req}: {id: string; req: CreateCustomDataSourceRequest}) =>
      api.updateCustomDataSource(id, req),
    onSuccess: () => {
      queryClient.invalidateQueries({queryKey: ['custom-datasources']})
      queryClient.invalidateQueries({queryKey: ['datasources']})
      resetForm()
    },
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

  const buildPayload = useCallback(() => {
    const host =
      formData.host ||
      (formData.source_type === 'cloudwatch' ? 'aws' : '') ||
      (formData.source_type === 'bigquery' ? formData.project_id || '' : '') ||
      'localhost'
    return {...formData, host}
  }, [formData])

  const testMutation = useMutation({
    mutationFn: () => {
      const payload = buildPayload()
      return api.testDataSourceConnection({
        source_type: payload.source_type,
        host: payload.host,
        port: payload.port,
        database_name: payload.database_name,
        username: payload.username,
        password: payload.password,
        api_key: payload.api_key,
        access_key_id: payload.access_key_id,
        secret_access_key: payload.secret_access_key,
        service_account_json: payload.service_account_json,
        account_identifier: payload.account_identifier,
        connection_string: payload.connection_string,
        project_id: payload.project_id,
        region: payload.region,
      })
    },
    onSuccess: (result) => setTestResult(result),
    onError: () => setTestResult({success: false, message: 'Connection test failed'}),
  })

  const DEFAULT_PORTS: Record<string, number> = {
    postgresql: 5432,
    mysql: 3306,
    mariadb: 3306,
    mssql: 1433,
    clickhouse: 8123,
    cockroachdb: 26257,
    prometheus: 9090,
    influxdb: 8086,
    elasticsearch: 9200,
    graphite: 80,
    loki: 3100,
    mongodb: 27017,
    redis: 6379,
  }

  const parseConnectionUrl = useCallback(
    (url: string) => {
      try {
        const normalized = url.replace(/^postgres:\/\//, 'postgresql://')
        if (normalized.startsWith('postgresql://') || normalized.startsWith('postgres://')) {
          const parsed = new URL(normalized.replace(/^postgres(ql)?:\/\//, 'http://'))
          setFormData((prev) => ({
            ...prev,
            host: parsed.hostname || prev.host,
            port: parsed.port ? parseInt(parsed.port) : 5432,
            database_name: parsed.pathname.replace(/^\//, '') || prev.database_name,
            username: parsed.username ? decodeURIComponent(parsed.username) : prev.username,
            password: parsed.password ? decodeURIComponent(parsed.password) : prev.password,
          }))
        } else if (normalized.startsWith('mysql://') || normalized.startsWith('mariadb://')) {
          const parsed = new URL(normalized.replace(/^mysql:\/\//, 'http://').replace(/^mariadb:\/\//, 'http://'))
          setFormData((prev) => ({
            ...prev,
            host: parsed.hostname || prev.host,
            port: parsed.port ? parseInt(parsed.port) : 3306,
            database_name: parsed.pathname.replace(/^\//, '') || prev.database_name,
            username: parsed.username ? decodeURIComponent(parsed.username) : prev.username,
            password: parsed.password ? decodeURIComponent(parsed.password) : prev.password,
          }))
        } else if (normalized.startsWith('mongodb://') || normalized.startsWith('mongodb+srv://')) {
          setFormData((prev) => ({
            ...prev,
            connection_string: url,
          }))
        } else if (normalized.startsWith('redis://')) {
          const parsed = new URL(normalized.replace('redis://', 'http://'))
          setFormData((prev) => ({
            ...prev,
            host: parsed.hostname || prev.host,
            port: parsed.port ? parseInt(parsed.port) : 6379,
            password: parsed.password ? decodeURIComponent(parsed.password) : prev.password,
          }))
        }
      } catch {
        // Invalid URL — don't update fields
      }
    },
    []
  )

  function resetForm() {
    setShowForm(false)
    setEditingId(null)
    setTestResult(null)
    setConnectionMode('fields')
    setConnectionUrl('')
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
    const payload = buildPayload()
    if (editingId) {
      updateMutation.mutate({id: editingId, req: payload})
    } else {
      createMutation.mutate(payload)
    }
  }

  function updateField(field: string, value: string | number | undefined) {
    setFormData((prev) => ({...prev, [field]: value}))
  }

  const isPostgres = formData.source_type === 'postgresql'
  const isJdbcDatabase =
    ['postgresql', 'mysql', 'mariadb', 'mssql', 'clickhouse', 'cockroachdb', 'snowflake'].includes(
      formData.source_type
    )
  const isPrometheus = formData.source_type === 'prometheus'
  const isSqlite = formData.source_type === 'sqlite'
  const isBigQuery = formData.source_type === 'bigquery'
  const isCloudWatch = formData.source_type === 'cloudwatch'
  const isMongoDB = formData.source_type === 'mongodb'
  const isHttpApi = ['prometheus', 'influxdb', 'elasticsearch', 'graphite', 'loki'].includes(
    formData.source_type
  )

  return (
    <div className="mx-auto max-w-4xl space-y-4 p-4">
      <PageHeader
        icon={Database}
        title="Custom Data Sources"
        description="Connect external databases and metrics sources to your dashboards"
        actions={
          <Button size="sm" onClick={() => setShowForm(true)} disabled={showForm} className="gap-1.5 shrink-0">
            <Plus className="h-3.5 w-3.5" />
            Add Data Source
          </Button>
        }
      />

      {showForm && (
        <SectionCard title={`${editingId ? 'Edit' : 'New'} Data Source`} icon={Database}>
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
                <div className="mt-1">
                  <DataSourceTypePicker
                    value={formData.source_type}
                    onChange={(val) => {
                      updateField('source_type', val)
                      updateField('port', DEFAULT_PORTS[val] ?? 5432)
                    }}
                    disabled={!!editingId}
                  />
                </div>
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

            {(isPostgres || (formData.source_type === 'mysql') || (formData.source_type === 'mariadb')) && (
              <div className="flex items-center gap-2">
                <span className="text-sm font-medium">Connection</span>
                <div className="bg-muted inline-flex rounded-md p-0.5">
                  <button
                    type="button"
                    className={`rounded px-2.5 py-1 text-xs font-medium transition-colors ${connectionMode === 'fields' ? 'bg-background' : 'text-muted-foreground hover:text-foreground'}`}
                    onClick={() => setConnectionMode('fields')}
                  >
                    Fields
                  </button>
                  <button
                    type="button"
                    className={`rounded px-2.5 py-1 text-xs font-medium transition-colors ${connectionMode === 'url' ? 'bg-background' : 'text-muted-foreground hover:text-foreground'}`}
                    onClick={() => setConnectionMode('url')}
                  >
                    <Link2 className="mr-1 inline h-3 w-3" />
                    URL
                  </button>
                </div>
              </div>
            )}

            {isBigQuery ? (
              <>
                <div>
                  <label className="text-sm font-medium">Project ID</label>
                  <input
                    type="text"
                    required
                    className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 text-sm"
                    placeholder="my-gcp-project"
                    value={formData.project_id ?? formData.host}
                    onChange={(e) => {
                      updateField('project_id', e.target.value)
                      updateField('host', e.target.value)
                    }}
                  />
                </div>
                <div>
                  <label className="text-sm font-medium">Dataset (optional)</label>
                  <input
                    type="text"
                    className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 text-sm"
                    placeholder="my_dataset"
                    value={formData.database_name ?? ''}
                    onChange={(e) => updateField('database_name', e.target.value || undefined)}
                  />
                </div>
              </>
            ) : isCloudWatch ? (
              <>
                <div>
                  <label className="text-sm font-medium">AWS Region</label>
                  <input
                    type="text"
                    required
                    className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 text-sm"
                    placeholder="us-east-1"
                    value={formData.region ?? ''}
                    onChange={(e) => updateField('region', e.target.value)}
                  />
                </div>
              </>
            ) : isMongoDB ? (
              <>
                <div>
                  <label className="text-sm font-medium">Connection String</label>
                  <input
                    type="text"
                    className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 font-mono text-sm"
                    placeholder="mongodb://user:password@host:27017/dbname"
                    value={formData.connection_string ?? ''}
                    onChange={(e) => updateField('connection_string', e.target.value || undefined)}
                  />
                  <p className="text-muted-foreground mt-1 text-xs">
                    Paste a MongoDB connection string, or use host/port below.
                  </p>
                </div>
                {!formData.connection_string && (
                  <div className="grid grid-cols-3 gap-4">
                    <div className="col-span-2">
                      <label className="text-sm font-medium">Host</label>
                      <input
                        type="text"
                        className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 text-sm"
                        placeholder="localhost"
                        value={formData.host}
                        onChange={(e) => updateField('host', e.target.value)}
                      />
                    </div>
                    <div>
                      <label className="text-sm font-medium">Port</label>
                      <input
                        type="number"
                        className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 text-sm"
                        value={formData.port ?? 27017}
                        onChange={(e) => updateField('port', parseInt(e.target.value) || undefined)}
                      />
                    </div>
                  </div>
                )}
              </>
            ) : (isPostgres || isJdbcDatabase) && !isSqlite && connectionMode === 'url' ? (
              <div>
                <label className="text-sm font-medium">Connection URL</label>
                <input
                  type="text"
                  className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 font-mono text-sm"
                  placeholder={
                    formData.source_type === 'postgresql'
                      ? 'postgresql://user:password@host:5432/dbname'
                      : formData.source_type === 'mysql' || formData.source_type === 'mariadb'
                        ? 'mysql://user:password@host:3306/dbname'
                        : 'postgresql://user:password@host:5432/dbname'
                  }
                  value={connectionUrl}
                  onChange={(e) => {
                    setConnectionUrl(e.target.value)
                    parseConnectionUrl(e.target.value)
                  }}
                />
                <p className="text-muted-foreground mt-1 text-xs">
                  Paste a connection URL and the fields below will auto-populate.
                </p>
                {connectionUrl && formData.host && (
                  <div className="bg-muted/50 mt-2 rounded-md px-3 py-2 text-xs">
                    <span className="text-muted-foreground">Parsed: </span>
                    {formData.username && <span>{formData.username}@</span>}
                    <span className="font-medium">{formData.host}</span>
                    {formData.port && <span>:{formData.port}</span>}
                    {formData.database_name && <span>/{formData.database_name}</span>}
                  </div>
                )}
              </div>
            ) : (
              <>
                {isSqlite ? (
                  <div>
                    <label className="text-sm font-medium">Database File Path</label>
                    <input
                      type="text"
                      required
                      className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 text-sm"
                      placeholder="/path/to/database.db"
                      value={formData.host}
                      onChange={(e) => updateField('host', e.target.value)}
                    />
                  </div>
                ) : !isBigQuery && !isCloudWatch ? (
                  <>
                    <div className="grid grid-cols-3 gap-4">
                      <div className="col-span-2">
                        <label className="text-sm font-medium">
                          {formData.source_type === 'snowflake' ? 'Account Identifier' : 'Host'}
                        </label>
                        <input
                          type="text"
                          required={!isMongoDB}
                          className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 text-sm"
                          placeholder={
                            formData.source_type === 'snowflake'
                              ? 'xy12345.us-east-1'
                              : isPostgres
                                ? 'db.example.com'
                                : isPrometheus
                                  ? 'prometheus.example.com'
                                  : 'host.example.com'
                          }
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

                    {(isJdbcDatabase || isHttpApi) && !isSqlite && (
                      <div>
                        <label className="text-sm font-medium">Database Name</label>
                        <input
                          type="text"
                          className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 text-sm"
                          placeholder={
                            formData.source_type === 'clickhouse'
                              ? 'default'
                              : formData.source_type === 'cockroachdb'
                                ? 'defaultdb'
                                : 'postgres'
                          }
                          value={formData.database_name ?? ''}
                          onChange={(e) => updateField('database_name', e.target.value || undefined)}
                        />
                      </div>
                    )}
                  </>
                ) : null}
              </>
            )}

            <div className="border-t pt-4">
              <h3 className="mb-3 text-sm font-semibold">Credentials</h3>
              {isBigQuery ? (
                <div>
                  <label className="text-sm font-medium">Service Account JSON</label>
                  <textarea
                    className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 font-mono text-sm"
                    rows={6}
                    placeholder='{"type":"service_account","project_id":"..."}'
                    value={formData.service_account_json ?? ''}
                    onChange={(e) => updateField('service_account_json', e.target.value || undefined)}
                  />
                  <p className="text-muted-foreground mt-1 text-xs">
                    Paste the contents of your GCP service account JSON key file.
                  </p>
                </div>
              ) : isCloudWatch ? (
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="text-sm font-medium">Access Key ID</label>
                    <input
                      type="text"
                      className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 text-sm"
                      placeholder="AKIA..."
                      value={formData.access_key_id ?? ''}
                      onChange={(e) => updateField('access_key_id', e.target.value || undefined)}
                    />
                  </div>
                  <div>
                    <label className="text-sm font-medium">Secret Access Key</label>
                    <input
                      type="password"
                      className="border-input bg-background mt-1 w-full rounded-md border px-3 py-2 text-sm"
                      placeholder={editingId ? '(unchanged)' : 'Enter secret key'}
                      value={formData.secret_access_key ?? ''}
                      onChange={(e) => updateField('secret_access_key', e.target.value || undefined)}
                    />
                  </div>
                </div>
              ) : isJdbcDatabase && !isSqlite ? (
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
              ) : isMongoDB && formData.connection_string ? (
                <p className="text-muted-foreground text-xs">
                  Credentials are included in the connection string.
                </p>
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
                className={`rounded-md border p-3 text-sm ${testResult.success ? 'bg-success-bg text-success-fg border-success-border' : 'bg-danger-bg text-danger-fg border-danger-border'}`}
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
                disabled={
                  testMutation.isPending ||
                  (isBigQuery && !formData.project_id && !formData.host) ||
                  (isCloudWatch && !formData.region) ||
                  (isMongoDB && !formData.connection_string && !formData.host) ||
                  (!isBigQuery && !isCloudWatch && !isMongoDB && !formData.host)
                }
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
                disabled={
                  createMutation.isPending ||
                  updateMutation.isPending ||
                  !formData.name ||
                  (isBigQuery ? !formData.project_id && !formData.host : isCloudWatch ? !formData.region : isMongoDB ? !formData.connection_string && !formData.host : !formData.host)
                }
              >
                {editingId ? 'Update' : 'Create'} Data Source
              </Button>
            </div>
          </form>
        </SectionCard>
      )}

      {isLoading ? (
        <div className="text-muted-foreground py-12 text-center">Loading data sources...</div>
      ) : !dataSources?.length ? (
        <EmptyState
          icon={Database}
          title="No custom data sources"
          description="Add a PostgreSQL or Prometheus data source to query from your dashboards."
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
                    {!ds.enabled && (
                      <Badge variant="warning" size="sm">Disabled</Badge>
                    )}
                  </div>
                  <div className="text-muted-foreground text-sm font-mono">
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
