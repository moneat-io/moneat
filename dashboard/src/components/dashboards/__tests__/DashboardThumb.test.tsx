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

import {describe, expect, it} from 'vitest'
import {render} from '@testing-library/react'
import {DashboardThumb, SparkThumb} from '../DashboardThumb'
import type {ThumbKind} from '../dashboardThumbHelpers'

const KINDS: ThumbKind[] = ['service', 'host', 'k8s', 'db', 'logs', 'vitals']

describe('DashboardThumb', () => {
  it('renders every thumbnail shape on the dark canvas', () => {
    for (const kind of KINDS) {
      const {container, unmount} = render(<DashboardThumb kind={kind} />)
      // Each shape renders some markup (tiles, panels, bars, heat cells or svg).
      expect(container.firstChild).not.toBeNull()
      expect(container.querySelectorAll('div, svg, span').length).toBeGreaterThan(0)
      unmount()
    }
  })

  it('shows the k8s saturation labels and the logs legend', () => {
    const {getByText, unmount} = render(<DashboardThumb kind="k8s" />)
    expect(getByText('pods')).toBeInTheDocument()
    expect(getByText('pod cpu saturation')).toBeInTheDocument()
    unmount()

    const logs = render(<DashboardThumb kind="logs" />)
    expect(logs.getByText('info')).toBeInTheDocument()
    expect(logs.getByText('error')).toBeInTheDocument()
  })
})

describe('SparkThumb', () => {
  it('renders a compact sparkline polyline for each kind', () => {
    for (const kind of KINDS) {
      const {container, unmount} = render(<SparkThumb kind={kind} />)
      expect(container.querySelector('polyline')).not.toBeNull()
      unmount()
    }
  })
})
