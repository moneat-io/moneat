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
import {GRADIENT_BG} from '@/components/landing/Landing'

// Shared form-control styling for the auth pages: the internal-style-guide form
// (crisp, labeled, indigo focus halo) rendered on the dark, gradient-accented
// public canvas. Kept in a plain module so the brand gradient stays sourced from
// the Landing kit rather than duplicated as hex.
export const authInputClass = cn(
  'h-11 rounded-md border border-white/10 bg-white/[0.03] px-3.5 text-sm text-slate-100 shadow-none',
  'placeholder:text-slate-500 focus-visible:border-indigo-400/70 focus-visible:ring-2 focus-visible:ring-indigo-500/25',
)

export const authLabelClass = 'text-[13px] font-medium text-slate-200'

export const authPrimaryButtonClass = cn(
  'h-11 w-full border-0 text-white shadow-[0_10px_28px_-10px_#6366F1] transition hover:brightness-110',
  GRADIENT_BG,
)

export const authSecondaryButtonClass = cn(
  'h-11 w-full border-white/15 bg-white/[0.03] text-slate-100 hover:bg-white/[0.07] hover:text-white',
)
