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

import {useEffect, useRef, useState} from 'react'
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

function displayAmount(totalSeconds: number, unit: DurationUnit): string {
  return String(totalSeconds === 0 ? 0 : Math.round(totalSeconds / UNIT_SECONDS[unit]))
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
  const initialUnit = bestUnit(seconds)
  const [unit, setUnit] = useState<DurationUnit>(initialUnit)
  const [amount, setAmount] = useState(() => displayAmount(seconds, initialUnit))
  const pendingSeconds = useRef<number | null>(null)

  useEffect(() => {
    if (pendingSeconds.current === seconds) {
      pendingSeconds.current = null
      return
    }
    const nextUnit = bestUnit(seconds)
    setUnit(nextUnit)
    setAmount(displayAmount(seconds, nextUnit))
  }, [seconds])

  const commit = (nextAmount: number, nextUnit: DurationUnit) => {
    const normalizedAmount = Math.max(0, Math.round(nextAmount))
    const raw = normalizedAmount * UNIT_SECONDS[nextUnit]
    const nextSeconds = Math.max(minSeconds, raw)
    const committedUnit = raw < minSeconds ? bestUnit(nextSeconds) : nextUnit
    setUnit(committedUnit)
    setAmount(displayAmount(nextSeconds, committedUnit))
    pendingSeconds.current = nextSeconds
    onChange(nextSeconds)
  }

  const onAmountChange = (rawAmount: string) => {
    setAmount(rawAmount)
    if (rawAmount.trim() === '') return
    const parsed = Number(rawAmount)
    if (Number.isFinite(parsed)) commit(parsed, unit)
  }

  const onAmountBlur = () => {
    if (amount.trim() === '' || !Number.isFinite(Number(amount))) {
      setAmount(displayAmount(seconds, unit))
    }
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
        onChange={(event) => onAmountChange(event.target.value)}
        onBlur={onAmountBlur}
      />
      <Select
        value={unit}
        onValueChange={(value) => {
          const nextUnit = value as DurationUnit
          const parsed = Number(amount)
          if (amount.trim() !== '' && Number.isFinite(parsed)) {
            commit(parsed, nextUnit)
          } else {
            setUnit(nextUnit)
            setAmount(displayAmount(seconds, nextUnit))
          }
        }}
      >
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
