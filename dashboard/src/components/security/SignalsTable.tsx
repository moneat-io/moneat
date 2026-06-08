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

import type {KeyboardEvent} from 'react'
import type {SignalResponse} from '@/lib/api'
import {Badge} from '@/components/ui/badge'
import {cn} from '@/lib/utils'
import {colorFor, severityColors, STATUS_LABELS, statusColors} from './securityChips'

interface SignalsTableProps {
  signals: SignalResponse[]
  selectedId?: number
  onSelect: (signal: SignalResponse) => void
}

export function SignalsTable({signals, selectedId, onSelect}: SignalsTableProps) {
  function activateSignal(event: KeyboardEvent<HTMLTableRowElement>, signal: SignalResponse) {
    if (event.key !== 'Enter' && event.key !== ' ') return
    event.preventDefault()
    onSelect(signal)
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs">
        <thead>
          <tr className="border-b text-left text-muted-foreground">
            <th className="px-4 py-2 font-medium">Severity</th>
            <th className="px-4 py-2 font-medium">Signal</th>
            <th className="px-4 py-2 font-medium">Status</th>
            <th className="px-4 py-2 font-medium">Source</th>
            <th className="px-4 py-2 font-medium text-right">Samples</th>
            <th className="px-4 py-2 font-medium">Last seen</th>
          </tr>
        </thead>
        <tbody>
          {signals.map((s) => (
            <tr
              key={s.id}
              role="button"
              tabIndex={0}
              aria-label={`Open signal ${s.rule_name}`}
              onClick={() => onSelect(s)}
              onKeyDown={(event) => activateSignal(event, s)}
              className={cn(
                'cursor-pointer border-b outline-none last:border-0 hover:bg-muted/30 focus-visible:bg-muted/40',
                'focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring',
                selectedId === s.id && 'bg-muted/40'
              )}>
              <td className="px-4 py-2">
                <Badge variant="outline" className={cn('text-[10px]', colorFor(severityColors, s.severity))}>
                  {s.severity}
                </Badge>
              </td>
              <td className="px-4 py-2 font-medium">{s.rule_name}</td>
              <td className="px-4 py-2">
                <Badge variant="outline" className={cn('text-[10px]', colorFor(statusColors, s.status))}>
                  {STATUS_LABELS[s.status]}
                </Badge>
              </td>
              <td className="px-4 py-2 text-muted-foreground">{s.source.replace(/_/g, ' ')}</td>
              <td className="px-4 py-2 text-right tabular-nums">{s.sample_count}</td>
              <td className="px-4 py-2 text-muted-foreground">{s.last_seen}</td>
            </tr>
          ))}
          {signals.length === 0 && (
            <tr>
              <td colSpan={6} className="py-6 text-center text-muted-foreground">
                No signals
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}
