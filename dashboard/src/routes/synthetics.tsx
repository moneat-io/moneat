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
import {Button} from '@/components/ui/button'
import {PageHeader} from '@/components/ui/page-header'
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
    <div className="space-y-2">
      <div className="px-6 py-4 space-y-4">
      <PageHeader
        icon={FlaskConical}
        title="Synthetics"
        description="Synthetic test results and monitoring"
        actions={
          <>
            <a href="/docs/datadog-agent/synthetics" target="_blank" rel="noreferrer"
              className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors">
              <BookOpen className="h-3.5 w-3.5" />
              View docs
            </a>
            <Button size="sm" onClick={() => setCreateOpen(true)} className="gap-1.5">
              <Plus className="h-4 w-4" />New test
            </Button>
          </>
        }
      />
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
