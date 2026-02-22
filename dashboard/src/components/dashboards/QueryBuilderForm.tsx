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

import {useState} from 'react'
import {useQuery} from '@tanstack/react-query'
import {api, type QueryDsl, type MetricDef, type GroupByDef, type FilterDef} from '@/lib/api'
import {Button} from '@/components/ui/button'
import {Plus, Trash2} from 'lucide-react'

const AGG_FUNCTIONS = ['count', 'avg', 'sum', 'min', 'max', 'p50', 'p75', 'p90', 'p95', 'p99', 'uniq']
const FILTER_OPS = [
  {value: 'eq', label: '='},
  {value: 'neq', label: '≠'},
  {value: 'gt', label: '>'},
  {value: 'gte', label: '≥'},
  {value: 'lt', label: '<'},
  {value: 'lte', label: '≤'},
  {value: 'like', label: 'contains'},
  {value: 'in', label: 'in'},
  {value: 'is_null', label: 'is null'},
  {value: 'is_not_null', label: 'is not null'},
]

interface QueryBuilderFormProps {
  value: QueryDsl
  onChange: (query: QueryDsl) => void
}

export function QueryBuilderForm({value, onChange}: QueryBuilderFormProps) {
  const {data: dataSources} = useQuery({
    queryKey: ['datasources'],
    queryFn: () => api.getDataSources(),
    staleTime: 60000,
  })

  const selectedSource = dataSources?.find((ds) => ds.name === value.dataSource)
  const fields = selectedSource?.fields ?? []

  const updateMetric = (index: number, updates: Partial<MetricDef>) => {
    const newMetrics = [...value.metrics]
    newMetrics[index] = {...newMetrics[index], ...updates}
    onChange({...value, metrics: newMetrics})
  }

  const addMetric = () => {
    onChange({
      ...value,
      metrics: [...value.metrics, {function: 'count', alias: `metric_${value.metrics.length + 1}`}],
    })
  }

  const removeMetric = (index: number) => {
    onChange({...value, metrics: value.metrics.filter((_, i) => i !== index)})
  }

  const updateGroupBy = (index: number, updates: Partial<GroupByDef>) => {
    const newGroupBy = [...value.groupBy]
    newGroupBy[index] = {...newGroupBy[index], ...updates}
    onChange({...value, groupBy: newGroupBy})
  }

  const addGroupBy = () => {
    onChange({
      ...value,
      groupBy: [...value.groupBy, {field: fields[0]?.name || '', type: 'field' as const}],
    })
  }

  const removeGroupBy = (index: number) => {
    onChange({...value, groupBy: value.groupBy.filter((_, i) => i !== index)})
  }

  const updateFilter = (index: number, updates: Partial<FilterDef>) => {
    const newFilters = [...value.filters]
    newFilters[index] = {...newFilters[index], ...updates}
    onChange({...value, filters: newFilters})
  }

  const addFilter = () => {
    onChange({
      ...value,
      filters: [...value.filters, {field: fields[0]?.name || '', op: 'eq', value: ''}],
    })
  }

  const removeFilter = (index: number) => {
    onChange({...value, filters: value.filters.filter((_, i) => i !== index)})
  }

  return (
    <div className="space-y-4">
      {/* Data Source */}
      <div>
        <label className="text-xs font-medium text-muted-foreground mb-1 block">Data Source</label>
        <select
          className="w-full rounded-md border bg-background px-3 py-1.5 text-sm"
          value={value.dataSource}
          onChange={(e) => onChange({...value, dataSource: e.target.value})}
        >
          {dataSources?.map((ds) => (
            <option key={ds.name} value={ds.name}>
              {ds.label}
            </option>
          ))}
        </select>
      </div>

      {/* Metrics */}
      <div>
        <div className="flex items-center justify-between mb-1">
          <label className="text-xs font-medium text-muted-foreground">Metrics</label>
          <Button variant="ghost" size="sm" className="h-6 text-xs" onClick={addMetric}>
            <Plus className="h-3 w-3 mr-1" /> Add
          </Button>
        </div>
        <div className="space-y-2">
          {value.metrics.map((metric, i) => (
            <div key={i} className="flex items-center gap-2">
              <select
                className="rounded-md border bg-background px-2 py-1 text-xs flex-shrink-0"
                value={metric.function}
                onChange={(e) => updateMetric(i, {function: e.target.value})}
              >
                {AGG_FUNCTIONS.map((fn) => (
                  <option key={fn} value={fn}>{fn}</option>
                ))}
              </select>
              <select
                className="rounded-md border bg-background px-2 py-1 text-xs flex-1 min-w-0"
                value={metric.field || ''}
                onChange={(e) => updateMetric(i, {field: e.target.value || null})}
              >
                <option value="">(none)</option>
                {fields.filter((f) => f.type !== 'String').map((f) => (
                  <option key={f.name} value={f.name}>{f.name}</option>
                ))}
              </select>
              <input
                className="rounded-md border bg-background px-2 py-1 text-xs w-24"
                placeholder="alias"
                value={metric.alias || ''}
                onChange={(e) => updateMetric(i, {alias: e.target.value || null})}
              />
              <button onClick={() => removeMetric(i)} className="text-muted-foreground hover:text-destructive p-1">
                <Trash2 className="h-3 w-3" />
              </button>
            </div>
          ))}
        </div>
      </div>

      {/* Group By */}
      <div>
        <div className="flex items-center justify-between mb-1">
          <label className="text-xs font-medium text-muted-foreground">Group By</label>
          <div className="flex gap-1">
            <Button
              variant="ghost" size="sm" className="h-6 text-xs"
              onClick={() => onChange({
                ...value,
                groupBy: [...value.groupBy, {field: 'timestamp', type: 'time' as const, interval: 'auto'}],
              })}
            >
              <Plus className="h-3 w-3 mr-1" /> Time
            </Button>
            <Button variant="ghost" size="sm" className="h-6 text-xs" onClick={addGroupBy}>
              <Plus className="h-3 w-3 mr-1" /> Field
            </Button>
          </div>
        </div>
        <div className="space-y-2">
          {value.groupBy.map((gb, i) => (
            <div key={i} className="flex items-center gap-2">
              <span className="text-xs text-muted-foreground w-10 shrink-0">{gb.type}</span>
              {gb.type === 'time' ? (
                <select
                  className="rounded-md border bg-background px-2 py-1 text-xs flex-1"
                  value={gb.interval || 'auto'}
                  onChange={(e) => updateGroupBy(i, {interval: e.target.value})}
                >
                  <option value="auto">Auto</option>
                  <option value="1 MINUTE">1 min</option>
                  <option value="5 MINUTE">5 min</option>
                  <option value="15 MINUTE">15 min</option>
                  <option value="1 HOUR">1 hour</option>
                  <option value="1 DAY">1 day</option>
                  <option value="1 WEEK">1 week</option>
                </select>
              ) : (
                <select
                  className="rounded-md border bg-background px-2 py-1 text-xs flex-1"
                  value={gb.field}
                  onChange={(e) => updateGroupBy(i, {field: e.target.value})}
                >
                  {fields.map((f) => (
                    <option key={f.name} value={f.name}>{f.name}</option>
                  ))}
                </select>
              )}
              <button onClick={() => removeGroupBy(i)} className="text-muted-foreground hover:text-destructive p-1">
                <Trash2 className="h-3 w-3" />
              </button>
            </div>
          ))}
        </div>
      </div>

      {/* Filters */}
      <div>
        <div className="flex items-center justify-between mb-1">
          <label className="text-xs font-medium text-muted-foreground">Filters</label>
          <Button variant="ghost" size="sm" className="h-6 text-xs" onClick={addFilter}>
            <Plus className="h-3 w-3 mr-1" /> Add
          </Button>
        </div>
        <div className="space-y-2">
          {value.filters.map((filter, i) => (
            <div key={i} className="flex items-center gap-2">
              <select
                className="rounded-md border bg-background px-2 py-1 text-xs flex-1 min-w-0"
                value={filter.field}
                onChange={(e) => updateFilter(i, {field: e.target.value})}
              >
                {fields.map((f) => (
                  <option key={f.name} value={f.name}>{f.name}</option>
                ))}
              </select>
              <select
                className="rounded-md border bg-background px-2 py-1 text-xs w-20"
                value={filter.op}
                onChange={(e) => updateFilter(i, {op: e.target.value})}
              >
                {FILTER_OPS.map((op) => (
                  <option key={op.value} value={op.value}>{op.label}</option>
                ))}
              </select>
              {filter.op !== 'is_null' && filter.op !== 'is_not_null' && (
                <input
                  className="rounded-md border bg-background px-2 py-1 text-xs flex-1 min-w-0"
                  placeholder="value"
                  value={filter.value || ''}
                  onChange={(e) => updateFilter(i, {value: e.target.value})}
                />
              )}
              <button onClick={() => removeFilter(i)} className="text-muted-foreground hover:text-destructive p-1">
                <Trash2 className="h-3 w-3" />
              </button>
            </div>
          ))}
        </div>
      </div>

      {/* Limit */}
      <div>
        <label className="text-xs font-medium text-muted-foreground mb-1 block">Limit</label>
        <input
          type="number"
          className="w-24 rounded-md border bg-background px-3 py-1.5 text-sm"
          value={value.limit}
          min={1}
          max={10000}
          onChange={(e) => onChange({...value, limit: parseInt(e.target.value) || 100})}
        />
      </div>
    </div>
  )
}
