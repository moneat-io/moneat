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

describe('ThemeSwitcher – extended branch coverage', () => {
  // ──── Theme: forest ────
  describe('forest theme', () => {
    it('applies dark and theme-forest classes', async () => {
      const user = userEvent.setup()
      render(<ThemeSwitcher />)
      await selectTheme(user, 'Forest')
      expect(localStorage.getItem('theme')).toBe('forest')
      expect(document.documentElement.classList.contains('dark')).toBe(true)
      expect(document.documentElement.classList.contains('theme-forest')).toBe(true)
    })
  })

  // ──── Theme: sunset ────
  describe('sunset theme', () => {
    it('applies dark and theme-sunset classes', async () => {
      const user = userEvent.setup()
      render(<ThemeSwitcher />)
      await selectTheme(user, 'Sunset')
      expect(localStorage.getItem('theme')).toBe('sunset')
      expect(document.documentElement.classList.contains('dark')).toBe(true)
      expect(document.documentElement.classList.contains('theme-sunset')).toBe(true)
    })
  })

  // ──── Theme: gamer (loads VT323 font) ────
  describe('gamer theme', () => {
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

  // ──── Theme: retro ────
  describe('retro theme', () => {
    it('applies theme-retro class without dark', async () => {
      const user = userEvent.setup()
      render(<ThemeSwitcher />)
      await selectTheme(user, 'Retro')
      expect(localStorage.getItem('theme')).toBe('retro')
      expect(document.documentElement.classList.contains('theme-retro')).toBe(true)
      expect(document.documentElement.classList.contains('dark')).toBe(false)
    })
  })

  // ──── Theme: retro-dark ────
  describe('retro-dark theme', () => {
    it('applies dark and theme-retro-dark classes', async () => {
      const user = userEvent.setup()
      render(<ThemeSwitcher />)
      await selectTheme(user, 'Retro Dark')
      expect(localStorage.getItem('theme')).toBe('retro-dark')
      expect(document.documentElement.classList.contains('dark')).toBe(true)
      expect(document.documentElement.classList.contains('theme-retro-dark')).toBe(true)
    })
  })

  // ──── Theme: terminal (loads IBM Plex Mono font) ────
  describe('terminal theme', () => {
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

  // ──── Theme: dark (default) ────
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

  // ──── Theme: light ────
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

  // ──── Persistence: loads saved theme on mount ────
  describe('localStorage persistence', () => {
    it('loads forest theme from localStorage', () => {
      localStorage.setItem('theme', 'forest')
      render(<ThemeSwitcher />)
      expect(document.documentElement.classList.contains('theme-forest')).toBe(true)
      expect(document.documentElement.classList.contains('dark')).toBe(true)
    })

    it('loads retro theme from localStorage', () => {
      localStorage.setItem('theme', 'retro')
      render(<ThemeSwitcher />)
      expect(document.documentElement.classList.contains('theme-retro')).toBe(true)
      expect(document.documentElement.classList.contains('dark')).toBe(false)
    })

    it('loads retro-dark theme from localStorage', () => {
      localStorage.setItem('theme', 'retro-dark')
      render(<ThemeSwitcher />)
      expect(document.documentElement.classList.contains('theme-retro-dark')).toBe(true)
      expect(document.documentElement.classList.contains('dark')).toBe(true)
    })

    it('loads gamer theme from localStorage and adds font', () => {
      localStorage.setItem('theme', 'gamer')
      render(<ThemeSwitcher />)
      expect(document.documentElement.classList.contains('theme-gamer')).toBe(true)
      expect(document.getElementById('vt323-font')).toBeTruthy()
    })

    it('loads terminal theme from localStorage and adds font', () => {
      localStorage.setItem('theme', 'terminal')
      render(<ThemeSwitcher />)
      expect(document.documentElement.classList.contains('theme-terminal')).toBe(true)
      expect(document.getElementById('ibm-plex-mono-font')).toBeTruthy()
    })
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
