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
import {ArrowDown, ArrowUp} from "lucide-react"

import {cn} from "@/lib/utils"
import {Card} from "@/components/ui/card"
import type {StatusTone} from "@/components/ui/status-dot"

const toneText: Record<StatusTone, string> = {
  success: "text-success-fg",
  warning: "text-warning-fg",
  danger: "text-danger-fg",
  info: "text-info-fg",
  neutral: "text-muted-foreground",
  accent: "text-primary",
}

export interface StatDelta {
  readonly value: string
  readonly direction: "up" | "down" | "flat"
  /** Override the colour of the delta. Defaults: up→success, down→danger, flat→muted. */
  readonly tone?: StatusTone
}

export interface StatCardProps extends React.HTMLAttributes<HTMLDivElement> {
  readonly label: string
  readonly value: React.ReactNode
  /** Small trailing unit rendered muted next to the value, e.g. "ms" or "%". */
  readonly unit?: string
  readonly icon?: React.ComponentType<{className?: string}>
  /** Tints the icon / leading accent. */
  readonly tone?: StatusTone
  readonly delta?: StatDelta
  readonly subtitle?: string
}

/** A metric tile: one number with a label, optional trend and icon (style guide). */
export function StatCard({
  label,
  value,
  unit,
  icon: Icon,
  tone = "accent",
  delta,
  subtitle,
  className,
  ...props
}: StatCardProps) {
  const deltaTone: StatusTone =
    delta?.tone ??
    (delta?.direction === "up"
      ? "success"
      : delta?.direction === "down"
        ? "danger"
        : "neutral")

  return (
    <Card className={cn("p-4", className)} {...props}>
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
          {label}
        </span>
        {Icon && <Icon className={cn("h-4 w-4 shrink-0", toneText[tone])} />}
      </div>
      <div className="mt-1.5 flex items-baseline gap-1">
        <span className="text-2xl font-semibold tabular-nums tracking-tight">
          {value}
        </span>
        {unit && <span className="text-sm text-muted-foreground">{unit}</span>}
      </div>
      <div className="mt-1 flex items-center gap-2">
        {delta && (
          <span
            className={cn(
              "inline-flex items-center gap-0.5 text-xs font-medium tabular-nums",
              toneText[deltaTone]
            )}
          >
            {delta.direction === "up" && <ArrowUp className="h-3 w-3" />}
            {delta.direction === "down" && <ArrowDown className="h-3 w-3" />}
            {delta.value}
          </span>
        )}
        {subtitle && (
          <span className="text-xs text-muted-foreground">{subtitle}</span>
        )}
      </div>
    </Card>
  )
}
