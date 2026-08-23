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

import {Input} from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'

type DurationUnit = 'seconds' | 'minutes' | 'hours'

const UNIT_SECONDS: Record<DurationUnit, number> = {
  seconds: 1,
  minutes: 60,
  hours: 3600,
}

// Pick the largest unit that represents the value without a fractional part, so a
// stored 300s reads as "5 minutes" but 90s stays "90 seconds".
function bestUnit(totalSeconds: number): DurationUnit {
  if (totalSeconds > 0 && totalSeconds % UNIT_SECONDS.hours === 0) return 'hours'
  if (totalSeconds > 0 && totalSeconds % UNIT_SECONDS.minutes === 0) return 'minutes'
  return 'seconds'
}

interface DurationFieldProps {
  id?: string
  ariaLabel: string
  seconds: number
  minSeconds?: number
  onChange: (seconds: number) => void
}

// A numeric amount paired with a unit that together resolve to whole seconds — the
// wire unit for grouping windows and recovery grace periods.
export function DurationField({id, ariaLabel, seconds, minSeconds = 0, onChange}: Readonly<DurationFieldProps>) {
  const unit = bestUnit(seconds)
  const amount = seconds === 0 ? 0 : Math.round(seconds / UNIT_SECONDS[unit])

  const commit = (nextAmount: number, nextUnit: DurationUnit) => {
    const raw = Math.max(0, Math.round(nextAmount)) * UNIT_SECONDS[nextUnit]
    onChange(Math.max(minSeconds, raw))
  }

  return (
    <div className="flex items-center gap-2">
      <Input
        id={id}
        type="number"
        min={0}
        aria-label={`${ariaLabel} amount`}
        className="h-8 w-24"
        value={amount}
        onChange={(event) => commit(Number(event.target.value), unit)}
      />
      <Select value={unit} onValueChange={(value) => commit(amount, value as DurationUnit)}>
        <SelectTrigger className="h-8 w-32" aria-label={`${ariaLabel} unit`}>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value="seconds">seconds</SelectItem>
          <SelectItem value="minutes">minutes</SelectItem>
          <SelectItem value="hours">hours</SelectItem>
        </SelectContent>
      </Select>
    </div>
  )
}
