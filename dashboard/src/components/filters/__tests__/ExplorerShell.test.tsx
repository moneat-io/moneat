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

import {render, screen, fireEvent} from '@testing-library/react'
import {describe, it, expect} from 'vitest'

import {ExplorerShell} from '@/components/filters/ExplorerShell'

describe('ExplorerShell', () => {
  it('renders title, icon, actions, toolbar, rail, and content', () => {
    const {container} = render(
      <ExplorerShell
        title="Issues"
        icon={<span data-testid="icon">I</span>}
        actions={<button type="button">Refresh</button>}
        searchBar={<input aria-label="Search" />}
        rail={<div>Facet rail</div>}
        toolbar={<span>42 results</span>}
      >
        <div>Issue rows</div>
      </ExplorerShell>
    )

    const shell = container.firstElementChild as HTMLElement
    // Bound to the fixed content region so the rail/header stay put and only the
    // result list scrolls (see ExplorerShell layout contract).
    expect(shell.className).toContain('h-[calc(100dvh-var(--header-height,0px))]')
    expect(shell.className).not.toContain('min-h-[calc(100dvh-var(--header-height,0px))]')
    expect(screen.getByText('Issues')).toBeTruthy()
    expect(screen.getByTestId('icon')).toBeTruthy()
    expect(screen.getByRole('button', {name: 'Refresh'})).toBeTruthy()
    expect(screen.getByLabelText('Search')).toBeTruthy()
    expect(screen.getByText('Facet rail')).toBeTruthy()
    expect(screen.getByText('42 results')).toBeTruthy()
    expect(screen.getByText('Issue rows')).toBeTruthy()
  })

  it('toggles the rail open and closed', () => {
    render(
      <ExplorerShell
        searchBar={<input aria-label="Search" />}
        rail={<div>Facet rail</div>}
      >
        <div>Rows</div>
      </ExplorerShell>
    )

    fireEvent.click(screen.getByRole('button', {name: 'Hide facets'}))
    expect(screen.queryByText('Facet rail')).toBeNull()

    fireEvent.click(screen.getByRole('button', {name: 'Show facets'}))
    expect(screen.getByText('Facet rail')).toBeTruthy()
  })

  it('respects an initially closed rail and renders without optional chrome', () => {
    render(
      <ExplorerShell
        searchBar={<input aria-label="Search" />}
        rail={<div>Facet rail</div>}
        defaultRailOpen={false}
      >
        <div>Rows</div>
      </ExplorerShell>
    )

    expect(screen.queryByText('Facet rail')).toBeNull()
    expect(screen.getByRole('button', {name: 'Show facets'})).toBeTruthy()

    render(
      <ExplorerShell searchBar={<input aria-label="Plain search" />}>
        <div>Plain rows</div>
      </ExplorerShell>
    )
    expect(screen.getByLabelText('Plain search')).toBeTruthy()
    expect(screen.getByText('Plain rows')).toBeTruthy()
  })

  it('renders section tabs in the header', () => {
    render(
      <ExplorerShell
        tabs={<button type="button">APM Errors</button>}
        searchBar={<input aria-label="Search" />}
      >
        <div>Rows</div>
      </ExplorerShell>
    )

    expect(screen.getByRole('button', {name: 'APM Errors'})).toBeTruthy()
  })
})
