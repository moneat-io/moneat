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

import {AlertTriangle} from 'lucide-react'
import {formatErrorForLogging} from '@/lib/api'

interface SecurityErrorProps {
  title: string
  error: unknown
}

// Shared error state for the security tabs so a failed query reads as an outage/permission problem
// rather than an empty result. Distinct from the "no rules/signals/events" empty states.
export function SecurityError({title, error}: SecurityErrorProps) {
  return (
    <div className="flex flex-col items-center gap-1.5 rounded-md border border-destructive/30 bg-destructive/5 py-8 text-center">
      <AlertTriangle className="h-5 w-5 text-destructive" />
      <p className="text-xs font-medium text-destructive">{title}</p>
      <p className="max-w-md text-[11px] text-muted-foreground">{formatErrorForLogging(error)}</p>
    </div>
  )
}
