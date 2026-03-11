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
      return {id: `slot-${i}`, status: 'unknown', count: 0}
    }

    const upCount = slotHeartbeats.filter((h) => h.status === 1).length
    const downCount = slotHeartbeats.filter((h) => h.status === 0).length

    if (downCount > 0) {
      return {id: `slot-${i}`, status: 'down', count: slotHeartbeats.length}
    } else if (upCount > 0) {
      return {id: `slot-${i}`, status: 'up', count: slotHeartbeats.length}
    } else {
      return {id: `slot-${i}`, status: 'pending', count: slotHeartbeats.length}
    }
  }).reverse()

  return (
    <div className={cn("flex gap-[2px] h-10 items-end", className)}>
      {slots.map((slot) => (
        <div
          key={slot.id}
          className={cn(
            'flex-1 rounded-sm transition-all',
            slot.status === 'up' && 'bg-emerald-500',
            slot.status === 'down' && 'bg-red-500',
            slot.status === 'pending' && 'bg-yellow-500',
            slot.status === 'unknown' && 'bg-slate-200 dark:bg-muted'
          )}
          style={{
            height: slot.count > 0 ? '100%' : '20%',
            opacity: slot.count > 0 ? 1 : 0.5,
          }}
          title={(() => {
            if (slot.count === 0) return 'No data'
            const plural = slot.count > 1 ? 's' : ''
            return `${slot.count} check${plural} - ${slot.status}`
          })()}
        />
      ))}
    </div>
  )
}
