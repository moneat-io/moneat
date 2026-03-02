// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {createFileRoute, Link} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Flamegraph} from '@/components/profiling/Flamegraph'
import {Card, CardContent} from '@/components/ui/card'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
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

const TYPE_COLORS: Record<string, string> = {
  cpu: 'bg-orange-500/15 text-orange-400 border-orange-500/30',
  wall: 'bg-blue-500/15 text-blue-400 border-blue-500/30',
  heap: 'bg-green-500/15 text-green-400 border-green-500/30',
  alloc: 'bg-emerald-500/15 text-emerald-400 border-emerald-500/30',
  goroutine: 'bg-purple-500/15 text-purple-400 border-purple-500/30',
  mutex: 'bg-red-500/15 text-red-400 border-red-500/30',
  block: 'bg-yellow-500/15 text-yellow-400 border-yellow-500/30',
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

  const {data: profilesData, isLoading} = useQuery({
    queryKey: ['profiles', {limit: 200}],
    queryFn: () => api.getProfiles({limit: 200}),
    enabled: api.isAuthenticated(),
  })

  const profile = profilesData?.profiles?.find(
    (p) => p.profileId === profileId,
  )

  const {data: flamegraphData, isLoading: isFlamegraphLoading} = useQuery({
    queryKey: ['profileFlamegraph', profileId],
    queryFn: () => api.getProfileFlamegraph(profileId),
    enabled: api.isAuthenticated() && !!profileId,
  })

  const frames = flamegraphData?.frames

  if (isLoading) {
    return (
      <div className="p-6 flex flex-col items-center justify-center py-24 gap-3">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
        <p className="text-sm text-muted-foreground">Loading profile...</p>
      </div>
    )
  }

  if (!profile) {
    return (
      <div className="p-6 flex flex-col items-center justify-center py-24 gap-3">
        <p className="text-sm text-muted-foreground">Profile not found</p>
      </div>
    )
  }

  const tagEntries = profile ? Object.entries(profile.tags) : []

  return (
    <div className="p-6 space-y-5">
      {/* Header */}
      <div className="flex items-start justify-between gap-4">
        <div className="flex items-start gap-3 min-w-0">
          <Button
            variant="ghost"
            size="icon"
            className="h-8 w-8 mt-0.5 shrink-0"
            asChild
          >
            <Link to="/profiles" search={{tab: 'sentry'}}>
              <ArrowLeft className="h-4 w-4" />
            </Link>
          </Button>
          <div className="space-y-1 min-w-0">
            <div className="flex items-center gap-2.5 flex-wrap">
              <h1 className="text-xl font-bold tracking-tight leading-tight">
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
          className="shrink-0"
          onClick={() => api.downloadProfile(profileId, undefined, profile.profileType)}
        >
          <Download className="h-3.5 w-3.5 mr-1.5" />
          {profile.profileType.toLowerCase() === 'jfr'
            ? 'Download JFR'
            : 'Download pprof'}
        </Button>
      </div>

      {/* Metadata grid */}
      {profile && (
        <Card>
          <CardContent className="py-3 px-4">
            <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-x-6 gap-y-2.5">
              <MetaItem
                icon={Server}
                label="Service"
                value={profile.service || '—'}
              />
              <MetaItem
                icon={Globe}
                label="Environment"
                value={profile.env || '—'}
              />
              <MetaItem
                icon={HardDrive}
                label="Host"
                value={profile.host || '—'}
                mono
              />
              <MetaItem
                icon={Timer}
                label="Duration"
                value={formatDuration(profile.durationNs)}
                mono
              />
              <MetaItem
                icon={Layers}
                label="Size"
                value={formatBytes(profile.sizeBytes)}
                mono
              />
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
              <div className="flex flex-wrap items-center gap-x-5 gap-y-1 mt-2.5 pt-2.5 border-t text-xs text-muted-foreground">
                {profile.startTime && (
                  <span className="flex items-center gap-1.5">
                    <Clock className="h-3 w-3" />
                    {formatTime(profile.startTime)}
                    {profile.endTime && (
                      <> — {formatTime(profile.endTime)}</>
                    )}
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
          </CardContent>
        </Card>
      )}

      {/* Tags */}
      {tagEntries.length > 0 && (
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="text-xs font-medium text-muted-foreground mr-1">
            Tags
          </span>
          {tagEntries.map(([k, v]) => (
            <Badge
              key={k}
              variant="outline"
              className="text-[11px] font-mono py-0 h-5"
            >
              {k}
              <span className="text-muted-foreground mx-0.5">=</span>
              {v}
            </Badge>
          ))}
        </div>
      )}

      {/* Flamegraph */}
      <div>
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-sm font-semibold text-muted-foreground uppercase tracking-wide">
            Flamegraph
          </h2>
          {isFlamegraphLoading && (
            <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
          )}
        </div>
        <Flamegraph
          frames={frames}
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
    <div className="flex items-center gap-2 min-w-0">
      <Icon className="h-3.5 w-3.5 text-muted-foreground shrink-0" />
      <div className="min-w-0">
        <p className="text-[10px] text-muted-foreground leading-none mb-0.5">
          {label}
        </p>
        <p
          className={`text-sm font-medium leading-tight truncate ${mono ? 'font-mono' : ''}`}
        >
          {value}
        </p>
      </div>
    </div>
  )
}
