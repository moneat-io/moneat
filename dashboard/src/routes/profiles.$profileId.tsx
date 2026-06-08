// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useState, useMemo} from 'react'
import {createFileRoute, Link} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Flamegraph} from '@/components/profiling/Flamegraph'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {EmptyState} from '@/components/ui/empty-state'
import {
  ArrowLeft,
  Clock,
  Code2,
  Download,
  Globe,
  HardDrive,
  Layers,
  Loader2,
  Server,
  Tag,
  Timer,
} from 'lucide-react'

export const Route = createFileRoute('/profiles/$profileId')({
  component: ProfileDetailPage,
})

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function formatDuration(ns: number): string {
  if (ns < 1_000_000_000) return `${(ns / 1_000_000).toFixed(0)}ms`
  return `${(ns / 1_000_000_000).toFixed(1)}s`
}

function formatTime(iso: string): string {
  try {
    return new Date(iso).toLocaleString(undefined, {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    })
  } catch {
    return iso
  }
}

// Profile types are categorical — differentiate with the chart palette (style guide).
const TYPE_COLORS: Record<string, string> = {
  cpu: 'bg-chart-6/15 text-chart-6 border-chart-6/30',
  wall: 'bg-chart-2/15 text-chart-2 border-chart-2/30',
  heap: 'bg-chart-4/15 text-chart-4 border-chart-4/30',
  alloc: 'bg-chart-3/15 text-chart-3 border-chart-3/30',
  goroutine: 'bg-chart-10/15 text-chart-10 border-chart-10/30',
  mutex: 'bg-chart-8/15 text-chart-8 border-chart-8/30',
  block: 'bg-chart-5/15 text-chart-5 border-chart-5/30',
}

function profileTypeBadgeClass(type: string): string {
  const key = type.toLowerCase()
  for (const [k, v] of Object.entries(TYPE_COLORS)) {
    if (key.includes(k)) return v
  }
  return 'bg-secondary text-secondary-foreground'
}

function ProfileDetailPage() {
  const {profileId} = Route.useParams()

  const {data: profile, isLoading} = useQuery({
    queryKey: ['profile', profileId],
    queryFn: () => api.getProfile(profileId),
    enabled: api.isAuthenticated() && !!profileId,
    retry: false,
  })

  const [sampleType, setSampleType] = useState<string | null>(null)
  const [thread, setThread] = useState<string | null>(null)
  const [compareId, setCompareId] = useState<string | null>(null)

  const {data: flamegraphData, isLoading: isFlamegraphLoading} = useQuery({
    queryKey: ['profileFlamegraph', profileId, sampleType, thread],
    queryFn: () => api.getProfileFlamegraph(profileId, {sampleType, thread}),
    enabled: api.isAuthenticated() && !!profileId,
  })

  const frames = flamegraphData?.frames

  const {data: comparableData} = useQuery({
    queryKey: ['profilesCompare', profile?.service, profile?.profileType],
    queryFn: () =>
      api.getProfiles({
        service: profile?.service || undefined,
        type: profile?.profileType || undefined,
        limit: 50,
      }),
    enabled: api.isAuthenticated() && !!profile,
  })

  const compareProfiles = useMemo(() => {
    const list = comparableData?.profiles ?? []
    return list
      .filter((p) => p.profileId !== profileId)
      .map((p) => ({
        profileId: p.profileId,
        label: `${formatTime(p.startTime)} · ${p.host || p.version || p.profileId.slice(0, 8)}`,
      }))
  }, [comparableData, profileId])

  const {data: baselineData, isLoading: baselineLoading} = useQuery({
    queryKey: ['profileFlamegraph', compareId, sampleType, thread],
    queryFn: () => api.getProfileFlamegraph(compareId as string, {sampleType, thread}),
    enabled: api.isAuthenticated() && !!compareId,
  })

  // Reset selectors and comparison when navigating to another profile.
  const [activeProfileId, setActiveProfileId] = useState(profileId)
  if (profileId !== activeProfileId) {
    setActiveProfileId(profileId)
    setSampleType(null)
    setThread(null)
    setCompareId(null)
  }

  if (isLoading) {
    return (
      <div
        className="flex flex-col gap-2 p-3"
        style={{height: 'calc(100vh - var(--header-height, 0px))'}}
      >
        <div className="h-7 w-64 shrink-0 animate-pulse rounded bg-muted" />
        <div className="flex-1 animate-pulse rounded-lg border bg-card" />
      </div>
    )
  }

  if (!profile) {
    return (
      <div className="p-6">
        <EmptyState
          icon={Layers}
          title="Profile not found"
          description="The profile may have expired out of the retention window, or the ID may be incorrect."
          action={
            <Button asChild variant="secondary" size="sm">
              <Link to="/profiles">
                <ArrowLeft className="h-4 w-4" />
                Back to profiles
              </Link>
            </Button>
          }
        />
      </div>
    )
  }

  const tagEntries = profile ? Object.entries(profile.tags) : []

  const sidebarMeta = (
    <div className="rounded-lg border p-2.5 space-y-2 shrink-0">
      <div className="grid grid-cols-2 gap-x-3 gap-y-2">
        <MetaItem icon={Server} label="Service" value={profile.service || '—'} />
        <MetaItem icon={Globe} label="Environment" value={profile.env || '—'} />
        <MetaItem icon={HardDrive} label="Host" value={profile.host || '—'} mono />
        <MetaItem icon={Timer} label="Duration" value={formatDuration(profile.durationNs)} mono />
        <MetaItem icon={Layers} label="Size" value={formatBytes(profile.sizeBytes)} mono />
        <MetaItem
          icon={Code2}
          label="Runtime"
          value={
            profile.runtime
              ? `${profile.language} / ${profile.runtime}`
              : profile.language || '—'
          }
        />
      </div>
      {(profile.startTime || profile.version) && (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 pt-2 border-t text-[11px] text-muted-foreground">
          {profile.startTime && (
            <span className="flex items-center gap-1.5">
              <Clock className="h-3 w-3" />
              {formatTime(profile.startTime)}
              {profile.endTime && <> — {formatTime(profile.endTime)}</>}
            </span>
          )}
          {profile.version && (
            <span className="flex items-center gap-1.5">
              <Tag className="h-3 w-3" />
              v{profile.version}
            </span>
          )}
        </div>
      )}
      {tagEntries.length > 0 && (
        <div className="pt-2 border-t space-y-1">
          {tagEntries.map(([k, v]) => (
            <div
              key={k}
              className="grid grid-cols-[minmax(0,7rem)_1fr] gap-2 text-[10px] font-mono leading-tight"
            >
              <span className="text-muted-foreground truncate" title={k}>
                {k}
              </span>
              <span className="truncate" title={v}>
                {v}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  )

  return (
    <div
      className="flex flex-col overflow-hidden p-3 gap-y-2"
      style={{height: 'calc(100vh - var(--header-height, 0px))'}}
    >
      {/* Header */}
      <div className="flex items-start justify-between gap-2">
        <div className="flex items-start gap-2 min-w-0">
          <Button
            variant="ghost"
            size="icon"
            className="h-7 w-7 mt-0.5 shrink-0"
            asChild
          >
            <Link to="/profiles">
              <ArrowLeft className="h-3.5 w-3.5" />
            </Link>
          </Button>
          <div className="space-y-0.5 min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <h1 className="text-xl font-semibold tracking-tight leading-tight">
                {profile?.service || 'Profile'}
              </h1>
              {profile && (
                <Badge
                  variant="outline"
                  className={`text-[11px] border ${profileTypeBadgeClass(profile.profileType)}`}
                >
                  {profile.profileType}
                </Badge>
              )}
              {profile?.language && (
                <span className="text-xs text-muted-foreground bg-muted px-2 py-0.5 rounded">
                  {profile.language}
                  {profile.runtime ? ` / ${profile.runtime}` : ''}
                </span>
              )}
            </div>
            <p className="text-xs text-muted-foreground font-mono truncate">
              {profileId}
            </p>
          </div>
        </div>
        <Button
          variant="outline"
          size="sm"
          className="shrink-0 h-7 text-xs"
          onClick={() => api.downloadProfile(profileId, undefined, profile.profileType)}
        >
          <Download className="h-3 w-3 mr-1" />
          {profile.profileType.toLowerCase() === 'jfr'
            ? 'Download JFR'
            : 'Download pprof'}
        </Button>
      </div>

      {/* Metadata + tags now live in the flamegraph sidebar (meta prop). */}

      {/* Flamegraph */}
      <div className="flex flex-col flex-1 min-h-0">
        <div className="flex items-center justify-between mb-2">
          <h2 className="text-xs font-semibold text-muted-foreground uppercase tracking-wide">
            Flamegraph
          </h2>
          {isFlamegraphLoading && (
            <Loader2 className="h-3.5 w-3.5 animate-spin text-muted-foreground" />
          )}
        </div>
        <Flamegraph
          frames={frames}
          language={profile.language || profile.runtime}
          service={profile.service}
          meta={sidebarMeta}
          compareProfiles={compareProfiles}
          compareId={compareId}
          onCompareChange={setCompareId}
          baselineFrames={baselineData?.frames}
          baselineLoading={baselineLoading}
          sampleTypes={flamegraphData?.sampleTypes}
          threads={flamegraphData?.threads}
          selectedSampleType={flamegraphData?.selectedSampleType}
          selectedThread={flamegraphData?.selectedThread ?? null}
          unit={flamegraphData?.unit}
          onSampleTypeChange={setSampleType}
          onThreadChange={setThread}
          emptyMessage={
            isFlamegraphLoading
              ? 'Loading flamegraph data…'
              : 'No flamegraph data available'
          }
        />
      </div>
    </div>
  )
}

function MetaItem({
  icon: Icon,
  label,
  value,
  mono,
}: {
  icon: React.ComponentType<{className?: string}>
  label: string
  value: string
  mono?: boolean
}) {
  return (
    <div className="flex items-center gap-1.5 min-w-0">
      <Icon className="h-3 w-3 text-muted-foreground shrink-0" />
      <div className="min-w-0">
        <p className="text-[10px] text-muted-foreground leading-none mb-0.5">
          {label}
        </p>
        <p
          className={`text-xs font-medium leading-tight truncate ${mono ? 'font-mono' : ''}`}
        >
          {value}
        </p>
      </div>
    </div>
  )
}
