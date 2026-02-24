import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import type {AnalyticsPeriod} from '@/lib/api'

interface AnalyticsDatePickerProps {
  period: AnalyticsPeriod
  onPeriodChange: (period: AnalyticsPeriod) => void
  customFrom: string
  customTo: string
  onCustomRangeChange: (from: string, to: string) => void
}

export function AnalyticsDatePicker({period, onPeriodChange, customFrom, customTo, onCustomRangeChange}: AnalyticsDatePickerProps) {
  return (
    <div className="flex items-center gap-2">
      <Select value={period} onValueChange={(v) => onPeriodChange(v as AnalyticsPeriod)}>
        <SelectTrigger className="w-[140px] h-8 text-xs">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="today">Today</SelectItem>
          <SelectItem value="7d">Last 7 days</SelectItem>
          <SelectItem value="30d">Last 30 days</SelectItem>
          <SelectItem value="month">This month</SelectItem>
          <SelectItem value="6mo">Last 6 months</SelectItem>
          <SelectItem value="12mo">Last 12 months</SelectItem>
          <SelectItem value="custom">Custom range</SelectItem>
        </SelectContent>
      </Select>
      {period === 'custom' && (
        <div className="flex items-center gap-1.5">
          <input
            type="date"
            value={customFrom}
            onChange={(e) => onCustomRangeChange(e.target.value, customTo)}
            className="h-8 rounded-md border bg-background px-2 text-xs"
          />
          <span className="text-xs text-muted-foreground">to</span>
          <input
            type="date"
            value={customTo}
            onChange={(e) => onCustomRangeChange(customFrom, e.target.value)}
            className="h-8 rounded-md border bg-background px-2 text-xs"
          />
        </div>
      )}
    </div>
  )
}
