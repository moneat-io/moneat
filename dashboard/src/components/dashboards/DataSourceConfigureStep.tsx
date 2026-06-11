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

import {type ReactNode, useState} from 'react'
import {
  ArrowLeft, BookOpen, ChevronRight, Eye, EyeOff, FlaskConical, Info,
  Link2, Loader2, Lock, Sliders, Target, TriangleAlert, Upload, Check, X,
} from 'lucide-react'
import {cn} from '@/lib/utils'
import type {TestConnectionResult} from '@/lib/api'
import {AWS_REGIONS, CATEGORY_LABELS, type VendorDef} from './dataSourceCatalog'
import {DataSourceLogo} from './DataSourceLogo'
import {
  type DsFormState, type PreviewTone,
  buildEndpointPreview, smartSplitHost, validatePort,
} from './dataSourceConnection'

interface ConfigureStepProps {
  readonly state: DsFormState
  readonly vendor: VendorDef
  readonly editing: boolean
  readonly update: (patch: Partial<DsFormState>) => void
  readonly onBack: () => void
  readonly testResult: TestConnectionResult | null
  readonly testing: boolean
  readonly onTest: () => void
}

const INPUT =
  'h-8 w-full rounded-md border border-input bg-background px-2.5 text-sm text-foreground outline-none transition-colors focus:border-ring focus:ring-2 focus:ring-ring/20 placeholder:text-muted-foreground/60'
const SELECT = INPUT + ' cursor-pointer pr-7'

const TONE_COLOR: Record<PreviewTone, string> = {
  scheme: '#7dd3fc', host: '#9ee3ff', port: '#f0c150', path: '#7fd0a6',
  db: '#e1888c', user: '#cdd6e0', pw: '#6e7480', muted: '#8a99a9',
}

// ---- small field primitives (module-scope, not exported) -------------------

function Field(props: {
  label: ReactNode; value: string; onChange: (v: string) => void; id: string
  placeholder?: string; hint?: ReactNode; optional?: boolean; mono?: boolean
  invalid?: boolean; errorText?: string
}) {
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <label htmlFor={props.id} className="flex items-center gap-1.5 text-xs font-medium text-foreground">
        {props.label}
        {props.optional && <span className="font-normal text-muted-foreground/70">optional</span>}
      </label>
      <input
        id={props.id}
        value={props.value}
        onChange={(e) => props.onChange(e.target.value)}
        placeholder={props.placeholder}
        className={cn(INPUT, props.mono && 'font-mono text-xs', props.invalid && 'border-danger-border focus:ring-danger-border/30')}
      />
      {props.hint && <span className="text-[10px] leading-snug text-muted-foreground/70">{props.hint}</span>}
      {props.invalid && props.errorText && (
        <span className="flex items-center gap-1 text-[10px] text-danger-fg">
          <TriangleAlert className="h-3 w-3" /> {props.errorText}
        </span>
      )}
    </div>
  )
}

function PasswordField(props: {
  label: ReactNode; value: string; onChange: (v: string) => void; id: string
  placeholder?: string; hint?: ReactNode
}) {
  const [show, setShow] = useState(false)
  return (
    <div className="flex min-w-0 flex-col gap-1.5">
      <label htmlFor={props.id} className="text-xs font-medium text-foreground">{props.label}</label>
      <div className="relative">
        <input
          id={props.id}
          type={show ? 'text' : 'password'}
          value={props.value}
          onChange={(e) => props.onChange(e.target.value)}
          placeholder={props.placeholder}
          autoComplete="off"
          className={cn(INPUT, 'pr-9')}
        />
        <button
          type="button"
          onClick={() => setShow((s) => !s)}
          aria-label={show ? 'Hide value' : 'Show value'}
          className="absolute right-1.5 top-1/2 grid h-6 w-6 -translate-y-1/2 place-items-center text-muted-foreground/70 hover:text-foreground"
        >
          {show ? <EyeOff className="h-3.5 w-3.5" /> : <Eye className="h-3.5 w-3.5" />}
        </button>
      </div>
      {props.hint && <span className="text-[10px] leading-snug text-muted-foreground/70">{props.hint}</span>}
    </div>
  )
}

function CredGroup({title = 'Credentials', children}: {title?: string; children: ReactNode}) {
  return (
    <div className="flex flex-col gap-3 rounded-md border border-border/70 bg-background p-3">
      <div className="flex items-center gap-1.5 text-[0.6875rem] font-bold uppercase tracking-wider text-muted-foreground/80">
        <Lock className="h-3 w-3" /> {title}
      </div>
      {children}
      <div className="flex items-center gap-1.5 text-[10px] text-muted-foreground">
        <Lock className="h-3 w-3 text-success-solid" /> Encrypted at rest · never returned in API responses.
      </div>
    </div>
  )
}

function Segmented<T extends string>(props: {
  value: T; onChange: (v: T) => void; options: ReadonlyArray<{value: T; label: ReactNode}>
}) {
  return (
    <div className="inline-flex rounded-md border border-input bg-muted p-0.5">
      {props.options.map((o) => (
        <button
          key={o.value}
          type="button"
          onClick={() => props.onChange(o.value)}
          className={cn(
            'inline-flex items-center gap-1.5 rounded-sm px-2.5 py-1 text-xs font-medium transition-colors',
            props.value === o.value
              ? 'bg-card text-foreground shadow-sm'
              : 'text-muted-foreground hover:text-foreground'
          )}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

function Advanced({children}: {children: ReactNode}) {
  const [open, setOpen] = useState(false)
  return (
    <div className="mt-3 border-t border-dashed border-border/70 pt-3">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground hover:text-foreground"
      >
        <ChevronRight className={cn('h-3 w-3 transition-transform', open && 'rotate-90')} /> Advanced options
      </button>
      {open && <div className="mt-3 grid gap-3">{children}</div>}
    </div>
  )
}

// ---- the configure step ----------------------------------------------------

export function DataSourceConfigureStep({
  state, vendor, editing, update, onBack, testResult, testing, onTest,
}: ConfigureStepProps) {
  const [adjust, setAdjust] = useState<{text: string; prev: Partial<DsFormState>} | null>(null)
  const preview = buildEndpointPreview(state)
  const portCheck = validatePort(state.port)
  const credPlaceholder = editing ? '(unchanged)' : '••••••••'

  function onHostChange(value: string) {
    if (!/[:/@]/.test(value)) {
      update({host: value})
      setAdjust(null)
      return
    }
    const r = smartSplitHost(value)
    if (!r.didSplit) {
      update({host: value})
      setAdjust(null)
      return
    }
    const prev: Partial<DsFormState> = {host: state.host, scheme: state.scheme, port: state.port, basePath: state.basePath}
    const patch: Partial<DsFormState> = {host: r.host}
    const parts: string[] = []
    const httpish = vendor.arch === 'http' || vendor.arch === 'influx'
    if (r.scheme && httpish) {
      patch.scheme = r.scheme
      parts.push(`scheme → ${r.scheme}`)
    }
    if (r.port) {
      patch.port = r.port
      parts.push(`port → ${r.port}`)
    }
    if (r.path && r.path !== '/' && vendor.arch === 'http') {
      patch.basePath = r.path
      parts.push(`path → ${r.path}`)
    }
    update(patch)
    setAdjust(parts.length > 0 ? {text: `Split your pasted value — ${parts.join(', ')}.`, prev} : null)
  }

  async function onServiceAccountFile(files: FileList | null) {
    const file = files?.[0]
    if (!file) return
    update({serviceAccount: await file.text()})
  }

  const adjustBanner = adjust && (
    <div className="flex w-fit items-center gap-1.5 rounded-sm border border-info-border bg-info-bg px-2 py-1 text-[10px] text-info-fg">
      <Info className="h-3 w-3" /> {adjust.text}
      <button
        type="button"
        className="font-semibold underline"
        onClick={() => {
          update(adjust.prev)
          setAdjust(null)
        }}
      >
        undo
      </button>
    </div>
  )
  const portError = !portCheck.ok && (
    <span className="flex items-center gap-1 text-[10px] text-danger-fg">
      <TriangleAlert className="h-3 w-3" /> {portCheck.message}
    </span>
  )

  const nameAndDescription = (
    <div className="grid grid-cols-1 gap-3 border-t border-border/70 pt-3 sm:grid-cols-2">
      <Field id="ds-name" label="Display name" value={state.name} onChange={(v) => update({name: v})}
        placeholder="auto from type + host" hint="Shown across dashboards & the source picker." />
      <Field id="ds-desc" label="Description" optional value={state.description} onChange={(v) => update({description: v})}
        placeholder="e.g. Prod read replica" />
    </div>
  )

  const hostPortRow = (full = false) => (
    <>
      <div className={cn('grid gap-3', full ? 'grid-cols-[104px_1fr_116px]' : 'grid-cols-[1fr_116px]')}>
        {full && (
          <div className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-foreground">Scheme</span>
            <Segmented
              value={state.scheme}
              onChange={(v) => update({scheme: v})}
              options={[{value: 'http', label: 'http'}, {value: 'https', label: 'https'}]}
            />
          </div>
        )}
        <Field id="ds-host" label="Host" value={state.host} onChange={onHostChange}
          placeholder={vendor.arch === 'http' || vendor.arch === 'influx' ? 'metrics.example.com' : 'db.internal.example.com'}
          hint="Hostname or IP only — no scheme, port, or path." />
        <Field id="ds-port" label="Port" value={state.port} onChange={(v) => update({port: v})}
          placeholder={String(vendor.port ?? '')} invalid={!portCheck.ok} />
      </div>
      {adjustBanner}
      {portError}
    </>
  )

  return (
    <div>
      {/* identity header */}
      <div className="mb-3 flex items-center gap-2.5">
        <button type="button" onClick={onBack} aria-label="Choose a different source"
          className="grid h-7 w-7 place-items-center rounded-md border border-border bg-card text-muted-foreground hover:bg-accent/40 hover:text-foreground">
          <ArrowLeft className="h-3.5 w-3.5" />
        </button>
        <DataSourceLogo type={vendor.key} size={28} />
        <div className="min-w-0">
          <div className="text-[0.9375rem] font-semibold text-foreground">{vendor.label}</div>
          <div className="text-xs text-muted-foreground">{CATEGORY_LABELS[vendor.category]}</div>
        </div>
        <a href="/docs" target="_blank" rel="noopener noreferrer"
          className="ml-auto inline-flex items-center gap-1.5 text-xs text-accent-foreground/90 hover:underline">
          <BookOpen className="h-3 w-3" /> Setup guide
        </a>
      </div>

      {/* connection method */}
      {vendor.supportsString && (
        <div className="mb-3 flex items-center gap-2">
          <span className="text-xs text-muted-foreground">Connection method</span>
          <Segmented
            value={state.method}
            onChange={(v) => update({method: v})}
            options={[
              {value: 'guided', label: <><Sliders className="h-3 w-3" /> Guided fields</>},
              {value: 'string', label: <><Link2 className="h-3 w-3" /> Connection string</>},
            ]}
          />
        </div>
      )}

      {/* live endpoint preview */}
      <div className="relative mb-4 overflow-hidden rounded-md border border-border" style={{background: '#0c0f15'}}>
        <button
          type="button"
          onClick={onTest}
          disabled={testing}
          className="absolute right-3 top-2.5 inline-flex h-6 items-center gap-1.5 rounded-md border border-white/15 bg-white/5 px-2.5 text-xs font-semibold text-slate-200 hover:bg-white/10 disabled:opacity-60"
        >
          {testing ? <Loader2 className="h-3 w-3 animate-spin" /> : <FlaskConical className="h-3 w-3" />}
          {testing ? 'Testing…' : 'Test connection'}
        </button>
        <div className="px-3 pb-2.5 pt-2.5">
          <div className="mb-1.5 flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider" style={{color: '#7d8a99'}}>
            <Target className="h-3 w-3" /> Moneat will connect to
          </div>
          <div className="break-all font-mono text-sm leading-relaxed">
            {preview.segments.map((seg, i) => (
              <span key={i} style={{color: TONE_COLOR[seg.tone]}}>{seg.text}</span>
            ))}
          </div>
          <div className="mt-1.5 text-xs" style={{color: '#8a99a9'}}>
            {!preview.portOk && <span style={{color: '#f0c150'}}>⚠ fix the port to continue · </span>}
            {preview.sub}
          </div>
        </div>
      </div>

      {/* test result */}
      {testResult && (
        <div className={cn('mb-4 flex flex-col gap-1 rounded-md border p-2.5 text-xs',
          testResult.success ? 'border-success-border bg-success-bg text-success-fg' : 'border-danger-border bg-danger-bg text-danger-fg')}>
          <div className="flex items-center gap-1.5 font-semibold">
            {testResult.success ? <Check className="h-3.5 w-3.5" /> : <X className="h-3.5 w-3.5" />}
            {testResult.message}
          </div>
          {testResult.tables && testResult.tables.length > 0 && (
            <span className="font-mono text-muted-foreground">tables: {testResult.tables.slice(0, 8).join(', ')}</span>
          )}
          {testResult.metrics && testResult.metrics.length > 0 && (
            <span className="font-mono text-muted-foreground">metrics: {testResult.metrics.slice(0, 8).join(', ')}</span>
          )}
        </div>
      )}

      {/* fields */}
      <div className="grid gap-3">
        {state.method === 'string' ? (
          <>
            <Field id="ds-connstr" label="Connection string" mono value={state.connStr} onChange={(v) => update({connStr: v})}
              placeholder={vendor.stringPlaceholder ?? `${vendor.scheme ?? 'scheme'}://user:pass@host:${vendor.port ?? ''}/db`}
              hint="Paste a full URI. We parse host, port, database and credentials from it — nothing is sent anywhere until you press Test." />
            {nameAndDescription}
          </>
        ) : vendor.arch === 'sql' || vendor.arch === 'clickhouse' ? (
          <>
            {vendor.arch === 'clickhouse' && (
              <div className="flex flex-col gap-1.5">
                <span className="text-xs font-medium text-foreground">Protocol</span>
                <Segmented
                  value={state.chProtocol}
                  onChange={(v) => update({chProtocol: v, port: v === 'native' ? '9000' : '8123'})}
                  options={[{value: 'http', label: 'HTTP · 8123'}, {value: 'native', label: 'Native · 9000'}]}
                />
                <span className="text-[10px] text-muted-foreground/70">HTTP interface is firewall-friendly; native is lower-latency.</span>
              </div>
            )}
            {hostPortRow(false)}
            <div className="grid grid-cols-2 gap-3">
              <Field id="ds-db" label="Database" value={state.database} onChange={(v) => update({database: v})}
                placeholder={vendor.dbPlaceholder ?? 'database'} />
              <Field id="ds-user" label="Username" value={state.username} onChange={(v) => update({username: v})}
                placeholder={vendor.userPlaceholder ?? 'user'} />
            </div>
            <CredGroup>
              <PasswordField id="ds-pass" label="Password" value={state.password} onChange={(v) => update({password: v})} placeholder={credPlaceholder} />
            </CredGroup>
            {nameAndDescription}
            <Advanced>
              {vendor.arch === 'sql' && (
                <div className="flex flex-col gap-1.5">
                  <label htmlFor="ds-tls" className="text-xs font-medium text-foreground">TLS / SSL mode</label>
                  <select id="ds-tls" className={SELECT} value={state.tlsMode} onChange={(e) => update({tlsMode: e.target.value})}>
                    <option value="disable">Disable — no encryption</option>
                    <option value="require">Require — encrypt, skip cert check</option>
                    <option value="verify-ca">Verify CA</option>
                    <option value="verify-full">Verify full — encrypt + hostname check</option>
                  </select>
                  <span className="text-[10px] text-muted-foreground/70">Most managed databases require TLS.</span>
                </div>
              )}
              <Field id="ds-timeout" label="Connect timeout (s)" optional value={state.timeout} onChange={(v) => update({timeout: v})} placeholder="10" />
              <Field id="ds-schema" label="Default schema" optional value={state.schema} onChange={(v) => update({schema: v})} placeholder="public" />
            </Advanced>
          </>
        ) : vendor.arch === 'http' ? (
          <>
            {hostPortRow(true)}
            <Field id="ds-path" label="Base path" optional mono value={state.basePath} onChange={(v) => update({basePath: v})}
              placeholder="/"
              hint={<>Prefix if it sits behind a reverse proxy (e.g. <code className="font-mono">/prometheus</code>). The API path <code className="font-mono">{vendor.apiPath}</code> is appended automatically.</>} />
            {vendor.indexField && (
              <Field id="ds-index" label={vendor.indexField} optional value={state.database} onChange={(v) => update({database: v})} placeholder={vendor.indexPlaceholder} />
            )}
            <div className="flex flex-col gap-1.5">
              <label htmlFor="ds-auth" className="text-xs font-medium text-foreground">Authentication</label>
              <select id="ds-auth" className={SELECT} value={state.authMethod} onChange={(e) => update({authMethod: e.target.value as DsFormState['authMethod']})}>
                <option value="none">None (open / network-restricted)</option>
                <option value="basic">Basic auth</option>
                <option value="bearer">Bearer token</option>
                <option value="header">Custom header (API key)</option>
              </select>
            </div>
            {state.authMethod === 'basic' && (
              <CredGroup>
                <div className="grid grid-cols-2 gap-3">
                  <Field id="ds-user" label="Username" value={state.username} onChange={(v) => update({username: v})} placeholder="user" />
                  <PasswordField id="ds-pass" label="Password" value={state.password} onChange={(v) => update({password: v})} placeholder={credPlaceholder} />
                </div>
              </CredGroup>
            )}
            {state.authMethod === 'bearer' && (
              <CredGroup>
                <PasswordField id="ds-token" label="Bearer token" value={state.token} onChange={(v) => update({token: v})} placeholder="eyJhbGciOi…" />
              </CredGroup>
            )}
            {state.authMethod === 'header' && (
              <CredGroup>
                <div className="grid grid-cols-2 gap-3">
                  <Field id="ds-hk" label="Header name" value={state.headerName} onChange={(v) => update({headerName: v})} placeholder="X-Scope-OrgID" />
                  <PasswordField id="ds-hv" label="Header value" value={state.headerValue} onChange={(v) => update({headerValue: v})} placeholder={credPlaceholder} />
                </div>
              </CredGroup>
            )}
            {nameAndDescription}
            <Advanced>
              <Field id="ds-timeout" label="Request timeout (s)" optional value={state.timeout} onChange={(v) => update({timeout: v})} placeholder="30" />
            </Advanced>
          </>
        ) : vendor.arch === 'influx' ? (
          <>
            <div className="flex flex-col gap-1.5">
              <span className="text-xs font-medium text-foreground">InfluxDB version</span>
              <Segmented
                value={state.influxVersion}
                onChange={(v) => update({influxVersion: v})}
                options={[{value: '2', label: '2.x (Flux)'}, {value: '1', label: '1.x (InfluxQL)'}]}
              />
            </div>
            {hostPortRow(true)}
            {state.influxVersion === '2' ? (
              <>
                <div className="grid grid-cols-2 gap-3">
                  <Field id="ds-org" label="Organization" value={state.org} onChange={(v) => update({org: v})} placeholder="my-org" />
                  <Field id="ds-bucket" label="Bucket" value={state.bucket} onChange={(v) => update({bucket: v})} placeholder="metrics" />
                </div>
                <CredGroup title="API token">
                  <PasswordField id="ds-token" label="API token" value={state.token} onChange={(v) => update({token: v})}
                    placeholder={credPlaceholder} hint="Generate a read or all-access token in InfluxDB → Load Data → API Tokens." />
                </CredGroup>
              </>
            ) : (
              <>
                <Field id="ds-db" label="Database" value={state.database} onChange={(v) => update({database: v})} placeholder="telegraf" />
                <CredGroup>
                  <div className="grid grid-cols-2 gap-3">
                    <Field id="ds-user" label="Username" optional value={state.username} onChange={(v) => update({username: v})} placeholder="admin" />
                    <PasswordField id="ds-pass" label="Password" value={state.password} onChange={(v) => update({password: v})} placeholder={credPlaceholder} />
                  </div>
                </CredGroup>
              </>
            )}
            {nameAndDescription}
          </>
        ) : vendor.arch === 'file' ? (
          <>
            <Field id="ds-host" label="Database file path" mono value={state.host} onChange={(v) => update({host: v})}
              placeholder="/var/lib/moneat/analytics.db"
              hint="Absolute path on the Moneat server. The file must be readable by the Moneat service account." />
            <div className="flex items-start gap-2 rounded-md border border-info-border bg-info-bg p-2.5 text-xs text-info-fg">
              <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />
              <span>SQLite is a local file — there is no host, port, or network credential to configure.</span>
            </div>
            {nameAndDescription}
          </>
        ) : vendor.arch === 'connstr' ? (
          <>
            <Field id="ds-connstr" label="Connection string" mono value={state.connStr} onChange={(v) => update({connStr: v})}
              placeholder={vendor.stringPlaceholder}
              hint={`The recommended way to connect ${vendor.label}. Credentials and options travel inside the URI.`} />
            <button type="button" onClick={() => update({manual: !state.manual})}
              className="flex w-fit items-center gap-1.5 text-xs font-medium text-muted-foreground hover:text-foreground">
              <ChevronRight className={cn('h-3 w-3 transition-transform', state.manual && 'rotate-90')} /> Enter host &amp; port manually instead
            </button>
            {state.manual && (
              <div className="grid gap-3">
                {hostPortRow(false)}
                <div className="grid grid-cols-2 gap-3">
                  <Field id="ds-db" label={vendor.dbPlaceholder === 'db index' ? 'Database (index)' : 'Database'} optional
                    value={state.database} onChange={(v) => update({database: v})} placeholder={vendor.dbPlaceholder} />
                  <Field id="ds-user" label="Username" optional value={state.username} onChange={(v) => update({username: v})} placeholder="default" />
                </div>
                <CredGroup>
                  <PasswordField id="ds-pass" label="Password" value={state.password} onChange={(v) => update({password: v})} placeholder={credPlaceholder} />
                </CredGroup>
              </div>
            )}
            {nameAndDescription}
          </>
        ) : vendor.arch === 'bigquery' ? (
          <>
            <div className="grid grid-cols-2 gap-3">
              <Field id="ds-proj" label="GCP Project ID" value={state.projectId} onChange={(v) => update({projectId: v})} placeholder="my-gcp-project" />
              <Field id="ds-db" label="Default dataset" optional value={state.database} onChange={(v) => update({database: v})} placeholder="analytics" />
            </div>
            <CredGroup title="Service account">
              <div className="flex flex-col gap-1.5">
                <span className="text-xs font-medium text-foreground">Service account JSON key</span>
                <label htmlFor="ds-sa-file" className="inline-flex w-fit cursor-pointer items-center gap-1.5 rounded-md border border-input bg-card px-2.5 py-1 text-xs font-medium hover:bg-accent/40">
                  <Upload className="h-3 w-3" /> Upload JSON key file
                </label>
                <input
                  id="ds-sa-file"
                  type="file"
                  accept="application/json,.json"
                  className="sr-only"
                  onChange={(e) => { void onServiceAccountFile(e.currentTarget.files) }}
                />
              </div>
              <div className="flex flex-col gap-1.5">
                <label htmlFor="ds-sa" className="flex items-center gap-1.5 text-xs font-medium text-foreground">
                  …or paste the JSON <span className="font-normal text-muted-foreground/70">optional</span>
                </label>
                <textarea id="ds-sa" rows={5} value={state.serviceAccount} onChange={(e) => update({serviceAccount: e.target.value})}
                  placeholder={'{ "type": "service_account", "project_id": "…" }'}
                  className={cn(INPUT, 'h-auto resize-y py-2 font-mono text-xs leading-relaxed')} />
              </div>
            </CredGroup>
            {nameAndDescription}
          </>
        ) : vendor.arch === 'snowflake' ? (
          <>
            <Field id="ds-acct" label="Account identifier" mono value={state.account} onChange={(v) => update({account: v})}
              placeholder="xy12345.us-east-1"
              hint={<>From your account URL — the part before <code className="font-mono">.snowflakecomputing.com</code>.</>} />
            <div className="grid grid-cols-2 gap-3">
              <Field id="ds-wh" label="Warehouse" value={state.warehouse} onChange={(v) => update({warehouse: v})} placeholder="COMPUTE_WH" />
              <Field id="ds-db" label="Database" value={state.database} onChange={(v) => update({database: v})} placeholder="ANALYTICS" />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <Field id="ds-schema" label="Schema" optional value={state.schema} onChange={(v) => update({schema: v})} placeholder="PUBLIC" />
              <Field id="ds-role" label="Role" optional value={state.role} onChange={(v) => update({role: v})} placeholder="ACCOUNTADMIN" />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <Field id="ds-user" label="Username" value={state.username} onChange={(v) => update({username: v})} placeholder="svc_moneat" />
              <PasswordField id="ds-pass" label="Password" value={state.password} onChange={(v) => update({password: v})} placeholder={credPlaceholder} />
            </div>
            {nameAndDescription}
          </>
        ) : vendor.arch === 'cloudwatch' ? (
          <>
            <div className="flex flex-col gap-1.5">
              <label htmlFor="ds-region" className="text-xs font-medium text-foreground">AWS region</label>
              <select id="ds-region" className={SELECT} value={state.region} onChange={(e) => update({region: e.target.value})}>
                {AWS_REGIONS.map((r) => <option key={r} value={r}>{r}</option>)}
              </select>
            </div>
            <label className="flex cursor-pointer items-center gap-2 text-xs text-foreground">
              <input type="checkbox" checked={state.useRole} onChange={(e) => update({useRole: e.target.checked})} className="h-3.5 w-3.5" />
              Use the instance role / default credential chain
            </label>
            {!state.useRole && (
              <CredGroup title="Access keys">
                <div className="grid grid-cols-2 gap-3">
                  <Field id="ds-ak" label="Access key ID" mono value={state.accessKey} onChange={(v) => update({accessKey: v})} placeholder="AKIA…" />
                  <PasswordField id="ds-sk" label="Secret access key" value={state.secretKey} onChange={(v) => update({secretKey: v})} placeholder={credPlaceholder} />
                </div>
              </CredGroup>
            )}
            {nameAndDescription}
          </>
        ) : null}
      </div>
    </div>
  )
}
