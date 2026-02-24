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

import {useState} from 'react'
import {Link, useNavigate, useRouterState} from '@tanstack/react-router'
import {useMutation, useQuery, useQueryClient} from '@tanstack/react-query'
import {api} from '@/lib/api'
import {trackEvent} from '@/lib/analytics'
import {useProject} from '@/contexts/project-context'
import {ThemeSwitcher} from '@/components/theme-switcher'
import {Button} from '@/components/ui/button'
import {Input} from '@/components/ui/input'
import {Badge} from '@/components/ui/badge'
import {useToast} from '@/hooks/use-toast'
import {
    Activity,
    AlertCircle,
    BarChart3,
    Bell,
    BookOpen,
    Brain,
    Check,
    ChevronLeft,
    ChevronRight,
    Globe,
    Home,
    LayoutDashboard,
    MessageSquare,
    Package,
    Play,
    ScrollText,
    Server,
    Shield,
    Timer,
} from 'lucide-react'
import {cn} from '@/lib/utils'
import {platforms, type PlatformType} from '@/routes/projects'
import {Tooltip, TooltipContent, TooltipTrigger} from '@/components/ui/tooltip'
import {Logo} from '@/components/logo'
import {Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle,} from '@/components/ui/dialog'
import {isSidebarItemVisible} from '@/lib/sidebar-config'
import {hasEnterpriseModule, useEnterpriseFeatures} from '@/hooks/useEnterpriseFeatures'

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
  const { toast } = useToast()
  const { data: features } = useEnterpriseFeatures()

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

  interface NavItem {
    key: string
    icon: React.ComponentType<{className?: string}>
    label: string
    href: string
    requiresProject: boolean
    badge?: string
  }

  const baseNavItems: NavItem[] = [
    // Core Observability (most used)
    { key: 'overview', icon: Home, label: 'Overview', href: '/', requiresProject: false },
    { key: 'issues', icon: AlertCircle, label: 'Issues', href: '/issues', requiresProject: false },
    { key: 'performance', icon: Timer, label: 'Performance', href: '/performance', requiresProject: false },
    { key: 'logs', icon: ScrollText, label: 'Logs', href: activeProjectId ? `/projects/${activeProjectId}/logs` : '/projects', requiresProject: true },
    { key: 'dashboards', icon: LayoutDashboard, label: 'Dashboards', href: '/dashboards', requiresProject: false, badge: 'Beta' },
    // Monitoring & Uptime
    { key: 'monitoring', icon: Server, label: 'Monitoring', href: '/monitoring', requiresProject: false },
    { key: 'uptime', icon: Activity, label: 'Uptime', href: '/uptime', requiresProject: false },
    { key: 'status-pages', icon: Globe, label: 'Status Pages', href: '/status-pages', requiresProject: false },
    // User Insights
    { key: 'replays', icon: Play, label: 'Replays', href: '/replays', requiresProject: false },
    { key: 'feedback', icon: MessageSquare, label: 'Feedback', href: '/feedback', requiresProject: false },
    ...(hasEnterpriseModule(features, 'analytics') ? [{ key: 'analytics', icon: BarChart3, label: 'Analytics', href: '/analytics', requiresProject: false }] : []),
    // Additional Features
    { key: 'releases', icon: Package, label: 'Releases', href: '/releases', requiresProject: false },
    { key: 'ai', icon: Brain, label: 'AI', href: '/ai', requiresProject: false },
    ...(hasEnterpriseModule(features, 'oncall') ? [{ key: 'on-call', icon: Bell, label: 'On-Call', href: '/on-call', requiresProject: false }] : []),
    // Management
    ...(user?.isAdmin ? [{ key: 'admin', icon: Shield, label: 'Admin', href: '/admin', requiresProject: false }] : []),
  ]

  const navItems = baseNavItems.filter(item => {
    return isSidebarItemVisible(item.key, user?.sidebarHiddenItems || [])
  })

  const projectNavItems = activeProjectId ? [
    { icon: BookOpen, label: 'Setup Guide', href: `/projects/${activeProjectId}` },
  ] : []

  const renderSidebarContent = () => (
    <>
      {/* Logo */}
      <div className={cn('p-3 border-b flex items-center', isExpanded ? 'justify-start px-4' : 'justify-center')}>
        {isExpanded ? (
          <Logo className="h-7" />
        ) : (
          <Logo markOnly className="h-7 w-8" />
        )}
      </div>

      {/* Navigation Items */}
      <nav
        className={cn(
          'flex-1 overflow-y-auto',
          isExpanded
            ? 'p-2'
            : 'py-2 [&::-webkit-scrollbar]:hidden [scrollbar-width:none] [-ms-overflow-style:none]'
        )}
      >
        <div className={cn('space-y-1', !isExpanded && 'px-2')}>
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
                  'flex items-center gap-3 py-2 rounded-md transition-colors',
                  isExpanded ? 'px-3' : 'px-2',
                  isActive
                    ? 'bg-primary text-primary-foreground'
                    : 'hover:bg-accent text-muted-foreground hover:text-foreground',
                  !isExpanded && 'justify-center'
                )}
              >
                <Icon className="h-5 w-5 flex-shrink-0" />
                {isExpanded && (
                  <div className="flex items-center gap-2 flex-1">
                    <span className="text-sm font-medium">{item.label}</span>
                    {item.badge && (
                      <Badge variant="secondary" className="h-4 px-1.5 text-[10px] font-medium">
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
                  'flex items-center gap-3 py-2 rounded-md transition-colors',
                  isExpanded ? 'px-3' : 'px-2',
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
              <ThemeSwitcher />
            </div>
          ) : (
            <Tooltip>
              <TooltipTrigger asChild>
                <div>
                  <ThemeSwitcher />
                </div>
              </TooltipTrigger>
              <TooltipContent side="right">
                <p>Toggle theme</p>
              </TooltipContent>
            </Tooltip>
          )}
        </div>

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
    </>
  )

  return (
    <>
      {/* Fixed Sidebar */}
      <div
        className={cn(
          'fixed left-0 top-0 h-full bg-card border-r flex flex-col transition-all duration-300 z-40',
          isExpanded ? 'w-64' : 'w-16'
        )}
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
