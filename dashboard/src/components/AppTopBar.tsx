// Moneat - observability platform
// Copyright (C) 2026 Moneat
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY IMPLIED WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
// See the GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

import {Logo} from '@/components/Logo'

export const TOPBAR_HEIGHT = 49

interface AppTopBarProps {
  sidebarWidth: number
  isSidebarExpanded: boolean
}

export function AppTopBar({sidebarWidth, isSidebarExpanded}: AppTopBarProps) {
  return (
    <div
      className="flex items-center border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60"
    >
      {/* Logo area – width matches the sidebar below */}
      <div
        className="shrink-0 flex items-center justify-center h-full py-[9px] transition-all duration-300"
        style={{width: sidebarWidth}}
      >
        {isSidebarExpanded ? (
          <Logo className="h-9" />
        ) : (
          <Logo markOnly className="h-9 w-10" />
        )}
      </div>

    </div>
  )
}
