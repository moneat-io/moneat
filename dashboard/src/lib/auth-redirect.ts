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

import {APP_OVERVIEW_VIEW} from './overview-route'

const LOGIN_PATH = '/login'
const AUTH_PAGE_PATHS = new Set([
  LOGIN_PATH,
  '/signup',
  '/verify-email',
  '/forgot-password',
  '/reset-password',
])
const pendingAuthRedirect = {
  path: undefined as string | undefined,
}

export interface BrowserLocationLike {
  readonly pathname: string
  readonly search?: string
  readonly hash?: string
}

export function currentRouteFromLocation(location: BrowserLocationLike): string {
  return `${location.pathname}${location.search ?? ''}${location.hash ?? ''}`
}

function normalizePath(pathname: string): string {
  if (!pathname || pathname === '/') return '/'
  let end = pathname.length
  while (end > 1 && pathname[end - 1] === '/') {
    end -= 1
  }
  return pathname.slice(0, end) || '/'
}

export function isAuthPagePath(pathname: string): boolean {
  return AUTH_PAGE_PATHS.has(normalizePath(pathname))
}

export function isPublicLandingLocation(location: BrowserLocationLike): boolean {
  if (normalizePath(location.pathname) !== '/') return false
  const params = new URLSearchParams(location.search ?? '')
  return params.get('view') !== APP_OVERVIEW_VIEW
}

export function normalizeInternalRedirectPath(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined
  const trimmed = value.trim()
  if (!trimmed) return undefined

  if (!trimmed.startsWith('/')) return undefined
  if (trimmed[1] === '/' || trimmed[1] === '\\') return undefined
  return trimmed
}

export function storePendingAuthRedirect(location: BrowserLocationLike): void {
  const redirectPath = normalizeInternalRedirectPath(currentRouteFromLocation(location))
  if (redirectPath === undefined) return
  pendingAuthRedirect.path = redirectPath
}

export function consumePendingAuthRedirect(): string | undefined {
  const value = pendingAuthRedirect.path
  pendingAuthRedirect.path = undefined
  return normalizeInternalRedirectPath(value)
}
