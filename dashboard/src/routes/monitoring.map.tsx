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

import {createFileRoute, redirect, useNavigate} from '@tanstack/react-router'
import {api} from '@/lib/api'
import {InfrastructureMap, type MonitoringMapScope} from '@/components/monitoring/InfrastructureMap'

interface MonitoringMapSearch {
  scope: MonitoringMapScope
}

function parseMapScope(value: unknown): MonitoringMapScope {
  return value === 'services' || value === 'containers' ? value : 'hosts'
}

export const Route = createFileRoute('/monitoring/map')({
  validateSearch: (search: Record<string, unknown>): MonitoringMapSearch => ({
    scope: parseMapScope(search.scope),
  }),
  beforeLoad: () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: MonitoringMapPage,
})

function MonitoringMapPage() {
  const {scope} = Route.useSearch()
  const navigate = useNavigate({from: '/monitoring/map'})

  return (
    <InfrastructureMap
      initialScope={scope}
      onScopeChange={(nextScope) => {
        navigate({search: {scope: nextScope}, replace: true})
      }}
    />
  )
}
