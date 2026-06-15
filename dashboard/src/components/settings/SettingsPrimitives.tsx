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

import * as React from 'react'

import {cn} from '@/lib/utils'

/**
 * Compact settings building blocks shared across every Settings view.
 * They map the target-state design language (section head → blocks → label/control
 * rows) onto the app's semantic tokens so density and theme stay one-line changes.
 */

export interface SettingsSectionProps {
  readonly title: React.ReactNode
  readonly description?: React.ReactNode
  readonly actions?: React.ReactNode
  readonly className?: string
}

/** Title + description on the left, actions on the right, with a bottom rule. */
export function SettingsSection({title, description, actions, className}: SettingsSectionProps) {
  return (
    <div
      className={cn(
        'mb-5 flex flex-col items-start justify-between gap-3 border-b pb-3.5 sm:flex-row sm:items-start',
        className
      )}
    >
      <div className="min-w-0">
        <h2 className="text-lg font-semibold tracking-tight">{title}</h2>
        {description && (
          <p className="mt-1 max-w-[64ch] text-sm text-muted-foreground">{description}</p>
        )}
      </div>
      {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
    </div>
  )
}

export interface SettingsBlockProps {
  readonly title: React.ReactNode
  /** Optional right-aligned controls in the block heading. */
  readonly actions?: React.ReactNode
  readonly hint?: React.ReactNode
  readonly className?: string
  readonly children: React.ReactNode
}

/** An uppercase mini-heading over a stack of setting rows. */
export function SettingsBlock({title, actions, hint, className, children}: SettingsBlockProps) {
  return (
    <section className={cn('mb-7', className)}>
      <div className="mb-1 flex items-center gap-2">
        <h3 className="text-[11px] font-bold uppercase tracking-[0.06em] text-muted-foreground/80">
          {title}
        </h3>
        {actions && <div className="ml-auto flex items-center gap-1.5">{actions}</div>}
      </div>
      {hint && <p className="mb-2.5 text-xs text-muted-foreground">{hint}</p>}
      {children}
    </section>
  )
}

export interface SettingRowProps {
  readonly label: React.ReactNode
  readonly description?: React.ReactNode
  readonly children?: React.ReactNode
  /** Top-align the control instead of centering (textareas, cert blobs, etc.). */
  readonly align?: 'center' | 'start'
  readonly className?: string
}

/** Label + description on the left; control(s) right-aligned on the right. */
export function SettingRow({label, description, children, align = 'center', className}: SettingRowProps) {
  return (
    <div
      className={cn(
        'grid grid-cols-1 gap-2 border-t py-3 first:border-t-0 sm:grid-cols-[minmax(0,1fr)_minmax(200px,320px)] sm:gap-7',
        align === 'start' ? 'sm:items-start' : 'sm:items-center',
        className
      )}
    >
      <div className="min-w-0">
        <div className="text-sm font-medium">{label}</div>
        {description && (
          <div className="mt-0.5 max-w-[56ch] text-xs leading-relaxed text-muted-foreground">
            {description}
          </div>
        )}
      </div>
      {children !== undefined && (
        <div className="flex items-center gap-2 sm:justify-end">{children}</div>
      )}
    </div>
  )
}
