import {cn} from '@/lib/utils'
import {Battery, Signal, Wifi} from 'lucide-react'
import type {ReplayOrientation} from '@/components/mobile-replay-viewer'

interface MobileDeviceContainerProps {
  children: React.ReactNode
  platform: 'android' | 'ios' | string
  orientation?: ReplayOrientation
  className?: string
}

function StatusBar({ platform, compact }: { platform: string; compact?: boolean }) {
  const isIOS = platform === 'ios'
  const now = new Date()
  const time = now.toLocaleTimeString('en-US', {
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  })

  if (compact) {
    return (
      <div className="flex flex-col items-center gap-1 py-1 text-[8px] text-white/90">
        <span>{time}</span>
        <div className="flex gap-0.5">
          <Signal className="h-2.5 w-2.5" />
          <Wifi className="h-2.5 w-2.5" />
          <Battery className="h-2.5 w-2.5" />
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
        <Battery className="h-3 w-3" />
        {!isIOS && <span className="text-[9px] opacity-70">100%</span>}
      </div>
    </div>
  )
}

export function MobileDeviceContainer({
  children,
  platform,
  orientation = 'portrait',
  className,
}: MobileDeviceContainerProps) {
  const isIOS = platform === 'ios'
  const isLandscape = orientation === 'landscape'

  return (
    <div className={cn('flex items-center justify-center', className)}>
      {/* Phone bezel - dimensions swap for landscape */}
      <div
        className={cn(
          'relative bg-[#1a1a1e] shadow-2xl shadow-black/50 border border-white/[0.08] overflow-hidden flex',
          isIOS ? 'rounded-[2.5rem]' : 'rounded-[1.5rem]',
          isLandscape ? 'flex-row' : 'flex-col'
        )}
        style={
          isLandscape
            ? { width: 560, height: 320, maxWidth: '100%', maxHeight: 'min(320px, calc(100vh - 280px))' }
            : { width: 320, maxWidth: '100%' }
        }
      >
        {/* Top/Left bezel: notch + status bar */}
        <div
          className={cn(
            'relative bg-[#1a1a1e] flex items-center justify-center',
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
          <StatusBar platform={platform} compact={isLandscape} />
        </div>

        {/* Screen content area */}
        <div className="relative bg-black overflow-hidden flex-1 min-w-0 min-h-0">
          {children}
        </div>

        {/* Bottom/Right bezel: home indicator */}
        <div
          className={cn(
            'bg-[#1a1a1e] flex justify-center items-center',
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
