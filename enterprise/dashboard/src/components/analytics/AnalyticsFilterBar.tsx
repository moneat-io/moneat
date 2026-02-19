// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

import {useState} from 'react'
import {Button} from '@/components/ui/button'
import {Badge} from '@/components/ui/badge'
import {Select, SelectContent, SelectItem, SelectTrigger, SelectValue} from '@/components/ui/select'
import {Input} from '@/components/ui/input'
import {Popover, PopoverContent, PopoverTrigger} from '@/components/ui/popover'
import {Filter, Plus, X} from 'lucide-react'
import type {AnalyticsFilter} from '@/lib/api'

const FILTER_PROPERTIES = [
  {value: 'page', label: 'Page'},
  {value: 'source', label: 'Source'},
  {value: 'country', label: 'Country'},
  {value: 'browser', label: 'Browser'},
  {value: 'os', label: 'OS'},
  {value: 'device', label: 'Device'},
  {value: 'utm_source', label: 'UTM Source'},
  {value: 'utm_medium', label: 'UTM Medium'},
  {value: 'utm_campaign', label: 'UTM Campaign'},
]

const FILTER_OPERATORS = [
  {value: 'is', label: 'is'},
  {value: 'is_not', label: 'is not'},
  {value: 'contains', label: 'contains'},
  {value: 'not_contains', label: 'does not contain'},
] as const

interface AnalyticsFilterBarProps {
  filters: AnalyticsFilter[]
  onFiltersChange: (filters: AnalyticsFilter[]) => void
}

export function AnalyticsFilterBar({filters, onFiltersChange}: AnalyticsFilterBarProps) {
  const [isAdding, setIsAdding] = useState(false)
  const [newProperty, setNewProperty] = useState('')
  const [newOperator, setNewOperator] = useState<AnalyticsFilter['operator']>('is')
  const [newValue, setNewValue] = useState('')

  const handleAdd = () => {
    if (!newProperty || !newValue) return
    onFiltersChange([...filters, {property: newProperty, operator: newOperator, value: newValue}])
    setNewProperty('')
    setNewOperator('is')
    setNewValue('')
    setIsAdding(false)
  }

  const handleRemove = (index: number) => {
    onFiltersChange(filters.filter((_, i) => i !== index))
  }

  const operatorLabel = (op: string) =>
    FILTER_OPERATORS.find(o => o.value === op)?.label || op

  const propertyLabel = (prop: string) =>
    FILTER_PROPERTIES.find(p => p.value === prop)?.label || prop

  return (
    <div className="flex flex-wrap items-center gap-2">
      {filters.map((filter, i) => (
        <Badge key={i} variant="secondary" className="gap-1.5 pl-2.5 pr-1 py-1 text-xs font-normal">
          <span className="font-medium">{propertyLabel(filter.property)}</span>
          <span className="text-muted-foreground">{operatorLabel(filter.operator)}</span>
          <span>{filter.value}</span>
          <button
            onClick={() => handleRemove(i)}
            className="ml-1 rounded-full p-0.5 hover:bg-muted-foreground/20 transition-colors"
          >
            <X className="h-3 w-3" />
          </button>
        </Badge>
      ))}

      <Popover open={isAdding} onOpenChange={setIsAdding}>
        <PopoverTrigger asChild>
          <Button variant="outline" size="sm" className="h-7 gap-1.5 text-xs">
            {filters.length > 0 ? <Plus className="h-3 w-3" /> : <Filter className="h-3 w-3" />}
            {filters.length > 0 ? 'Add filter' : 'Filter'}
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-80 p-3" align="start">
          <div className="space-y-3">
            <div className="grid grid-cols-2 gap-2">
              <Select value={newProperty} onValueChange={setNewProperty}>
                <SelectTrigger className="h-8 text-xs">
                  <SelectValue placeholder="Property" />
                </SelectTrigger>
                <SelectContent>
                  {FILTER_PROPERTIES.map((prop) => (
                    <SelectItem key={prop.value} value={prop.value} className="text-xs">
                      {prop.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>

              <Select value={newOperator} onValueChange={(v) => setNewOperator(v as AnalyticsFilter['operator'])}>
                <SelectTrigger className="h-8 text-xs">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {FILTER_OPERATORS.map((op) => (
                    <SelectItem key={op.value} value={op.value} className="text-xs">
                      {op.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <Input
              placeholder="Value..."
              value={newValue}
              onChange={(e) => setNewValue(e.target.value)}
              className="h-8 text-xs"
              onKeyDown={(e) => e.key === 'Enter' && handleAdd()}
            />

            <div className="flex gap-2">
              <Button size="sm" className="h-7 text-xs" onClick={handleAdd} disabled={!newProperty || !newValue}>
                Apply
              </Button>
              <Button size="sm" variant="ghost" className="h-7 text-xs" onClick={() => setIsAdding(false)}>
                Cancel
              </Button>
            </div>
          </div>
        </PopoverContent>
      </Popover>

      {filters.length > 0 && (
        <Button
          variant="ghost"
          size="sm"
          className="h-7 text-xs text-muted-foreground"
          onClick={() => onFiltersChange([])}
        >
          Clear all
        </Button>
      )}
    </div>
  )
}
