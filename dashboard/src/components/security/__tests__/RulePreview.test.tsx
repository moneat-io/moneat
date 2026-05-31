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
import {describe, expect, it} from 'vitest'
import {RulePreview} from '../RulePreview'

describe('RulePreview', () => {
  it('summarises the would-be signal count and sample groups', () => {
    render(
      <RulePreview
        preview={{
          match_count: 3,
          window_seconds: 300,
          samples: [
            {group_values: {'user.id': 'alice'}, count: 12},
            {group_values: {'user.id': 'bob'}, count: 5},
          ],
        }}
      />
    )
    expect(screen.getByText(/Would produce 3 signals over the last 24h/)).toBeInTheDocument()
    expect(screen.getByText('user.id=alice')).toBeInTheDocument()
    expect(screen.getByText('12')).toBeInTheDocument()
  })

  it('uses the singular form for a single match and labels empty groups', () => {
    render(
      <RulePreview
        preview={{match_count: 1, window_seconds: 60, samples: [{group_values: {}, count: 1}]}}
      />
    )
    expect(screen.getByText(/Would produce 1 signal over the last 24h/)).toBeInTheDocument()
    expect(screen.getByText('(all)')).toBeInTheDocument()
  })
})
