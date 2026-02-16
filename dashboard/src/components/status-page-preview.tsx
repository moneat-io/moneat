// Moneat - Mobile-First Error Monitoring Platform
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

import {
  CheckCircle2,
  XCircle,
  AlertTriangle,
  Activity,
  AlertCircle,
} from 'lucide-react'
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip'

// Preview component for rendering a status page with given configuration
export function StatusPagePreview({
  name,
  description,
  logoUrl,
  primaryColor,
  darkMode,
  showUptimeHistory,
  historyDays,
  monitors = [],
}: {
  name: string
  description?: string | null
  logoUrl?: string | null
  primaryColor: string
  darkMode: boolean
  showUptimeHistory: boolean
  historyDays: number
  monitors?: Array<{
    name: string
    displayName?: string | null
    status: string
    uptimePercentage: number
    uptimeHistory?: {date: string; uptime: number}[] | null
  }>
}) {
  const isDarkMode = darkMode

  // Calculate overall status from monitors
  const allOperational = monitors.length === 0 || monitors.every((m) => m.status === 'operational')
  const anyDown = monitors.some((m) => m.status === 'down')
  const anyDegraded = monitors.some((m) => m.status === 'degraded')

  const overallStatus = anyDown ? 'down' : anyDegraded ? 'degraded' : allOperational ? 'operational' : 'unknown'

  return (
    <TooltipProvider delayDuration={0}>
      <div className={`min-h-full font-sans antialiased ${isDarkMode ? 'dark bg-slate-950 text-slate-100' : 'bg-white text-slate-900'}`}>
        {/* Minimal Top Bar */}
        <header className={`border-b ${isDarkMode ? 'border-slate-800' : 'border-slate-100'}`}>
          <div className="max-w-3xl mx-auto px-6 h-14 flex items-center justify-between">
            <div className="flex items-center gap-2.5">
              {logoUrl ? (
                <img src={logoUrl} alt={name} className="h-6 w-6 object-contain rounded" />
              ) : (
                <div className="h-6 w-6 rounded flex items-center justify-center" style={{backgroundColor: primaryColor + '18'}}>
                  <Activity className="h-3.5 w-3.5" style={{color: primaryColor}} />
                </div>
              )}
              <span className="font-semibold text-sm tracking-tight">{name}</span>
            </div>
          </div>
        </header>

        <main className="max-w-3xl mx-auto px-6 py-10 space-y-8">
          {/* Status Banner */}
          <StatusBanner status={overallStatus} isDarkMode={isDarkMode} />

          {description && (
            <div className={`text-sm ${isDarkMode ? 'text-slate-400' : 'text-slate-600'}`}>
              {description}
            </div>
          )}

          {/* Monitors */}
          <section className="space-y-4">
            <div className="flex items-center justify-between">
              <h2 className={`text-xs font-semibold uppercase tracking-wider ${isDarkMode ? 'text-slate-400' : 'text-slate-500'}`}>
                System Status
              </h2>
              {showUptimeHistory && (
                <span className={`text-xs ${isDarkMode ? 'text-slate-500' : 'text-slate-400'}`}>
                  {historyDays}-day uptime
                </span>
              )}
            </div>

            <div className={`rounded-xl border ${isDarkMode ? 'border-slate-800 divide-slate-800' : 'border-slate-200 divide-slate-100'} divide-y`}>
              {monitors.length === 0 ? (
                <div className={`px-5 py-12 text-center text-sm ${isDarkMode ? 'text-slate-500' : 'text-slate-400'}`}>
                  No monitors configured yet.
                </div>
              ) : (
                monitors.map((monitor) => (
                  <MonitorRow
                    key={monitor.name}
                    monitor={monitor}
                    showHistory={showUptimeHistory}
                    historyDays={historyDays}
                    isDarkMode={isDarkMode}
                  />
                ))
              )}
            </div>
          </section>
        </main>

        {/* Footer */}
        <footer className={`border-t ${isDarkMode ? 'border-slate-800' : 'border-slate-100'} mt-8`}>
          <div className="max-w-3xl mx-auto px-6 py-8 flex flex-col sm:flex-row items-center justify-between gap-4">
            <p className={`text-xs ${isDarkMode ? 'text-slate-600' : 'text-slate-400'}`}>
              Powered by{' '}
              <span className={`font-medium ${isDarkMode ? 'text-slate-400' : 'text-slate-600'}`}>
                Moneat
              </span>
            </p>
            <p className={`text-xs ${isDarkMode ? 'text-slate-600' : 'text-slate-400'}`}>
              Preview Mode
            </p>
          </div>
        </footer>
      </div>
    </TooltipProvider>
  )
}

// ─── Status Banner ───────────────────────────────────────────────────────────

function StatusBanner({status, isDarkMode}: {status: string; isDarkMode: boolean}) {
  const config = {
    operational: {
      icon: CheckCircle2,
      title: 'All Systems Operational',
      bg: isDarkMode ? 'bg-emerald-950/40' : 'bg-emerald-50',
      border: isDarkMode ? 'border-emerald-900/50' : 'border-emerald-200',
      iconColor: isDarkMode ? 'text-emerald-400' : 'text-emerald-600',
      textColor: isDarkMode ? 'text-emerald-300' : 'text-emerald-800',
    },
    degraded: {
      icon: AlertTriangle,
      title: 'Partial System Outage',
      bg: isDarkMode ? 'bg-amber-950/40' : 'bg-amber-50',
      border: isDarkMode ? 'border-amber-900/50' : 'border-amber-200',
      iconColor: isDarkMode ? 'text-amber-400' : 'text-amber-600',
      textColor: isDarkMode ? 'text-amber-300' : 'text-amber-800',
    },
    down: {
      icon: XCircle,
      title: 'Major System Outage',
      bg: isDarkMode ? 'bg-red-950/40' : 'bg-red-50',
      border: isDarkMode ? 'border-red-900/50' : 'border-red-200',
      iconColor: isDarkMode ? 'text-red-400' : 'text-red-600',
      textColor: isDarkMode ? 'text-red-300' : 'text-red-800',
    },
    unknown: {
      icon: AlertCircle,
      title: 'Status Unknown',
      bg: isDarkMode ? 'bg-slate-900' : 'bg-slate-50',
      border: isDarkMode ? 'border-slate-700' : 'border-slate-200',
      iconColor: isDarkMode ? 'text-slate-400' : 'text-slate-500',
      textColor: isDarkMode ? 'text-slate-300' : 'text-slate-700',
    },
  }

  const c = config[status as keyof typeof config] || config.unknown
  const Icon = c.icon

  return (
    <div className={`rounded-xl border ${c.border} ${c.bg} px-6 py-5 flex items-center gap-4`}>
      <Icon className={`h-6 w-6 flex-shrink-0 ${c.iconColor}`} />
      <span className={`text-base font-semibold ${c.textColor}`}>{c.title}</span>
    </div>
  )
}

// ─── Monitor Row ─────────────────────────────────────────────────────────────

function MonitorRow({
  monitor,
  showHistory,
  historyDays,
  isDarkMode,
}: {
  monitor: {
    name: string
    displayName?: string | null
    status: string
    uptimePercentage: number
    uptimeHistory?: {date: string; uptime: number}[] | null
  }
  showHistory: boolean
  historyDays: number
  isDarkMode: boolean
}) {
  const statusColors = {
    operational: isDarkMode ? 'text-emerald-400' : 'text-emerald-600',
    degraded: isDarkMode ? 'text-amber-400' : 'text-amber-600',
    down: isDarkMode ? 'text-red-400' : 'text-red-600',
  }

  const statusDotColors = {
    operational: isDarkMode ? 'bg-emerald-400' : 'bg-emerald-500',
    degraded: isDarkMode ? 'bg-amber-400' : 'bg-amber-500',
    down: isDarkMode ? 'bg-red-400' : 'bg-red-500',
  }

  const uptimeColor = monitor.uptimePercentage >= 99.9
    ? (isDarkMode ? 'text-emerald-400' : 'text-emerald-600')
    : monitor.uptimePercentage >= 99
      ? (isDarkMode ? 'text-emerald-400' : 'text-emerald-600')
      : monitor.uptimePercentage >= 95
        ? (isDarkMode ? 'text-amber-400' : 'text-amber-600')
        : (isDarkMode ? 'text-red-400' : 'text-red-600')

  const history = showHistory && monitor.uptimeHistory
    ? monitor.uptimeHistory.slice(-historyDays)
    : null

  return (
    <div className={`px-5 py-4 ${isDarkMode ? 'hover:bg-slate-900/50' : 'hover:bg-slate-50/80'} transition-colors`}>
      {/* Top: name + status */}
      <div className="flex items-center justify-between mb-1">
        <div className="flex items-center gap-2.5 min-w-0">
          <span className={`inline-block h-2 w-2 rounded-full flex-shrink-0 ${statusDotColors[monitor.status as keyof typeof statusDotColors] || 'bg-slate-400'}`} />
          <span className="font-medium text-sm truncate">
            {monitor.displayName || monitor.name}
          </span>
        </div>
        <div className="flex items-center gap-3 flex-shrink-0 ml-4">
          <span className={`text-xs font-medium tabular-nums ${uptimeColor}`}>
            {monitor.uptimePercentage.toFixed(2)}%
          </span>
          <span className={`text-xs capitalize ${statusColors[monitor.status as keyof typeof statusColors] || (isDarkMode ? 'text-slate-400' : 'text-slate-500')}`}>
            {monitor.status === 'operational' ? 'Operational' : monitor.status}
          </span>
        </div>
      </div>

      {/* Uptime History Bar */}
      {history && history.length > 0 && (
        <div className="mt-3">
          <div className="flex items-stretch gap-[1.5px] h-8 w-full">
            {history.map((point, index) => {
              const barColor = getBarColor(point.uptime, isDarkMode)
              return (
                <Tooltip key={index}>
                  <TooltipTrigger asChild>
                    <div
                      className={`flex-1 rounded-[2px] ${barColor} transition-opacity hover:opacity-80 cursor-default min-w-[2px]`}
                    />
                  </TooltipTrigger>
                  <TooltipContent side="top" className="text-xs">
                    <p className="font-medium">{new Date(point.date).toLocaleDateString(undefined, {month: 'short', day: 'numeric'})}</p>
                    <p className={`tabular-nums ${point.uptime >= 99 ? 'text-emerald-600 dark:text-emerald-400' : point.uptime >= 90 ? 'text-amber-600 dark:text-amber-400' : 'text-red-600 dark:text-red-400'}`}>
                      {point.uptime.toFixed(2)}% uptime
                    </p>
                  </TooltipContent>
                </Tooltip>
              )
            })}
          </div>
          <div className={`flex justify-between mt-1.5 text-[10px] ${isDarkMode ? 'text-slate-600' : 'text-slate-400'}`}>
            <span>{historyDays}d ago</span>
            <span>Today</span>
          </div>
        </div>
      )}
    </div>
  )
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

function getBarColor(uptime: number, isDarkMode: boolean) {
  if (uptime >= 99) return isDarkMode ? 'bg-emerald-500/70' : 'bg-emerald-400'
  if (uptime >= 90) return isDarkMode ? 'bg-amber-500/70' : 'bg-amber-400'
  return isDarkMode ? 'bg-red-500/70' : 'bg-red-400'
}
