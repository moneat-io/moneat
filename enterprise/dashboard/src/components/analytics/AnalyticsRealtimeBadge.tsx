// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {cn} from '@/lib/utils'

interface AnalyticsRealtimeBadgeProps {
  projectId: number
}

export function AnalyticsRealtimeBadge({projectId}: AnalyticsRealtimeBadgeProps) {
  const {data} = useQuery({
    queryKey: ['analytics-realtime', projectId],
    queryFn: () => api.getAnalyticsRealtime(projectId),
    refetchInterval: 30_000,
  })

  const count = data?.currentVisitors ?? 0

  return (
    <div className="flex items-center gap-2 px-3 py-1.5 rounded-full border bg-card text-sm">
      <div className="relative flex h-2 w-2">
        <span
          className={cn(
            'absolute inline-flex h-full w-full rounded-full opacity-75',
            count > 0 ? 'animate-ping bg-green-400' : 'bg-muted-foreground'
          )}
        />
        <span
          className={cn(
            'relative inline-flex rounded-full h-2 w-2',
            count > 0 ? 'bg-green-500' : 'bg-muted-foreground'
          )}
        />
      </div>
      <span className="font-semibold tabular-nums">{count}</span>
      <span className="text-muted-foreground text-xs">
        {count === 1 ? 'current visitor' : 'current visitors'}
      </span>
    </div>
  )
}
