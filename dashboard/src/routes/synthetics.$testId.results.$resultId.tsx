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

import {createFileRoute, useNavigate} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {useState} from 'react'
import {
  Activity,
  ArrowLeft,
  Check,
  CheckCircle2,
  Code,
  ExternalLink,
  List,
  Monitor,
  Network,
  Sliders,
  X,
  XCircle,
} from 'lucide-react'

import {api, type SyntheticRunDetail} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {cn} from '@/lib/utils'
import {TimingWaterfall} from '@/components/synthetics/SyntheticsViz'
import {locationMeta} from '@/components/synthetics/syntheticsHelpers'

export const Route = createFileRoute('/synthetics/$testId/results/$resultId')({
  component: SyntheticRunDrillIn,
})

function rowKey(prefix: string, index: number, ...parts: Array<string | number | null | undefined>): string {
  const body = parts.map((part) => String(part ?? '').trim()).filter(Boolean).join('-')
  return `${prefix}-${body || 'blank'}-${index}`
}

function runStatusLabel(passed: boolean, isBrowser: boolean, failedStep?: number | null): string {
  if (passed) return 'Passed'
  if (isBrowser && failedStep) return `Failed at step ${failedStep}`
  return 'Failed'
}

function httpStatusIcon(statusCode: number) {
  if (statusCode >= 400) return <XCircle className="h-4 w-4 text-danger-fg" />
  return <CheckCircle2 className="h-4 w-4 text-success-fg" />
}

function stepFrameClass(status: string, active: boolean): string {
  const border = status === 'failed' ? 'border-danger-border' : 'border-border'
  return cn('relative h-14 overflow-hidden rounded border', border, active && 'ring-2 ring-danger-solid/40')
}

function stepBadgeClass(status: string): string {
  if (status === 'passed') return 'bg-success-solid'
  if (status === 'failed') return 'bg-danger-solid'
  return 'bg-muted-foreground'
}

function stepStatusClass(status: string): string {
  if (status === 'passed') return 'border-success-border bg-success-bg text-success-fg'
  if (status === 'failed') return 'border-transparent bg-danger-solid text-white'
  return 'bg-muted text-muted-foreground'
}

function stepIcon(status: string) {
  if (status === 'passed') return <Check className="h-3 w-3" />
  if (status === 'failed') return <X className="h-3 w-3" />
  return <Check className="h-3 w-3" />
}

function compactStepIcon(status: string) {
  if (status === 'passed') return <Check className="h-2 w-2" />
  if (status === 'failed') return <X className="h-2 w-2" />
  return null
}

function stepRowClass(status: string): string {
  if (status === 'failed') return 'border-danger-border bg-danger-bg'
  if (status === 'skipped') return 'opacity-50'
  return 'bg-card'
}

function stepDurationLabel(status: string, durationMs?: number | null): string {
  if (status === 'skipped') return 'skipped'
  if (!durationMs) return ''
  return `${(durationMs / 1000).toFixed(1)}s`
}

function consoleLevelClass(level: string): string {
  if (level === 'error') return 'text-danger-fg'
  if (level === 'warning' || level === 'warn') return 'text-warning-fg'
  return 'text-muted-foreground'
}

function screenshotFallbackBackground(status: string): string {
  if (status === 'failed') return 'linear-gradient(135deg,#3a1620,#1c0e12)'
  return 'linear-gradient(135deg,#13283a,#0e1c2b)'
}

function SyntheticRunDrillIn() {
  const {testId, resultId} = Route.useParams()
  const navigate = useNavigate()
  const {data: run, isLoading} = useQuery({
    queryKey: ['synthetic-run', testId, resultId],
    queryFn: () => api.getSyntheticRunDetail(testId, resultId),
  })
  const {data: locationsData} = useQuery({queryKey: ['synthetic-locations'], queryFn: () => api.listSyntheticLocations()})
  const locations = locationsData ?? []

  if (isLoading) {
    return <div className="flex items-center justify-center py-10 text-sm text-muted-foreground">Loading…</div>
  }
  if (!run) {
    return (
      <div className="flex flex-col items-center gap-3 py-12 text-center">
        <p className="text-sm font-medium">Run not found</p>
        <Button variant="outline" size="sm" onClick={() => navigate({to: '/synthetics/$testId', params: {testId}})}>
          Back to test
        </Button>
      </div>
    )
  }

  const passed = run.status === 'passed'
  const detail = run.detail
  const isBrowser = Boolean(detail?.browser)
  const meta = locationMeta(run.locationCode, locations)

  return (
    <div className="px-5 py-4">
      {/* Header */}
      <div className="mb-4 flex flex-wrap items-start gap-3">
        <Button size="icon" variant="outline" className="mt-0.5 h-7 w-7" onClick={() => navigate({to: '/synthetics/$testId', params: {testId}})}>
          <ArrowLeft className="h-3.5 w-3.5" />
        </Button>
        <div className="min-w-0">
          <h1 className="text-xl font-bold tracking-tight">Run detail</h1>
          <p className="text-sm text-muted-foreground">A single synthetic run — request, response, assertions, timing and captured screenshots.</p>
        </div>
        <div className="ml-auto flex items-center gap-2">
          <Button size="sm" variant="ghost" className="h-7 gap-1.5" onClick={() => navigate({to: '/synthetics/$testId', params: {testId}})}>
            <ExternalLink className="h-3.5 w-3.5" />
            Open test
          </Button>
        </div>
      </div>

      {/* Run summary */}
      <div className="mb-4 flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
        <span className="text-base font-bold text-foreground">{run.testName}</span>
        <span className="text-muted-foreground/50">·</span>
        <span className="inline-flex items-center gap-1.5">
          <span className="grid h-4 w-4 place-items-center rounded-full text-[8px] font-bold text-white" style={{backgroundColor: meta.color}}>
            {meta.abbr}
          </span>
          {meta.name}
        </span>
        <span className="text-muted-foreground/50">·</span>
        <span>{new Date(run.timestamp).toLocaleString()}</span>
        {run.attempt > 1 && (
          <>
            <span className="text-muted-foreground/50">·</span>
            <span>attempt {run.attempt}</span>
          </>
        )}
        <Badge variant={passed ? 'success' : 'danger'} size="sm" className="ml-1">
          {runStatusLabel(passed, isBrowser, detail?.browser?.failedStep)}
        </Badge>
      </div>

      {/* Error banner */}
      {!passed && run.errorMessage && (
        <div className="mb-4 flex items-center gap-3 rounded-lg border border-danger-border bg-danger-bg px-3.5 py-2.5 text-sm">
          <XCircle className="h-4 w-4 shrink-0 text-danger-fg" />
          <div className="min-w-0 flex-1">
            <b>Run failed —</b> <span className="font-mono text-xs">{run.errorMessage}</span>
          </div>
        </div>
      )}

      {isBrowser ? <BrowserResult detail={detail} /> : <HttpResult detail={detail} run={run} />}
    </div>
  )
}

function HttpResult({detail, run}: Readonly<{detail?: SyntheticRunDetail | null; run: {statusCode: number; durationMs: number}}>) {
  const [tab, setTab] = useState<'response' | 'request'>('response')
  const assertions = detail?.assertions ?? []
  return (
    <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-[1fr_320px]">
      <div className="flex min-w-0 flex-col gap-3.5">
        {/* Timing */}
        <div className="rounded-lg border bg-card">
          <div className="flex items-center gap-2 border-b px-3.5 py-2.5">
            <Activity className="h-3.5 w-3.5 text-muted-foreground" />
            <h3 className="text-sm font-semibold">Timing</h3>
            <Badge variant="neutral" size="sm" className="ml-auto font-mono">
              {Math.round(run.durationMs)} ms total
            </Badge>
          </div>
          <div className="p-3.5">
            <TimingWaterfall timings={detail?.timings} />
          </div>
        </div>

        {/* Request & response */}
        <div className="rounded-lg border bg-card">
          <div className="flex items-center gap-2 border-b px-3.5 py-2.5">
            <Code className="h-3.5 w-3.5 text-muted-foreground" />
            <h3 className="text-sm font-semibold">Request &amp; response</h3>
          </div>
          <div className="p-3.5">
            <div className="mb-3 flex gap-1 border-b">
              {(['response', 'request'] as const).map((t) => (
                <button
                  key={t}
                  type="button"
                  onClick={() => setTab(t)}
                  className={cn(
                    '-mb-px border-b-2 px-3 py-1.5 text-xs font-medium capitalize transition-colors',
                    tab === t ? 'border-accent text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground'
                  )}
                >
                  {t}
                </button>
              ))}
            </div>
            {tab === 'response' ? (
              <div>
                <div className="mb-2 inline-flex items-center gap-1.5 font-mono text-sm font-bold text-foreground">
                  {httpStatusIcon(run.statusCode)}
                  {run.statusCode || '—'}
                </div>
                <HeaderList headers={detail?.response?.headers} />
                <CodeBlock body={detail?.response?.body} />
              </div>
            ) : (
              <div>
                <div className="mb-2 flex items-center gap-2 font-mono text-xs">
                  <span className="font-bold text-accent-subtle-fg">{detail?.request?.method}</span>
                  <span className="truncate">{detail?.request?.url}</span>
                </div>
                <HeaderList headers={detail?.request?.headers} />
                <CodeBlock body={detail?.request?.body} />
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Side: assertions + metadata */}
      <div className="flex flex-col gap-3.5">
        <div className="rounded-lg border bg-card">
          <div className="flex items-center gap-2 border-b px-3.5 py-2.5">
            <CheckCircle2 className="h-3.5 w-3.5 text-muted-foreground" />
            <h3 className="text-sm font-semibold">Assertions</h3>
            <Badge variant={assertions.some((a) => !a.passed) ? 'danger' : 'success'} size="sm" className="ml-auto">
              {assertions.filter((a) => a.passed).length} / {assertions.length}
            </Badge>
          </div>
          <div className="p-2.5">
            {assertions.length === 0 && <div className="px-1 py-2 text-xs text-muted-foreground">No assertions.</div>}
            {assertions.map((a, i) => (
              <div key={rowKey('assertion', i, a.label, a.expected, a.actual)} className={cn('flex items-start gap-2 rounded-md p-2 text-xs', !a.passed && 'bg-danger-bg')}>
                {a.passed ? <CheckCircle2 className="mt-0.5 h-3.5 w-3.5 shrink-0 text-success-fg" /> : <XCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-danger-fg" />}
                <span className="flex-1">
                  {a.label}
                  {!a.passed && a.actual && <span className="mt-0.5 block font-mono text-[11px] text-danger-fg">got {a.actual}</span>}
                </span>
                <span className="font-mono text-[11px] text-muted-foreground">{a.passed ? a.actual : a.expected}</span>
              </div>
            ))}
          </div>
        </div>
        {detail?.resolvedIp && (
          <div className="rounded-lg border bg-card">
            <div className="flex items-center gap-2 border-b px-3.5 py-2.5">
              <Sliders className="h-3.5 w-3.5 text-muted-foreground" />
              <h3 className="text-sm font-semibold">Run metadata</h3>
            </div>
            <dl className="grid grid-cols-[auto_1fr] gap-x-3.5 gap-y-2 p-3.5 text-sm">
              <dt className="text-muted-foreground">Resolved IP</dt>
              <dd className="text-right font-mono text-xs">{detail.resolvedIp}</dd>
            </dl>
          </div>
        )}
      </div>
    </div>
  )
}

function HeaderList({headers}: Readonly<{headers?: Record<string, string>}>) {
  const entries = Object.entries(headers ?? {})
  if (entries.length === 0) return null
  return (
    <dl className="mb-3 grid grid-cols-[auto_1fr] gap-x-3.5 gap-y-1 text-xs">
      {entries.slice(0, 12).map(([k, v]) => (
        <div key={k} className="contents">
          <dt className="text-muted-foreground">{k}</dt>
          <dd className="truncate text-right font-mono">{v}</dd>
        </div>
      ))}
    </dl>
  )
}

function CodeBlock({body}: Readonly<{body?: string}>) {
  if (!body) return null
  return <pre className="max-h-72 overflow-auto rounded-md border bg-[#0c0f15] p-3 font-mono text-xs text-[#d7e1ec]">{body.slice(0, 6000)}</pre>
}

function BrowserResult({detail}: Readonly<{detail?: SyntheticRunDetail | null}>) {
  const browser = detail?.browser
  const steps = browser?.steps ?? []
  const failingStepIdx = steps.findIndex((s) => s.status === 'failed')
  return (
    <div className="flex flex-col gap-3.5">
      {/* Filmstrip */}
      <div className="rounded-lg border bg-card">
        <div className="flex items-center gap-2 border-b px-3.5 py-2.5">
          <Monitor className="h-3.5 w-3.5 text-muted-foreground" />
          <h3 className="text-sm font-semibold">Screenshots</h3>
          <span className="ml-auto text-xs text-muted-foreground">captured at each step</span>
        </div>
        <div className="flex gap-2.5 overflow-x-auto p-3.5">
          {steps.map((s, i) => (
            <div key={rowKey('screenshot-step', i, s.action, s.label, s.status)} className="w-24 shrink-0">
              <div className={stepFrameClass(s.status, i === failingStepIdx)}>
                <Screenshot screenshotKey={s.screenshotKey} status={s.status} />
                <span className={cn('absolute bottom-1 left-1 grid h-3.5 w-3.5 place-items-center rounded-full text-white', stepBadgeClass(s.status))}>
                  {compactStepIcon(s.status)}
                </span>
              </div>
              <div className={cn('mt-1 truncate text-center text-[10px]', s.status === 'failed' ? 'text-danger-fg' : 'text-muted-foreground')}>
                {i + 1} · {s.action}
              </div>
            </div>
          ))}
          {steps.length === 0 && <div className="px-1 py-3 text-xs text-muted-foreground">No screenshots captured.</div>}
        </div>
      </div>

      <div className="grid grid-cols-1 gap-3.5 lg:grid-cols-[1fr_320px]">
        {/* Steps */}
        <div className="rounded-lg border bg-card">
          <div className="flex items-center gap-2 border-b px-3.5 py-2.5">
            <List className="h-3.5 w-3.5 text-muted-foreground" />
            <h3 className="text-sm font-semibold">Steps</h3>
          </div>
          <div className="flex flex-col gap-1.5 p-3.5">
            {steps.map((s, i) => (
              <div
                key={rowKey('browser-step', i, s.action, s.label, s.status)}
                className={cn(
                  'flex items-center gap-2.5 rounded-md border p-2',
                  stepRowClass(s.status)
                )}
              >
                <span className="w-4 text-right text-[10px] tabular-nums text-muted-foreground">{i + 1}</span>
                <span className={cn('grid h-6 w-6 place-items-center rounded border', stepStatusClass(s.status))}>
                  {stepIcon(s.status)}
                </span>
                <span className="flex-1 text-sm">
                  <span className="font-semibold capitalize">{s.label || s.action}</span>
                  {s.errorMessage && <span className="mt-0.5 block font-mono text-[11px] text-danger-fg">{s.errorMessage}</span>}
                </span>
                <span className={cn('font-mono text-[11px]', s.status === 'failed' ? 'font-semibold text-danger-fg' : 'text-muted-foreground')}>
                  {stepDurationLabel(s.status, s.durationMs)}
                </span>
              </div>
            ))}
          </div>
        </div>

        {/* Console + network */}
        <div className="flex flex-col gap-3.5">
          <div className="rounded-lg border bg-card">
            <div className="flex items-center gap-2 border-b px-3.5 py-2.5">
              <Code className="h-3.5 w-3.5 text-muted-foreground" />
              <h3 className="text-sm font-semibold">Console</h3>
              {(browser?.console?.length ?? 0) > 0 && (
                <Badge variant="neutral" size="sm" className="ml-auto">
                  {browser?.console?.length}
                </Badge>
              )}
            </div>
            <div className="p-2.5">
              {(browser?.console ?? []).length === 0 && <div className="px-1 py-2 text-xs text-muted-foreground">No console output.</div>}
              {(browser?.console ?? []).slice(0, 20).map((c, i) => (
                <div key={rowKey('console', i, c.level, c.text)} className="flex gap-2 border-b border-border/40 py-1.5 font-mono text-[11px] last:border-b-0">
                  <span className={cn('shrink-0 font-bold uppercase', consoleLevelClass(c.level))}>{c.level}</span>
                  <span className="min-w-0 flex-1 break-all">{c.text}</span>
                </div>
              ))}
            </div>
          </div>
          <div className="rounded-lg border bg-card">
            <div className="flex items-center gap-2 border-b px-3.5 py-2.5">
              <Network className="h-3.5 w-3.5 text-muted-foreground" />
              <h3 className="text-sm font-semibold">Network</h3>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-xs">
                <tbody>
                  {(browser?.network ?? []).slice(0, 20).map((n, i) => (
                    <tr key={rowKey('network', i, n.method, n.url, n.status)} className="border-b border-border/40 last:border-b-0">
                      <td className="px-3 py-1.5">
                        <Badge variant={(n.status ?? 0) >= 400 ? 'danger' : 'success'} size="sm">
                          {n.status}
                        </Badge>
                      </td>
                      <td className="truncate px-1 py-1.5 font-mono text-[11px]">{n.method} {n.url}</td>
                      <td className="px-3 py-1.5 text-right font-mono text-muted-foreground">{n.durationMs ? `${n.durationMs}ms` : ''}</td>
                    </tr>
                  ))}
                  {(browser?.network ?? []).length === 0 && (
                    <tr>
                      <td className="px-3 py-2 text-xs text-muted-foreground">No network activity.</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
          {browser && (
            <div className="rounded-lg border bg-card">
              <div className="flex items-center gap-2 border-b px-3.5 py-2.5">
                <Sliders className="h-3.5 w-3.5 text-muted-foreground" />
                <h3 className="text-sm font-semibold">Run metadata</h3>
              </div>
              <dl className="grid grid-cols-[auto_1fr] gap-x-3.5 gap-y-2 p-3.5 text-sm">
                <dt className="text-muted-foreground">Browser</dt>
                <dd className="text-right">{browser.browser || 'Chromium'}</dd>
                <dt className="text-muted-foreground">Viewport</dt>
                <dd className="text-right font-mono text-xs">{browser.viewport || '—'}</dd>
              </dl>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

function Screenshot({screenshotKey, status}: Readonly<{screenshotKey?: string; status: string}>) {
  const [errored, setErrored] = useState(false)
  if (!screenshotKey || errored) {
    return (
      <div
        className="h-full w-full"
        style={{background: screenshotFallbackBackground(status)}}
      />
    )
  }
  return <img src={api.syntheticScreenshotUrl(screenshotKey)} alt="step screenshot" className="h-full w-full object-cover" onError={() => setErrored(true)} />
}
