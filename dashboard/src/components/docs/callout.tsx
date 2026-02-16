// Moneat - Mobile-First Error Monitoring Platform
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

import {type ReactNode} from 'react'
import {AlertCircle, Info, Lightbulb, AlertTriangle} from 'lucide-react'
import {cn} from '@/lib/utils'

type CalloutVariant = 'info' | 'tip' | 'warning' | 'danger'

const variants: Record<CalloutVariant, {icon: typeof Info; className: string; label: string}> = {
  info: {
    icon: Info,
    className: 'border-blue-500/30 bg-blue-500/5 text-blue-200',
    label: 'Info',
  },
  tip: {
    icon: Lightbulb,
    className: 'border-emerald-500/30 bg-emerald-500/5 text-emerald-200',
    label: 'Tip',
  },
  warning: {
    icon: AlertTriangle,
    className: 'border-amber-500/30 bg-amber-500/5 text-amber-200',
    label: 'Warning',
  },
  danger: {
    icon: AlertCircle,
    className: 'border-red-500/30 bg-red-500/5 text-red-200',
    label: 'Important',
  },
}

interface CalloutProps {
  variant?: CalloutVariant
  title?: string
  children: ReactNode
}

export function Callout({variant = 'info', title, children}: CalloutProps) {
  const config = variants[variant]
  const Icon = config.icon

  return (
    <div className={cn('rounded-lg border p-4', config.className)}>
      <div className="flex gap-3">
        <Icon className="h-5 w-5 shrink-0 mt-0.5" />
        <div className="space-y-1">
          {title && <p className="font-medium text-sm">{title}</p>}
          <div className="text-sm leading-relaxed opacity-90">{children}</div>
        </div>
      </div>
    </div>
  )
}
