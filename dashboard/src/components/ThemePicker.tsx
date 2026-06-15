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

import {Check} from 'lucide-react'
import {cn} from '@/lib/utils'
import {THEME_OPTIONS, useTheme} from '@/lib/theme'

/**
 * Expanded, inline variant of the theme picker for roomy surfaces (Settings).
 * Shares the same theme engine as the sidebar {@link ThemeSwitcher}, so a change
 * here is reflected there instantly.
 */
export function ThemePicker() {
  const {theme, setTheme} = useTheme()

  return (
    <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
      {THEME_OPTIONS.map((option) => {
        const Icon = option.icon
        const active = theme === option.id
        return (
          <button
            key={option.id}
            type="button"
            onClick={() => setTheme(option.id)}
            aria-pressed={active}
            className={cn(
              'flex h-[34px] items-center gap-2.5 rounded-md border px-2.5 text-sm font-medium transition-colors',
              active
                ? 'border-primary bg-primary/10 text-foreground'
                : 'text-muted-foreground hover:bg-muted/60 hover:text-foreground'
            )}
          >
            <Icon className="h-4 w-4 shrink-0" />
            <span className="flex-1 text-left">{option.label}</span>
            {active && <Check className="h-4 w-4 shrink-0 text-primary" />}
          </button>
        )
      })}
    </div>
  )
}
