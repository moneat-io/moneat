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

import type {StatusTone} from '@/components/ui/status-dot'
import type {Tone} from './overviewMockData'

// Tone -> design-system class maps. The mockup's --scale-good/warn/bad collapse
// onto the app's shared status language (success / warning / danger / neutral).
export const toneText: Record<Tone, string> = {
  good: 'text-success-fg',
  warn: 'text-warning-fg',
  bad: 'text-danger-fg',
  neutral: 'text-muted-foreground',
}
export const toneBgSolid: Record<Tone, string> = {
  good: 'bg-success-solid',
  warn: 'bg-warning-solid',
  bad: 'bg-danger-solid',
  neutral: 'bg-muted-foreground/70',
}
export const toneSoftBg: Record<Tone, string> = {
  good: 'bg-success-bg',
  warn: 'bg-warning-bg',
  bad: 'bg-danger-bg',
  neutral: 'bg-muted',
}
export const toneBorder: Record<Tone, string> = {
  good: 'border-success-border',
  warn: 'border-warning-border',
  bad: 'border-danger-border',
  neutral: 'border-border',
}
export const toneDot: Record<Tone, StatusTone> = {
  good: 'success',
  warn: 'warning',
  bad: 'danger',
  neutral: 'neutral',
}
