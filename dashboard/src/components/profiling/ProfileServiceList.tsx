// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

import {useMemo} from 'react'
import {useQuery} from '@tanstack/react-query'
import {Link} from '@tanstack/react-router'
import {Bar, BarChart as RechartsBarChart, ResponsiveContainer} from 'recharts'
import {
  Activity,
  Clock,
  Code2,
  HardDrive,
  Layers,
  Loader2,
  Search,
  Server,
} from 'lucide-react'
import {api, type ProfileServiceSummary} from '@/lib/api'
import {Card} from '@/components/ui/card'
import {Badge} from '@/components/ui/badge'
import {Input} from '@/components/ui/input'
import {cn, formatRelativeTime} from '@/lib/utils'
import {getNow} from '@/lib/demo'
import {ProfilingEmptyState} from './ProfilingEmptyState'
import {ProfileStatCard} from './ProfileStatCard'
import {
  LIVE_THRESHOLD_MS,
  formatBytes,
  formatCompact,
  parseUtcDate,
  profileTypeBadgeClass,
} from './profileFormat'

interface Props {
  serviceFilter: string
  onServiceFilterChange: (val: string) => void
}

export function ProfileServiceList({serviceFilter, onServiceFilterChange}: Props) {
  const {data, isLoading} = useQuery({
    queryKey: ['profileServices'],
    queryFn: () => api.getProfileServices(),
    enabled: api.isAuthenticated(),
  })

  const services = data?.services ?? []

  const filtered = useMemo(() => {
    const q = serviceFilter.trim().toLowerCase()
    if (!q) return services
    return services.filter((s) => s.service.toLowerCase().includes(q))
  }, [services, serviceFilter])

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-8">
        <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
      </div>
    )
  }

  if (services.length === 0) {
    return <ProfilingEmptyState />
  }

  return (
    <div className="space-y-2">
      <div className="relative max-w-xs">
        <Search className="absolute left-2 top-1/2 -translate-y-1/2 h-3.5 w-3.5 text-muted-foreground" />
        <Input
          placeholder="Filter by service..."
          value={serviceFilter}
          onChange={(e) => onServiceFilterChange(e.target.value)}
          className="pl-8 h-7 text-xs"
        />
      </div>

      {data && (
        <div className="grid grid-cols-2 md:grid-cols-5 gap-1.5">
          <ProfileStatCard
            label="Total Profiles"
            value={data.totalProfiles.toLocaleString()}
            icon={<Layers className="h-3.5 w-3.5" />}
          />
          <ProfileStatCard
            label="Services"
            value={String(data.serviceCount)}
            icon={<Server className="h-3.5 w-3.5" />}
          />
          <ProfileStatCard
            label="Profile Types"
            value={String(data.typeCount)}
            icon={<Code2 className="h-3.5 w-3.5" />}
          />
          <ProfileStatCard
            label="Hosts"
            value={data.hostCount.toLocaleString()}
            icon={<Activity className="h-3.5 w-3.5" />}
          />
          <ProfileStatCard
            label="Total Size"
            value={formatBytes(data.totalSizeBytes)}
            icon={<HardDrive className="h-3.5 w-3.5" />}
          />
        </div>
      )}

      {filtered.length === 0 ? (
        <p className="text-xs text-muted-foreground py-6 text-center">
          No services match "{serviceFilter}".
        </p>
      ) : (
        <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
          {filtered.map((service) => (
            <ServiceCard key={service.service || '(unknown)'} service={service} />
          ))}
        </div>
      )}
    </div>
  )
}

function ServiceCard({service}: {service: ProfileServiceSummary}) {
  const lastSeenMs = parseUtcDate(service.lastSeen).getTime()
  const isLive =
    Number.isFinite(lastSeenMs) && getNow() - lastSeenMs < LIVE_THRESHOLD_MS
  const language = service.languages[0] || service.runtimes[0] || ''

  return (
    <Link
      to="/profiles/service/$service"
      params={{service: service.service || '(unknown)'}}
      className="block group focus:outline-none"
    >
      <Card className="h-full p-3 space-y-2 transition-colors group-hover:border-primary/40 group-hover:bg-accent/30 group-focus-visible:border-primary/60">
        <div className="flex items-start justify-between gap-2">
          <div className="flex items-center gap-1.5 min-w-0">
            <span
              className={cn(
                'h-1.5 w-1.5 rounded-full shrink-0',
                isLive ? 'bg-success-solid animate-pulse' : 'bg-muted-foreground/30',
              )}
              title={isLive ? 'Receiving profiles' : 'Idle'}
            />
            <span className="font-semibold text-sm truncate group-hover:text-primary">
              {service.service || '(unknown)'}
            </span>
          </div>
          {language && (
            <span className="text-[10px] uppercase tracking-wide text-muted-foreground bg-muted px-1.5 py-0.5 rounded shrink-0">
              {language}
            </span>
          )}
        </div>

        <div className="flex items-center justify-between text-[11px] text-muted-foreground gap-2">
          <span className="truncate" title={service.environments.join(', ')}>
            {service.environments.length > 0 ? service.environments.join(', ') : '—'}
          </span>
          <span className="shrink-0">
            {service.hostCount} {service.hostCount === 1 ? 'host' : 'hosts'}
          </span>
        </div>

        <div className="flex flex-wrap gap-1">
          {service.types.slice(0, 5).map((t) => (
            <Badge
              key={t.profileType}
              variant="outline"
              className={cn('text-[10px] border', profileTypeBadgeClass(t.profileType))}
            >
              {t.profileType} {formatCompact(t.count)}
            </Badge>
          ))}
        </div>

        <div className="flex items-end justify-between gap-2 pt-0.5">
          <div className="text-[11px] text-muted-foreground space-y-0.5 min-w-0">
            <div className="font-mono tabular-nums">
              {service.profileCount.toLocaleString()} · {formatBytes(service.totalSizeBytes)}
            </div>
            <div className="flex items-center gap-1">
              <Clock className="h-3 w-3" />
              {Number.isFinite(lastSeenMs) ? formatRelativeTime(lastSeenMs) : '—'}
            </div>
          </div>
          <div className="w-20 h-7 shrink-0">
            <Sparkline data={service.series} live={isLive} />
          </div>
        </div>
      </Card>
    </Link>
  )
}

function Sparkline({
  data,
  live,
}: {
  data: ProfileServiceSummary['series']
  live: boolean
}) {
  if (!data || data.length === 0) {
    return <div className="h-full w-full rounded bg-muted/40" />
  }
  const color = live ? 'hsl(var(--chart-1))' : 'hsl(var(--muted-foreground) / 0.4)'
  return (
    <ResponsiveContainer width="100%" height="100%">
      <RechartsBarChart data={data} margin={{top: 2, right: 0, bottom: 0, left: 0}}>
        <Bar
          dataKey="count"
          fill={color}
          radius={[1, 1, 0, 0]}
          isAnimationActive={false}
        />
      </RechartsBarChart>
    </ResponsiveContainer>
  )
}
