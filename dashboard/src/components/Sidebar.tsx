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

import {useEffect, useState} from 'react'
import {Link, useNavigate, useRouterState} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {trackEvent} from '@/lib/analytics'
import {useProject} from '@/contexts/ProjectContext'
import {ThemeSwitcher} from '@/components/ThemeSwitcher'
import {Avatar, AvatarFallback} from '@/components/ui/avatar'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Badge} from '@/components/ui/badge'
import {useToast} from '@/hooks/useToast'
import {
    Activity,
    AlertCircle,
    BarChart3,
    Bell,
    BookOpen,
    Brain,
    HelpCircle,
    Check,
    ChevronDown,
    ChevronLeft,
    ChevronRight,
    Flame,
    FlaskConical,
    Globe,
    Home,
    LayoutDashboard,
    LogOut,
    MessageSquare,
    Package,
    Play,
    Plus,
    Rocket,
    ScrollText,
    Search,
    Server,
    Settings,
    Shield,
    ShieldAlert,
    Sparkles,
    Timer,
} from 'lucide-react'
import {cn} from '@/lib/utils'
import {platforms, getPlatformInfo, type PlatformType} from '@/routes/projects'
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import {Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle,} from '@/components/ui/dialog'
import {Logo} from '@/components/Logo'
import {isSidebarItemVisible} from '@/lib/sidebar-config'
import {hasEnterpriseModule, useEnterpriseFeatures} from '@/hooks/useEnterpriseFeatures'
import {useCommandPalette} from '@/hooks/useCommandPalette'

type PlatformFilter = 'all' | 'mobile' | 'frontend' | 'backend' | 'desktop-gaming'

export const SIDEBAR_COLLAPSED_WIDTH = 56
export const SIDEBAR_EXPANDED_WIDTH = 176

interface SidebarProps {
  readonly isExpanded: boolean
  readonly onExpandedChange: (expanded: boolean) => void
  readonly headerHeight: number
}

function getInitials(name?: string) {
  if (!name) return 'U'
  return name.split(' ').map((n) => n[0]).join('').toUpperCase().slice(0, 2)
}

const platformFilterTabs: Array<{ id: PlatformFilter; label: string }> = [
  { id: 'all', label: 'All' },
  { id: 'mobile', label: 'Mobile' },
  { id: 'frontend', label: 'Frontend' },
  { id: 'backend', label: 'Backend' },
  { id: 'desktop-gaming', label: 'Desktop & Gaming' },
]

export function Sidebar({ isExpanded, onExpandedChange, headerHeight }: SidebarProps) {
  const router = useRouterState()
  const currentPath = router.location.pathname
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { selectedProjectId, setSelectedProjectId } = useProject()
  const { toast } = useToast()
  const { data: features } = useEnterpriseFeatures()
  const { openPalette } = useCommandPalette() ?? {}

  // Create project dialog state
  const [showCreateDialog, setShowCreateDialog] = useState(false)

  useEffect(() => {
    const handler = () => setShowCreateDialog(true)
    globalThis.addEventListener('open-create-project-dialog', handler)
    return () => globalThis.removeEventListener('open-create-project-dialog', handler)
  }, [])
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
      trackEvent('Project Create', { framework: project.framework || 'none' })
      queryClient.invalidateQueries({ queryKey: ['projects'] })
      setSelectedProjectId(project.id)
      resetCreateForm()
      navigate({ to: `/projects/${project.id}` })
    },
    onError: (error: Error) => {
      if (error.message.includes('project_limit_reached')) {
        toast({
          title: 'Project Limit Reached',
          description: (
            <>
              You've reached the maximum number of projects for your plan.{' '}
              <Link to="/settings" search={{tab: 'billing'}} className="underline font-medium">
                Upgrade your plan
              </Link>{' '}
              to add more projects.
            </>
          ),
          variant: 'destructive',
        })
      } else if (error.message.includes('already exists')) {
        toast({
          title: 'Project Already Exists',
          description: 'A project with this name already exists. Please choose a different name.',
          variant: 'destructive',
        })
      } else {
        toast({
          title: 'Error',
          description: error.message || 'Failed to create project. Please try again.',
          variant: 'destructive',
        })
      }
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

  type NavGroupId = 'core' | 'infrastructure' | 'insights' | 'operations' | 'analytics' | 'management'

  interface NavItem {
    key: string
    icon: React.ComponentType<{className?: string}>
    label: string
    href: string
    requiresProject: boolean
    badge?: string
    group: NavGroupId
  }

  const datadogCoreNavItems: NavItem[] = hasEnterpriseModule(features, 'datadog')
    ? [{key: 'profiles', icon: Flame, label: 'Profiles', href: '/profiles', requiresProject: false, group: 'core'}]
    : []
  const datadogOperationsNavItems: NavItem[] = hasEnterpriseModule(features, 'datadog')
    ? [
      {key: 'security', icon: ShieldAlert, label: 'Security', href: '/security', requiresProject: false, group: 'operations'},
      {key: 'synthetics', icon: FlaskConical, label: 'Synthetics', href: '/synthetics', requiresProject: false, group: 'operations'},
    ]
    : []
  const onCallNavItems: NavItem[] = hasEnterpriseModule(features, 'oncall')
    ? [{
      key: 'on-call',
      icon: Bell,
      label: 'On-Call',
      href: '/on-call',
      requiresProject: false,
      group: 'operations',
      ...(features?.selfHost ? {badge: 'Enterprise'} : {}),
    }]
    : []
  const adminNavItems: NavItem[] = user?.isAdmin
    ? [{key: 'admin', icon: Shield, label: 'Admin', href: '/admin', requiresProject: false, group: 'management'}]
    : []

  const baseNavItems: NavItem[] = [
    // Core Observability
    { key: 'overview', icon: Home, label: 'Overview', href: '/', requiresProject: false, group: 'core' },
    { key: 'issues', icon: AlertCircle, label: 'Issues', href: '/issues', requiresProject: false, group: 'core' },
    { key: 'performance', icon: Timer, label: 'Performance', href: '/performance', requiresProject: false, group: 'core' },
    { key: 'logs', icon: ScrollText, label: 'Logs', href: '/logs', requiresProject: false, group: 'core' },
    ...datadogCoreNavItems,
    // Infrastructure & Uptime
    { key: 'monitoring', icon: Server, label: 'Monitoring', href: '/monitoring', requiresProject: false, group: 'infrastructure' },
    { key: 'uptime', icon: Activity, label: 'Uptime', href: '/uptime', requiresProject: false, group: 'infrastructure' },
    { key: 'status-pages', icon: Globe, label: 'Status Pages', href: '/status-pages', requiresProject: false, group: 'infrastructure' },
    // Insights & Tools
    { key: 'dashboards', icon: LayoutDashboard, label: 'Dashboards', href: '/dashboards', requiresProject: false, group: 'insights' },
    { key: 'replays', icon: Play, label: 'Replays', href: '/replays', requiresProject: false, group: 'insights' },
    { key: 'feedback', icon: MessageSquare, label: 'Feedback', href: '/feedback', requiresProject: false, group: 'insights' },
    { key: 'releases', icon: Package, label: 'Releases', href: '/releases', requiresProject: false, group: 'insights' },
    { key: 'ai', icon: Brain, label: 'AI', href: '/ai', requiresProject: false, group: 'insights' },
    // Operations (enterprise)
    ...datadogOperationsNavItems,
    ...onCallNavItems,
    { key: 'analytics', icon: BarChart3, label: 'Analytics', href: '/analytics', requiresProject: false, group: 'analytics' },
    // Management
    ...adminNavItems,
  ]

  const navItems = baseNavItems.filter(item => {
    return isSidebarItemVisible(item.key, user?.sidebarHiddenItems || [])
  })

  const groupLabels: Record<NavGroupId, string> = {
    core: 'Observability',
    infrastructure: 'Infrastructure',
    insights: 'Insights',
    operations: 'Operations',
    analytics: 'Analytics',
    management: 'Management',
  }

  const navGroups = (['core', 'infrastructure', 'insights', 'operations', 'analytics', 'management'] as const).map(groupId => ({
    id: groupId,
    label: groupLabels[groupId],
    items: navItems.filter(item => item.group === groupId),
  })).filter(g => g.items.length > 0)


  const FadingDivider = () => (
    <div
      className="h-px my-1 bg-border shrink-0"
      style={{
        maskImage: 'linear-gradient(to right, transparent, black 15%, black 85%, transparent)',
        WebkitMaskImage: 'linear-gradient(to right, transparent, black 15%, black 85%, transparent)',
      }}
    />
  )

  const renderSidebarContent = () => (
    <>
      {/* Logo at top */}
      <div className={cn('shrink-0 border-b flex items-center justify-center py-2', isExpanded ? 'px-2.5' : 'px-1.5')}>
        <Link to="/" className="flex items-center justify-center focus:outline-none focus:ring-2 focus:ring-ring rounded">
          {isExpanded ? <Logo className="h-6" /> : <Logo markOnly className="h-6 w-8" />}
        </Link>
      </div>
      {/* Search bar */}
      <div className={cn('shrink-0 border-b p-1.5', !isExpanded && 'px-1.5')}>
        {isExpanded ? (
          <button
            type="button"
            onClick={() => openPalette?.()}
            className="flex w-full items-center gap-1.5 rounded-md border bg-muted/50 px-2 py-1 text-left text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          >
            <Search className="h-3 w-3 shrink-0" />
            <span className="flex-1 truncate">Search...</span>
            <span className="flex items-center gap-0.5 rounded border border-border/60 bg-background/60 px-1 py-0.5 text-[9px] text-muted-foreground">
              <Sparkles className="h-2 w-2" />
              <kbd className="font-mono">/</kbd>
            </span>
            <kbd className="rounded border bg-muted px-1 py-0.5 font-mono text-[9px]">⌘K</kbd>
          </button>
        ) : (
          <Tooltip>
            <TooltipTrigger asChild>
              <button
                type="button"
                onClick={() => openPalette?.()}
                className="flex w-full items-center justify-center rounded-md border bg-muted/50 p-1.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
              >
                <Search className="h-3 w-3" />
              </button>
            </TooltipTrigger>
            <TooltipContent side="right">
              <p>Search</p>
              <p className="text-xs text-muted-foreground">⌘K</p>
            </TooltipContent>
          </Tooltip>
        )}
      </div>
      {/* Project chooser */}
      <div className={cn('shrink-0 border-b p-1.5', !isExpanded && 'px-1.5')}>
        {projects && projects.length > 0 ? (
          (() => {
            const platformId = activeProject ? (activeProject.keys?.[0]?.platformTarget || activeProject.framework || 'other') : 'other'
            const platformInfo = getPlatformInfo(platformId) || getPlatformInfo('other')
            const PlatformIcon = platformInfo?.icon || Package
            return isExpanded ? (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  type="button"
                  className="flex w-full items-center gap-1.5 rounded-md border bg-muted/50 px-2 py-1 text-left text-xs cursor-default transition-colors hover:bg-muted focus:outline-none focus:ring-2 focus:ring-ring"
                >
                  {activeProject && (
                    <div
                      className="h-4 w-4 rounded flex items-center justify-center flex-shrink-0"
                      style={{backgroundColor: platformInfo?.color || '#4b5563'}}
                    >
                      <PlatformIcon className="h-2.5 w-2.5 text-white" />
                    </div>
                  )}
                  <span className="flex-1 truncate text-foreground">{activeProject?.name}</span>
                  <ChevronDown className="h-3 w-3 shrink-0 text-muted-foreground" />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" side="right" className="w-[--radix-dropdown-menu-trigger-width] min-w-[10rem]">
                {projects.map((project) => {
                  const pId = project.keys?.[0]?.platformTarget || project.framework || 'other'
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
                      className="h-4 w-4 rounded flex items-center justify-center flex-shrink-0"
                      style={{backgroundColor: pInfo?.color || '#4b5563'}}
                    >
                      <PIco className="h-2.5 w-2.5 text-white" />
                      </div>
                      <span className="truncate">{project.name}</span>
                    </DropdownMenuItem>
                  )
                })}
                <DropdownMenuSeparator />
                <DropdownMenuItem onClick={() => window.dispatchEvent(new CustomEvent('open-create-project-dialog'))} className="text-xs">
                  <Plus className="h-3 w-3" />
                  New Project
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          ) : (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  type="button"
                  className="flex w-full items-center justify-center rounded-md border bg-muted/50 p-1.5 cursor-default transition-colors hover:bg-muted focus:outline-none focus:ring-2 focus:ring-ring"
                >
                  {activeProject && (
                    <div
                      className="h-4 w-4 rounded flex items-center justify-center"
                      style={{backgroundColor: platformInfo?.color || '#4b5563'}}
                    >
                      <PlatformIcon className="h-2.5 w-2.5 text-white" />
                    </div>
                  )}
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" side="right" className="min-w-[10rem]">
                {projects.map((project) => {
                  const pId = project.keys?.[0]?.platformTarget || project.framework || 'other'
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
                      className="h-4 w-4 rounded flex items-center justify-center flex-shrink-0"
                      style={{backgroundColor: pInfo?.color || '#4b5563'}}
                    >
                      <PIco className="h-2.5 w-2.5 text-white" />
                      </div>
                      <span className="truncate">{project.name}</span>
                    </DropdownMenuItem>
                  )
                })}
                <DropdownMenuSeparator />
                <DropdownMenuItem onClick={() => window.dispatchEvent(new CustomEvent('open-create-project-dialog'))} className="text-xs">
                  <Plus className="h-3 w-3" />
                  New Project
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          )
            })()
        ) : (
          isExpanded && (
            <Button
              size="sm"
              variant="outline"
              onClick={() => window.dispatchEvent(new CustomEvent('open-create-project-dialog'))}
              className="w-full justify-center gap-1 h-7 text-xs"
            >
              <Plus className="h-3 w-3" />
              New Project
            </Button>
          )
        )}
      </div>
      {/* Navigation Items */}
      <nav
        className={cn(
          'flex-1 overflow-y-auto overscroll-contain',
          isExpanded
            ? 'p-1.5'
            : 'py-1.5 [&::-webkit-scrollbar]:hidden [scrollbar-width:none] [-ms-overflow-style:none]'
        )}
      >
        <div className={cn(!isExpanded && 'px-1.5')}>
          {navGroups.map((group, groupIndex) => (
            <div key={group.id}>
              {groupIndex > 0 && <FadingDivider />}
              {isExpanded && (
                <div className="px-2.5 pt-0.5 pb-0.5">
                  <span className="text-[10px] font-medium uppercase tracking-wider text-muted-foreground/70">
                    {group.label}
                  </span>
                </div>
              )}
              <div className={cn('space-y-1')}>
                {group.items.map((item) => {
                  const isActive = item.href === '/'
                    ? currentPath === '/'
                    : currentPath === item.href ||
                      (currentPath.startsWith(item.href + '/') &&
                        !navItems.some(
                          (other) =>
                            other.href !== item.href &&
                            (currentPath === other.href || currentPath.startsWith(other.href + '/')) &&
                            other.href.startsWith(item.href + '/')
                        ))
                  const Icon = item.icon

                  const linkContent = (
                    <Link
                      key={item.href}
                      to={item.href}
                      className={cn(
                        'flex items-center gap-2 py-1.5 rounded-md transition-colors',
                        isExpanded ? 'px-2.5' : 'px-1.5',
                        isActive
                          ? 'bg-[hsl(var(--sidebar-active))] text-[hsl(var(--sidebar-active-foreground))]'
                          : 'hover:bg-accent text-muted-foreground hover:text-foreground',
                        !isExpanded && 'justify-center'
                      )}
                    >
                      <Icon className="h-4 w-4 flex-shrink-0" />
                      {isExpanded && (
                        <div className="flex items-center gap-1.5 flex-1">
                          <span className="text-xs font-medium">{item.label}</span>
                          {item.badge && (
                            <Badge variant="secondary" className="h-3 px-1 text-[9px] font-medium">
                              {item.badge}
                            </Badge>
                          )}
                        </div>
                      )}
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
            </div>
          ))}
        </div>
      </nav>

      {/* Bottom Section - compact single row when expanded */}
      <div className={cn('border-t p-1.5', isExpanded ? 'py-1' : 'space-y-0.5')}>
        {isExpanded ? (
          <div className="flex items-center gap-1">
            <Tooltip>
              <TooltipTrigger asChild>
                <div className="shrink-0">
                  <ThemeSwitcher />
                </div>
              </TooltipTrigger>
              <TooltipContent side="right">
                <p>Theme</p>
              </TooltipContent>
            </Tooltip>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  type="button"
                  className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
                  title="Sentry SDK Setup and Docs"
                >
                  <HelpCircle className="h-3.5 w-3.5" />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" side="right" className="w-48">
                <DropdownMenuItem onClick={() => navigate({ to: activeProjectId ? `/projects/${activeProjectId}` : '/' })}>
                  <Rocket className="h-4 w-4 mr-2" />
                  Sentry SDK Setup
                </DropdownMenuItem>
                <DropdownMenuItem asChild>
                  <a href="/docs/" target="_blank" rel="noopener noreferrer">
                    <BookOpen className="h-4 w-4 mr-2" />
                    Docs
                  </a>
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-7 w-7 shrink-0 text-muted-foreground hover:text-foreground"
                  onClick={() => onExpandedChange(false)}
                >
                  <ChevronLeft className="h-3.5 w-3.5" />
                </Button>
              </TooltipTrigger>
              <TooltipContent side="right">
                <p>Collapse</p>
              </TooltipContent>
            </Tooltip>
            <div className="ml-auto shrink-0">
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                <button
                  type="button"
                  className="flex h-7 w-7 items-center justify-center rounded-md transition-colors hover:bg-accent focus:outline-none focus:ring-2 focus:ring-ring"
                >
                  <Avatar className="h-5 w-5">
                      <AvatarFallback className="bg-primary text-primary-foreground text-[8px]">
                        {getInitials(user?.name)}
                      </AvatarFallback>
                    </Avatar>
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" side="right" className="w-48">
            <DropdownMenuItem onClick={() => navigate({to: '/settings', search: {tab: 'api-keys'}})}>
              <Settings className="h-4 w-4 mr-2" />
              Settings
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={async () => { await api.logout(); window.location.href = '/login' }}>
              <LogOut className="h-4 w-4 mr-2" />
              Logout
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
            </div>
          </div>
        ) : (
          <>
            <Tooltip>
              <TooltipTrigger asChild>
                <div className="flex justify-center py-1">
                  <ThemeSwitcher />
                </div>
              </TooltipTrigger>
              <TooltipContent side="right">
                <p>Theme</p>
              </TooltipContent>
            </Tooltip>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  type="button"
                  className="flex w-full items-center justify-center rounded-md py-0.5 text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
                  title="Sentry SDK Setup and Docs"
                >
                  <HelpCircle className="h-3.5 w-3.5" />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start" side="right" className="w-48">
                <DropdownMenuItem onClick={() => navigate({ to: activeProjectId ? `/projects/${activeProjectId}` : '/' })}>
                  <Rocket className="h-4 w-4 mr-2" />
                  Sentry SDK Setup
                </DropdownMenuItem>
                <DropdownMenuItem asChild>
                  <a href="/docs/" target="_blank" rel="noopener noreferrer">
                    <BookOpen className="h-4 w-4 mr-2" />
                    Docs
                  </a>
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
            <Button
              variant="ghost"
              size="icon"
              className="w-full py-0.5 text-muted-foreground hover:text-foreground"
              onClick={() => onExpandedChange(true)}
            >
              <ChevronRight className="h-3.5 w-3.5 mx-auto" />
            </Button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  type="button"
                  className="flex w-full justify-center rounded-md py-1 transition-colors hover:bg-accent focus:outline-none focus:ring-2 focus:ring-ring"
                >
                  <Avatar className="h-5 w-5">
                    <AvatarFallback className="bg-primary text-primary-foreground text-[8px]">
                      {getInitials(user?.name)}
                    </AvatarFallback>
                  </Avatar>
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" side="right" className="w-48">
                <DropdownMenuItem onClick={() => navigate({to: '/settings', search: {tab: 'api-keys'}})}>
                  <Settings className="h-4 w-4 mr-2" />
                  Settings
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuItem onClick={async () => { await api.logout(); window.location.href = '/login' }}>
                  <LogOut className="h-4 w-4 mr-2" />
                  Logout
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </>
        )}
      </div>
    </>
  )

  return (
    <>
      {/* Fixed Sidebar */}
      <div
        className={cn(
          'sidebar fixed left-0 bg-card border-r flex flex-col transition-all duration-300 z-40',
          isExpanded ? 'w-44' : 'w-14'
        )}
        style={{top: headerHeight, height: `calc(100vh - ${headerHeight}px)`}}
      >
        {renderSidebarContent()}
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
              <label htmlFor="new-project-name" className="text-sm font-medium mb-2 block">Project Name</label>
              <Input
                id="new-project-name"
                placeholder="My awesome app"
                value={newProjectName}
                onChange={(e) => setNewProjectName(e.target.value)}
                autoFocus
              />
            </div>

            <div>
              <p className="text-sm font-medium mb-3 block">Select Platform</p>
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
                <p className="text-sm font-medium mb-3 block">Select Target Platforms</p>
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
