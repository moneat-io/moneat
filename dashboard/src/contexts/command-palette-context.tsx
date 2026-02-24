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

import {useState, useCallback, type ReactNode} from 'react'
import {CommandPaletteContext} from '@/contexts/CommandPaletteContext'

export function CommandPaletteProvider({children}: {children: ReactNode}) {
  const [open, setOpen] = useState(false)
  const openPalette = useCallback(() => setOpen(true), [])
  const setOpenValue = useCallback(
    (value: boolean | ((prev: boolean) => boolean)) =>
      setOpen(typeof value === 'function' ? value : () => value),
    [],
  )
  return (
    <CommandPaletteContext.Provider value={{open, setOpen: setOpenValue, openPalette}}>
      {children}
    </CommandPaletteContext.Provider>
  )
}
