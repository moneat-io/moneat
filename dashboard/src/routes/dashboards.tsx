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

import {createFileRoute, Outlet} from '@tanstack/react-router'
import {LayoutDashboard} from 'lucide-react'

export const Route = createFileRoute('/dashboards')({
  component: DashboardsLayout,
})

function DashboardsLayout() {
  return (
    <div className="p-6 space-y-5">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2.5">
          <div className="flex items-center justify-center h-8 w-8 rounded-lg bg-gradient-to-br from-violet-500 to-purple-600">
            <LayoutDashboard className="h-4 w-4 text-white" />
          </div>
          <div>
            <h1 className="text-xl font-semibold leading-tight">Dashboards</h1>
            <p className="text-muted-foreground text-xs">
              Build custom dashboards with drag-and-drop widgets
            </p>
          </div>
        </div>
      </div>

      <Outlet />
    </div>
  )
}
