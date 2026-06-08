// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useMemo, useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {Link} from '@tanstack/react-router'
import {
  Bar,
  BarChart as RechartsBarChart,
  Cell,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import {ArrowLeft, Clock, Layers, Loader2, Server, X} from 'lucide-react'
import {api} from '@/lib/api'
import {Flamegraph} from '@/components/profiling/Flamegraph'
import {ProfileList} from '@/components/profiling/ProfileList'
import {Button} from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {cn, formatRelativeTime} from '@/lib/utils'
import {getNow} from '@/lib/demo'
import {formatBytes, formatDuration, parseUtcDate} from './profileFormat'

const ALL_ENVS = '__all'
const MERGE_PROFILE_CAP = 25

type ProfileRangeKey = '1h' | '6h' | '24h' | '7d'

const PROFILE_TIME_PRESETS: Array<{
  label: string
  value: ProfileRangeKey
  minutes: number
}> = [
  {label: '1h', value: '1h', minutes: 60},
  {label: '6h', value: '6h', minutes: 360},
  {label: '24h', value: '24h', minutes: 1440},
  {label: '7d', value: '7d', minutes: 10080},
]

const RANGES: Record<ProfileRangeKey, {label: string; ms: number}> = {
  '1h': {label: '1h', ms: 60 * 60 * 1000},
  '6h': {label: '6h', ms: 6 * 60 * 60 * 1000},
  '24h': {label: '24h', ms: 24 * 60 * 60 * 1000},
  '7d': {label: '7d', ms: 7 * 24 * 60 * 60 * 1000},
}

interface ServiceExplorerFilters {
  service?: string
  env?: string
  type?: string
  rangeKey: ProfileRangeKey
}

interface SampleSelection {
  signature: string
  sampleType: string | null
  thread: string | null
}

interface ZoomSelection {
  signature: string
  from: number
  to: number
}

interface Props {
  service?: string
  filters?: ServiceExplorerFilters
}

export function ServiceExplorer({service, filters}: Props) {
  const [internalEnv, setInternalEnv] = useState<string>(ALL_ENVS)
  const [internalRangeKey, setInternalRangeKey] = useState<ProfileRangeKey>('24h')
  const [internalSelectedType, setInternalSelectedType] = useState<string | null>(null)
  const [sampleSelection, setSampleSelection] = useState<SampleSelection>({
    signature: '',
    sampleType: null,
    thread: null,
  })
  const [zoom, setZoom] = useState<ZoomSelection | null>(null)
  const [showBrowse, setShowBrowse] = useState(false)
  const filtersControlled = filters !== undefined
  const activeService = filtersControlled ? filters.service : service
  const activeRangeKey = filters?.rangeKey ?? internalRangeKey

  const {data: servicesData, isLoading: servicesLoading} = useQuery({
    queryKey: ['profileServices'],
    queryFn: () => api.getProfileServices(),
    enabled: api.isAuthenticated(),
  })
  const summary = activeService
    ? servicesData?.services.find((s) => s.service === activeService)
    : undefined

  const range = useMemo(() => {
    const to = getNow()
    return {from: to - RANGES[activeRangeKey].ms, to}
  }, [activeRangeKey])

  const envParam = filtersControlled
    ? filters.env
    : internalEnv === ALL_ENVS
      ? undefined
      : internalEnv
  const effectiveType =
    filters?.type ?? internalSelectedType ?? summary?.types[0]?.profileType ?? undefined
  const filterSignature = [activeService ?? '', envParam ?? '', effectiveType ?? ''].join('\u001f')
  const zoomSignature = `${filterSignature}\u001f${activeRangeKey}`
  const activeZoom = zoom?.signature === zoomSignature ? zoom : null
  const mergeWindow = activeZoom ?? range
  const activeSampleType =
    sampleSelection.signature === filterSignature ? sampleSelection.sampleType : null
  const activeThread =
    sampleSelection.signature === filterSignature ? sampleSelection.thread : null

  const {data: timeseries} = useQuery({
    queryKey: ['profileTimeseries', activeService, envParam, effectiveType, range.from, range.to],
    queryFn: () =>
      api.getProfileTimeseries({
        service: activeService,
        env: envParam,
        type: effectiveType,
        from: range.from,
        to: range.to,
        buckets: 48,
      }),
    enabled: api.isAuthenticated(),
  })

  const {data: merged, isFetching: mergeFetching} = useQuery({
    queryKey: [
      'mergedFlamegraph',
      activeService,
      envParam,
      effectiveType,
      mergeWindow.from,
      mergeWindow.to,
      activeSampleType,
      activeThread,
    ],
    queryFn: () =>
      api.getMergedFlamegraph({
        service: activeService,
        env: envParam,
        type: effectiveType,
        from: mergeWindow.from,
        to: mergeWindow.to,
        sampleType: activeSampleType,
        thread: activeThread,
        maxProfiles: MERGE_PROFILE_CAP,
      }),
    enabled: api.isAuthenticated(),
  })

  const bucketMs = (timeseries?.bucketSeconds ?? 0) * 1000
  const buckets = useMemo(
    () =>
      buildTimelineBuckets(
        timeseries?.points ?? [],
        bucketMs,
        range.from,
        range.to,
      ),
    [timeseries?.points, bucketMs, range.from, range.to],
  )

  const handleTypeChange = (type: string) => {
    setInternalSelectedType(type)
    setSampleSelection({signature: '', sampleType: null, thread: null})
  }

  const handleRangeChange = (key: ProfileRangeKey) => {
    setInternalRangeKey(key)
    setZoom(null)
  }

  const toggleBucket = (ts: number) => {
    if (!bucketMs) return
    if (activeZoom && activeZoom.from === ts) {
      setZoom(null)
    } else {
      setZoom({signature: zoomSignature, from: ts, to: ts + bucketMs})
    }
  }

  const handleSampleTypeChange = (value: string) => {
    setSampleSelection({
      signature: filterSignature,
      sampleType: value,
      thread: null,
    })
  }

  const handleThreadChange = (value: string | null) => {
    setSampleSelection((current) => ({
      signature: filterSignature,
      sampleType: current.signature === filterSignature ? current.sampleType : null,
      thread: value,
    }))
  }

  if (servicesLoading) {
    return (
      <div className="p-3 flex flex-col items-center justify-center py-16 gap-2">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        <p className="text-xs text-muted-foreground">Loading service…</p>
      </div>
    )
  }

  const types = summary?.types ?? []
  const environments = summary?.environments ?? []
  const language = summary?.languages[0] || summary?.runtimes[0] || undefined

  const metaSidebar = (
    <div className="rounded-lg border p-2.5 space-y-2 shrink-0 text-[11px]">
      <div className="grid grid-cols-2 gap-x-3 gap-y-1.5">
        <Meta label="Hosts" value={String(summary?.hostCount ?? 0)} />
        <Meta
          label="Avg duration"
          value={summary ? formatDuration(summary.avgDurationNs) : '—'}
        />
        <Meta label="Total size" value={summary ? formatBytes(summary.totalSizeBytes) : '—'} />
        <Meta
          label="Profiles"
          value={(summary?.profileCount ?? 0).toLocaleString()}
        />
      </div>
      {merged && (
        <div className="pt-2 border-t text-muted-foreground">
          Aggregated from{' '}
          <span className="text-foreground font-medium">{merged.mergedCount ?? 0}</span> of{' '}
          <span className="text-foreground font-medium">{merged.totalCount ?? 0}</span>{' '}
          profiles in window
        </div>
      )}
      {summary && (
        <div className="pt-2 border-t flex items-center gap-1 text-muted-foreground">
          <Clock className="h-3 w-3" />
          {Number.isFinite(parseUtcDate(summary.lastSeen).getTime())
            ? `last ${formatRelativeTime(parseUtcDate(summary.lastSeen).getTime())}`
            : '—'}
        </div>
      )}
    </div>
  )

  return (
    <div
      className="flex flex-col overflow-hidden p-3 gap-y-2"
      style={{height: 'calc(100vh - var(--header-height, 0px))'}}
    >
      {/* Header + controls */}
      <div className="flex items-start justify-between gap-2 shrink-0 flex-wrap">
        <div className="flex items-start gap-2 min-w-0">
          <Button variant="ghost" size="icon" className="h-7 w-7 mt-0.5 shrink-0" asChild>
            <Link to="/profiles">
              <ArrowLeft className="h-3.5 w-3.5" />
            </Link>
          </Button>
          <div className="min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <Server className="h-4 w-4 text-muted-foreground" />
              <h1 className="text-lg font-bold tracking-tight leading-tight truncate">
                {activeService ?? 'All services'}
              </h1>
              {language && (
                <span className="text-xs text-muted-foreground bg-muted px-2 py-0.5 rounded">
                  {language}
                </span>
              )}
            </div>
            <p className="text-xs text-muted-foreground mt-0.5">
              Aggregated flamegraph across the selected window
            </p>
          </div>
        </div>

        {!filtersControlled && (
          <div className="flex items-center gap-1.5 flex-wrap">
            {types.length > 1 && (
              <Select value={effectiveType} onValueChange={handleTypeChange}>
                <SelectTrigger className="h-7 w-[130px] text-xs">
                  <SelectValue placeholder="Type" />
                </SelectTrigger>
                <SelectContent>
                  {types.map((t) => (
                    <SelectItem key={t.profileType} value={t.profileType}>
                      {t.profileType} ({t.count.toLocaleString()})
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
            {environments.length > 1 && (
              <Select value={internalEnv} onValueChange={setInternalEnv}>
                <SelectTrigger className="h-7 w-[150px] text-xs">
                  <SelectValue placeholder="All environments" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={ALL_ENVS}>All environments</SelectItem>
                  {environments.map((e) => (
                    <SelectItem key={e} value={e}>
                      {e}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
            <div className="inline-flex rounded-md border p-0.5 text-xs">
              {PROFILE_TIME_PRESETS.map((preset) => (
                <button
                  key={preset.value}
                  type="button"
                  onClick={() => handleRangeChange(preset.value)}
                  className={cn(
                    'px-2 py-1 rounded font-medium transition-colors',
                    activeRangeKey === preset.value
                      ? 'bg-secondary text-secondary-foreground'
                      : 'text-muted-foreground hover:text-foreground',
                  )}
                >
                  {preset.label}
                </button>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Volume timeline */}
      <div className="rounded-lg border bg-card px-2 pt-1.5 pb-1 shrink-0">
        <div className="flex items-center justify-between px-1 mb-0.5">
          <span className="text-[10px] uppercase tracking-wide text-muted-foreground font-medium">
            Profile volume
          </span>
          {activeZoom ? (
            <button
              type="button"
              onClick={() => setZoom(null)}
              className="inline-flex items-center gap-1 text-[10px] text-primary hover:underline"
            >
              <X className="h-3 w-3" />
              {new Date(activeZoom.from).toLocaleString()} — clear selection
            </button>
          ) : (
            <span className="text-[10px] text-muted-foreground">
              click a bar to focus the flamegraph on that window
            </span>
          )}
        </div>
        <VolumeTimeline
          buckets={buckets}
          rangeMs={RANGES[activeRangeKey].ms}
          selectedFrom={activeZoom?.from ?? null}
          onSelect={toggleBucket}
        />
      </div>

      {/* Merged flamegraph */}
      <div className="flex flex-col flex-1 min-h-0">
        <div className="flex items-center justify-between mb-1.5">
          <h2 className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">
            Merged flamegraph
          </h2>
          {mergeFetching && (
            <Loader2 className="h-3.5 w-3.5 animate-spin text-muted-foreground" />
          )}
        </div>
        <Flamegraph
          frames={merged?.frames}
          language={language}
          service={activeService}
          meta={metaSidebar}
          sampleTypes={merged?.sampleTypes}
          threads={merged?.threads}
          selectedSampleType={merged?.selectedSampleType}
          selectedThread={merged?.selectedThread ?? null}
          unit={merged?.unit}
          onSampleTypeChange={handleSampleTypeChange}
          onThreadChange={handleThreadChange}
          emptyMessage={
            mergeFetching
              ? 'Aggregating profiles…'
              : 'No profiling data in this window'
          }
        />
      </div>

      {/* Browse raw profiles in window */}
      <div className="shrink-0">
        <button
          type="button"
          onClick={() => setShowBrowse((v) => !v)}
          className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
        >
          <Layers className="h-3.5 w-3.5" />
          {showBrowse ? 'Hide' : 'Browse'} individual profiles in window
        </button>
        {showBrowse && (
          <div className="mt-1.5 max-h-64 overflow-auto rounded-lg border">
            <ProfileList
              embedded
              scope={{
                service: activeService,
                env: envParam,
                type: effectiveType,
                from: mergeWindow.from,
                to: mergeWindow.to,
              }}
            />
          </div>
        )}
      </div>
    </div>
  )
}

function Meta({label, value}: {label: string; value: string}) {
  return (
    <div className="min-w-0">
      <p className="text-[10px] text-muted-foreground leading-none mb-0.5">{label}</p>
      <p className="text-xs font-medium tabular-nums truncate">{value}</p>
    </div>
  )
}

interface TimelineBucket {
  ts: number
  count: number
}

function VolumeTimeline({
  buckets,
  rangeMs,
  selectedFrom,
  onSelect,
}: {
  buckets: TimelineBucket[]
  rangeMs: number
  selectedFrom: number | null
  onSelect: (ts: number) => void
}) {
  if (buckets.length === 0) {
    return (
      <div className="h-[72px] flex items-center justify-center text-[11px] text-muted-foreground">
        No activity in this window
      </div>
    )
  }
  const showDate = rangeMs > 24 * 60 * 60 * 1000
  return (
    <ResponsiveContainer width="100%" height={72}>
      <RechartsBarChart
        data={buckets}
        margin={{top: 2, right: 4, bottom: 0, left: 4}}
        onClick={(state) => {
          const payload = (
            state as {activePayload?: Array<{payload?: TimelineBucket}>}
          )?.activePayload?.[0]?.payload
          if (payload) onSelect(payload.ts)
        }}
      >
        <XAxis
          dataKey="ts"
          tickFormatter={(ts: number) => formatTick(ts, showDate)}
          tick={{fontSize: 9, fill: 'hsl(var(--muted-foreground))'}}
          axisLine={false}
          tickLine={false}
          minTickGap={28}
        />
        <YAxis hide />
        <Tooltip
          cursor={{fill: 'hsl(var(--muted) / 0.4)'}}
          labelFormatter={(ts) => new Date(ts as number).toLocaleString()}
          formatter={(value) => [`${value} profiles`, '']}
          contentStyle={{
            fontSize: 11,
            borderRadius: 6,
            border: '1px solid hsl(var(--border))',
            background: 'hsl(var(--popover))',
          }}
        />
        <Bar dataKey="count" radius={[1, 1, 0, 0]} isAnimationActive={false} cursor="pointer">
          {buckets.map((b) => (
            <Cell
              key={b.ts}
              fill={
                selectedFrom === b.ts
                  ? 'hsl(var(--chart-2))'
                  : 'hsl(var(--chart-1))'
              }
            />
          ))}
        </Bar>
      </RechartsBarChart>
    </ResponsiveContainer>
  )
}

function buildTimelineBuckets(
  points: {ts: number; count: number}[],
  bucketMs: number,
  from: number,
  to: number,
): TimelineBucket[] {
  if (bucketMs <= 0) {
    return points.map((p) => ({ts: p.ts, count: p.count}))
  }
  const counts = new Map(points.map((p) => [p.ts, p.count]))
  const start = Math.floor(from / bucketMs) * bucketMs
  const out: TimelineBucket[] = []
  for (let t = start; t < to; t += bucketMs) {
    out.push({ts: t, count: counts.get(t) ?? 0})
  }
  return out
}

function formatTick(ts: number, showDate: boolean): string {
  const d = new Date(ts)
  const time = d.toLocaleTimeString(undefined, {hour: '2-digit', minute: '2-digit'})
  if (!showDate) return time
  return `${d.toLocaleDateString(undefined, {month: 'numeric', day: 'numeric'})} ${time}`
}
