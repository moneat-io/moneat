import { useState } from 'react'
import { Link, useNavigate, useRouterState } from '@tanstack/react-router'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api'
import { useProject } from '@/contexts/project-context'
import { ThemeToggle } from '@/components/theme-toggle'
import { Button } from '@/components/ui/button'
import {
  Home,
  FolderKanban,
  Settings,
  LogOut,
  ChevronLeft,
  ChevronRight,
  User,
  BarChart3,
  Timer,
  BookOpen,
  Package,
  Play,
  MessageSquare,
} from 'lucide-react'
import { cn } from '@/lib/utils'
import { getPlatformInfo } from '@/routes/projects'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
} from '@/components/ui/select'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { Logo } from '@/components/logo'

export function Sidebar() {
  const [isExpanded, setIsExpanded] = useState(false)
  const router = useRouterState()
  const currentPath = router.location.pathname
  const navigate = useNavigate()
  const { selectedProjectId, setSelectedProjectId } = useProject()

  const handleProjectChange = (projectId: number) => {
    setSelectedProjectId(projectId)
    // If we're on a project setup page, navigate to the new project's setup page
    if (/^\/projects\/\d+$/.test(currentPath)) {
      navigate({ to: '/projects/$projectId', params: { projectId: String(projectId) } })
    }
  }

  const { data: user } = useQuery({
    queryKey: ['currentUser'],
    queryFn: () => api.getCurrentUser(),
    enabled: api.isAuthenticated(),
  })

  const { data: projects } = useQuery({
    queryKey: ['projects'],
    queryFn: () => api.getProjects(),
    enabled: api.isAuthenticated(),
  })

  const navItems = [
    { icon: Home, label: 'Dashboard', href: '/', requiresProject: false },
    { icon: BarChart3, label: 'Analytics', href: '/analytics', requiresProject: false },
    { icon: Timer, label: 'Performance', href: '/performance', requiresProject: false },
    { icon: Play, label: 'Replays', href: '/replays', requiresProject: false },
    { icon: MessageSquare, label: 'Feedback', href: '/feedback', requiresProject: false },
    { icon: Package, label: 'Releases', href: '/releases', requiresProject: false },
    { icon: FolderKanban, label: 'Projects', href: '/projects', requiresProject: false },
    { icon: Settings, label: 'Settings', href: '/settings', requiresProject: false },
  ]
  
  const projectNavItems = selectedProjectId ? [
    { icon: BookOpen, label: 'Setup Guide', href: `/projects/${selectedProjectId}` }
  ] : []
  const currentProject = projects?.find((project) => project.id === selectedProjectId) || projects?.[0]

  const renderProjectPlatformIcon = (
    platformId?: string,
    iconClassName = 'h-3.5 w-3.5',
    containerClassName = 'h-5 w-5'
  ) => {
    const platformInfo = getPlatformInfo(platformId) || getPlatformInfo('other')
    const PlatformIcon = platformInfo?.icon || Package

    return (
      <div
        className={cn('rounded flex items-center justify-center flex-shrink-0', containerClassName)}
        style={{ backgroundColor: platformInfo?.color || '#4b5563' }}
      >
        <PlatformIcon className={cn(iconClassName, 'text-white')} />
      </div>
    )
  }

  const getInitials = (name?: string) => {
    if (!name) return 'U'
    return name
      .split(' ')
      .map((n) => n[0])
      .join('')
      .toUpperCase()
      .slice(0, 2)
  }

  return (
    <div
      className={cn(
        'fixed left-0 top-0 h-full bg-card border-r flex flex-col transition-all duration-300 z-40',
        isExpanded ? 'w-64' : 'w-16'
      )}
    >
      {/* Logo */}
      <div className={cn('p-3 border-b flex items-center', isExpanded ? 'justify-start px-4' : 'justify-center')}>
        {isExpanded ? (
          <Logo className="h-7" />
        ) : (
          <Logo markOnly className="h-7 w-8" />
        )}
      </div>

      {/* User Section */}
      <div className="p-4 border-b">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              variant="ghost"
              className={cn(
                'w-full justify-start gap-3 hover:bg-accent',
                !isExpanded && 'justify-center px-0'
              )}
            >
              <Avatar className="h-8 w-8">
                <AvatarFallback className="bg-primary text-primary-foreground text-xs">
                  {getInitials(user?.name)}
                </AvatarFallback>
              </Avatar>
              {isExpanded && (
                <div className="flex-1 text-left overflow-hidden">
                  <div className="font-semibold text-sm truncate">{user?.name || 'User'}</div>
                  <div className="text-xs text-muted-foreground truncate">{user?.email}</div>
                </div>
              )}
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="w-56">
            <DropdownMenuItem className="flex items-center gap-2">
              <User className="h-4 w-4" />
              <div>
                <div className="font-semibold">{user?.name || 'User'}</div>
                <div className="text-xs text-muted-foreground">{user?.email}</div>
              </div>
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      {/* Project Selector */}
      {projects && projects.length > 0 && (
        <div className="p-2 border-b">
          {isExpanded ? (
            <Select
              value={selectedProjectId?.toString() || projects[0]?.id.toString() || ''}
              onValueChange={(val) => handleProjectChange(Number(val))}
            >
              <SelectTrigger className="w-full">
                <div className="flex items-center gap-2 min-w-0">
                  {renderProjectPlatformIcon(currentProject?.platform, 'h-3.5 w-3.5', 'h-5 w-5')}
                  <span className="truncate">{currentProject?.name || 'Select project'}</span>
                </div>
              </SelectTrigger>
              <SelectContent>
                {projects.map((project) => (
                  <SelectItem key={project.id} value={project.id.toString()}>
                    <div className="flex items-center gap-2">
                      {renderProjectPlatformIcon(project.platform, 'h-3 w-3', 'h-4 w-4')}
                      <span>{project.name}</span>
                    </div>
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          ) : (
            <DropdownMenu>
              <Tooltip>
                <TooltipTrigger asChild>
                  <DropdownMenuTrigger asChild>
                    <Button variant="ghost" className="w-full justify-center px-0">
                      {renderProjectPlatformIcon(currentProject?.platform, 'h-4 w-4', 'h-8 w-8')}
                    </Button>
                  </DropdownMenuTrigger>
                </TooltipTrigger>
                <TooltipContent side="right">
                  <p>{currentProject?.name || 'Select project'}</p>
                </TooltipContent>
              </Tooltip>
              <DropdownMenuContent align="start" side="right" className="w-56">
                {projects.map((project) => (
                  <DropdownMenuItem
                    key={project.id}
                    onClick={() => handleProjectChange(project.id)}
                    className={cn(
                      'cursor-pointer',
                      project.id === selectedProjectId && 'bg-accent'
                    )}
                  >
                    <div className="mr-2">
                      {renderProjectPlatformIcon(project.platform, 'h-3 w-3', 'h-4 w-4')}
                    </div>
                    {project.name}
                  </DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
          )}
        </div>
      )}

      {/* Navigation Items */}
      <nav className="flex-1 p-2">
        <div className="space-y-1">
          {navItems.map((item) => {
            const isActive = currentPath === item.href
            const Icon = item.icon

            const linkContent = (
              <Link
                key={item.href}
                to={item.href}
                className={cn(
                  'flex items-center gap-3 px-3 py-2 rounded-md transition-colors',
                  isActive
                    ? 'bg-primary text-primary-foreground'
                    : 'hover:bg-accent text-muted-foreground hover:text-foreground',
                  !isExpanded && 'justify-center'
                )}
              >
                <Icon className="h-5 w-5 flex-shrink-0" />
                {isExpanded && <span className="text-sm font-medium">{item.label}</span>}
              </Link>
            )

            if (!isExpanded) {
              return (
                <Tooltip key={item.href}>
                  <TooltipTrigger asChild>
                    {linkContent}
                  </TooltipTrigger>
                  <TooltipContent side="right">
                    <p>{item.label}</p>
                  </TooltipContent>
                </Tooltip>
              )
            }

            return linkContent
          })}
        </div>
      </nav>

      {/* Setup Guide - at bottom, above divider */}
      {projectNavItems.length > 0 && (
        <div className="p-2">
          {projectNavItems.map((item) => {
            const isActive = currentPath === item.href
            const Icon = item.icon

            const linkContent = (
              <Link
                key={item.href}
                to={item.href}
                className={cn(
                  'flex items-center gap-3 px-3 py-2 rounded-md transition-colors',
                  isActive
                    ? 'bg-primary text-primary-foreground'
                    : 'hover:bg-accent text-muted-foreground hover:text-foreground',
                  !isExpanded && 'justify-center'
                )}
              >
                <Icon className="h-5 w-5 flex-shrink-0" />
                {isExpanded && <span className="text-sm font-medium">{item.label}</span>}
              </Link>
            )

            if (!isExpanded) {
              return (
                <Tooltip key={item.href}>
                  <TooltipTrigger asChild>
                    {linkContent}
                  </TooltipTrigger>
                  <TooltipContent side="right">
                    <p>{item.label}</p>
                  </TooltipContent>
                </Tooltip>
              )
            }

            return linkContent
          })}
        </div>
      )}

      {/* Bottom Section */}
      <div className="p-2 border-t space-y-1">
        {/* Theme Toggle */}
        <div className={cn('w-full', !isExpanded && 'flex justify-center')}>
          {isExpanded ? (
            <div className="flex items-center justify-between px-3 py-2">
              <span className="text-sm text-muted-foreground">Theme</span>
              <ThemeToggle />
            </div>
          ) : (
            <Tooltip>
              <TooltipTrigger asChild>
                <div>
                  <ThemeToggle />
                </div>
              </TooltipTrigger>
              <TooltipContent side="right">
                <p>Toggle theme</p>
              </TooltipContent>
            </Tooltip>
          )}
        </div>

        {/* Logout Button */}
        {isExpanded ? (
          <Button
            variant="ghost"
            className="w-full justify-start gap-3 text-muted-foreground hover:text-foreground"
            onClick={() => {
              api.logout()
              window.location.href = '/login'
            }}
          >
            <LogOut className="h-5 w-5 flex-shrink-0" />
            <span className="text-sm">Logout</span>
          </Button>
        ) : (
          <Tooltip>
            <TooltipTrigger asChild>
              <Button
                variant="ghost"
                className="w-full justify-center px-0 text-muted-foreground hover:text-foreground"
                onClick={() => {
                  api.logout()
                  window.location.href = '/login'
                }}
              >
                <LogOut className="h-5 w-5 flex-shrink-0" />
              </Button>
            </TooltipTrigger>
            <TooltipContent side="right">
              <p>Logout</p>
            </TooltipContent>
          </Tooltip>
        )}

        {/* Expand/Collapse Button */}
        <Button
          variant="ghost"
          className={cn(
            'w-full justify-start gap-3 text-muted-foreground hover:text-foreground',
            !isExpanded && 'justify-center px-0'
          )}
          onClick={() => setIsExpanded(!isExpanded)}
        >
          {isExpanded ? (
            <>
              <ChevronLeft className="h-5 w-5 flex-shrink-0" />
              <span className="text-sm">Collapse</span>
            </>
          ) : (
            <ChevronRight className="h-5 w-5 flex-shrink-0" />
          )}
        </Button>
      </div>
    </div>
  )
}
