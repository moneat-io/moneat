import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'

export function AnalyticsRealtimeBadge({projectId}: {projectId: string | number}) {
  const {data} = useQuery({
    queryKey: ['analytics-realtime', projectId],
    queryFn: () => api.getAnalyticsRealtime(projectId),
    refetchInterval: 30_000,
  })

  if (!data || data.currentVisitors === 0) return null

  return (
    <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
      <span className="relative flex h-2 w-2">
        <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-success-solid opacity-75" />
        <span className="relative inline-flex rounded-full h-2 w-2 bg-success-solid" />
      </span>
      {data.currentVisitors} current visitor{data.currentVisitors !== 1 ? 's' : ''}
    </div>
  )
}
