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

import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
import {cn} from '@/lib/utils'
import {Pause, Play, Radio, RadioTower} from 'lucide-react'

interface LiveTailToggleProps {
  enabled: boolean
  paused: boolean
  bufferedCount: number
  status: 'connecting' | 'open' | 'closed'
  onToggleEnabled: () => void
  onTogglePaused: () => void
}

function statusLabel(status: 'connecting' | 'open' | 'closed') {
  if (status === 'open') return 'Connected'
  if (status === 'connecting') return 'Connecting...'
  return 'Disconnected'
}

function statusDot(status: 'connecting' | 'open' | 'closed') {
  if (status === 'open') return 'bg-emerald-500'
  if (status === 'connecting') return 'bg-amber-500 animate-pulse'
  return 'bg-zinc-400'
}

export function LiveTailToggle({
  enabled,
  paused,
  bufferedCount,
  status,
  onToggleEnabled,
  onTogglePaused,
}: LiveTailToggleProps) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Button
        variant={enabled ? 'default' : 'outline'}
        size="sm"
        onClick={onToggleEnabled}
        className={cn(
          'gap-2 font-medium',
          enabled && 'bg-emerald-600 hover:bg-emerald-700 text-white shadow-sm shadow-emerald-500/20'
        )}
      >
        {enabled ? <RadioTower className="h-4 w-4" /> : <Radio className="h-4 w-4" />}
        {enabled ? 'Stop Tail' : 'Live Tail'}
      </Button>

      {enabled && (
        <>
          <div className="flex items-center gap-1.5 rounded-md border bg-card/80 px-2.5 py-1.5">
            <span className={cn('h-2 w-2 rounded-full', statusDot(status))} />
            <span className="text-xs text-muted-foreground">{statusLabel(status)}</span>
          </div>

          <Button variant="ghost" size="sm" onClick={onTogglePaused} className="gap-1.5 text-xs">
            {paused ? <Play className="h-3.5 w-3.5" /> : <Pause className="h-3.5 w-3.5" />}
            {paused ? 'Resume' : 'Pause'}
          </Button>
        </>
      )}

      {enabled && paused && bufferedCount > 0 && (
        <Badge variant="secondary" className="text-xs font-mono">
          {bufferedCount} buffered
        </Badge>
      )}
    </div>
  )
}
