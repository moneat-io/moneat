import {Badge} from '@/components/ui/badge'
import {X} from 'lucide-react'
import type {AnalyticsFilter} from '@/lib/api'

interface AnalyticsFilterBarProps {
  filters: AnalyticsFilter[]
  onFiltersChange: (filters: AnalyticsFilter[]) => void
}

export function AnalyticsFilterBar({filters, onFiltersChange}: AnalyticsFilterBarProps) {
  if (filters.length === 0) return null

  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {filters.map((filter, i) => (
        <Badge key={`${filter.property}-${filter.value}`} variant="secondary" className="gap-1 text-xs">
          {filter.property} {filter.operator} {filter.value}
          <button
            onClick={() => onFiltersChange(filters.filter((_, idx) => idx !== i))}
            className="ml-0.5 hover:text-foreground"
          >
            <X className="h-3 w-3" />
          </button>
        </Badge>
      ))}
      {filters.length > 0 && (
        <button
          onClick={() => onFiltersChange([])}
          className="text-xs text-muted-foreground hover:text-foreground"
        >
          Clear all
        </button>
      )}
    </div>
  )
}
