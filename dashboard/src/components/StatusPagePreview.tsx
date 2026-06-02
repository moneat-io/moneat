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

import {Activity, AlertCircle, AlertTriangle, CheckCircle2, XCircle,} from 'lucide-react'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger,} from '@/components/ui/tooltip'
import {useTimezone} from '@/hooks/useTimezone'
import {formatMonthDay} from '@/lib/date-format'

export type StatusValue = 'operational' | 'degraded' | 'down' | 'unknown'

export type StatusPageMonitorEntry = {
  name: string
  displayName?: string | null
  status: StatusValue
  uptimePercentage: number
  uptimeHistory?: {date: string; uptime: number}[] | null
}

type StatusConfigItem = {
  icon: typeof CheckCircle2
  title: string
  bg: string
  border: string
  iconColor: string
  textColor: string
}

// The status families use the shared status tokens, which adapt to the preview's
// own theme via the `dark` class applied to its container below.
const STATUS_CONFIG_LIGHT: Record<StatusValue, StatusConfigItem> = {
  operational: {icon: CheckCircle2, title: 'All Systems Operational', bg: 'bg-success-bg', border: 'border-success-border', iconColor: 'text-success-fg', textColor: 'text-success-fg'},
  degraded: {icon: AlertTriangle, title: 'Partial System Outage', bg: 'bg-warning-bg', border: 'border-warning-border', iconColor: 'text-warning-fg', textColor: 'text-warning-fg'},
  down: {icon: XCircle, title: 'Major System Outage', bg: 'bg-danger-bg', border: 'border-danger-border', iconColor: 'text-danger-fg', textColor: 'text-danger-fg'},
  unknown: {icon: AlertCircle, title: 'Status Unknown', bg: 'bg-muted', border: 'border-border', iconColor: 'text-muted-foreground', textColor: 'text-muted-foreground'},
}

const STATUS_CONFIG_DARK: Record<StatusValue, StatusConfigItem> = STATUS_CONFIG_LIGHT

function getBarColor(uptime: number) {
  if (uptime >= 99) return 'bg-success-solid'
  if (uptime >= 90) return 'bg-warning-solid'
  return 'bg-danger-solid'
}

function getUptimeColor(uptime: number): string {
  if (uptime >= 99) return 'text-success-fg'
  if (uptime >= 90) return 'text-warning-fg'
  return 'text-danger-fg'
}

function computeOverallStatus(monitors: StatusPageMonitorEntry[]): StatusValue {
  if (monitors.length === 0) return 'unknown'
  if (monitors.some((m) => m.status === 'down')) return 'down'
  if (monitors.some((m) => m.status === 'degraded')) return 'degraded'
  if (monitors.every((m) => m.status === 'operational')) return 'operational'
  return 'unknown'
}

const STATUS_DOT_COLORS_DARK: Record<string, string> = {
  operational: 'bg-success-solid',
  degraded: 'bg-warning-solid',
  down: 'bg-danger-solid',
}

const STATUS_DOT_COLORS_LIGHT: Record<string, string> = STATUS_DOT_COLORS_DARK

const STATUS_TEXT_COLORS_DARK: Record<string, string> = {
  operational: 'text-success-fg',
  degraded: 'text-warning-fg',
  down: 'text-danger-fg',
}

const STATUS_TEXT_COLORS_LIGHT: Record<string, string> = STATUS_TEXT_COLORS_DARK

function getTooltipUptimeColor(uptime: number): string {
  if (uptime >= 99) return 'text-success-fg'
  if (uptime >= 90) return 'text-warning-fg'
  return 'text-danger-fg'
}

function UptimeHistoryBar({
  history,
  historyDays,
  isDarkMode,
  timezone,
}: {
  readonly history: {date: string; uptime: number}[]
  readonly historyDays: number
  readonly isDarkMode: boolean
  readonly timezone: string
}) {
  return (
    <div className="mt-3">
      <div className="flex items-stretch gap-[1.5px] h-8 w-full">
        {history.map((point) => (
          <Tooltip key={point.date}>
            <TooltipTrigger asChild>
              <div
                className={`flex-1 rounded-[2px] ${getBarColor(point.uptime)} transition-opacity hover:opacity-80 cursor-default min-w-[2px]`}
              />
            </TooltipTrigger>
            <TooltipContent side="top" className="text-xs">
              <p className="font-medium">{formatMonthDay(new Date(point.date), timezone)}</p>
              <p className={`tabular-nums ${getTooltipUptimeColor(point.uptime)}`}>
                {point.uptime.toFixed(2)}% uptime
              </p>
            </TooltipContent>
          </Tooltip>
        ))}
      </div>
      <div className={`flex justify-between mt-1.5 text-[10px] ${isDarkMode ? 'text-slate-600' : 'text-slate-400'}`}>
        <span>{historyDays}d ago</span>
        <span>{(() => {
          const lastEntry = history.at(-1)
          if (!lastEntry) return 'Today'
          const lastDate = new Date(lastEntry.date)
          const today = new Date()
          if (lastDate.toDateString() === today.toDateString()) return 'Today'
          return formatMonthDay(lastDate, timezone)
        })()}</span>
      </div>
    </div>
  )
}

// ─── Status Banner ───────────────────────────────────────────────────────────

export function StatusBanner({status, isDarkMode}: {readonly status: StatusValue; readonly isDarkMode: boolean}) {
  const configs = isDarkMode ? STATUS_CONFIG_DARK : STATUS_CONFIG_LIGHT
  const c = configs[status] ?? configs.unknown
  const Icon = c.icon

  return (
    <div className={`rounded-xl border ${c.border} ${c.bg} px-6 py-5 flex items-center gap-4`}>
      <Icon className={`h-6 w-6 flex-shrink-0 ${c.iconColor}`} />
      <span className={`text-base font-semibold ${c.textColor}`}>{c.title}</span>
    </div>
  )
}

// ─── Monitor Row ─────────────────────────────────────────────────────────────

export function StatusPageMonitorRow({
  monitor,
  showHistory,
  historyDays,
  isDarkMode,
  timezone,
}: {
  readonly monitor: StatusPageMonitorEntry
  readonly showHistory: boolean
  readonly historyDays: number
  readonly isDarkMode: boolean
  readonly timezone: string
}) {
  const statusDotColors = isDarkMode ? STATUS_DOT_COLORS_DARK : STATUS_DOT_COLORS_LIGHT
  const statusColors = isDarkMode ? STATUS_TEXT_COLORS_DARK : STATUS_TEXT_COLORS_LIGHT
  const uptimeColor = getUptimeColor(monitor.uptimePercentage)
  const dotColor = statusDotColors[monitor.status] ?? 'bg-slate-400'
  const textColor = statusColors[monitor.status] ?? (isDarkMode ? 'text-slate-400' : 'text-slate-500')
  const hoverBg = isDarkMode ? 'hover:bg-slate-900/50' : 'hover:bg-slate-50/80'
  const history = showHistory && monitor.uptimeHistory ? monitor.uptimeHistory.slice(-historyDays) : null

  return (
    <div className={`px-5 py-4 ${hoverBg} transition-colors`}>
      {/* Top: name + status */}
      <div className="flex items-center justify-between mb-1">
        <div className="flex items-center gap-2.5 min-w-0">
          <span className={`inline-block h-2 w-2 rounded-full flex-shrink-0 ${dotColor}`} />
          <span className="font-medium text-sm truncate">
            {monitor.displayName ?? monitor.name}
          </span>
        </div>
        <div className="flex items-center gap-3 flex-shrink-0 ml-4">
          <span className={`text-xs font-medium tabular-nums ${uptimeColor}`}>
            {monitor.uptimePercentage.toFixed(2)}%
          </span>
          <span className={`text-xs capitalize ${textColor}`}>
            {monitor.status === 'operational' ? 'Operational' : monitor.status}
          </span>
        </div>
      </div>

      {/* Uptime History Bar */}
      {history && history.length > 0 && (
        <UptimeHistoryBar history={history} historyDays={historyDays} isDarkMode={isDarkMode} timezone={timezone} />
      )}
    </div>
  )
}

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
  readonly name: string
  readonly description?: string | null
  readonly logoUrl?: string | null
  readonly primaryColor: string
  readonly darkMode: boolean
  readonly showUptimeHistory: boolean
  readonly historyDays: number
  readonly monitors?: StatusPageMonitorEntry[]
}) {
  const isDarkMode = darkMode
  const { timezone } = useTimezone()
  const overallStatus = computeOverallStatus(monitors)

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
                  <StatusPageMonitorRow
                    key={monitor.name}
                    monitor={monitor}
                    showHistory={showUptimeHistory}
                    historyDays={historyDays}
                    isDarkMode={isDarkMode}
                    timezone={timezone}
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
