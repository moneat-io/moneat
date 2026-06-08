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

import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {cn} from '@/lib/utils'
import {logLevelBadgeClass} from '@/lib/severity'
import {GROUP_BY_OPTIONS, KNOWN_LEVELS} from '@/components/logs/logManagementShared'

/** Read-only chips echoing the active level filter, collapsing to "All levels"
 * when nothing (or everything) is selected. Shared by the management sheet and
 * the Explorer create dialogs. */
export function LevelChips({levels}: {readonly levels: string[]}) {
  const active = Array.from(new Set(levels.map((level) => level.trim()).filter((level) => level !== '')))
  const hasAllKnownLevels = KNOWN_LEVELS.every((knownLevel) => active.includes(knownLevel))
  if (active.length === 0 || hasAllKnownLevels) {
    return <span className="text-[11px] text-muted-foreground">All levels</span>
  }
  return (
    <div className="flex flex-wrap items-center gap-1">
      {active.map((level) => (
        <span
          key={level}
          className={cn(
            'rounded border px-1.5 py-px font-mono text-[10px] uppercase leading-4',
            logLevelBadgeClass(level)
          )}
        >
          {level}
        </span>
      ))}
    </div>
  )
}

export function GroupBySelect({
  value,
  onChange,
}: {
  readonly value: string
  readonly onChange: (next: string) => void
}) {
  return (
    <Select value={value} onValueChange={onChange}>
      <SelectTrigger className="h-9">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {GROUP_BY_OPTIONS.map((option) => (
          <SelectItem key={option.value} value={option.value}>
            {option.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  )
}
