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
import {describe, it, expect, beforeEach, vi} from 'vitest'
import {ThemePicker} from '../ThemePicker'

// Mock the lucide-react icons so we can assert on stable testids.
vi.mock('lucide-react', () => ({
  Moon: () => <div data-testid="moon-icon" />,
  Sun: () => <div data-testid="sun-icon" />,
  CloudMoon: () => <div data-testid="cloud-moon-icon" />,
  Leaf: () => <div data-testid="leaf-icon" />,
  Sunset: () => <div data-testid="sunset-icon" />,
  Gamepad2: () => <div data-testid="gamepad-icon" />,
  Check: () => <div data-testid="check-icon" />,
  Newspaper: () => <div data-testid="newspaper-icon" />,
  Monitor: () => <div data-testid="monitor-icon" />,
  Terminal: () => <div data-testid="terminal-icon" />,
}))

const ALL_THEMES = ['Light', 'Dark', 'Midnight', 'Forest', 'Sunset', 'Gamer', 'Retro', 'Retro Dark', 'Terminal']

beforeEach(() => {
  localStorage.clear()
  document.documentElement.className = ''
  document.getElementById('vt323-font')?.remove()
  document.getElementById('ibm-plex-mono-font')?.remove()
})

describe('ThemePicker', () => {
  it('renders every theme option', () => {
    render(<ThemePicker />)
    for (const label of ALL_THEMES) {
      expect(screen.getByRole('button', {name: label})).toBeInTheDocument()
    }
  })

  it('marks the active theme pressed and applies it on mount', () => {
    localStorage.setItem('theme', 'forest')
    render(<ThemePicker />)
    expect(screen.getByRole('button', {name: 'Forest'})).toHaveAttribute('aria-pressed', 'true')
    expect(document.documentElement.classList.contains('theme-forest')).toBe(true)
    expect(document.documentElement.classList.contains('dark')).toBe(true)
  })

  it('switches theme on click and persists it', async () => {
    const user = userEvent.setup()
    render(<ThemePicker />)
    await user.click(screen.getByRole('button', {name: 'Terminal'}))
    expect(localStorage.getItem('theme')).toBe('terminal')
    expect(document.documentElement.classList.contains('theme-terminal')).toBe(true)
    expect(screen.getByRole('button', {name: 'Terminal'})).toHaveAttribute('aria-pressed', 'true')
    expect(document.getElementById('ibm-plex-mono-font')).toBeTruthy()
  })
})
