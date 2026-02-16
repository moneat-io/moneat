import {type UptimeHeartbeat} from '@/lib/api'
import {cn} from '@/lib/utils'
import { getNow } from '@/lib/demo'

interface HeartbeatBarProps {
  heartbeats: UptimeHeartbeat[]
  maxBars?: number
}

export default function HeartbeatBar({heartbeats, maxBars = 100, className}: HeartbeatBarProps & {className?: string}) {
  // Group heartbeats into time slots
  const now = getNow()
  const slotDuration = 24 * 60 * 60 * 1000 / maxBars // 24 hours divided into slots

  const slots = Array.from({length: maxBars}, (_, i) => {
    const slotEnd = now - i * slotDuration
    const slotStart = slotEnd - slotDuration

    const slotHeartbeats = heartbeats.filter(
      (h) => h.timestamp >= slotStart && h.timestamp < slotEnd
    )

    if (slotHeartbeats.length === 0) {
      return {status: 'unknown', count: 0}
    }

    const upCount = slotHeartbeats.filter((h) => h.status === 1).length
    const downCount = slotHeartbeats.filter((h) => h.status === 0).length

    if (downCount > 0) {
      return {status: 'down', count: slotHeartbeats.length}
    } else if (upCount > 0) {
      return {status: 'up', count: slotHeartbeats.length}
    } else {
      return {status: 'pending', count: slotHeartbeats.length}
    }
  }).reverse()

  return (
    <div className={cn("flex gap-[2px] h-10 items-end", className)}>
      {slots.map((slot, i) => (
        <div
          key={i}
          className={cn(
            'flex-1 rounded-sm transition-all',
            slot.status === 'up' && 'bg-emerald-500',
            slot.status === 'down' && 'bg-red-500',
            slot.status === 'pending' && 'bg-yellow-500',
            slot.status === 'unknown' && 'bg-muted'
          )}
          style={{
            height: slot.count > 0 ? '100%' : '20%',
            opacity: slot.count > 0 ? 1 : 0.3,
          }}
          title={
            slot.count > 0
              ? `${slot.count} check${slot.count > 1 ? 's' : ''} - ${slot.status}`
              : 'No data'
          }
        />
      ))}
    </div>
  )
}
