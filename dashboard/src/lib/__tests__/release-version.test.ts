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
import {compareReleaseVersionPrecedence} from '../release-version'

describe('compareReleaseVersionPrecedence', () => {
  it('compares package-prefixed semantic releases with build numbers', () => {
    expect(
      compareReleaseVersionPrecedence('com.bandapella@4.5.1+16', 'com.bandapella@4.4.2+15')
    ).toBeGreaterThan(0)
  })

  it('uses numeric build metadata as a same-version tie breaker', () => {
    expect(
      compareReleaseVersionPrecedence('com.bandapella@4.5.1+16', 'com.bandapella@4.5.1+15')
    ).toBeGreaterThan(0)
  })

  it('keeps stable releases ahead of prereleases', () => {
    expect(compareReleaseVersionPrecedence('frontend@1.2.0', 'frontend@1.2.0-beta.1')).toBeGreaterThan(0)
  })

  it('does not compare versions from different release families', () => {
    expect(compareReleaseVersionPrecedence('api@9.0.0', 'worker@1.0.0')).toBe(0)
  })
})
