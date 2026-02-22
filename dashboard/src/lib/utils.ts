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
  
  // Handle ClickHouse DateTime format (YYYY-MM-DD HH:MM:SS) as UTC
  let date: Date
  if (typeof dateValue === 'number') {
    date = new Date(dateValue)
  } else if (dateValue.includes('T')) {
    date = new Date(dateValue)
  } else {
    date = new Date(dateValue + ' UTC')
  }
  
  const now = getNowDate()
  const seconds = Math.floor((now.getTime() - date.getTime()) / 1000)

  if (seconds < 60) return 'just now'
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`
  if (seconds < 604800) return `${Math.floor(seconds / 86400)}d ago`
  return formatDate(date, timezone ?? browserTimezone())
}
