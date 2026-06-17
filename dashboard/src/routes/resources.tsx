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

import {createFileRoute, redirect} from '@tanstack/react-router'
import {api} from '@/lib/api'
import {MonitoringTabBar} from '@/components/monitoring/MonitoringTabBar'
import {ResourceCatalog} from '@/components/monitoring/catalog/ResourceCatalog'

// /resources sits outside the /monitoring layout, so it renders the shared
// Infrastructure tab strip itself to stay in step with Map, Processes, etc.
// The catalog already reserves the bar's 43px in its viewport height calc.
function ResourcesPage() {
  return (
    <>
      <MonitoringTabBar />
      <ResourceCatalog />
    </>
  )
}

export const Route = createFileRoute('/resources')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      const hasSession = await api.checkAuth()
      if (!hasSession) throw redirect({to: '/login'})
    }
  },
  component: ResourcesPage,
})
