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

import {cn} from '@/lib/utils'
import type {ReactNode} from 'react'

/** Uppercase section divider label with a trailing rule. */
export function SecLabel({children, className}: {children: ReactNode; className?: string}) {
  return (
    <div className={cn('mb-2 flex items-center gap-2', className)}>
      <span className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
        {children}
      </span>
      <span className="h-px flex-1 bg-border/70" />
    </div>
  )
}

/** Muted helper row shown at the bottom of a panel. */
export function Hint({icon, children}: {icon?: ReactNode; children: ReactNode}) {
  return (
    <div className="mt-2.5 flex items-start gap-2 text-[11px] text-muted-foreground/80">
      {icon && <span className="mt-px shrink-0">{icon}</span>}
      <span>{children}</span>
    </div>
  )
}
