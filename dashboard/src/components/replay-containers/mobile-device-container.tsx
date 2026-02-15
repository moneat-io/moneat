import {cn} from '@/lib/utils'
import {Battery, Signal, Wifi} from 'lucide-react'

interface MobileDeviceContainerProps {
  children: React.ReactNode
  platform: 'android' | 'ios' | string
  className?: string
}

function StatusBar({ platform }: { platform: string }) {
  const isIOS = platform === 'ios'
  const now = new Date()
  const time = now.toLocaleTimeString('en-US', {
    hour: 'numeric',
    minute: '2-digit',
    hour12: true,
  })

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
  className,
}: MobileDeviceContainerProps) {
  const isIOS = platform === 'ios'

  return (
    <div className={cn('flex items-center justify-center', className)}>
      {/* Phone bezel */}
      <div
        className={cn(
          'relative bg-[#1a1a1e] shadow-2xl shadow-black/50 border border-white/[0.08] overflow-hidden',
          isIOS ? 'rounded-[2.5rem]' : 'rounded-[1.5rem]'
        )}
        style={{ width: 320, maxWidth: '100%' }}
      >
        {/* Top bezel area with notch / pill */}
        <div className="relative bg-[#1a1a1e]">
          {isIOS ? (
            /* iOS Dynamic Island / Notch */
            <div className="flex justify-center pt-2 pb-0">
              <div className="w-[90px] h-[25px] bg-black rounded-full" />
            </div>
          ) : (
            /* Android punch-hole camera */
            <div className="flex justify-center pt-2 pb-0">
              <div className="w-3 h-3 bg-black/80 rounded-full border border-white/[0.05]" />
            </div>
          )}
          {/* Status bar */}
          <StatusBar platform={platform} />
        </div>

        {/* Screen content area */}
        <div className="relative bg-black overflow-hidden">
          {children}
        </div>

        {/* Bottom bezel / home indicator */}
        <div className="bg-[#1a1a1e] flex justify-center py-2">
          {isIOS ? (
            <div className="w-[100px] h-[4px] bg-white/30 rounded-full" />
          ) : (
            <div className="flex items-center gap-6">
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
