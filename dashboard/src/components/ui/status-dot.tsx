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

// One status language across the app (style guide). Pair color with text/label
// so meaning survives for colorblind users and in grayscale.
export type StatusTone =
  | "success"
  | "warning"
  | "danger"
  | "info"
  | "neutral"
  | "accent"

const toneBg: Record<StatusTone, string> = {
  success: "bg-success-solid",
  warning: "bg-warning-solid",
  danger: "bg-danger-solid",
  info: "bg-info-solid",
  neutral: "bg-muted-foreground/70",
  accent: "bg-primary",
}

const sizePx: Record<NonNullable<StatusDotProps["size"]>, string> = {
  sm: "h-1.5 w-1.5",
  default: "h-2 w-2",
  lg: "h-2.5 w-2.5",
}

export interface StatusDotProps extends React.HTMLAttributes<HTMLSpanElement> {
  readonly tone?: StatusTone
  readonly size?: "sm" | "default" | "lg"
  /** Animated ping ring — use sparingly for live/incident states. */
  readonly pulse?: boolean
}

/** A compact status indicator dot, optionally pulsing. */
export function StatusDot({
  tone = "neutral",
  size = "default",
  pulse = false,
  className,
  ...props
}: StatusDotProps) {
  return (
    <span
      className={cn("relative inline-flex shrink-0", sizePx[size], className)}
      {...props}
    >
      {pulse && (
        <span
          className={cn(
            "absolute inset-0 animate-ping rounded-full opacity-75",
            toneBg[tone]
          )}
          aria-hidden="true"
        />
      )}
      <span
        className={cn("relative inline-flex rounded-full", sizePx[size], toneBg[tone])}
      />
    </span>
  )
}
