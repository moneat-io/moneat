// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY IMPLIED WARRANTY OF MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
// See the GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import {createContext, useContext, useState, useCallback, type ReactNode} from 'react'

interface CommandPaletteContextValue {
  open: boolean
  setOpen: (open: boolean | ((prev: boolean) => boolean)) => void
  openPalette: () => void
}

const CommandPaletteContext = createContext<CommandPaletteContextValue | null>(null)

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

export function useCommandPalette() {
  const ctx = useContext(CommandPaletteContext)
  if (!ctx) return null
  return ctx
}
