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

import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type {UserEvent} from '@testing-library/user-event'
import {describe, it, expect, vi, beforeEach} from 'vitest'
import {ThemeSwitcher} from '../ThemeSwitcher'
import {clearAuthStorage} from '@/test/utils'

async function selectTheme(user: UserEvent, themeName: string) {
  await user.click(screen.getByRole('button', {name: /select theme/i}))
  await user.click(await screen.findByText(themeName))
}

// Mock the lucide-react icons
vi.mock('lucide-react', () => ({
  Moon: () => <div data-testid="moon-icon" />,
  Sun: () => <div data-testid="sun-icon" />,
  Palette: () => <div data-testid="palette-icon" />,
  CloudMoon: () => <div data-testid="cloud-moon-icon" />,
  Leaf: () => <div data-testid="leaf-icon" />,
  Sunset: () => <div data-testid="sunset-icon" />,
  Gamepad2: () => <div data-testid="gamepad-icon" />,
  Check: () => <div data-testid="check-icon" />,
  Newspaper: () => <div data-testid="newspaper-icon" />,
  Monitor: () => <div data-testid="monitor-icon" />,
  Terminal: () => <div data-testid="terminal-icon" />,
}))

// Mock ResizeObserver which is used by Radix UI
globalThis.ResizeObserver = class ResizeObserver {
  observe() { /* no-op */ }
  unobserve() { /* no-op */ }
  disconnect() { /* no-op */ }
}

// Mock scrollIntoView
globalThis.HTMLElement.prototype.scrollIntoView = vi.fn()
globalThis.HTMLElement.prototype.releasePointerCapture = vi.fn()
globalThis.HTMLElement.prototype.hasPointerCapture = vi.fn()

beforeEach(() => {
  clearAuthStorage()
  document.documentElement.className = ''
  document.getElementById('vt323-font')?.remove()
  document.getElementById('ibm-plex-mono-font')?.remove()
  vi.clearAllMocks()
})

const themeSelectionCases: Array<{
  theme: string
  displayName: string
  dark: boolean
  themeClass: string
}> = [
  { theme: 'forest', displayName: 'Forest', dark: true, themeClass: 'theme-forest' },
  { theme: 'sunset', displayName: 'Sunset', dark: true, themeClass: 'theme-sunset' },
  { theme: 'retro', displayName: 'Retro', dark: false, themeClass: 'theme-retro' },
  { theme: 'retro-dark', displayName: 'Retro Dark', dark: true, themeClass: 'theme-retro-dark' },
]

const localStoragePersistenceCases: Array<{
  theme: string
  themeClass: string
  dark: boolean
  fontId?: string
}> = [
  { theme: 'forest', themeClass: 'theme-forest', dark: true },
  { theme: 'retro', themeClass: 'theme-retro', dark: false },
  { theme: 'retro-dark', themeClass: 'theme-retro-dark', dark: true },
  { theme: 'gamer', themeClass: 'theme-gamer', dark: true, fontId: 'vt323-font' },
  { theme: 'terminal', themeClass: 'theme-terminal', dark: true, fontId: 'ibm-plex-mono-font' },
]

describe('ThemeSwitcher – extended branch coverage', () => {
  describe('theme selection', () => {
    it.each(themeSelectionCases)(
      'applies correct classes for $displayName',
      async ({ theme, displayName, dark, themeClass }) => {
        const user = userEvent.setup()
        render(<ThemeSwitcher />)
        await selectTheme(user, displayName)
        expect(localStorage.getItem('theme')).toBe(theme)
        expect(document.documentElement.classList.contains(themeClass)).toBe(true)
        expect(document.documentElement.classList.contains('dark')).toBe(dark)
      }
    )
  })

  describe('gamer theme (VT323 font)', () => {
    it('applies dark and theme-gamer classes and loads VT323 font', async () => {
      const user = userEvent.setup()
      render(<ThemeSwitcher />)
      await selectTheme(user, 'Gamer')
      expect(localStorage.getItem('theme')).toBe('gamer')
      expect(document.documentElement.classList.contains('dark')).toBe(true)
      expect(document.documentElement.classList.contains('theme-gamer')).toBe(true)
      const fontLink = document.getElementById('vt323-font')
      expect(fontLink).toBeTruthy()
      expect(fontLink?.getAttribute('href')).toContain('VT323')
    })

    it('does not add duplicate font link on second gamer theme selection', async () => {
      const user = userEvent.setup()
      render(<ThemeSwitcher />)
      await selectTheme(user, 'Gamer')
      await selectTheme(user, 'Dark')
      await selectTheme(user, 'Gamer')
      expect(document.querySelectorAll('#vt323-font').length).toBe(1)
    })
  })

  describe('terminal theme (IBM Plex Mono font)', () => {
    it('applies dark and theme-terminal classes and loads IBM Plex Mono font', async () => {
      const user = userEvent.setup()
      render(<ThemeSwitcher />)
      await selectTheme(user, 'Terminal')
      expect(localStorage.getItem('theme')).toBe('terminal')
      expect(document.documentElement.classList.contains('dark')).toBe(true)
      expect(document.documentElement.classList.contains('theme-terminal')).toBe(true)
      const fontLink = document.getElementById('ibm-plex-mono-font')
      expect(fontLink).toBeTruthy()
      expect(fontLink?.getAttribute('href')).toContain('IBM+Plex+Mono')
    })

    it('does not add duplicate IBM Plex Mono font link', async () => {
      const user = userEvent.setup()
      render(<ThemeSwitcher />)
      await selectTheme(user, 'Terminal')
      await selectTheme(user, 'Light')
      await selectTheme(user, 'Terminal')
      expect(document.querySelectorAll('#ibm-plex-mono-font').length).toBe(1)
    })
  })

  describe('dark theme', () => {
    it('applies dark class and removes other theme classes', async () => {
      localStorage.setItem('theme', 'midnight')
      const user = userEvent.setup()
      render(<ThemeSwitcher />)
      await selectTheme(user, 'Dark')
      expect(localStorage.getItem('theme')).toBe('dark')
      expect(document.documentElement.classList.contains('dark')).toBe(true)
      expect(document.documentElement.classList.contains('theme-midnight')).toBe(false)
    })
  })

  describe('light theme', () => {
    it('removes all theme classes for light mode', async () => {
      localStorage.setItem('theme', 'gamer')
      const user = userEvent.setup()
      render(<ThemeSwitcher />)
      await selectTheme(user, 'Light')
      expect(localStorage.getItem('theme')).toBe('light')
      expect(document.documentElement.classList.contains('dark')).toBe(false)
      expect(document.documentElement.classList.contains('theme-gamer')).toBe(false)
    })
  })

  describe('localStorage persistence', () => {
    it.each(localStoragePersistenceCases)(
      'loads $theme from localStorage',
      ({ theme, themeClass, dark, fontId }) => {
        localStorage.setItem('theme', theme)
        render(<ThemeSwitcher />)
        expect(document.documentElement.classList.contains(themeClass)).toBe(true)
        expect(document.documentElement.classList.contains('dark')).toBe(dark)
        if (fontId) {
          expect(document.getElementById(fontId)).toBeTruthy()
        }
      }
    )
  })

  // ──── Theme removal: switching removes previous theme ────
  describe('theme class cleanup', () => {
    it('removes previous theme class when switching themes', async () => {
      localStorage.setItem('theme', 'forest')
      const user = userEvent.setup()
      render(<ThemeSwitcher />)
      expect(document.documentElement.classList.contains('theme-forest')).toBe(true)
      await selectTheme(user, 'Sunset')
      expect(document.documentElement.classList.contains('theme-forest')).toBe(false)
      expect(document.documentElement.classList.contains('theme-sunset')).toBe(true)
    })

    it('removes all theme classes when switching to light', async () => {
      localStorage.setItem('theme', 'terminal')
      const user = userEvent.setup()
      render(<ThemeSwitcher />)
      await selectTheme(user, 'Light')
      expect(document.documentElement.classList.contains('dark')).toBe(false)
      expect(document.documentElement.classList.contains('theme-terminal')).toBe(false)
      expect(document.documentElement.classList.contains('theme-forest')).toBe(false)
      expect(document.documentElement.classList.contains('theme-gamer')).toBe(false)
    })
  })

  // ──── Check icon: shown next to active theme ────
  describe('check icon', () => {
    it('shows check icon next to the active dark theme', async () => {
      localStorage.setItem('theme', 'dark')
      const user = userEvent.setup()
      render(<ThemeSwitcher />)
      await user.click(screen.getByRole('button', {name: /select theme/i}))
      const darkItem = screen.getByText('Dark').closest('[role="menuitem"]')
      expect(darkItem?.querySelector('[data-testid="check-icon"]')).toBeTruthy()
      const lightItem = screen.getByText('Light').closest('[role="menuitem"]')
      expect(lightItem?.querySelector('[data-testid="check-icon"]')).toBeFalsy()
    })
  })
})
