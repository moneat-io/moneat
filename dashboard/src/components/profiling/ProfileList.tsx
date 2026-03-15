// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useQuery} from '@tanstack/react-query'
import {api, type ProfileResponse} from '@/lib/api'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import {Badge} from '@/components/ui/badge'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Activity,
  Clock,
  Code2,
  Download,
  ExternalLink,
  HardDrive,
  Layers,
  Loader2,
  Search,
  Server,
} from 'lucide-react'
import {useMemo} from 'react'
import {Link} from '@tanstack/react-router'
import {formatRelativeTime} from '@/lib/utils'

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function formatDuration(ns: number): string {
  if (ns < 1_000_000_000) return `${(ns / 1_000_000).toFixed(0)}ms`
  return `${(ns / 1_000_000_000).toFixed(1)}s`
}

function parseUtcDate(value: string): Date {
  if (value.includes('T')) return new Date(value)
  return new Date(value + ' UTC')
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

interface Props {
  serviceFilter: string
  onServiceFilterChange: (val: string) => void
  typeFilter: string
  onTypeFilterChange: (val: string) => void
}

export function ProfileList({
  serviceFilter,
  onServiceFilterChange,
  typeFilter,
  onTypeFilterChange,
}: Props) {
  const {data, isLoading} = useQuery({
    queryKey: ['profiles', serviceFilter, typeFilter],
    queryFn: () =>
      api.getProfiles({
        service: serviceFilter || undefined,
        type: typeFilter || undefined,
        limit: 50,
      }),
    enabled: api.isAuthenticated(),
  })

  const profiles = data?.profiles ?? []

  const stats = useMemo(() => {
    if (profiles.length === 0) return null

    const services = new Set(profiles.map((p) => p.service))
    const types = new Set(profiles.map((p) => p.profileType))
    const totalSize = profiles.reduce((sum, p) => sum + p.sizeBytes, 0)
    const durations = profiles.map((p) => p.durationNs).sort((a, b) => a - b)
    const avgDuration =
      durations.reduce((sum, d) => sum + d, 0) / durations.length

    return {
      totalProfiles: data?.totalCount ?? profiles.length,
      serviceCount: services.size,
      typeCount: types.size,
      totalSize,
      avgDuration,
    }
  }, [profiles, data?.totalCount])

  const availableTypes = useMemo(
    () => [...new Set(profiles.map((p) => p.profileType))].sort(),
    [profiles],
  )

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-8">
        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
      </div>
    )
  }

  if (profiles.length === 0) {
    return <ProfilingEmptyState />
  }

  return (
    <div className="space-y-2">
      {/* Filters */}
      <div className="flex items-center gap-1.5">
        <div className="relative flex-1 max-w-xs">
          <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground" />
          <Input
            placeholder="Filter by service..."
            value={serviceFilter}
            onChange={(e) => onServiceFilterChange(e.target.value)}
            className="pl-8 h-7 text-xs"
          />
        </div>
        {availableTypes.length > 1 && (
          <Select
            value={typeFilter || '__all'}
            onValueChange={(v) => onTypeFilterChange(v === '__all' ? '' : v)}
          >
            <SelectTrigger className="h-7 w-[140px] text-xs">
              <SelectValue placeholder="All types" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="__all">All types</SelectItem>
              {availableTypes.map((t) => (
                <SelectItem key={t} value={t}>
                  {t}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
      </div>

      {/* Summary stats */}
      {stats && (
        <div className="grid grid-cols-2 md:grid-cols-5 gap-1.5">
          <StatCard
            label="Total Profiles"
            value={stats.totalProfiles.toLocaleString()}
            icon={<Layers className="h-3.5 w-3.5" />}
          />
          <StatCard
            label="Services"
            value={String(stats.serviceCount)}
            icon={<Server className="h-3.5 w-3.5" />}
          />
          <StatCard
            label="Profile Types"
            value={String(stats.typeCount)}
            icon={<Code2 className="h-3.5 w-3.5" />}
          />
          <StatCard
            label="Avg Duration"
            value={formatDuration(stats.avgDuration)}
            icon={<Activity className="h-3.5 w-3.5" />}
          />
          <StatCard
            label="Total Size"
            value={formatBytes(stats.totalSize)}
            icon={<HardDrive className="h-3.5 w-3.5" />}
          />
        </div>
      )}

      {/* Table */}
      <div className="rounded-lg border">
        <Table className="[&_th]:h-8 [&_th]:px-2 [&_th]:text-xs [&_td]:py-1.5 [&_td]:px-2 [&_td]:text-xs">
          <TableHeader>
            <TableRow>
              <TableHead>Service</TableHead>
              <TableHead>Type</TableHead>
              <TableHead className="hidden md:table-cell">
                Environment
              </TableHead>
              <TableHead className="hidden lg:table-cell">Host</TableHead>
              <TableHead>Duration</TableHead>
              <TableHead className="hidden sm:table-cell">Size</TableHead>
              <TableHead>Time</TableHead>
              <TableHead className="w-[48px]" />
            </TableRow>
          </TableHeader>
          <TableBody>
            {profiles.map((profile: ProfileResponse) => (
              <TableRow key={profile.profileId} className="group">
                <TableCell>
                  <div className="flex items-center gap-2 min-w-0">
                    <Link
                      to="/profiles/$profileId"
                      params={{profileId: profile.profileId}}
                      className="font-medium text-primary hover:underline truncate"
                    >
                      {profile.service || '(unknown)'}
                    </Link>
                    {profile.language && (
                      <span className="text-[10px] text-muted-foreground bg-muted px-1.5 py-0.5 rounded shrink-0">
                        {profile.language}
                      </span>
                    )}
                  </div>
                </TableCell>
                <TableCell>
                  <Badge
                    variant="outline"
                    className={`text-[11px] border ${profileTypeBadgeClass(profile.profileType)}`}
                  >
                    {profile.profileType}
                  </Badge>
                </TableCell>
                <TableCell className="hidden md:table-cell text-muted-foreground">
                  {profile.env || '—'}
                </TableCell>
                <TableCell className="hidden lg:table-cell text-muted-foreground font-mono truncate max-w-[140px]">
                  {profile.host || '—'}
                </TableCell>
                <TableCell className="font-mono tabular-nums">
                  {formatDuration(profile.durationNs)}
                </TableCell>
                <TableCell className="hidden sm:table-cell font-mono tabular-nums text-muted-foreground">
                  {formatBytes(profile.sizeBytes)}
                </TableCell>
                <TableCell className="text-muted-foreground">
                  <span title={parseUtcDate(profile.startTime).toLocaleString()}>
                    <Clock className="h-3 w-3 inline mr-1 -mt-px" />
                    {formatRelativeTime(profile.startTime)}
                  </span>
                </TableCell>
                <TableCell>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-6 w-6 opacity-0 group-hover:opacity-100 transition-opacity"
                    onClick={(e) => {
                      e.stopPropagation()
                      api.downloadProfile(
                        profile.profileId,
                        undefined,
                        profile.profileType
                      )
                    }}
                  >
                    <Download className="h-3.5 w-3.5" />
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    </div>
  )
}

function ProfilingEmptyState() {
  return (
    <div className="rounded-xl border border-dashed py-10 px-4 max-w-lg mx-auto bg-card">
      <div className="flex flex-col items-center text-center">
        <div className="h-12 w-12 rounded-full bg-gradient-to-br from-violet-500/20 to-orange-500/20 border border-violet-500/20 flex items-center justify-center mb-3">
          <Layers className="h-5 w-5 text-violet-500 dark:text-violet-400" />
        </div>
        <p className="font-semibold text-sm text-foreground">No profiles yet</p>
        <p className="text-xs text-muted-foreground mt-1 max-w-sm">
          Set up continuous profiling with the Sentry SDK or Datadog Agent to
          start collecting flamegraph data from your applications.
        </p>
        <a
          href="https://moneat.io/docs/sdk-setup"
          target="_blank"
          rel="noopener noreferrer"
          className="inline-flex items-center gap-1 mt-3 text-xs font-medium text-primary hover:underline"
        >
          Read the setup guide
          <ExternalLink className="h-3.5 w-3.5" />
        </a>
      </div>
    </div>
  )
}


function StatCard({
  label,
  value,
  icon,
}: {
  label: string
  value: string
  icon: React.ReactNode
}) {
  return (
    <div className="rounded-lg border bg-card px-2.5 py-2 flex flex-col gap-0.5">
      <div className="flex items-center gap-1 text-muted-foreground">
        {icon}
        <span className="text-[11px] font-medium">{label}</span>
      </div>
      <span className="text-lg font-bold tabular-nums tracking-tight">
        {value}
      </span>
    </div>
  )
}
