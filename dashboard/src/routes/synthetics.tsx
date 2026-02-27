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

import {createFileRoute, Outlet, redirect} from '@tanstack/react-router'
import {BookOpen, FlaskConical, Plus} from 'lucide-react'
import {useState} from 'react'
import {api} from '@/lib/api'
import {BetaBanner} from '@/components/BetaBanner'
import {Button} from '@/components/ui/button'
import CreateSyntheticTestDialog from '@/components/CreateSyntheticTestDialog'

export const Route = createFileRoute('/synthetics')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      const hasSession = await api.checkAuth()
      if (!hasSession) throw redirect({to: '/login'})
    }
  },
  component: SyntheticsLayout,
})

function SyntheticsLayout() {
  const [createOpen, setCreateOpen] = useState(false)

  return (
    <div className="space-y-6">
      <BetaBanner pageKey="synthetics" />
      <div className="p-6 space-y-6">
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-gradient-to-br from-violet-500 to-purple-600">
            <FlaskConical className="h-5 w-5 text-white" />
          </div>
          <div>
            <h2 className="text-2xl font-bold">Synthetics</h2>
            <p className="text-muted-foreground text-sm">Synthetic test results and monitoring</p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <a href="/docs/datadog-agent/synthetics" target="_blank" rel="noreferrer"
            className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors">
            <BookOpen className="h-4 w-4" />
            View docs
          </a>
          <Button size="sm" onClick={() => setCreateOpen(true)}>
            <Plus className="h-4 w-4 mr-1.5" />New Test
          </Button>
        </div>
      </div>
      <Outlet />
    </div>
      <CreateSyntheticTestDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        onSuccess={() => setCreateOpen(false)}
      />
    </div>
  )
}
