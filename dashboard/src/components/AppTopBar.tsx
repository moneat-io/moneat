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

import {useQuery} from '@tanstack/react-query'
import {Search} from 'lucide-react'
import {api} from '@/lib/api'
import {useProject} from '@/contexts/project-context'
import {useCommandPalette} from '@/contexts/command-palette-context'
import {cn} from '@/lib/utils'

export function AppTopBar() {
  const {openPalette} = useCommandPalette() ?? {}
  const {selectedProjectId} = useProject()

  const {data: projects} = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
    enabled: api.isAuthenticated(),
  })

  const activeProject = projects?.find((p) => p.id === selectedProjectId) ?? projects?.[0]
  const projectCount = projects?.length ?? 0

  return (
    <div
      className={cn(
        'relative flex items-center justify-center gap-4 border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60 px-4 py-2.5',
      )}
    >
      <button
        type="button"
        onClick={() => openPalette?.()}
        className="flex w-full max-w-xl items-center gap-3 rounded-lg border bg-muted/50 px-3 py-2 text-left text-sm text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
      >
        <Search className="h-4 w-4 shrink-0" />
        <span className="flex-1">Search dashboards, projects, pages...</span>
        <kbd className="hidden rounded border bg-muted px-1.5 py-0.5 font-mono text-[10px] sm:inline-block">
          ⌘K
        </kbd>
      </button>
      <div className="absolute right-4 top-1/2 -translate-y-1/2 flex shrink-0 items-center gap-2 text-xs text-muted-foreground">
        {activeProject && (
          <span className="truncate max-w-[140px]" title={activeProject.name}>
            {activeProject.name}
          </span>
        )}
        <span className="text-muted-foreground/60">·</span>
        <span>
          {projectCount} {projectCount === 1 ? 'project' : 'projects'}
        </span>
      </div>
    </div>
  )
}
