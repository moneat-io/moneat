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

import {type ClassValue, clsx} from "clsx"
import {twMerge} from "tailwind-merge"
import {getNowDate} from './demo'
import {browserTimezone, formatDate} from './date-format'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function formatRelativeTime(dateValue: string | number | undefined, timezone?: string): string {
  if (!dateValue) return 'unknown'

  const date = parseRelativeDate(dateValue)
  if (!Number.isFinite(date.getTime())) return 'unknown'

  const now = getNowDate()
  const seconds = Math.floor((now.getTime() - date.getTime()) / 1000)

  if (seconds < 60) return 'just now'
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`
  if (seconds < 604800) return `${Math.floor(seconds / 86400)}d ago`
  return formatDate(date, timezone ?? browserTimezone())
}

function parseRelativeDate(dateValue: string | number): Date {
  if (typeof dateValue === 'number') return new Date(dateValue)
  return new Date(normalizeRelativeDateString(dateValue))
}

function normalizeRelativeDateString(value: string): string {
  const trimmed = value.trim()
  if (trimmed.includes('T')) return trimmed

  const separatorIndex = trimmed.indexOf(' ')
  if (separatorIndex > 0) {
    const datePart = trimmed.slice(0, separatorIndex)
    const timePart = trimmed.slice(separatorIndex + 1)
    if (isIsoDatePart(datePart) && isClockTimePart(timePart)) {
      return `${datePart}T${timePart}Z`
    }
  }

  return `${trimmed} UTC`
}

function isIsoDatePart(value: string): boolean {
  return value.length === 10 &&
    isDigit(value[0]) &&
    isDigit(value[1]) &&
    isDigit(value[2]) &&
    isDigit(value[3]) &&
    value[4] === '-' &&
    isDigit(value[5]) &&
    isDigit(value[6]) &&
    value[7] === '-' &&
    isDigit(value[8]) &&
    isDigit(value[9])
}

function isClockTimePart(value: string): boolean {
  const timeAndFraction = value.split('.')
  if (timeAndFraction.length > 2) return false

  const timePart = timeAndFraction[0]
  const fractionPart = timeAndFraction[1]
  const hasValidTime = timePart.length === 8 &&
    isDigit(timePart[0]) &&
    isDigit(timePart[1]) &&
    timePart[2] === ':' &&
    isDigit(timePart[3]) &&
    isDigit(timePart[4]) &&
    timePart[5] === ':' &&
    isDigit(timePart[6]) &&
    isDigit(timePart[7])

  if (!hasValidTime) return false
  return fractionPart === undefined || isDigitString(fractionPart)
}

function isDigitString(value: string): boolean {
  return value.length > 0 && Array.from(value).every(isDigit)
}

function isDigit(value: string | undefined): boolean {
  return value !== undefined && value >= '0' && value <= '9'
}
