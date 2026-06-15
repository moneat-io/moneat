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

import {ConnectorLogo, DiscordLogo, SlackLogo} from '../BrandLogos'

describe('BrandLogos', () => {
  it('renders connector logo glyphs and brand colors', () => {
    const {container} = render(
      <div>
        <SlackLogo className="slack-logo" />
        <DiscordLogo className="discord-logo" />
        <ConnectorLogo providerId="slack" />
        <ConnectorLogo providerId="github" />
        <ConnectorLogo providerId="not-real" className="fallback-logo" />
      </div>
    )

    expect(container.querySelector('.slack-logo')).toHaveAttribute('viewBox', '0 0 24 24')
    expect(container.querySelector('.discord-logo')).toHaveAttribute('viewBox', '0 0 24 24')
    expect(screen.queryAllByRole('img', {hidden: true})).toHaveLength(0)
    expect(container.querySelector('span')).toHaveStyle({color: '#611f69'})
    expect(container.querySelector('.fallback-logo svg')).toBeInTheDocument()
    expect(container.querySelectorAll('span svg')).toHaveLength(3)
  })
})
