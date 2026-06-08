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

import React from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'

/** Clears localStorage, sessionStorage, and sets authenticated state for tests. */
export function clearAuthStorage(): void {
  localStorage.clear()
  sessionStorage.clear()
  sessionStorage.setItem('authenticated', 'true')
}

/** Renders UI wrapped with QueryClientProvider (no retries). */
export function renderWithQueryClient(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>
  )
}

/** Renders a route component with QueryClientProvider. */
export function renderRouteWithProviders(Component: React.ComponentType) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <Component />
    </QueryClientProvider>
  )
}

/** Extracts and renders a TanStack file-route component. Reduces boilerplate in route tests. */
export function renderRoute(Route: unknown) {
  if (!Route || typeof Route !== 'object' || !('component' in Route)) {
    throw new Error('Route has no component')
  }
  const Component = (Route as { component?: React.ComponentType }).component
  if (!Component) throw new Error('Route has no component')
  return renderRouteWithProviders(Component)
}
