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
import {
  currentRouteFromLocation,
  isAuthPagePath,
  isPublicLandingLocation,
  normalizeInternalRedirectPath,
  consumePendingAuthRedirect,
  storePendingAuthRedirect,
} from '@/lib/auth-redirect'

describe('auth redirect helpers', () => {
  it('detects auth page paths', () => {
    expect(isAuthPagePath('/reset-password')).toBe(true)
    expect(isAuthPagePath('/login/')).toBe(true)
    expect(isAuthPagePath('/issues')).toBe(false)
  })

  it('preserves the authenticated overview but not the public landing page', () => {
    expect(isPublicLandingLocation({pathname: '/'})).toBe(true)
    expect(isPublicLandingLocation({pathname: '/', search: '?view=overview'})).toBe(false)
  })

  it('handles locations without search or hash values', () => {
    expect(currentRouteFromLocation({pathname: '/logs'})).toBe('/logs')
  })

  it('normalizes post-login redirects to same-origin internal paths', () => {
    expect(normalizeInternalRedirectPath('/replays/replay-1?tab=errors#event')).toBe(
      '/replays/replay-1?tab=errors#event'
    )
    expect(normalizeInternalRedirectPath('https://moneat.io/issues/issue-1')).toBeUndefined()
    expect(normalizeInternalRedirectPath('https://www.moneat.io/logs?query=error')).toBeUndefined()
    expect(normalizeInternalRedirectPath('https://example.com/issues/issue-1')).toBeUndefined()
    expect(normalizeInternalRedirectPath('//example.com/issues/issue-1')).toBeUndefined()
    expect(normalizeInternalRedirectPath('/\\example.com/issues/issue-1')).toBeUndefined()
    expect(normalizeInternalRedirectPath('')).toBeUndefined()
    expect(normalizeInternalRedirectPath(null)).toBeUndefined()
  })

  it('stores and consumes a pending auth redirect path', () => {
    storePendingAuthRedirect({
      pathname: '/issues/issue-123',
      search: '?projectId=service-1&status=unresolved',
      hash: '#events',
    })

    expect(consumePendingAuthRedirect()).toBe('/issues/issue-123?projectId=service-1&status=unresolved#events')
    expect(consumePendingAuthRedirect()).toBeUndefined()
  })
})
