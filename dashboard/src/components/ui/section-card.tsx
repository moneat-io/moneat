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

import * as React from "react"

import {cn} from "@/lib/utils"
import {Card} from "@/components/ui/card"
import type {StatusTone} from "@/components/ui/status-dot"

type IconTone = StatusTone | "muted"

const iconToneText: Record<IconTone, string> = {
  muted: "text-muted-foreground",
  success: "text-success-fg",
  warning: "text-warning-fg",
  danger: "text-danger-fg",
  info: "text-info-fg",
  neutral: "text-muted-foreground",
  accent: "text-primary",
}

export interface SectionCardProps {
  readonly title: React.ReactNode
  readonly icon?: React.ComponentType<{className?: string}>
  readonly iconTone?: IconTone
  /** Small count/label chip rendered after the title. */
  readonly count?: React.ReactNode
  /** Right-aligned header actions. */
  readonly actions?: React.ReactNode
  readonly className?: string
  readonly bodyClassName?: string
  /** Drop body padding — for embedded tables, logs, waterfalls, etc. */
  readonly flushBody?: boolean
  readonly children: React.ReactNode
}

/** A titled card with the style guide's header (icon tile + title + count + actions)
 * over a body. The workhorse container for detail-page sections. */
export function SectionCard({
  title,
  icon: Icon,
  iconTone = "muted",
  count,
  actions,
  className,
  bodyClassName,
  flushBody = false,
  children,
}: SectionCardProps) {
  return (
    <Card className={cn("overflow-hidden", className)}>
      <div className="flex items-center justify-between gap-2 border-b px-4 py-2.5">
        <div className="flex min-w-0 items-center gap-2">
          {Icon && (
            <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-muted">
              <Icon className={cn("h-4 w-4", iconToneText[iconTone])} />
            </span>
          )}
          <span className="truncate text-sm font-semibold">{title}</span>
          {count !== undefined && count !== null && (
            <span className="rounded-full bg-muted px-1.5 py-0.5 text-[11px] font-medium tabular-nums text-muted-foreground">
              {count}
            </span>
          )}
        </div>
        {actions && <div className="flex shrink-0 items-center gap-1.5">{actions}</div>}
      </div>
      <div className={cn(flushBody ? "" : "px-4 py-3", bodyClassName)}>{children}</div>
    </Card>
  )
}
