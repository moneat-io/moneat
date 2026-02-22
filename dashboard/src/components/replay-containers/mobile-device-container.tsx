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

import {cn} from '@/lib/utils'
import {Signal, Wifi} from 'lucide-react'
import type {ReplayOrientation} from '@/components/mobile-replay-viewer'
import {useTimezone} from '@/hooks/useTimezone'
import {formatTimeHM12} from '@/lib/date-format'

export interface StatusBarContext {
  /** Device time from the replay (epoch ms) */
  deviceTimeMs?: number | null
  /** Battery level 0-100 */
  batteryLevel?: number | null
  /** Whether the device is charging */
  isCharging?: boolean | null
}

interface MobileDeviceContainerProps {
  children: React.ReactNode
  platform: 'android' | 'ios' | string
  orientation?: ReplayOrientation
  className?: string
  statusBarContext?: StatusBarContext
}

function BatteryIcon({ level, charging, className }: { level?: number | null; charging?: boolean | null; className?: string }) {
  const pct = typeof level === 'number' && Number.isFinite(level) ? Math.max(0, Math.min(level, 100)) : 100
  const fillWidth = Math.round((pct / 100) * 10)
  const isLow = pct <= 20 && !charging
  const fillColor = isLow ? '#ef4444' : charging ? '#34d399' : 'currentColor'

  return (
    <svg viewBox="0 0 16 10" fill="none" className={className} aria-label={`Battery ${pct}%`}>
      <rect x="0.5" y="0.5" width="13" height="9" rx="1.5" stroke="currentColor" strokeWidth="1" />
      <rect x="14" y="3" width="1.5" height="4" rx="0.5" fill="currentColor" opacity="0.5" />
      <rect x="2" y="2" width={fillWidth} height="6" rx="0.5" fill={fillColor} />
    </svg>
  )
}

function StatusBar({ platform, compact, context }: { platform: string; compact?: boolean; context?: StatusBarContext }) {
  const { timezone } = useTimezone()
  const isIOS = platform === 'ios'
  const date = typeof context?.deviceTimeMs === 'number' && Number.isFinite(context?.deviceTimeMs)
    ? new Date(context.deviceTimeMs)
    : new Date()
  const time = isNaN(date.getTime()) ? formatTimeHM12(new Date(), timezone) : formatTimeHM12(date, timezone)
  const batteryLevel = context?.batteryLevel
  const hasBatteryLevel = typeof batteryLevel === 'number' && Number.isFinite(batteryLevel)

  if (compact) {
    return (
      <div className="flex flex-col items-center gap-1 py-1 text-[8px] text-white/90">
        <span>{time}</span>
        <div className="flex flex-col items-center gap-0.5">
          <Signal className="h-2.5 w-2.5" />
          <Wifi className="h-2.5 w-2.5" />
          <BatteryIcon level={batteryLevel} charging={context?.isCharging} className="h-2.5 w-auto" />
        </div>
      </div>
    )
  }

  return (
    <div className="flex items-center justify-between px-5 py-1.5 text-[10px] font-medium text-white/90">
      <span className="w-12 text-left">{time}</span>
      <div className="flex items-center gap-1.5">
        <Signal className="h-3 w-3" />
        <Wifi className="h-3 w-3" />
        <BatteryIcon level={batteryLevel} charging={context?.isCharging} className="h-3 w-auto" />
        {!isIOS && hasBatteryLevel && <span className="text-[9px] opacity-70">{Math.round(batteryLevel)}%</span>}
      </div>
    </div>
  )
}

export function MobileDeviceContainer({
  children,
  platform,
  orientation = 'portrait',
  className,
  statusBarContext,
}: MobileDeviceContainerProps) {
  const isIOS = platform === 'ios'
  const isLandscape = orientation === 'landscape'

  return (
    <div className={cn('flex items-center justify-center', className)}>
      {/* Phone bezel - dimensions swap for landscape, smooth transition */}
      <div
        className={cn(
          'relative bg-[#1a1a1e] shadow-2xl shadow-black/50 border border-white/[0.08] overflow-hidden flex transition-all duration-300 ease-in-out',
          isIOS ? 'rounded-[2.5rem]' : 'rounded-[1.5rem]',
          isLandscape ? 'flex-row' : 'flex-col'
        )}
        style={
          isLandscape
            ? { width: 560, height: 320, maxWidth: '100%', maxHeight: 'min(320px, calc(100vh - 280px))' }
            : { width: 320, height: 640, maxWidth: '100%', maxHeight: 'min(640px, calc(100vh - 280px))' }
        }
      >
        {/* Top/Left bezel: notch + status bar */}
        <div
          className={cn(
            'relative bg-[#1a1a1e] flex items-center justify-center shrink-0',
            isLandscape ? 'w-10 flex-col gap-1' : 'flex-col'
          )}
        >
          {isIOS ? (
            <div className={cn('flex justify-center', isLandscape ? 'pt-1' : 'pt-2 pb-0')}>
              <div className={cn('bg-black rounded-full', isLandscape ? 'w-[25px] h-[50px]' : 'w-[90px] h-[25px]')} />
            </div>
          ) : (
            <div className={cn('flex justify-center', isLandscape ? 'pt-1' : 'pt-2 pb-0')}>
              <div className="w-3 h-3 bg-black/80 rounded-full border border-white/[0.05]" />
            </div>
          )}
          <StatusBar platform={platform} compact={isLandscape} context={statusBarContext} />
        </div>

        {/* Screen content area */}
        <div className="relative bg-black overflow-hidden flex-1 min-w-0 min-h-0">
          {children}
        </div>

        {/* Bottom/Right bezel: home indicator */}
        <div
          className={cn(
            'bg-[#1a1a1e] flex justify-center items-center shrink-0',
            isLandscape ? 'w-10 flex-col gap-4' : 'py-2'
          )}
        >
          {isIOS ? (
            <div className={cn('bg-white/30 rounded-full', isLandscape ? 'w-[4px] h-[60px]' : 'w-[100px] h-[4px]')} />
          ) : (
            <div className={cn('flex gap-6', isLandscape && 'flex-col')}>
              <div className="w-3 h-3 border border-white/20 rounded-sm" />
              <div className="w-3 h-3 border border-white/20 rounded-full" />
              <div className="w-0 h-0 border-l-[6px] border-l-transparent border-r-[6px] border-r-transparent border-b-[10px] border-b-white/20 rotate-[-90deg]" />
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
