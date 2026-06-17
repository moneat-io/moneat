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
import {describe, it, expect, vi} from 'vitest'
import {AlertCircle, Cpu} from 'lucide-react'

import {SegmentedTabs, type SegmentedOption} from '@/components/filters/SegmentedTabs'

type View = 'issues' | 'apm-errors'

const OPTIONS = [
  {value: 'issues', label: 'Issues', icon: AlertCircle},
  {value: 'apm-errors', label: 'APM Errors', icon: Cpu},
] as const satisfies ReadonlyArray<SegmentedOption<View>>

describe('SegmentedTabs', () => {
  it('renders every option and marks the active one pressed', () => {
    render(<SegmentedTabs ariaLabel="View" value="issues" options={OPTIONS} onChange={() => {}} />)

    expect(screen.getByRole('button', {name: 'Issues'}).getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByRole('button', {name: 'APM Errors'}).getAttribute('aria-pressed')).toBe('false')
  })

  it('calls onChange with the option value when a segment is clicked', () => {
    const onChange = vi.fn()
    render(<SegmentedTabs ariaLabel="View" value="issues" options={OPTIONS} onChange={onChange} />)

    fireEvent.click(screen.getByRole('button', {name: 'APM Errors'}))
    expect(onChange).toHaveBeenCalledWith('apm-errors')
  })
})
