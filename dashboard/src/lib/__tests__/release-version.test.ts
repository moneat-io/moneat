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

  it('orders prerelease identifiers using semantic version precedence', () => {
    expect(compareReleaseVersionPrecedence('frontend@1.2.0-beta.2', 'frontend@1.2.0-beta.1')).toBeGreaterThan(0)
    expect(compareReleaseVersionPrecedence('frontend@1.2.0-1', 'frontend@1.2.0-alpha')).toBeLessThan(0)
    expect(compareReleaseVersionPrecedence('frontend@1.2.0-alpha', 'frontend@1.2.0-1')).toBeGreaterThan(0)
    expect(compareReleaseVersionPrecedence('frontend@1.2.0-alpha', 'frontend@1.2.0-beta')).toBeLessThan(0)
  })

  it('keeps longer prerelease and build metadata ahead when the shared prefix matches', () => {
    expect(compareReleaseVersionPrecedence('frontend@1.2.0-beta.1', 'frontend@1.2.0-beta')).toBeGreaterThan(0)
    expect(compareReleaseVersionPrecedence('frontend@1.2.0+build.2', 'frontend@1.2.0+build')).toBeGreaterThan(0)
    expect(compareReleaseVersionPrecedence('frontend@1.2.0+build', 'frontend@1.2.0+build.2')).toBeLessThan(0)
  })

  it('falls back to the last version-like token when no package separator is present', () => {
    expect(compareReleaseVersionPrecedence('web build v2.4.0', 'web build v2.3.9')).toBeGreaterThan(0)
  })

  it('ignores unparsable release labels', () => {
    expect(compareReleaseVersionPrecedence('frontend@release', 'frontend@1.0.0')).toBe(0)
    expect(compareReleaseVersionPrecedence('frontend@1', 'frontend@1.0.0')).toBe(0)
  })

  it('treats equivalent version precedence as equal', () => {
    expect(compareReleaseVersionPrecedence('frontend@1.2.0-beta', 'frontend@1.2.0-beta')).toBe(0)
    expect(compareReleaseVersionPrecedence('frontend@1.2', 'frontend@1.2.0')).toBe(0)
  })

  it('does not compare versions from different release families', () => {
    expect(compareReleaseVersionPrecedence('api@9.0.0', 'worker@1.0.0')).toBe(0)
  })
})
