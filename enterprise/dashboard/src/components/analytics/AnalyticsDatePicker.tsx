// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Button} from '@/components/ui/button'
import {Popover, PopoverContent, PopoverTrigger} from '@/components/ui/popover'
import {Calendar} from 'lucide-react'
import {useState} from 'react'
import type {AnalyticsPeriod} from '@/lib/api'

const PERIOD_OPTIONS: {value: AnalyticsPeriod; label: string}[] = [
  {value: 'today', label: 'Today'},
  {value: '7d', label: 'Last 7 days'},
  {value: '30d', label: 'Last 30 days'},
  {value: 'month', label: 'This month'},
  {value: '6mo', label: 'Last 6 months'},
  {value: '12mo', label: 'Last 12 months'},
  {value: 'custom', label: 'Custom range'},
]

interface AnalyticsDatePickerProps {
  period: AnalyticsPeriod
  onPeriodChange: (period: AnalyticsPeriod) => void
  customFrom?: string
  customTo?: string
  onCustomRangeChange?: (from: string, to: string) => void
}

export function AnalyticsDatePicker({
  period,
  onPeriodChange,
  customFrom,
  customTo,
  onCustomRangeChange,
}: AnalyticsDatePickerProps) {
  const [localFrom, setLocalFrom] = useState(customFrom || '')
  const [localTo, setLocalTo] = useState(customTo || '')

  const handlePeriodChange = (value: string) => {
    onPeriodChange(value as AnalyticsPeriod)
  }

  const handleApplyCustom = () => {
    if (localFrom && localTo && onCustomRangeChange) {
      onCustomRangeChange(localFrom, localTo)
    }
  }

  if (period === 'custom') {
    return (
      <Popover>
        <PopoverTrigger asChild>
          <Button variant="outline" size="sm" className="gap-2">
            <Calendar className="h-4 w-4" />
            {customFrom && customTo
              ? `${customFrom} — ${customTo}`
              : 'Select dates'}
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-auto p-4" align="end">
          <div className="space-y-3">
            <div className="flex items-center gap-2">
              <Select value="custom" onValueChange={handlePeriodChange}>
                <SelectTrigger className="w-[160px]">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {PERIOD_OPTIONS.map((opt) => (
                    <SelectItem key={opt.value} value={opt.value}>
                      {opt.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="flex items-center gap-2">
              <input
                type="date"
                value={localFrom}
                onChange={(e) => setLocalFrom(e.target.value)}
                className="flex h-9 rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              />
              <span className="text-muted-foreground text-sm">to</span>
              <input
                type="date"
                value={localTo}
                onChange={(e) => setLocalTo(e.target.value)}
                className="flex h-9 rounded-md border border-input bg-transparent px-3 py-1 text-sm shadow-sm transition-colors placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              />
            </div>
            <Button size="sm" onClick={handleApplyCustom} disabled={!localFrom || !localTo}>
              Apply
            </Button>
          </div>
        </PopoverContent>
      </Popover>
    )
  }

  return (
    <Select value={period} onValueChange={handlePeriodChange}>
      <SelectTrigger className="w-[160px] h-9">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {PERIOD_OPTIONS.map((opt) => (
          <SelectItem key={opt.value} value={opt.value}>
            {opt.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}
