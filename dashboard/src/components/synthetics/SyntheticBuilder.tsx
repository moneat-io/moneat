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
import {useNavigate} from '@tanstack/react-router'
import {useMutation, useQuery} from '@tanstack/react-query'
import {
  Activity,
  ArrowLeft,
  Bell,
  Check,
  CheckCircle2,
  ChevronRight,
  Globe,
  Layers,
  Monitor,
  Network,
  Plus,
  Save,
  Server,
  Shield,
  X,
  XCircle,
  Zap,
} from 'lucide-react'

import {
  api,
  type AlertConfig,
  type AlertRecipient,
  type BrowserStep,
  type CreateSyntheticTestPayload,
  type SyntheticAssertionPayload,
  type SyntheticLocationResponse,
  type SyntheticRunResponse,
  type SyntheticTestResponse,
} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {useToast} from '@/hooks/useToast'
import {cn} from '@/lib/utils'
import {TimingWaterfall} from '@/components/synthetics/SyntheticsViz'
import {locationMeta} from '@/components/synthetics/syntheticsHelpers'

type TestType = 'api' | 'multistep' | 'browser' | 'tcp' | 'dns' | 'ssl' | 'ping'
type Step = 'request' | 'assertions' | 'locations' | 'alerting' | 'schedule'

const TYPE_OPTIONS: ReadonlyArray<{value: TestType; label: string; icon: React.ComponentType<{className?: string}>}> = [
  {value: 'api', label: 'API', icon: Zap},
  {value: 'multistep', label: 'Multistep', icon: Layers},
  {value: 'browser', label: 'Browser', icon: Monitor},
  {value: 'tcp', label: 'TCP', icon: Network},
  {value: 'dns', label: 'DNS', icon: Globe},
  {value: 'ssl', label: 'SSL', icon: Shield},
  {value: 'ping', label: 'Ping', icon: Activity},
]

const STEPS: ReadonlyArray<{value: Step; label: string}> = [
  {value: 'request', label: 'Request'},
  {value: 'assertions', label: 'Assertions'},
  {value: 'locations', label: 'Locations'},
  {value: 'alerting', label: 'Alerting'},
  {value: 'schedule', label: 'Schedule'},
]

const DEFAULT_ALERT: AlertConfig = {
  consecutiveChecks: 3,
  minLocations: 2,
  totalLocations: 4,
  retestCount: 1,
  renotifyMinutes: 30,
  notifyOnRecovery: true,
  slowResponseMs: null,
  slowResponseWindowMin: 10,
}

interface Draft {
  name: string
  testType: TestType
  url: string
  method: string
  headers: Array<[string, string]>
  body: string
  assertions: SyntheticAssertionPayload[]
  browserSteps: BrowserStep[]
  locations: string[]
  alertConfig: AlertConfig
  alertRecipients: AlertRecipient[]
  service: string
  environment: string
  tags: string[]
  intervalSeconds: number
  timeoutSeconds: number
}

function initialDraft(initial?: SyntheticTestResponse): Draft {
  return {
    name: initial?.name ?? 'New test',
    testType: (initial?.testType as TestType) ?? 'api',
    url: initial?.url ?? '',
    method: initial?.method ?? 'GET',
    headers: Object.entries(initial?.headers ?? {}),
    body: initial?.body ?? '',
    assertions: initial?.assertions ?? [{type: 'status_code', operator: 'equals', value: '200'}],
    browserSteps: initial?.browserSteps ?? [],
    locations: initial?.locations ?? ['aws-us-east-1'],
    alertConfig: initial?.alertConfig ?? DEFAULT_ALERT,
    alertRecipients: initial?.alertRecipients ?? [],
    service: initial?.service ?? '',
    environment: initial?.environment ?? 'production',
    tags: initial?.tags ?? [],
    intervalSeconds: initial?.intervalSeconds ?? 300,
    timeoutSeconds: initial?.timeoutSeconds ?? 30,
  }
}

function toPayload(draft: Draft): CreateSyntheticTestPayload {
  const headers = Object.fromEntries(draft.headers.filter(([k]) => k.trim()))
  return {
    name: draft.name,
    testType: draft.testType,
    intervalSeconds: draft.intervalSeconds,
    timeoutSeconds: draft.timeoutSeconds,
    url: draft.url || null,
    method: draft.method,
    headers: Object.keys(headers).length ? headers : null,
    body: draft.body || null,
    assertions: draft.assertions,
    browserSteps: draft.browserSteps,
    locations: draft.locations,
    alertConfig: draft.alertConfig,
    alertRecipients: draft.alertRecipients,
    service: draft.service || null,
    environment: draft.environment || null,
    tags: draft.tags,
  }
}

const inputClass =
  'h-8 w-full rounded-md border border-input bg-background px-2.5 text-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-ring'
const labelClass = 'mb-1.5 block text-xs font-semibold text-foreground'

export function SyntheticBuilder({
  mode,
  initial,
}: Readonly<{mode: 'create' | 'edit'; initial?: SyntheticTestResponse}>) {
  const navigate = useNavigate()
  const {toast} = useToast()
  const [draft, setDraft] = useState<Draft>(() => initialDraft(initial))
  const [step, setStep] = useState<Step>('request')
  const [previewLocation, setPreviewLocation] = useState('aws-us-east-1')
  const [preview, setPreview] = useState<SyntheticRunResponse | null>(null)

  const {data: locationsData} = useQuery({
    queryKey: ['synthetic-locations'],
    queryFn: () => api.listSyntheticLocations(),
  })
  const locations = useMemo(() => locationsData ?? [], [locationsData])
  const {data: variablesData} = useQuery({
    queryKey: ['synthetic-variables'],
    queryFn: () => api.listSyntheticVariables(),
  })
  const variables = variablesData ?? []

  const patch = (p: Partial<Draft>) => setDraft((d) => ({...d, ...p}))

  const previewMutation = useMutation({
    mutationFn: () => api.previewSyntheticTest(toPayload(draft), previewLocation),
    onSuccess: (run) => setPreview(run),
    onError: (e: Error) => toast({title: 'Preview failed', description: e.message, variant: 'destructive'}),
  })

  const saveMutation = useMutation({
    mutationFn: async () => {
      if (mode === 'edit' && initial) {
        return api.updateSyntheticTest(initial.id, toPayload(draft))
      }
      return api.createSyntheticTest(toPayload(draft))
    },
    onSuccess: (saved) => {
      toast({title: mode === 'edit' ? 'Test updated' : 'Test created'})
      navigate({to: '/synthetics/$testId', params: {testId: saved.id}})
    },
    onError: (e: Error) => toast({title: 'Save failed', description: e.message, variant: 'destructive'}),
  })

  const isBrowser = draft.testType === 'browser'
  const isNet = draft.testType === 'tcp' || draft.testType === 'dns' || draft.testType === 'ssl' || draft.testType === 'ping'
  const stepIdx = STEPS.findIndex((s) => s.value === step)

  return (
    <div className="flex h-full flex-col">
      {/* Header */}
      <div className="flex items-center gap-3 border-b bg-card px-4 py-2.5">
        <Button size="icon" variant="outline" className="h-7 w-7" title="Back" onClick={() => navigate({to: '/synthetics'})}>
          <ArrowLeft className="h-3.5 w-3.5" />
        </Button>
        <input
          value={draft.name}
          onChange={(e) => patch({name: e.target.value})}
          spellCheck={false}
          className="w-60 rounded border border-transparent bg-transparent px-1.5 py-1 text-base font-bold tracking-tight hover:bg-muted/50 focus:border-input focus:bg-background focus:outline-none"
        />
        <Badge variant="accent" size="sm" className="uppercase">
          {draft.testType} test
        </Badge>
        <div className="ml-auto flex items-center gap-2">
          <Button variant="ghost" size="sm" className="h-7" onClick={() => navigate({to: '/synthetics'})}>
            Cancel
          </Button>
          <Button size="sm" className="h-7 gap-1.5" disabled={saveMutation.isPending} onClick={() => saveMutation.mutate()}>
            <Save className="h-3.5 w-3.5" />
            {mode === 'edit' ? 'Save changes' : 'Save & run'}
          </Button>
        </div>
      </div>

      {/* Step rail */}
      <div className="flex items-center gap-1 border-b bg-muted/30 px-4 py-2">
        {STEPS.map((s, i) => (
          <div key={s.value} className="flex items-center">
            <button
              type="button"
              onClick={() => setStep(s.value)}
              className={cn(
                'flex h-8 items-center gap-2 rounded-md border px-2.5 text-sm font-medium transition-colors',
                step === s.value
                  ? 'border-accent-subtle-border bg-accent-subtle-bg text-accent-subtle-fg'
                  : 'border-transparent text-muted-foreground hover:bg-muted'
              )}
            >
              <span
                className={cn(
                  'grid h-4.5 w-4.5 place-items-center rounded-full border text-[10px] font-bold',
                  i < stepIdx
                    ? 'border-transparent bg-success-solid text-white'
                    : step === s.value
                      ? 'border-transparent bg-accent text-accent-foreground'
                      : 'border-input bg-muted text-muted-foreground'
                )}
              >
                {i < stepIdx ? <Check className="h-2.5 w-2.5" /> : i + 1}
              </span>
              {s.value === 'request' && isBrowser ? 'Steps' : s.label}
            </button>
            {i < STEPS.length - 1 && <span className="mx-0.5 h-px w-4 bg-border" />}
          </div>
        ))}
      </div>

      {/* Builder + preview */}
      <div className="grid min-h-0 flex-1 grid-cols-1 lg:grid-cols-[1fr_400px]">
        <div className="overflow-y-auto border-r p-4">
          {step === 'request' && (
            <RequestStep
              draft={draft}
              patch={patch}
              isBrowser={isBrowser}
              isNet={isNet}
              variables={variables.map((v) => v.name)}
            />
          )}
          {step === 'assertions' && <AssertionsStep draft={draft} patch={patch} />}
          {step === 'locations' && <LocationsStep draft={draft} patch={patch} locations={locations} />}
          {step === 'alerting' && <AlertingStep draft={draft} patch={patch} />}
          {step === 'schedule' && <ScheduleStep draft={draft} patch={patch} />}
        </div>

        {/* Live preview */}
        <aside className="flex min-h-0 flex-col overflow-y-auto bg-muted/30">
          <div className="flex items-center gap-2 border-b bg-card px-3.5 py-2.5">
            <Zap className="h-3.5 w-3.5 text-muted-foreground" />
            <h4 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Live preview</h4>
            <select
              value={previewLocation}
              onChange={(e) => setPreviewLocation(e.target.value)}
              className="ml-auto h-7 rounded-md border border-input bg-background px-2 text-xs"
            >
              {(draft.locations.length ? draft.locations : ['aws-us-east-1']).map((code) => (
                <option key={code} value={code}>
                  {locationMeta(code, locations).name}
                </option>
              ))}
            </select>
            <Button size="sm" className="h-7 gap-1.5" disabled={previewMutation.isPending} onClick={() => previewMutation.mutate()}>
              <Zap className="h-3 w-3" />
              {previewMutation.isPending ? 'Running…' : 'Run it now'}
            </Button>
          </div>
          <PreviewPane run={preview} pending={previewMutation.isPending} isBrowser={isBrowser} />
        </aside>
      </div>
    </div>
  )
}

function RequestStep({
  draft,
  patch,
  isBrowser,
  isNet,
  variables,
}: Readonly<{draft: Draft; patch: (p: Partial<Draft>) => void; isBrowser: boolean; isNet: boolean; variables: string[]}>) {
  return (
    <div>
      {/* Type selector */}
      <div className="mb-5 flex flex-wrap gap-1.5">
        {TYPE_OPTIONS.map(({value, label, icon: Icon}) => (
          <button
            key={value}
            type="button"
            onClick={() => patch({testType: value})}
            className={cn(
              'flex h-8 items-center gap-1.5 rounded-md border px-3 text-sm font-medium transition-colors',
              draft.testType === value
                ? 'border-accent-subtle-border bg-accent-subtle-bg text-accent-subtle-fg'
                : 'border-input bg-card text-muted-foreground hover:text-foreground'
            )}
          >
            <Icon className="h-3.5 w-3.5" />
            {label}
          </button>
        ))}
      </div>

      {isBrowser ? (
        <BrowserStepsEditor draft={draft} patch={patch} />
      ) : isNet ? (
        <NetEditor draft={draft} patch={patch} />
      ) : (
        <ApiEditor draft={draft} patch={patch} variables={variables} />
      )}
    </div>
  )
}

function ApiEditor({
  draft,
  patch,
  variables,
}: Readonly<{draft: Draft; patch: (p: Partial<Draft>) => void; variables: string[]}>) {
  const setHeader = (i: number, idx: 0 | 1, value: string) => {
    const next = draft.headers.map((h, j) => (j === i ? (idx === 0 ? [value, h[1]] : [h[0], value]) : h)) as Array<[string, string]>
    patch({headers: next})
  }
  return (
    <div>
      <h3 className="mb-1 text-base font-semibold">Request</h3>
      <p className="mb-4 text-xs text-muted-foreground">
        The HTTP request this test sends. Reference variables with <code className="font-mono text-accent-subtle-fg">{'{{NAME}}'}</code>.
      </p>
      <div className="mb-4">
        <label className={labelClass}>Endpoint</label>
        <div className="flex gap-2">
          <select value={draft.method} onChange={(e) => patch({method: e.target.value})} className={cn(inputClass, 'w-28 font-semibold text-accent-subtle-fg')}>
            {['GET', 'POST', 'PUT', 'DELETE', 'HEAD', 'PATCH'].map((m) => (
              <option key={m}>{m}</option>
            ))}
          </select>
          <input value={draft.url} onChange={(e) => patch({url: e.target.value})} placeholder="https://api.example.com/health" className={cn(inputClass, 'flex-1 font-mono text-xs')} />
        </div>
      </div>
      <div className="mb-2">
        <label className={labelClass}>Headers</label>
        {draft.headers.map((h, i) => (
          <div key={i} className="mb-1.5 grid grid-cols-[1fr_1fr_auto] gap-2">
            <input value={h[0]} onChange={(e) => setHeader(i, 0, e.target.value)} placeholder="Header" className={cn(inputClass, 'font-mono text-xs')} />
            <input value={h[1]} onChange={(e) => setHeader(i, 1, e.target.value)} placeholder="Value" className={cn(inputClass, 'font-mono text-xs')} />
            <Button size="icon" variant="ghost" className="h-8 w-8" onClick={() => patch({headers: draft.headers.filter((_, j) => j !== i)})}>
              <X className="h-3.5 w-3.5" />
            </Button>
          </div>
        ))}
        <button type="button" onClick={() => patch({headers: [...draft.headers, ['', '']]})} className="flex items-center gap-1.5 py-1.5 text-xs font-semibold text-accent-subtle-fg">
          <Plus className="h-3 w-3" />
          Add header
        </button>
      </div>
      {variables.length > 0 && (
        <div className="mt-2 inline-flex flex-wrap items-center gap-1.5 rounded-md border bg-muted/50 px-2 py-1 text-[11px] text-muted-foreground">
          Variables available:
          {variables.map((v) => (
            <code key={v} className="font-mono text-accent-subtle-fg">{`{{${v}}}`}</code>
          ))}
        </div>
      )}
      {(draft.method === 'POST' || draft.method === 'PUT' || draft.method === 'PATCH') && (
        <div className="mt-4">
          <label className={labelClass}>Request body</label>
          <textarea
            value={draft.body}
            onChange={(e) => patch({body: e.target.value})}
            rows={4}
            className="w-full rounded-md border border-input bg-background p-2 font-mono text-xs focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          />
        </div>
      )}
    </div>
  )
}

function NetEditor({draft, patch}: Readonly<{draft: Draft; patch: (p: Partial<Draft>) => void}>) {
  return (
    <div>
      <h3 className="mb-1 text-base font-semibold capitalize">{draft.testType} check</h3>
      <p className="mb-4 text-xs text-muted-foreground">Low-level network check run from each selected location.</p>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className={labelClass}>Hostname</label>
          <input value={draft.url} onChange={(e) => patch({url: e.target.value})} placeholder="api.example.com" className={cn(inputClass, 'font-mono text-xs')} />
        </div>
      </div>
    </div>
  )
}

const BROWSER_ACTIONS: ReadonlyArray<{value: string; label: string}> = [
  {value: 'navigate', label: 'Navigate'},
  {value: 'click', label: 'Click'},
  {value: 'type', label: 'Type'},
  {value: 'assert', label: 'Assert'},
  {value: 'wait', label: 'Wait'},
]

function BrowserStepsEditor({draft, patch}: Readonly<{draft: Draft; patch: (p: Partial<Draft>) => void}>) {
  const update = (i: number, p: Partial<BrowserStep>) => patch({browserSteps: draft.browserSteps.map((s, j) => (j === i ? {...s, ...p} : s))})
  return (
    <div>
      <h3 className="mb-1 text-base font-semibold">User journey</h3>
      <p className="mb-4 text-xs text-muted-foreground">Browser actions replayed from a real Chromium at each location. Add assertions between steps.</p>
      <div className="mb-3">
        <label className={labelClass}>Starting URL</label>
        <input value={draft.url} onChange={(e) => patch({url: e.target.value})} placeholder="https://example.com" className={cn(inputClass, 'font-mono text-xs')} />
      </div>
      <div className="flex flex-col gap-1.5">
        {draft.browserSteps.map((s, i) => (
          <div key={i} className="flex items-center gap-2 rounded-md border bg-card p-2">
            <span className="w-4 text-right text-[10px] tabular-nums text-muted-foreground">{i + 1}</span>
            <select value={s.action} onChange={(e) => update(i, {action: e.target.value})} className={cn(inputClass, 'h-7 w-24')}>
              {BROWSER_ACTIONS.map((a) => (
                <option key={a.value} value={a.value}>{a.label}</option>
              ))}
            </select>
            {s.action !== 'wait' && s.action !== 'navigate' && (
              <input value={s.selector ?? ''} onChange={(e) => update(i, {selector: e.target.value})} placeholder="selector" className={cn(inputClass, 'h-7 flex-1 font-mono text-xs')} />
            )}
            <input value={s.value ?? ''} onChange={(e) => update(i, {value: e.target.value})} placeholder={s.action === 'navigate' ? 'url' : s.action === 'wait' ? 'ms' : 'value'} className={cn(inputClass, 'h-7 flex-1 font-mono text-xs')} />
            <Button size="icon" variant="ghost" className="h-7 w-7" onClick={() => patch({browserSteps: draft.browserSteps.filter((_, j) => j !== i)})}>
              <X className="h-3.5 w-3.5" />
            </Button>
          </div>
        ))}
      </div>
      <button
        type="button"
        onClick={() => patch({browserSteps: [...draft.browserSteps, {action: 'click', label: '', selector: '', value: ''}]})}
        className="mt-2 flex items-center gap-1.5 py-1.5 text-xs font-semibold text-accent-subtle-fg"
      >
        <Plus className="h-3 w-3" />
        Add step
      </button>
    </div>
  )
}

const ASSERTION_TYPES: ReadonlyArray<{value: string; label: string}> = [
  {value: 'status_code', label: 'Status code'},
  {value: 'response_time', label: 'Response time'},
  {value: 'body_contains', label: 'Body contains'},
  {value: 'body_json_path', label: 'Body · JSON path'},
  {value: 'header', label: 'Header'},
]
const OPERATORS: ReadonlyArray<{value: string; label: string}> = [
  {value: 'equals', label: 'is'},
  {value: 'not_equals', label: 'is not'},
  {value: 'contains', label: 'contains'},
  {value: 'less_than', label: '<'},
  {value: 'greater_than', label: '>'},
]

function AssertionsStep({draft, patch}: Readonly<{draft: Draft; patch: (p: Partial<Draft>) => void}>) {
  const update = (i: number, p: Partial<SyntheticAssertionPayload>) =>
    patch({assertions: draft.assertions.map((a, j) => (j === i ? {...a, ...p} : a))})
  return (
    <div>
      <h3 className="mb-1 text-base font-semibold">Assertions</h3>
      <p className="mb-4 text-xs text-muted-foreground">The test passes only when every assertion holds, at every location.</p>
      <div className="flex flex-col gap-2">
        {draft.assertions.map((a, i) => (
          <div key={i} className="grid grid-cols-[1.2fr_1fr_1fr_auto] items-center gap-2 rounded-md border bg-card p-2">
            <select value={a.type} onChange={(e) => update(i, {type: e.target.value})} className={cn(inputClass, 'h-7')}>
              {ASSERTION_TYPES.map((t) => (
                <option key={t.value} value={t.value}>{t.label}</option>
              ))}
            </select>
            {a.type === 'body_json_path' || a.type === 'header' ? (
              <input value={a.target ?? ''} onChange={(e) => update(i, {target: e.target.value})} placeholder={a.type === 'header' ? 'header name' : '$.path'} className={cn(inputClass, 'h-7 font-mono text-xs')} />
            ) : (
              <select value={a.operator} onChange={(e) => update(i, {operator: e.target.value})} className={cn(inputClass, 'h-7')}>
                {OPERATORS.map((o) => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
            )}
            <input value={a.value ?? ''} onChange={(e) => update(i, {value: e.target.value})} placeholder="value" className={cn(inputClass, 'h-7 font-mono text-xs')} />
            <Button size="icon" variant="ghost" className="h-7 w-7" onClick={() => patch({assertions: draft.assertions.filter((_, j) => j !== i)})}>
              <X className="h-3.5 w-3.5" />
            </Button>
          </div>
        ))}
      </div>
      <button
        type="button"
        onClick={() => patch({assertions: [...draft.assertions, {type: 'status_code', operator: 'equals', value: '200'}]})}
        className="mt-2 flex items-center gap-1.5 py-1.5 text-xs font-semibold text-accent-subtle-fg"
      >
        <Plus className="h-3 w-3" />
        Add assertion
      </button>
    </div>
  )
}

function LocationsStep({
  draft,
  patch,
  locations,
}: Readonly<{draft: Draft; patch: (p: Partial<Draft>) => void; locations: SyntheticLocationResponse[]}>) {
  const toggle = (code: string) => {
    patch({locations: draft.locations.includes(code) ? draft.locations.filter((c) => c !== code) : [...draft.locations, code]})
  }
  const managed = locations.filter((l) => l.type === 'managed')
  const priv = locations.filter((l) => l.type === 'private')
  return (
    <div>
      <h3 className="mb-1 text-base font-semibold">Locations</h3>
      <p className="mb-4 text-xs text-muted-foreground">
        Run this test from every selected location. <b>{draft.locations.length} selected.</b>
      </p>
      <div className="mb-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Managed locations</div>
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
        {managed.map((l) => (
          <LocationCard key={l.id} code={l.code} name={l.name} region={l.region} selected={draft.locations.includes(l.code)} onToggle={() => toggle(l.code)} />
        ))}
      </div>
      {priv.length > 0 && (
        <>
          <div className="mb-2 mt-4 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Private locations</div>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            {priv.map((l) => (
              <LocationCard key={l.id} code={l.code} name={l.name} region={`${l.workerCount} worker${l.workerCount === 1 ? '' : 's'} online`} selected={draft.locations.includes(l.code)} onToggle={() => toggle(l.code)} isPrivate />
            ))}
          </div>
        </>
      )}
    </div>
  )
}

function LocationCard({
  code,
  name,
  region,
  selected,
  onToggle,
  isPrivate,
}: Readonly<{code: string; name: string; region: string; selected: boolean; onToggle: () => void; isPrivate?: boolean}>) {
  const meta = locationMeta(code)
  return (
    <button
      type="button"
      onClick={onToggle}
      className={cn('flex items-center gap-2.5 rounded-md border p-2.5 text-left transition-colors', selected ? 'border-accent-subtle-border bg-accent-subtle-bg' : 'border-input bg-card hover:border-border')}
    >
      <span className="grid h-5.5 w-5.5 shrink-0 place-items-center rounded-full text-[9px] font-bold text-white" style={{backgroundColor: meta.color}}>
        {isPrivate ? <Server className="h-2.5 w-2.5" /> : meta.abbr}
      </span>
      <span className="min-w-0 flex-1">
        <span className="block truncate text-sm font-semibold">{name}</span>
        <span className="block truncate text-[11px] text-muted-foreground">{region || code}</span>
      </span>
      <span className={cn('grid h-4.5 w-4.5 place-items-center rounded-full border', selected ? 'border-accent bg-accent text-accent-foreground' : 'border-input')}>
        {selected && <Check className="h-3 w-3" />}
      </span>
    </button>
  )
}

function NumberStepper({value, onChange, min = 1, max = 20}: Readonly<{value: number; onChange: (v: number) => void; min?: number; max?: number}>) {
  return (
    <select value={value} onChange={(e) => onChange(Number(e.target.value))} className="inline-flex h-6 rounded border border-input bg-card px-1.5 text-sm font-semibold text-accent-subtle-fg">
      {Array.from({length: max - min + 1}, (_, i) => min + i).map((n) => (
        <option key={n} value={n}>{n}</option>
      ))}
    </select>
  )
}

function AlertingStep({draft, patch}: Readonly<{draft: Draft; patch: (p: Partial<Draft>) => void}>) {
  const cfg = draft.alertConfig
  const setCfg = (p: Partial<AlertConfig>) => patch({alertConfig: {...cfg, ...p}})
  return (
    <div>
      <h3 className="mb-1 text-base font-semibold">Alerting</h3>
      <p className="mb-4 text-xs text-muted-foreground">When and whom to notify. Conditions reduce flapping from a single noisy location.</p>
      <div className="mb-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Trigger condition</div>
      <div className="rounded-md border bg-muted/40 p-4 text-base leading-loose">
        Alert when the test is <b className="text-accent-subtle-fg">failing</b> for{' '}
        <NumberStepper value={cfg.consecutiveChecks} onChange={(v) => setCfg({consecutiveChecks: v})} min={1} max={10} /> consecutive checks from at least{' '}
        <NumberStepper value={cfg.minLocations} onChange={(v) => setCfg({minLocations: v})} min={1} max={8} /> of{' '}
        <NumberStepper value={cfg.totalLocations} onChange={(v) => setCfg({totalLocations: v})} min={1} max={8} /> locations. Re-test{' '}
        <NumberStepper value={cfg.retestCount} onChange={(v) => setCfg({retestCount: v})} min={0} max={5} /> times before alerting.
      </div>
      <div className="mb-2 mt-4 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Recipients</div>
      <div className="flex flex-col gap-1.5">
        {draft.alertRecipients.map((r, i) => (
          <div key={i} className="flex items-center gap-2.5 rounded-md border bg-card p-2.5">
            <Bell className="h-3.5 w-3.5 text-muted-foreground" />
            <input value={r.target} onChange={(e) => patch({alertRecipients: draft.alertRecipients.map((x, j) => (j === i ? {...x, target: e.target.value} : x))})} placeholder="#channel or email" className={cn(inputClass, 'h-7 flex-1')} />
            <select value={r.type} onChange={(e) => patch({alertRecipients: draft.alertRecipients.map((x, j) => (j === i ? {...x, type: e.target.value} : x))})} className={cn(inputClass, 'h-7 w-28')}>
              {['slack', 'email', 'pagerduty', 'webhook'].map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
            <Button size="icon" variant="ghost" className="h-7 w-7" onClick={() => patch({alertRecipients: draft.alertRecipients.filter((_, j) => j !== i)})}>
              <X className="h-3.5 w-3.5" />
            </Button>
          </div>
        ))}
      </div>
      <button type="button" onClick={() => patch({alertRecipients: [...draft.alertRecipients, {type: 'slack', target: ''}]})} className="mt-1 flex items-center gap-1.5 py-1.5 text-xs font-semibold text-accent-subtle-fg">
        <Plus className="h-3 w-3" />
        Add recipient
      </button>
      <div className="mb-2 mt-4 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Options</div>
      <ToggleRow label="Notify on recovery" sub="Post when the test starts passing again" checked={cfg.notifyOnRecovery} onChange={(v) => setCfg({notifyOnRecovery: v})} />
      <ToggleRow label="Renotify if still failing" sub="Remind every 30 minutes until resolved" checked={cfg.renotifyMinutes != null} onChange={(v) => setCfg({renotifyMinutes: v ? 30 : null})} />
    </div>
  )
}

function ToggleRow({label, sub, checked, onChange}: Readonly<{label: string; sub: string; checked: boolean; onChange: (v: boolean) => void}>) {
  return (
    <div className="flex items-center gap-3 border-b py-3 last:border-b-0">
      <div className="flex-1">
        <div className="text-sm font-semibold">{label}</div>
        <div className="text-[11px] text-muted-foreground">{sub}</div>
      </div>
      <button
        type="button"
        onClick={() => onChange(!checked)}
        className={cn('relative h-5 w-9 shrink-0 rounded-full transition-colors', checked ? 'bg-accent' : 'bg-muted-foreground/40')}
        aria-pressed={checked}
      >
        <span className={cn('absolute top-0.5 h-4 w-4 rounded-full bg-white transition-all', checked ? 'left-4.5' : 'left-0.5')} />
      </button>
    </div>
  )
}

const INTERVALS: ReadonlyArray<{value: number; label: string}> = [
  {value: 30, label: '30 seconds'},
  {value: 60, label: '1 minute'},
  {value: 300, label: '5 minutes'},
  {value: 900, label: '15 minutes'},
  {value: 3600, label: '1 hour'},
]

function ScheduleStep({draft, patch}: Readonly<{draft: Draft; patch: (p: Partial<Draft>) => void}>) {
  return (
    <div>
      <h3 className="mb-1 text-base font-semibold">Schedule &amp; metadata</h3>
      <p className="mb-4 text-xs text-muted-foreground">How often the test runs, and how it&apos;s organized.</p>
      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className={labelClass}>Run every</label>
          <select value={draft.intervalSeconds} onChange={(e) => patch({intervalSeconds: Number(e.target.value)})} className={inputClass}>
            {INTERVALS.map((iv) => (
              <option key={iv.value} value={iv.value}>{iv.label}</option>
            ))}
          </select>
        </div>
        <div>
          <label className={labelClass}>Timeout</label>
          <select value={draft.timeoutSeconds} onChange={(e) => patch({timeoutSeconds: Number(e.target.value)})} className={inputClass}>
            {[10, 30, 60, 120].map((t) => (
              <option key={t} value={t}>{t} seconds</option>
            ))}
          </select>
        </div>
        <div>
          <label className={labelClass}>Service</label>
          <input value={draft.service} onChange={(e) => patch({service: e.target.value})} placeholder="payments" className={inputClass} />
        </div>
        <div>
          <label className={labelClass}>Environment</label>
          <input value={draft.environment} onChange={(e) => patch({environment: e.target.value})} placeholder="production" className={inputClass} />
        </div>
      </div>
      <div className="mt-4">
        <label className={labelClass}>Tags</label>
        <div className="flex flex-wrap items-center gap-1.5">
          {draft.tags.map((t, i) => (
            <span key={i} className="inline-flex items-center gap-1 rounded border bg-muted/50 px-2 py-0.5 text-[11px]">
              {t}
              <button type="button" onClick={() => patch({tags: draft.tags.filter((_, j) => j !== i)})}>
                <X className="h-2.5 w-2.5" />
              </button>
            </span>
          ))}
          <input
            placeholder="Add tag…"
            onKeyDown={(e) => {
              if (e.key === 'Enter' && e.currentTarget.value.trim()) {
                patch({tags: [...draft.tags, e.currentTarget.value.trim()]})
                e.currentTarget.value = ''
              }
            }}
            className={cn(inputClass, 'h-7 w-32 font-mono text-xs')}
          />
        </div>
      </div>
    </div>
  )
}

function PreviewPane({run, pending, isBrowser}: Readonly<{run: SyntheticRunResponse | null; pending: boolean; isBrowser: boolean}>) {
  if (pending) {
    return <div className="flex flex-1 items-center justify-center p-8 text-sm text-muted-foreground">Running preview…</div>
  }
  if (!run) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-2 p-8 text-center text-muted-foreground">
        <Zap className="h-6 w-6" />
        <p className="text-sm">Run the test to preview the response{isBrowser ? ', screenshots' : ', timing'} and assertions.</p>
      </div>
    )
  }
  const passed = run.status === 'passed'
  const detail = run.detail
  return (
    <div>
      <div className="flex items-center gap-2.5 border-b px-3.5 py-2.5">
        <span className={cn('inline-flex items-center gap-1.5 font-bold', passed ? 'text-success-fg' : 'text-danger-fg')}>
          {passed ? <CheckCircle2 className="h-4 w-4" /> : <XCircle className="h-4 w-4" />}
          {run.statusCode > 0 ? run.statusCode : passed ? 'Passed' : 'Failed'}
        </span>
        <Badge variant={passed ? 'success' : 'danger'} size="sm">
          {passed ? 'Passed' : 'Failed'}
        </Badge>
        <span className="ml-auto text-right text-[11px] text-muted-foreground">{Math.round(run.durationMs)} ms</span>
      </div>

      {detail?.timings && Object.keys(detail.timings).length > 0 && (
        <div className="border-b px-3.5 py-3">
          <div className="mb-2.5 flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
            <Activity className="h-3 w-3" />
            Timing
          </div>
          <TimingWaterfall timings={detail.timings} />
        </div>
      )}

      {detail?.assertions && detail.assertions.length > 0 && (
        <div className="border-b px-3.5 py-3">
          <div className="mb-2 flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
            <CheckCircle2 className="h-3 w-3" />
            Assertions · {detail.assertions.filter((a) => a.passed).length}/{detail.assertions.length}
          </div>
          {detail.assertions.map((a, i) => (
            <div key={i} className="flex items-center gap-2 border-b border-border/40 py-1.5 text-xs last:border-b-0">
              {a.passed ? <CheckCircle2 className="h-3.5 w-3.5 text-success-fg" /> : <XCircle className="h-3.5 w-3.5 text-danger-fg" />}
              <span className="flex-1">{a.label}</span>
              <span className="font-mono text-[11px] text-muted-foreground">{a.actual}</span>
            </div>
          ))}
        </div>
      )}

      {detail?.browser?.steps && detail.browser.steps.length > 0 && (
        <div className="border-b px-3.5 py-3">
          <div className="mb-2 flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
            <Monitor className="h-3 w-3" />
            Steps
          </div>
          {detail.browser.steps.map((s, i) => (
            <div key={i} className="flex items-center gap-2 py-1 text-xs">
              {s.status === 'passed' ? <Check className="h-3 w-3 text-success-fg" /> : s.status === 'failed' ? <X className="h-3 w-3 text-danger-fg" /> : <ChevronRight className="h-3 w-3 text-muted-foreground" />}
              <span className="flex-1 capitalize">{s.label || s.action}</span>
              <span className="font-mono text-[11px] text-muted-foreground">{s.durationMs ? `${s.durationMs}ms` : ''}</span>
            </div>
          ))}
        </div>
      )}

      {detail?.response?.body && (
        <div className="px-3.5 py-3">
          <div className="mb-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">Response body</div>
          <pre className="max-h-64 overflow-auto rounded-md border bg-[#0c0f15] p-3 font-mono text-xs text-[#d7e1ec]">{detail.response.body.slice(0, 4000)}</pre>
        </div>
      )}

      {run.errorMessage && (
        <div className="px-3.5 py-3">
          <div className="rounded-md border border-danger-border bg-danger-bg p-2.5 font-mono text-xs text-danger-fg">{run.errorMessage}</div>
        </div>
      )}
    </div>
  )
}
