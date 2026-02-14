import {useState} from 'react'
import type {LogTopValue} from '@/lib/api'
import {cn} from '@/lib/utils'
import {ArrowDown, ArrowUp} from 'lucide-react'

interface LogAggregateTableProps {
  values: LogTopValue[]
  totalCount: number
  field: string
  onValueClick?: (value: string) => void
}

function formatCount(n: number): string {
  if (n >= 1_000_000_000) return `${(n / 1_000_000_000).toFixed(1)}B`
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`
  return String(n)
}

type SortField = 'value' | 'count' | 'percentage'
type SortDir = 'asc' | 'desc'

export function LogAggregateTable({values, totalCount, field, onValueClick}: LogAggregateTableProps) {
  const [sortField, setSortField] = useState<SortField>('count')
  const [sortDir, setSortDir] = useState<SortDir>('desc')

  const toggleSort = (f: SortField) => {
    if (sortField === f) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortField(f)
      setSortDir('desc')
    }
  }

  const sorted = [...values].sort((a, b) => {
    const dir = sortDir === 'asc' ? 1 : -1
    if (sortField === 'value') return dir * a.value.localeCompare(b.value)
    return dir * (a.count - b.count)
  })

  const SortIcon = ({f}: {f: SortField}) => {
    if (sortField !== f) return null
    return sortDir === 'asc' ? <ArrowUp className="h-3 w-3" /> : <ArrowDown className="h-3 w-3" />
  }

  if (values.length === 0) {
    return (
      <div className="flex items-center justify-center py-12 text-sm text-muted-foreground">
        No data for field "{field}"
      </div>
    )
  }

  return (
    <div className="overflow-auto">
      <table className="w-full text-sm">
        <thead className="sticky top-0 z-10 bg-card/95 backdrop-blur-sm">
          <tr className="border-b text-left">
            <th
              className="cursor-pointer whitespace-nowrap px-3 py-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground hover:text-foreground"
              onClick={() => toggleSort('value')}
            >
              <span className="flex items-center gap-1">
                {field} <SortIcon f="value" />
              </span>
            </th>
            <th
              className="cursor-pointer whitespace-nowrap px-3 py-1.5 text-right text-[10px] font-semibold uppercase tracking-wider text-muted-foreground hover:text-foreground"
              onClick={() => toggleSort('count')}
            >
              <span className="flex items-center justify-end gap-1">
                Count <SortIcon f="count" />
              </span>
            </th>
            <th className="whitespace-nowrap px-3 py-1.5 text-right text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
              %
            </th>
            <th className="w-[100px] px-3 py-1.5" />
          </tr>
        </thead>
        <tbody className="divide-y divide-border/50">
          {sorted.map((item) => {
            const pct = totalCount > 0 ? (item.count / totalCount) * 100 : 0
            return (
              <tr
                key={item.value}
                className={cn(
                  'cursor-pointer transition-colors hover:bg-accent/40'
                )}
                onClick={() => onValueClick?.(item.value)}
              >
                <td className="px-3 py-1.5 font-mono text-xs">{item.value}</td>
                <td className="px-3 py-1.5 text-right font-mono text-[10px] text-muted-foreground">
                  {formatCount(item.count)}
                </td>
                <td className="px-3 py-1.5 text-right font-mono text-[10px] text-muted-foreground/70">
                  {pct.toFixed(1)}%
                </td>
                <td className="px-3 py-1.5">
                  <div className="h-1.5 w-full rounded-full bg-muted">
                    <div
                      className="h-1.5 rounded-full bg-primary/50"
                      style={{width: `${pct}%`}}
                    />
                  </div>
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
