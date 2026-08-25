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
import {describe, expect, it, vi} from 'vitest'

import {StatusPagePreview} from '../StatusPagePreview'

vi.mock('@/hooks/useTimezone', () => ({
  useTimezone: () => ({timezone: 'UTC'}),
}))

describe('StatusPagePreview', () => {
  it('keeps a hostile logo URL in the image source attribute', () => {
    const logoUrl = 'https://cdn.example.test/logo.png" onerror="alert(1)'

    render(
      <StatusPagePreview
        name="Acme status"
        logoUrl={logoUrl}
        primaryColor="#2563eb"
        darkMode={false}
        showUptimeHistory={false}
        historyDays={7}
      />,
    )

    const logo = screen.getByRole('img', {name: 'Acme status'})
    expect(logo).toHaveAttribute('src', logoUrl)
    expect(logo.parentElement?.querySelector('[onerror]')).toBeNull()
  })
})
