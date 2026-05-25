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

import {createFileRoute} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {TooltipProvider} from '@/components/ui/tooltip'
import {
    Activity,
    ChevronRight,
    Clock,
    Globe,
    Loader2,
} from 'lucide-react'
import {Helmet} from 'react-helmet-async'
import {browserTimezone, formatDateTime as formatDateTimeFn, formatMonthDay, formatTimeHM12} from '@/lib/date-format'
import {StatusBanner, StatusPageMonitorRow} from '@/components/StatusPagePreview'

export const Route = createFileRoute('/s/$slug')({
  component: PublicStatusPage,
})

function PublicStatusPage() {
  const {slug} = Route.useParams()

  const {data: statusPage, isLoading, error} = useQuery({
    queryKey: ['public-status-page', slug],
    queryFn: () => api.getPublicStatusPage(slug),
    retry: false,
  })

  if (isLoading) {
    return (
      <div className="min-h-screen bg-white flex items-center justify-center">
        <div className="flex flex-col items-center gap-3">
          <Loader2 className="h-8 w-8 animate-spin text-slate-400" />
          <p className="text-sm text-slate-500">Loading status...</p>
        </div>
      </div>
    )
  }

  if (error || !statusPage) {
    return (
      <div className="min-h-screen bg-white flex items-center justify-center p-4">
        <div className="text-center max-w-sm">
          <div className="inline-flex items-center justify-center w-14 h-14 rounded-full bg-slate-100 mb-4">
            <Globe className="h-7 w-7 text-slate-400" />
          </div>
          <h2 className="text-lg font-semibold text-slate-900 mb-2">Page Not Found</h2>
          <p className="text-sm text-slate-500 mb-6">
            This status page doesn't exist or is currently unavailable.
          </p>
          <a
            href="/"
            className="inline-flex items-center text-sm font-medium text-slate-600 hover:text-slate-900 transition-colors"
          >
            Go home
            <ChevronRight className="h-4 w-4 ml-0.5" />
          </a>
        </div>
      </div>
    )
  }

  const primaryColor = statusPage.primaryColor || '#3B82F6'
  const isDarkMode = statusPage.darkMode

  // Calculate overall status
  const allOperational = statusPage.monitors.every((m) => m.status === 'operational')
  const anyDown = statusPage.monitors.some((m) => m.status === 'down')
  const anyDegraded = statusPage.monitors.some((m) => m.status === 'degraded')

  const overallStatus = anyDown ? 'down' : anyDegraded ? 'degraded' : allOperational ? 'operational' : 'unknown'

  return (
    <TooltipProvider delayDuration={0}>
      <Helmet>
        <title>{statusPage.name} Status | Moneat</title>
        <meta name="description" content={`Current status and uptime for ${statusPage.name}.`} />
        {statusPage.activeIncidents.length > 0 && (
          <meta name="twitter:data1" content={`${statusPage.activeIncidents.length} active incidents`} />
        )}
      </Helmet>
      <div className={`min-h-screen font-sans antialiased ${isDarkMode ? 'dark bg-slate-950 text-slate-100' : 'bg-white text-slate-900'}`}>
        {/* Minimal Top Bar */}
        <header className={`border-b ${isDarkMode ? 'border-slate-800' : 'border-slate-100'}`}>
          <div className="max-w-3xl mx-auto px-6 h-14 flex items-center justify-between">
            <div className="flex items-center gap-2.5">
              {statusPage.logoUrl ? (
                <img src={statusPage.logoUrl} alt={statusPage.name} className="h-6 w-6 object-contain rounded" />
              ) : (
                <div className="h-6 w-6 rounded flex items-center justify-center" style={{backgroundColor: primaryColor + '18'}}>
                  <Activity className="h-3.5 w-3.5" style={{color: primaryColor}} />
                </div>
              )}
              <span className="font-semibold text-sm tracking-tight">{statusPage.name}</span>
            </div>
          </div>
        </header>

        <main className="max-w-3xl mx-auto px-6 py-10 space-y-8">
          {/* Status Banner */}
          <StatusBanner status={overallStatus} isDarkMode={isDarkMode} />

          {/* Active Incidents */}
          {statusPage.activeIncidents.length > 0 && (
            <section className="space-y-4">
              <h2 className={`text-xs font-semibold uppercase tracking-wider ${isDarkMode ? 'text-red-400' : 'text-red-600'}`}>
                Active Incidents
              </h2>
              <div className="space-y-4">
                {statusPage.activeIncidents.map((incident) => (
                  <IncidentCard key={incident.id} incident={incident} isDarkMode={isDarkMode} />
                ))}
              </div>
            </section>
          )}

          {/* Scheduled Maintenance */}
          {statusPage.scheduledMaintenance.length > 0 && (
            <section className="space-y-4">
              <h2 className={`text-xs font-semibold uppercase tracking-wider ${isDarkMode ? 'text-blue-400' : 'text-blue-600'}`}>
                Scheduled Maintenance
              </h2>
              <div className="space-y-4">
                {statusPage.scheduledMaintenance.map((maintenance) => (
                  <MaintenanceCard key={maintenance.id} maintenance={maintenance} isDarkMode={isDarkMode} />
                ))}
              </div>
            </section>
          )}

          {/* Monitors */}
          <section className="space-y-4">
            <div className="flex items-center justify-between">
              <h2 className={`text-xs font-semibold uppercase tracking-wider ${isDarkMode ? 'text-slate-400' : 'text-slate-500'}`}>
                System Status
              </h2>
              {statusPage.showUptimeHistory && (
                <span className={`text-xs ${isDarkMode ? 'text-slate-500' : 'text-slate-400'}`}>
                  {statusPage.historyDays}-day uptime
                </span>
              )}
            </div>

            <div className={`rounded-xl border ${isDarkMode ? 'border-slate-800 divide-slate-800' : 'border-slate-200 divide-slate-100'} divide-y`}>
              {statusPage.monitors.map((monitor) => (
                <StatusPageMonitorRow
                  key={monitor.name}
                  monitor={monitor}
                  showHistory={statusPage.showUptimeHistory}
                  historyDays={statusPage.historyDays}
                  isDarkMode={isDarkMode}
                  timezone={browserTimezone()}
                />
              ))}
              {statusPage.monitors.length === 0 && (
                <div className={`px-5 py-12 text-center text-sm ${isDarkMode ? 'text-slate-500' : 'text-slate-400'}`}>
                  No monitors configured yet.
                </div>
              )}
            </div>
          </section>
        </main>

        {/* Footer */}
        <footer className={`border-t ${isDarkMode ? 'border-slate-800' : 'border-slate-100'} mt-8`}>
          <div className="max-w-3xl mx-auto px-6 py-8 flex flex-col sm:flex-row items-center justify-between gap-4">
            <p className={`text-xs ${isDarkMode ? 'text-slate-600' : 'text-slate-400'}`}>
              Powered by{' '}
              <a
                href="https://moneat.io"
                target="_blank"
                rel="noopener noreferrer"
                className={`font-medium hover:underline ${isDarkMode ? 'text-slate-400' : 'text-slate-600'}`}
              >
                Moneat
              </a>
            </p>
            <p className={`text-xs ${isDarkMode ? 'text-slate-600' : 'text-slate-400'}`}>
              Updated {formatTimeHM12(new Date(), browserTimezone())}
            </p>
          </div>
        </footer>
      </div>
    </TooltipProvider>
  )
}

// ─── Incident Card ───────────────────────────────────────────────────────────

function IncidentCard({
  incident,
  isDarkMode,
}: {
  incident: {
    id: string
    title: string
    status: string
    impact: string
    createdAt: string
    updates: {id: string; status: string; message: string; createdAt: string}[]
  }
  isDarkMode: boolean
}) {
  const sortedUpdates = [...incident.updates].sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
  )

  return (
    <div className={`rounded-xl border ${isDarkMode ? 'border-slate-800 bg-slate-900/50' : 'border-slate-200 bg-white'} overflow-hidden`}>
      <div className={`px-5 py-4 ${isDarkMode ? 'border-b border-slate-800' : 'border-b border-slate-100'}`}>
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <h3 className="font-semibold text-sm">{incident.title}</h3>
            <div className="flex items-center gap-2 mt-1.5">
              <Badge variant="secondary" className={`text-[10px] px-1.5 py-0 h-5 font-medium ${getIncidentStatusColor(incident.status, isDarkMode)}`}>
                {incident.status.replace('_', ' ')}
              </Badge>
              <Badge variant="secondary" className={`text-[10px] px-1.5 py-0 h-5 font-medium ${getImpactColor(incident.impact, isDarkMode)}`}>
                {incident.impact}
              </Badge>
            </div>
          </div>
          <span className={`text-[11px] flex-shrink-0 ${isDarkMode ? 'text-slate-500' : 'text-slate-400'}`}>
            {formatMonthDay(new Date(incident.createdAt), browserTimezone())}
          </span>
        </div>
      </div>

      {sortedUpdates.length > 0 && (
        <div className="px-5 py-4">
          <div className="space-y-4">
            {sortedUpdates.map((update, i) => (
              <div key={update.id} className="relative pl-5">
                {/* Timeline dot and line */}
                <div className={`absolute left-0 top-[7px] h-2 w-2 rounded-full ${isDarkMode ? 'bg-slate-600' : 'bg-slate-300'} ${i === 0 ? (isDarkMode ? '!bg-slate-400' : '!bg-slate-500') : ''}`} />
                {i < sortedUpdates.length - 1 && (
                  <div className={`absolute left-[3px] top-[15px] w-0.5 bottom-[-12px] ${isDarkMode ? 'bg-slate-800' : 'bg-slate-200'}`} />
                )}
                <p className={`text-sm leading-relaxed ${isDarkMode ? 'text-slate-300' : 'text-slate-700'}`}>{update.message}</p>
                <p className={`text-[11px] mt-1 ${isDarkMode ? 'text-slate-600' : 'text-slate-400'}`}>
                  {formatDateTimeFn(new Date(update.createdAt), browserTimezone())}
                </p>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}

// ─── Maintenance Card ────────────────────────────────────────────────────────

function MaintenanceCard({
  maintenance,
  isDarkMode,
}: {
  maintenance: {
    id: string
    title: string
    status: string
    scheduledStartAt?: string | null
    scheduledEndAt?: string | null
    updates: {id: string; message: string; createdAt: string}[]
  }
  isDarkMode: boolean
}) {
  return (
    <div className={`rounded-xl border ${isDarkMode ? 'border-slate-800 bg-slate-900/50' : 'border-slate-200 bg-white'} px-5 py-4`}>
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <h3 className="font-semibold text-sm">{maintenance.title}</h3>
          <div className="flex items-center gap-2 mt-1.5">
            <Clock className={`h-3.5 w-3.5 flex-shrink-0 ${isDarkMode ? 'text-blue-400' : 'text-blue-500'}`} />
            <span className={`text-xs ${isDarkMode ? 'text-slate-400' : 'text-slate-500'}`}>
              {maintenance.scheduledStartAt && formatDateTime(maintenance.scheduledStartAt)}
              {maintenance.scheduledEndAt && ` - ${formatDateTime(maintenance.scheduledEndAt)}`}
            </span>
          </div>
        </div>
        <Badge variant="secondary" className={`text-[10px] px-1.5 py-0 h-5 font-medium flex-shrink-0 ${getIncidentStatusColor(maintenance.status, isDarkMode)}`}>
          {maintenance.status}
        </Badge>
      </div>
      {maintenance.updates.length > 0 && (
        <p className={`text-sm mt-3 ${isDarkMode ? 'text-slate-400' : 'text-slate-500'}`}>
          {maintenance.updates[0].message}
        </p>
      )}
    </div>
  )
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

function getIncidentStatusColor(status: string, isDarkMode: boolean) {
  const map: Record<string, string> = {
    investigating: isDarkMode ? 'bg-red-900/50 text-red-300' : 'bg-red-100 text-red-700',
    identified: isDarkMode ? 'bg-orange-900/50 text-orange-300' : 'bg-orange-100 text-orange-700',
    monitoring: isDarkMode ? 'bg-blue-900/50 text-blue-300' : 'bg-blue-100 text-blue-700',
    resolved: isDarkMode ? 'bg-emerald-900/50 text-emerald-300' : 'bg-emerald-100 text-emerald-700',
    scheduled: isDarkMode ? 'bg-purple-900/50 text-purple-300' : 'bg-purple-100 text-purple-700',
    in_progress: isDarkMode ? 'bg-blue-900/50 text-blue-300' : 'bg-blue-100 text-blue-700',
    completed: isDarkMode ? 'bg-emerald-900/50 text-emerald-300' : 'bg-emerald-100 text-emerald-700',
  }
  return map[status] || (isDarkMode ? 'bg-slate-800 text-slate-300' : 'bg-slate-100 text-slate-600')
}

function getImpactColor(impact: string, isDarkMode: boolean) {
  const map: Record<string, string> = {
    none: isDarkMode ? 'bg-slate-800 text-slate-400' : 'bg-slate-100 text-slate-500',
    minor: isDarkMode ? 'bg-amber-900/50 text-amber-300' : 'bg-amber-100 text-amber-700',
    major: isDarkMode ? 'bg-orange-900/50 text-orange-300' : 'bg-orange-100 text-orange-700',
    critical: isDarkMode ? 'bg-red-900/50 text-red-300' : 'bg-red-100 text-red-700',
  }
  return map[impact] || (isDarkMode ? 'bg-slate-800 text-slate-400' : 'bg-slate-100 text-slate-500')
}

function formatDateTime(dateStr: string) {
  return formatDateTimeFn(new Date(dateStr), browserTimezone())
}
