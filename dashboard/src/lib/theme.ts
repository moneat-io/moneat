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

import {useEffect, useSyncExternalStore} from 'react'
import {
  CloudMoon,
  Gamepad2,
  Leaf,
  Monitor,
  Moon,
  Newspaper,
  Sun,
  Sunset,
  Terminal,
} from 'lucide-react'
import type {LucideIcon} from 'lucide-react'

export type Theme =
  | 'light'
  | 'dark'
  | 'midnight'
  | 'forest'
  | 'sunset'
  | 'gamer'
  | 'retro'
  | 'retro-dark'
  | 'terminal'

export interface ThemeOption {
  readonly id: Theme
  readonly label: string
  readonly icon: LucideIcon
}

/** The full set of selectable themes, shared by every theme control. */
export const THEME_OPTIONS: readonly ThemeOption[] = [
  {id: 'light', label: 'Light', icon: Sun},
  {id: 'dark', label: 'Dark', icon: Moon},
  {id: 'midnight', label: 'Midnight', icon: CloudMoon},
  {id: 'forest', label: 'Forest', icon: Leaf},
  {id: 'sunset', label: 'Sunset', icon: Sunset},
  {id: 'gamer', label: 'Gamer', icon: Gamepad2},
  {id: 'retro', label: 'Retro', icon: Newspaper},
  {id: 'retro-dark', label: 'Retro Dark', icon: Monitor},
  {id: 'terminal', label: 'Terminal', icon: Terminal},
]

const DEFAULT_THEME: Theme = 'dark'
const THEME_IDS = new Set<string>(THEME_OPTIONS.map((option) => option.id))

const THEME_CLASSES = [
  'light',
  'dark',
  'theme-midnight',
  'theme-forest',
  'theme-sunset',
  'theme-gamer',
  'theme-retro',
  'theme-retro-dark',
  'theme-terminal',
]

// Themes that pull in a webfont the first time they're applied.
const THEME_FONTS: Partial<Record<Theme, {id: string; href: string}>> = {
  gamer: {id: 'vt323-font', href: 'https://fonts.googleapis.com/css2?family=VT323&display=swap'},
  terminal: {
    id: 'ibm-plex-mono-font',
    href: 'https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600;700&display=swap',
  },
}

function loadThemeFont(theme: Theme) {
  const font = THEME_FONTS[theme]
  const doc = globalThis.document
  if (!font || !doc || doc.getElementById(font.id)) return
  const link = doc.createElement('link')
  link.id = font.id
  link.rel = 'stylesheet'
  link.href = font.href
  doc.head.appendChild(link)
}

/** Apply a theme to <html> by toggling the appropriate classes (+ any webfont). */
export function applyTheme(theme: Theme) {
  const root = globalThis.document?.documentElement
  if (!root) return
  root.classList.remove(...THEME_CLASSES)
  if (theme === 'dark') {
    root.classList.add('dark')
  } else if (theme === 'retro') {
    root.classList.add('theme-retro')
  } else if (theme === 'retro-dark') {
    root.classList.add('dark', 'theme-retro-dark')
  } else if (theme !== 'light') {
    root.classList.add('dark', `theme-${theme}`)
  }
  loadThemeFont(theme)
}

function isTheme(value: string | null): value is Theme {
  return value !== null && THEME_IDS.has(value)
}

function getThemeSnapshot(): Theme {
  const storedTheme = globalThis.localStorage?.getItem('theme') ?? null
  return isTheme(storedTheme) ? storedTheme : DEFAULT_THEME
}

// Module-level subscriber set keeps every useTheme() consumer (sidebar switcher,
// settings picker, …) in sync within a tab — change it in one place, all update.
const listeners = new Set<() => void>()

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** Persist + apply a theme and notify every subscribed control. */
export function setTheme(theme: Theme) {
  globalThis.localStorage?.setItem('theme', theme)
  applyTheme(theme)
  for (const listener of listeners) listener()
}

/** Read the active theme and a setter, staying in sync across all consumers. */
export function useTheme(): {theme: Theme; setTheme: (theme: Theme) => void} {
  const theme = useSyncExternalStore(subscribe, getThemeSnapshot, getThemeSnapshot)
  useEffect(() => {
    applyTheme(theme)
  }, [theme])
  return {theme, setTheme}
}
