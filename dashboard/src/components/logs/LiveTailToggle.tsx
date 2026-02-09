import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
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
  if (status === 'connecting') return 'Connecting'
  return 'Disconnected'
}

function statusClass(status: 'connecting' | 'open' | 'closed') {
  if (status === 'open') return 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-300 border-emerald-500/30'
  if (status === 'connecting') return 'bg-amber-500/15 text-amber-700 dark:text-amber-300 border-amber-500/30'
  return 'bg-zinc-500/15 text-zinc-700 dark:text-zinc-300 border-zinc-500/30'
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
      <Button variant={enabled ? 'default' : 'outline'} size="sm" onClick={onToggleEnabled} className="gap-1.5">
        {enabled ? <RadioTower className="h-4 w-4" /> : <Radio className="h-4 w-4" />}
        {enabled ? 'Stop Live Tail' : 'Start Live Tail'}
      </Button>

      <Badge variant="outline" className={statusClass(status)}>
        {statusLabel(status)}
      </Badge>

      {enabled && (
        <Button variant="outline" size="sm" onClick={onTogglePaused} className="gap-1.5">
          {paused ? <Play className="h-4 w-4" /> : <Pause className="h-4 w-4" />}
          {paused ? 'Resume' : 'Pause'}
        </Button>
      )}

      {enabled && paused && bufferedCount > 0 && (
        <Badge variant="secondary">{bufferedCount} buffered</Badge>
      )}
    </div>
  )
}
