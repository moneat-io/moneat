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
import {ChevronDown, Search, Plus, Settings, LogOut} from 'lucide-react'
import {api} from '@/lib/api'
import {useProject} from '@/contexts/project-context'
import {useCommandPalette} from '@/hooks/useCommandPalette'
import {cn} from '@/lib/utils'
import {Avatar, AvatarFallback} from '@/components/ui/avatar'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
  DropdownMenuSeparator,
} from '@/components/ui/dropdown-menu'
import {Button} from '@/components/ui/button'
import {getPlatformInfo} from '@/routes/projects'
import {Package} from 'lucide-react'
import {useNavigate} from '@tanstack/react-router'

function getInitials(name?: string) {
  if (!name) return 'U'
  return name
    .split(' ')
    .map((n) => n[0])
    .join('')
    .toUpperCase()
    .slice(0, 2)
}

function getProjectPlatform(project: { keys?: { platformTarget?: string | null }[]; framework?: string }) {
  return project.keys?.[0]?.platformTarget || project.framework || 'other'
}

export function AppTopBar() {
  const {openPalette} = useCommandPalette() ?? {}
  const {selectedProjectId, setSelectedProjectId} = useProject()
  const navigate = useNavigate()

  const {data: user} = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => api.getCurrentUser(),
    enabled: api.isAuthenticated(),
  })

  const {data: projects} = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
    enabled: api.isAuthenticated(),
  })

  const activeProject = projects?.find((p) => p.id === selectedProjectId) ?? projects?.[0]

  const platformId = activeProject ? getProjectPlatform(activeProject) : 'other'
  const platformInfo = getPlatformInfo(platformId) || getPlatformInfo('other')
  const PlatformIcon = platformInfo?.icon || Package

  const handleLogout = async () => {
    await api.logout()
    window.location.href = '/login'
  }

  return (
    <div
      className={cn(
        'relative flex items-center justify-center border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60 px-4 py-[9px]',
      )}
    >
      <button
        type="button"
        onClick={() => openPalette?.()}
        className="flex w-full max-w-xl items-center gap-3 rounded-lg border bg-muted/50 px-3 py-1.5 text-left text-sm text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
      >
        <Search className="h-4 w-4 shrink-0" />
        <span className="flex-1">Search dashboards, projects, pages...</span>
        <kbd className="hidden rounded border bg-muted px-1.5 py-0.5 font-mono text-[10px] sm:inline-block">
          ⌘K
        </kbd>
      </button>
      <div className="absolute right-4 top-1/2 -translate-y-1/2 flex items-center gap-3">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              className="flex items-center gap-2 rounded-md border px-2.5 py-1 text-sm transition-colors hover:bg-muted focus:outline-none focus:ring-2 focus:ring-ring"
            >
              {activeProject && (
                <div
                  className="h-5 w-5 rounded flex items-center justify-center flex-shrink-0"
                  style={{backgroundColor: platformInfo?.color || '#4b5563'}}
                >
                  <PlatformIcon className="h-3 w-3 text-white" />
                </div>
              )}
              <span className="truncate max-w-[140px] text-foreground">
                {activeProject?.name ?? 'No project'}
              </span>
              <ChevronDown className="h-3.5 w-3.5 text-muted-foreground" />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-56">
            {projects?.map((project) => {
              const pId = getProjectPlatform(project)
              const pInfo = getPlatformInfo(pId) || getPlatformInfo('other')
              const PIco = pInfo?.icon || Package
              return (
                <DropdownMenuItem
                  key={project.id}
                  onClick={() => setSelectedProjectId(project.id)}
                  className={cn(
                    'flex items-center gap-2',
                    project.id === activeProject?.id && 'bg-accent'
                  )}
                >
                  <div
                    className="h-5 w-5 rounded flex items-center justify-center flex-shrink-0"
                    style={{backgroundColor: pInfo?.color || '#4b5563'}}
                  >
                    <PIco className="h-3 w-3 text-white" />
                  </div>
                  <span className="truncate">{project.name}</span>
                </DropdownMenuItem>
              )
            })}
          </DropdownMenuContent>
        </DropdownMenu>

        <Button
          size="sm"
          variant="outline"
          onClick={() => navigate({to: '/projects'})}
          className="gap-1.5"
        >
          <Plus className="h-4 w-4" />
          <span className="hidden sm:inline">New Project</span>
        </Button>

        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              className="focus:outline-none focus:ring-2 focus:ring-ring rounded-full"
            >
              <Avatar className="h-7 w-7 cursor-pointer">
                <AvatarFallback className="bg-primary text-primary-foreground text-[10px]">
                  {getInitials(user?.name)}
                </AvatarFallback>
              </Avatar>
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-48">
            <DropdownMenuItem onClick={() => navigate({to: '/settings'})}>
              <Settings className="h-4 w-4 mr-2" />
              Settings
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={handleLogout}>
              <LogOut className="h-4 w-4 mr-2" />
              Logout
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </div>
  )
}
