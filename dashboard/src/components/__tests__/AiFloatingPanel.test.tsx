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

import {render} from '@testing-library/react'
import {describe, expect, it} from 'vitest'

import {AiFloatingPanel} from '@/components/AiFloatingPanel'

function restoreWindowProperty(property: keyof Window, descriptor: PropertyDescriptor | undefined) {
  if (descriptor) {
    Object.defineProperty(globalThis.window, property, descriptor)
    return
  }

  delete (globalThis.window as Partial<Window>)[property]
}

describe('AiFloatingPanel', () => {
  it('renders nothing when no floating panel is active', () => {
    // With no command-palette provider the panel bails out, but its size/position
    // state initializers (clamped to the viewport for small screens) still run.
    const {container} = render(<AiFloatingPanel />)
    expect(container).toBeEmptyDOMElement()
  })

  it('clamps its initial geometry to a narrow viewport without throwing', () => {
    const originalWidth = Object.getOwnPropertyDescriptor(globalThis.window, 'innerWidth')
    const originalHeight = Object.getOwnPropertyDescriptor(globalThis.window, 'innerHeight')
    try {
      Object.defineProperty(globalThis.window, 'innerWidth', {configurable: true, value: 360})
      Object.defineProperty(globalThis.window, 'innerHeight', {configurable: true, value: 640})
      const {container} = render(<AiFloatingPanel />)
      expect(container).toBeEmptyDOMElement()
    } finally {
      restoreWindowProperty('innerWidth', originalWidth)
      restoreWindowProperty('innerHeight', originalHeight)
    }
  })
})
