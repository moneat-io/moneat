import {useState} from 'react'
import {Link, useNavigate, useRouterState} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {useProject} from '@/contexts/project-context'
import {ThemeToggle} from '@/components/theme-toggle'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {
    BookOpen,
    Check,
    ChevronLeft,
    ChevronRight,
    Home,
    LogOut,
    MessageSquare,
    Package,
    Play,
    Plus,
    ScrollText,
    Server,
    Settings,
    Shield,
    Timer,
    User,
} from 'lucide-react'
import {cn} from '@/lib/utils'
import {getPlatformInfo, platforms, type PlatformType} from '@/routes/projects'
import {DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger} from '@/components/ui/dropdown-menu'
import {Avatar, AvatarFallback} from '@/components/ui/avatar'
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip'
import {Logo} from '@/components/logo'
import {Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle,} from '@/components/ui/dialog'

type PlatformFilter = 'all' | 'mobile' | 'frontend' | 'backend' | 'desktop-gaming'

export const SIDEBAR_COLLAPSED_WIDTH = 64
export const SIDEBAR_EXPANDED_WIDTH = 256

interface SidebarProps {
  isExpanded: boolean
  onExpandedChange: (expanded: boolean) => void
}

const platformFilterTabs: Array<{ id: PlatformFilter; label: string }> = [
  { id: 'all', label: 'All' },
  { id: 'mobile', label: 'Mobile' },
  { id: 'frontend', label: 'Frontend' },
  { id: 'backend', label: 'Backend' },
  { id: 'desktop-gaming', label: 'Desktop & Gaming' },
]

export function Sidebar({ isExpanded, onExpandedChange }: SidebarProps) {
  const router = useRouterState()
  const currentPath = router.location.pathname
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { selectedProjectId, setSelectedProjectId } = useProject()

  // Create project dialog state
  const [showCreateDialog, setShowCreateDialog] = useState(false)
  const [newProjectName, setNewProjectName] = useState('')
  const [selectedPlatform, setSelectedPlatform] = useState<string | null>(null)
  const [selectedTargets, setSelectedTargets] = useState<string[]>([])
  const [platformFilter, setPlatformFilter] = useState<PlatformFilter>('all')

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

  const activeProject = projects?.find((project) => project.id === selectedProjectId) ?? projects?.[0] ?? null
  const activeProjectId = activeProject?.id ?? null

  const createProjectMutation = useMutation({
    mutationFn: (data: { name: string; framework: string; targets?: string[] }) =>
      api.createProject(data.name, data.framework, data.targets),
    onSuccess: (project) => {
      queryClient.invalidateQueries({ queryKey: ['projects'] })
      setSelectedProjectId(project.id)
      resetCreateForm()
      navigate({ to: `/projects/${project.id}` })
    },
  })

  const resetCreateForm = () => {
    setShowCreateDialog(false)
    setNewProjectName('')
    setSelectedPlatform(null)
    setSelectedTargets([])
    setPlatformFilter('all')
  }

  const handleCreateProject = () => {
    if (newProjectName && selectedPlatform) {
      const platform = platforms.find(p => p.id === selectedPlatform)
      const targets = platform?.targets && selectedTargets.length > 0 ? selectedTargets : undefined
      createProjectMutation.mutate({
        name: newProjectName,
        framework: selectedPlatform,
        targets,
      })
    }
  }

  const handlePlatformSelect = (platformId: string) => {
    setSelectedPlatform(platformId)
    const platform = platforms.find(p => p.id === platformId)
    if (platform?.targets && platform.defaultTargets) {
      setSelectedTargets(platform.defaultTargets)
    } else {
      setSelectedTargets([])
    }
  }

  const toggleTarget = (targetId: string) => {
    setSelectedTargets(prev =>
      prev.includes(targetId)
        ? prev.filter(id => id !== targetId)
        : [...prev, targetId]
    )
  }

  const filteredPlatforms = platforms.filter((platform: PlatformType) => {
    if (platform.alwaysVisible || platformFilter === 'all') return true
    if (platformFilter === 'desktop-gaming') {
      return platform.category === 'desktop' || platform.category === 'gaming'
    }
    return platform.category === platformFilter
  })

  const handleProjectSelect = (projectId: number) => {
    setSelectedProjectId(projectId)
    // Keep users on the same project subpage when switching projects.
    if (/^\/projects\/[^/]+\/settings\/?$/.test(currentPath)) {
      navigate({ to: '/projects/$projectId/settings', params: { projectId: String(projectId) } })
      return
    }
    if (/^\/projects\/[^/]+\/logs\/?$/.test(currentPath)) {
      navigate({ to: '/projects/$projectId/logs', params: { projectId: String(projectId) } })
      return
    }
    if (/^\/projects\/[^/]+\/?$/.test(currentPath) || currentPath === '/projects') {
      navigate({ to: '/projects/$projectId', params: { projectId: String(projectId) } })
    }
  }

  const navItems = [
    { icon: Home, label: 'Dashboard', href: '/', requiresProject: false },
    { icon: Timer, label: 'Performance', href: '/performance', requiresProject: false },
    { icon: ScrollText, label: 'Logs', href: activeProjectId ? `/projects/${activeProjectId}/logs` : '/projects', requiresProject: true },
    { icon: Play, label: 'Replays', href: '/replays', requiresProject: false },
    { icon: MessageSquare, label: 'Feedback', href: '/feedback', requiresProject: false },
    { icon: Package, label: 'Releases', href: '/releases', requiresProject: false },
    { icon: Server, label: 'Monitoring', href: '/monitoring', requiresProject: false },
    ...(user?.isAdmin ? [{ icon: Shield, label: 'Admin', href: '/admin', requiresProject: false }] : []),
    { icon: Settings, label: 'Settings', href: '/settings', requiresProject: false },
  ]

  const projectNavItems = activeProjectId ? [
    { icon: BookOpen, label: 'Setup Guide', href: `/projects/${activeProjectId}` },
  ] : []

  const getProjectPlatform = (project: any) => {
    return project?.keys?.[0]?.platformTarget || project?.framework || null
  }

  const renderProjectPlatformIcon = (
    project: any,
    iconClassName = 'h-3.5 w-3.5',
    containerClassName = 'h-5 w-5'
  ) => {
    const platformId = getProjectPlatform(project)
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
    <>
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

        {/* Projects Section */}
        <div className="border-b">
          {isExpanded ? (
            <div className="p-2">
              <div className="flex items-center justify-between px-2 mb-1">
                <span className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">Projects</span>
                <Tooltip>
                  <TooltipTrigger asChild>
                    <Button
                      variant="ghost"
                      size="icon"
                      className="h-5 w-5 text-muted-foreground hover:text-foreground"
                      onClick={() => setShowCreateDialog(true)}
                    >
                      <Plus className="h-3.5 w-3.5" />
                    </Button>
                  </TooltipTrigger>
                  <TooltipContent side="right">
                    <p>New Project</p>
                  </TooltipContent>
                </Tooltip>
              </div>
              <div className="max-h-40 overflow-y-auto space-y-0.5">
                {projects && projects.length > 0 ? (
                  projects.map((project) => (
                    <button
                      key={project.id}
                      onClick={() => handleProjectSelect(project.id)}
                      className={cn(
                        'w-full flex items-center gap-2 px-2 py-1.5 rounded-md text-sm transition-colors text-left',
                        project.id === activeProjectId
                          ? 'bg-primary/10 text-primary font-medium'
                          : 'text-muted-foreground hover:bg-accent hover:text-foreground'
                      )}
                    >
                      {renderProjectPlatformIcon(project, 'h-3 w-3', 'h-5 w-5')}
                      <span className="truncate">{project.name}</span>
                    </button>
                  ))
                ) : (
                  <p className="text-xs text-muted-foreground px-2 py-1">No projects yet</p>
                )}
              </div>
            </div>
          ) : (
            <div className="p-2 space-y-1">
              <Tooltip>
                <TooltipTrigger asChild>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="w-full h-8 text-muted-foreground hover:text-foreground"
                    onClick={() => setShowCreateDialog(true)}
                  >
                    <Plus className="h-4 w-4" />
                  </Button>
                </TooltipTrigger>
                <TooltipContent side="right">
                  <p>New Project</p>
                </TooltipContent>
              </Tooltip>
              <div className="max-h-32 overflow-y-auto space-y-1">
                {projects && projects.length > 0 && activeProject ? (
                  <DropdownMenu>
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <DropdownMenuTrigger asChild>
                          <button
                            className="w-full flex justify-center py-1 rounded-md transition-colors bg-primary/10 hover:bg-primary/20"
                          >
                            {renderProjectPlatformIcon(activeProject, 'h-3.5 w-3.5', 'h-7 w-7')}
                          </button>
                        </DropdownMenuTrigger>
                      </TooltipTrigger>
                      <TooltipContent side="right">
                        <p>Switch Project ({activeProject.name})</p>
                      </TooltipContent>
                    </Tooltip>
                    <DropdownMenuContent side="right" align="start" className="w-56">
                      {projects.map((project) => (
                        <DropdownMenuItem
                          key={project.id}
                          className="flex items-center gap-2"
                          onClick={() => handleProjectSelect(project.id)}
                        >
                          {renderProjectPlatformIcon(project, 'h-3 w-3', 'h-5 w-5')}
                          <span className="flex-1 truncate">{project.name}</span>
                          {project.id === activeProjectId && <Check className="h-3.5 w-3.5 text-primary" />}
                        </DropdownMenuItem>
                      ))}
                    </DropdownMenuContent>
                  </DropdownMenu>
                ) : (
                  <p className="text-[11px] text-center text-muted-foreground px-1 py-1">No projects</p>
                )}
              </div>
            </div>
          )}
        </div>

        {/* Navigation Items */}
        <nav className="flex-1 p-2 overflow-y-auto">
          <div className="space-y-1">
            {navItems.map((item) => {
              const isActive = item.href === '/'
                ? currentPath === '/'
                : currentPath === item.href || currentPath.startsWith(item.href + '/')
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
            onClick={() => onExpandedChange(!isExpanded)}
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

      {/* Create Project Dialog */}
      <Dialog open={showCreateDialog} onOpenChange={(open) => { if (!open) resetCreateForm() }}>
        <DialogContent className="max-w-2xl">
          <DialogHeader>
            <DialogTitle>Create New Project</DialogTitle>
            <DialogDescription>
              Set up a new project to start tracking errors and monitoring your applications.
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-4 mt-4">
            <div>
              <label className="text-sm font-medium mb-2 block">Project Name</label>
              <Input
                placeholder="My awesome app"
                value={newProjectName}
                onChange={(e) => setNewProjectName(e.target.value)}
                autoFocus
              />
            </div>

            <div>
              <label className="text-sm font-medium mb-3 block">Select Platform</label>
              <div className="mb-3 flex flex-wrap gap-2">
                {platformFilterTabs.map((tab) => (
                  <Button
                    key={tab.id}
                    type="button"
                    size="sm"
                    variant={platformFilter === tab.id ? 'default' : 'outline'}
                    onClick={() => setPlatformFilter(tab.id)}
                  >
                    {tab.label}
                  </Button>
                ))}
              </div>
              <div className="max-h-64 overflow-y-auto rounded-lg border p-3 pr-2">
                <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 gap-2">
                  {filteredPlatforms.map((platform: PlatformType) => {
                    const Icon = platform.icon
                    return (
                      <button
                        key={platform.id}
                        onClick={() => handlePlatformSelect(platform.id)}
                        className={cn(
                          'relative flex flex-col items-center gap-1.5 p-3 rounded-lg border-2 transition-all',
                          selectedPlatform === platform.id
                            ? 'border-primary bg-primary/5 shadow-md'
                            : 'border-border hover:border-primary/50 hover:bg-accent'
                        )}
                      >
                        <div className="p-2 rounded-lg" style={{ backgroundColor: platform.color }}>
                          <Icon className="h-5 w-5 text-white" />
                        </div>
                        <span className="text-xs font-medium text-center leading-tight">{platform.name}</span>
                      </button>
                    )
                  })}
                </div>
              </div>
            </div>

            {/* Target selection for multi-platform frameworks */}
            {selectedPlatform && platforms.find(p => p.id === selectedPlatform)?.targets && (
              <div>
                <label className="text-sm font-medium mb-3 block">Select Target Platforms</label>
                <div className="rounded-lg border p-4">
                  <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
                    {platforms.find(p => p.id === selectedPlatform)?.targets?.map(target => (
                      <button
                        key={target.id}
                        type="button"
                        onClick={() => toggleTarget(target.id)}
                        className={cn(
                          'flex items-center gap-2 px-3 py-2 rounded-lg border-2 transition-all text-sm font-medium',
                          selectedTargets.includes(target.id)
                            ? 'border-primary bg-primary/5'
                            : 'border-border hover:border-primary/50 hover:bg-accent'
                        )}
                      >
                        <div className={cn(
                          'w-4 h-4 rounded border-2 flex items-center justify-center',
                          selectedTargets.includes(target.id) ? 'bg-primary border-primary' : 'border-border'
                        )}>
                          {selectedTargets.includes(target.id) && (
                            <Check className="w-3 h-3 text-white" />
                          )}
                        </div>
                        {target.name}
                      </button>
                    ))}
                  </div>
                  {selectedTargets.length === 0 && (
                    <p className="text-sm text-destructive mt-2">Please select at least one target platform</p>
                  )}
                </div>
              </div>
            )}

            <div className="flex gap-2 pt-2">
              <Button
                onClick={handleCreateProject}
                disabled={
                  !newProjectName ||
                  !selectedPlatform ||
                  (platforms.find(p => p.id === selectedPlatform)?.targets && selectedTargets.length === 0) ||
                  createProjectMutation.isPending
                }
              >
                {createProjectMutation.isPending ? 'Creating...' : 'Create Project'}
              </Button>
              <Button variant="outline" onClick={resetCreateForm}>
                Cancel
              </Button>
            </div>
          </div>
        </DialogContent>
      </Dialog>
    </>
  )
}
