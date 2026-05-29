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
  HardDrive,
  Layers,
  Loader2,
  Search,
  Server,
} from 'lucide-react'
import {useMemo} from 'react'
import {Link} from '@tanstack/react-router'
import {formatRelativeTime} from '@/lib/utils'
import {ProfilingEmptyState} from './ProfilingEmptyState'
import {ProfileStatCard} from './ProfileStatCard'
import {
  formatBytes,
  formatDuration,
  parseUtcDate,
  profileTypeBadgeClass,
} from './profileFormat'

const EMBEDDED_LIMIT = 100
const DEFAULT_LIMIT = 50

interface ProfileScope {
  service?: string
  env?: string
  type?: string
  from?: number
  to?: number
}

interface Props {
  /** Embedded mode: scoped, compact list with no stats/filter chrome. */
  embedded?: boolean
  scope?: ProfileScope
  serviceFilter?: string
  onServiceFilterChange?: (val: string) => void
  typeFilter?: string
  onTypeFilterChange?: (val: string) => void
}

export function ProfileList({
  embedded = false,
  scope,
  serviceFilter = '',
  onServiceFilterChange,
  typeFilter = '',
  onTypeFilterChange,
}: Props) {
  const {data, isLoading} = useQuery({
    queryKey: embedded
      ? ['profiles', 'embedded', scope]
      : ['profiles', serviceFilter, typeFilter],
    queryFn: () =>
      api.getProfiles(
        embedded
          ? {
              service: scope?.service,
              env: scope?.env,
              type: scope?.type,
              from: scope?.from,
              to: scope?.to,
              limit: EMBEDDED_LIMIT,
            }
          : {
              service: serviceFilter || undefined,
              type: typeFilter || undefined,
              limit: DEFAULT_LIMIT,
            },
      ),
    enabled: api.isAuthenticated(),
  })

  const profiles = data?.profiles ?? []

  const stats = useMemo(() => {
    if (embedded || profiles.length === 0) return null

    const services = new Set(profiles.map((p) => p.service))
    const types = new Set(profiles.map((p) => p.profileType))
    const totalSize = profiles.reduce((sum, p) => sum + p.sizeBytes, 0)
    const durations = profiles.map((p) => p.durationNs)
    const avgDuration =
      durations.reduce((sum, d) => sum + d, 0) / durations.length

    return {
      totalProfiles: data?.totalCount ?? profiles.length,
      serviceCount: services.size,
      typeCount: types.size,
      totalSize,
      avgDuration,
    }
  }, [embedded, profiles, data?.totalCount])

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
    if (embedded) {
      return (
        <p className="text-xs text-muted-foreground py-6 text-center">
          No profiles in this window.
        </p>
      )
    }
    return <ProfilingEmptyState />
  }

  const table = (
    <Table className="[&_th]:h-8 [&_th]:px-2 [&_th]:text-xs [&_td]:py-1.5 [&_td]:px-2 [&_td]:text-xs">
      <TableHeader>
        <TableRow>
          <TableHead>Service</TableHead>
          <TableHead>Type</TableHead>
          <TableHead className="hidden md:table-cell">Environment</TableHead>
          <TableHead className="hidden lg:table-cell">Host</TableHead>
          <TableHead>Duration</TableHead>
          <TableHead className="hidden sm:table-cell">Size</TableHead>
          <TableHead>Time</TableHead>
          <TableHead className="w-[48px]" />
        </TableRow>
      </TableHeader>
      <TableBody>
        {profiles.map((profile: ProfileResponse) => {
          const parsedStart = parseUtcDate(profile.startTime)
          return (
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
                <span title={parsedStart.toLocaleString()}>
                  <Clock className="h-3 w-3 inline mr-1 -mt-px" />
                  {formatRelativeTime(parsedStart.getTime())}
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
                      profile.profileType,
                    )
                  }}
                >
                  <Download className="h-3.5 w-3.5" />
                </Button>
              </TableCell>
            </TableRow>
          )
        })}
      </TableBody>
    </Table>
  )

  if (embedded) {
    return table
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
            onChange={(e) => onServiceFilterChange?.(e.target.value)}
            className="pl-8 h-7 text-xs"
          />
        </div>
        {availableTypes.length > 1 && (
          <Select
            value={typeFilter || '__all'}
            onValueChange={(v) => onTypeFilterChange?.(v === '__all' ? '' : v)}
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
          <ProfileStatCard
            label="Total Profiles"
            value={stats.totalProfiles.toLocaleString()}
            icon={<Layers className="h-3.5 w-3.5" />}
          />
          <ProfileStatCard
            label="Services"
            value={String(stats.serviceCount)}
            icon={<Server className="h-3.5 w-3.5" />}
          />
          <ProfileStatCard
            label="Profile Types"
            value={String(stats.typeCount)}
            icon={<Code2 className="h-3.5 w-3.5" />}
          />
          <ProfileStatCard
            label="Avg Duration"
            value={formatDuration(stats.avgDuration)}
            icon={<Activity className="h-3.5 w-3.5" />}
          />
          <ProfileStatCard
            label="Total Size"
            value={formatBytes(stats.totalSize)}
            icon={<HardDrive className="h-3.5 w-3.5" />}
          />
        </div>
      )}

      {/* Table */}
      <div className="rounded-lg border">{table}</div>
    </div>
  )
}
