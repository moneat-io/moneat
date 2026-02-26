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
import {useState, useMemo} from 'react'
import {Link, useNavigate} from '@tanstack/react-router'
import {useProject} from '@/contexts/project-context'
import {Prism as SyntaxHighlighter} from 'react-syntax-highlighter'
import {oneDark} from 'react-syntax-highlighter/dist/esm/styles/prism'

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function formatDuration(ns: number): string {
  if (ns < 1_000_000_000) return `${(ns / 1_000_000).toFixed(0)}ms`
  return `${(ns / 1_000_000_000).toFixed(1)}s`
}

function timeAgo(iso: string): string {
  const now = Date.now()
  const then = new Date(iso).getTime()
  const diffMs = now - then
  if (diffMs < 0) return 'just now'

  const seconds = Math.floor(diffMs / 1000)
  if (seconds < 60) return `${seconds}s ago`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days}d ago`
  return new Date(iso).toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
  })
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
  const navigate = useNavigate()

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

  return (
    <div className="space-y-5">
      {/* Filters */}
      <div className="flex items-center gap-2">
        <div className="relative flex-1 max-w-xs">
          <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder="Filter by service..."
            value={serviceFilter}
            onChange={(e) => onServiceFilterChange(e.target.value)}
            className="pl-9 h-9"
          />
        </div>
        {availableTypes.length > 1 && (
          <Select
            value={typeFilter || '__all'}
            onValueChange={(v) => onTypeFilterChange(v === '__all' ? '' : v)}
          >
            <SelectTrigger className="h-9 w-[160px]">
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
        <div className="grid grid-cols-2 md:grid-cols-5 gap-3">
          <StatCard
            label="Total Profiles"
            value={stats.totalProfiles.toLocaleString()}
            icon={<Layers className="h-4 w-4" />}
          />
          <StatCard
            label="Services"
            value={String(stats.serviceCount)}
            icon={<Server className="h-4 w-4" />}
          />
          <StatCard
            label="Profile Types"
            value={String(stats.typeCount)}
            icon={<Code2 className="h-4 w-4" />}
          />
          <StatCard
            label="Avg Duration"
            value={formatDuration(stats.avgDuration)}
            icon={<Activity className="h-4 w-4" />}
          />
          <StatCard
            label="Total Size"
            value={formatBytes(stats.totalSize)}
            icon={<HardDrive className="h-4 w-4" />}
          />
        </div>
      )}

      {/* Table */}
      {isLoading ? (
        <div className="flex items-center justify-center py-16">
          <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
      ) : profiles.length === 0 ? (
        <ProfilingSetupGuide />
      ) : (
        <div className="rounded-lg border">
          <Table>
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
                <TableRow
                  key={profile.profileId}
                  className="cursor-pointer group"
                  onClick={() =>
                    navigate({
                      to: '/profiles/$profileId',
                      params: {profileId: profile.profileId},
                    })
                  }
                >
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
                  <TableCell className="hidden md:table-cell text-sm text-muted-foreground">
                    {profile.env || '—'}
                  </TableCell>
                  <TableCell className="hidden lg:table-cell text-sm text-muted-foreground font-mono truncate max-w-[160px]">
                    {profile.host || '—'}
                  </TableCell>
                  <TableCell className="text-sm font-mono tabular-nums">
                    {formatDuration(profile.durationNs)}
                  </TableCell>
                  <TableCell className="hidden sm:table-cell text-sm font-mono tabular-nums text-muted-foreground">
                    {formatBytes(profile.sizeBytes)}
                  </TableCell>
                  <TableCell className="text-sm text-muted-foreground">
                    <span title={new Date(profile.startTime).toLocaleString()}>
                      <Clock className="h-3 w-3 inline mr-1 -mt-px" />
                      {timeAgo(profile.startTime)}
                    </span>
                  </TableCell>
                  <TableCell>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-7 w-7 opacity-0 group-hover:opacity-100 transition-opacity"
                      onClick={(e) => e.stopPropagation()}
                      asChild
                    >
                      <a
                        href={api.getProfileDownloadUrl(profile.profileId)}
                        download
                      >
                        <Download className="h-3.5 w-3.5" />
                      </a>
                    </Button>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  )
}

function ProfilingSetupGuide() {
  const [activeTab, setActiveTab] = useState<'js' | 'python' | 'kotlin'>('js')
  const {selectedProjectId} = useProject()

  const {data: project} = useQuery({
    queryKey: ['project', selectedProjectId],
    queryFn: () => api.getProject(selectedProjectId!),
    enabled: !!selectedProjectId,
  })

  const dsn = project?.dsn || 'YOUR_DSN_HERE'

  const snippets = {
    js: `import * as Sentry from "@sentry/browser"; // or @sentry/node, @sentry/react, etc.

Sentry.init({
  dsn: "${dsn}",
  // Enable performance tracing (required for profiling)
  tracesSampleRate: 1.0,
  // Enable continuous profiling — sample 100% of traced transactions
  profilesSampleRate: 1.0,
});`,
    python: `import sentry_sdk

sentry_sdk.init(
    dsn="${dsn}",
    # Enable performance tracing (required for profiling)
    traces_sample_rate=1.0,
    # Enable continuous profiling
    profiles_sample_rate=1.0,
)`,
    kotlin: `import io.sentry.Sentry

Sentry.init { options ->
    options.dsn = "${dsn}"
    // Enable performance tracing (required for profiling)
    options.tracesSampleRate = 1.0
    // Enable continuous profiling
    options.profilesSampleRate = 1.0
}`
  }

  const steps = [
    {
      num: '1',
      color:
        'bg-violet-500/10 text-violet-600 dark:text-violet-400 border-violet-500/20',
      numColor: 'bg-violet-500 text-white',
      label: (
        <>
          Add{' '}
          <code className="font-mono text-violet-600 dark:text-violet-300 bg-violet-100 dark:bg-violet-900/30 px-1 py-0.5 rounded">
            profilesSampleRate
          </code>{' '}
          to your SDK config
        </>
      ),
    },
    {
      num: '2',
      color:
        'bg-blue-500/10 text-blue-600 dark:text-blue-300 border-blue-500/20',
      numColor: 'bg-blue-500 text-white',
      label: (
        <>
          Replace{' '}
          <code className="font-mono text-blue-600 dark:text-blue-300 bg-blue-100 dark:bg-blue-900/30 px-1 py-0.5 rounded">
            YOUR_DSN_HERE
          </code>{' '}
          with your project DSN from{' '}
          <span className="font-semibold">
            Projects → Settings → Client Keys
          </span>
        </>
      ),
    },
    {
      num: '3',
      color:
        'bg-emerald-500/10 text-emerald-600 dark:text-emerald-300 border-emerald-500/20',
      numColor: 'bg-emerald-500 text-white',
      label: (
        <>
          <span className="font-semibold">Deploy</span> and trigger some
          requests — profiles will appear here automatically
        </>
      ),
    },
  ]

  return (
    <div className="rounded-xl border border-dashed py-10 px-6 max-w-2xl mx-auto bg-card">
      {/* Header */}
      <div className="flex flex-col items-center text-center mb-8">
        <div className="h-14 w-14 rounded-full bg-gradient-to-br from-violet-500/20 to-orange-500/20 border border-violet-500/20 flex items-center justify-center mb-3">
          <Layers className="h-6 w-6 text-violet-500 dark:text-violet-400" />
        </div>
        <p className="font-semibold text-foreground">No profiles yet</p>
        <p className="text-sm text-muted-foreground mt-1 max-w-sm">
          Enable continuous profiling in your Sentry SDK to start collecting
          flamegraph data from your applications.
        </p>
      </div>

      <div className="space-y-3">
        {/* Step 1 — code snippet */}
        <div className={`rounded-lg border p-4 ${steps[0].color}`}>
          <div className="flex items-center gap-2 mb-3">
            <span
              className={`h-5 w-5 rounded-full text-[10px] font-bold flex items-center justify-center shrink-0 ${steps[0].numColor}`}
            >
              1
            </span>
            <p className="text-xs font-medium">{steps[0].label}</p>
          </div>

          {/* Tabs */}
          <div className="rounded-md border border-violet-500/20 overflow-hidden bg-[#282c34]">
            <div className="flex border-b border-white/10 bg-white/5">
              {(
                [
                  ['js', 'JS / TS'],
                  ['python', 'Python'],
                  ['kotlin', 'Kotlin / Java'],
                ] as const
              ).map(([key, label]) => (
                <button
                  key={key}
                  onClick={() => setActiveTab(key)}
                  className={`px-3 py-1.5 text-[11px] font-medium transition-colors ${
                    activeTab === key
                      ? 'text-white border-b-2 border-violet-400 -mb-px bg-white/10'
                      : 'text-white/60 hover:text-white hover:bg-white/5'
                  }`}
                >
                  {label}
                </button>
              ))}
            </div>
            <div className="text-xs">
              <SyntaxHighlighter
                language={activeTab === 'js' ? 'javascript' : activeTab}
                style={oneDark}
                customStyle={{
                  margin: 0,
                  padding: '1rem',
                  background: 'transparent',
                  fontSize: '11px',
                  lineHeight: '1.5',
                }}
                wrapLongLines={true}
              >
                {snippets[activeTab]}
              </SyntaxHighlighter>
            </div>
          </div>
        </div>

        {/* Step 2 (DSN injection note - only if DSN missing) */}
        {!project?.dsn && (
          <div className={`rounded-lg border p-4 ${steps[1].color}`}>
            <div className="flex items-start gap-2">
              <span
                className={`h-5 w-5 rounded-full text-[10px] font-bold flex items-center justify-center shrink-0 mt-0.5 ${steps[1].numColor}`}
              >
                2
              </span>
              <p className="text-xs">{steps[1].label}</p>
            </div>
          </div>
        )}

        {/* Step 3 */}
        <div className={`rounded-lg border p-4 ${steps[2].color}`}>
          <div className="flex items-start gap-2">
            <span
              className={`h-5 w-5 rounded-full text-[10px] font-bold flex items-center justify-center shrink-0 mt-0.5 ${steps[2].numColor}`}
            >
              {project?.dsn ? '2' : '3'}
            </span>
            <p className="text-xs">{steps[2].label}</p>
          </div>
        </div>
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
    <div className="rounded-lg border bg-card px-4 py-3 flex flex-col gap-1">
      <div className="flex items-center gap-1.5 text-muted-foreground">
        {icon}
        <span className="text-xs font-medium">{label}</span>
      </div>
      <span className="text-xl font-bold tabular-nums tracking-tight">
        {value}
      </span>
    </div>
  )
}
