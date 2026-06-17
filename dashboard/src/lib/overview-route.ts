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

export const APP_OVERVIEW_VIEW = 'overview'
export const APP_OVERVIEW_HREF = '/?view=overview'

export type AppOverviewSearch = {
  view?: typeof APP_OVERVIEW_VIEW
}

export const APP_OVERVIEW_SEARCH: AppOverviewSearch = {
  view: APP_OVERVIEW_VIEW,
}

export function normalizeAppOverviewSearch(search: Record<string, unknown>): AppOverviewSearch {
  return search.view === APP_OVERVIEW_VIEW ? {view: APP_OVERVIEW_VIEW} : {}
}

export function isAppOverviewSearch(search: unknown): boolean {
  return (
    typeof search === 'object' &&
    search !== null &&
    'view' in search &&
    search.view === APP_OVERVIEW_VIEW
  )
}

function normalizeRoutePath(pathname: string): string {
  if (!pathname || pathname === '/') return '/'
  return pathname.replace(/\/+$/, '') || '/'
}

export function isPublicLandingRoute(pathname: string, search: unknown): boolean {
  return normalizeRoutePath(pathname) === '/' && !isAppOverviewSearch(search)
}
