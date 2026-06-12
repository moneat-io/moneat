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

import {SOURCE_LOGO} from './dataSourceCatalog'

interface DataSourceLogoProps {
  readonly type: string
  readonly size?: number
}

/** Compact branded monogram tile for a data-source type. */
export function DataSourceLogo({type, size = 22}: DataSourceLogoProps) {
  const logo = SOURCE_LOGO[type] ?? {mono: '?', bg: 'hsl(var(--muted))', fg: 'hsl(var(--muted-foreground))'}
  return (
    <span
      aria-hidden="true"
      className="inline-grid shrink-0 place-items-center rounded font-extrabold tracking-tighter"
      style={{
        width: size,
        height: size,
        background: logo.bg,
        color: logo.fg ?? '#fff',
        fontSize: Math.round(size * 0.42),
      }}
    >
      {logo.mono}
    </span>
  )
}
