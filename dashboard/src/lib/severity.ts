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

import type {StatusTone} from "@/components/ui/status-dot"

// Single source of truth for mapping log / issue severity levels to the shared
// status language, replacing the per-page color helpers that used to duplicate
// (and drift on) these mappings.

export type SeverityBadgeVariant =
  | "dangerSolid"
  | "danger"
  | "warning"
  | "info"
  | "neutral"
  | "success"

export function normalizeLevel(level?: string | null): string {
  return (level ?? "").toString().trim().toLowerCase()
}

/** Map a log/issue level to a {@link Badge} variant. */
export function levelBadgeVariant(level?: string | null): SeverityBadgeVariant {
  switch (normalizeLevel(level)) {
    case "fatal":
    case "critical":
      return "dangerSolid"
    case "error":
      return "danger"
    case "warning":
    case "warn":
      return "warning"
    case "info":
    case "information":
      return "info"
    default:
      return "neutral"
  }
}

/** Map a log/issue level to a status tone (dots, left-border indicators). */
export function levelTone(level?: string | null): StatusTone {
  switch (normalizeLevel(level)) {
    case "fatal":
    case "critical":
    case "error":
      return "danger"
    case "warning":
    case "warn":
      return "warning"
    case "info":
    case "information":
      return "info"
    default:
      return "neutral"
  }
}

/** Soft level-badge classes (bg + text + border) in the shared status language.
 * Used by the logs explorer, detail panel and search bar so the level chips look
 * identical everywhere instead of each maintaining its own color map. */
export function logLevelBadgeClass(level?: string | null): string {
  switch (normalizeLevel(level)) {
    case "fatal":
    case "critical":
      return "bg-danger-solid text-white border-transparent"
    case "error":
      return "bg-danger-bg text-danger-fg border-danger-border"
    case "warn":
    case "warning":
      return "bg-warning-bg text-warning-fg border-warning-border"
    case "info":
    case "information":
      return "bg-info-bg text-info-fg border-info-border"
    default:
      return "bg-muted text-muted-foreground border-border"
  }
}

/** Tailwind left-border class for a level (replaces per-page getLevelBorderColor). */
export function levelBorderClass(level?: string | null): string {
  switch (levelTone(level)) {
    case "danger":
      return "border-l-danger-solid"
    case "warning":
      return "border-l-warning-solid"
    case "info":
      return "border-l-info-solid"
    default:
      return "border-l-border"
  }
}
