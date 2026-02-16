// Moneat - Mobile-First Error Monitoring Platform
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

import {isDemo} from '@/lib/demo'
import {Info} from 'lucide-react'

export function DemoBanner() {
  if (!isDemo()) {
    return null
  }

  return (
    <div className="bg-amber-500/10 border-b border-amber-500/20 px-4 py-2">
      <div className="flex items-center justify-center gap-2 text-sm text-amber-700 dark:text-amber-300">
        <Info className="h-4 w-4" />
        <span className="font-medium">Demo Mode</span>
        <span className="text-muted-foreground">•</span>
        <span>You're viewing read-only demo data. All write operations are disabled.</span>
      </div>
    </div>
  )
}
