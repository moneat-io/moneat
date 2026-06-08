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

import {createFileRoute, Link, Outlet, redirect, useRouterState} from '@tanstack/react-router'
import {useEffect, useRef, useState, type ComponentType} from 'react'
import {api} from '@/lib/api'
import {useEnterpriseFeatures, hasEnterpriseModule} from '@/hooks/useEnterpriseFeatures'
import {
    ArrowLeft,
    Box,
    Bug,
    CalendarClock,
    Database,
    HardDrive,
    HelpCircle,
    MoreHorizontal,
    Network,
    Package,
    Router,
    Ship,
    Terminal,
    Map as MapIcon,
} from 'lucide-react'
import {cn} from '@/lib/utils'
import {Button} from '@/components/ui/button'
import {DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger} from '@/components/ui/dropdown-menu'
import {Tooltip, TooltipContent, TooltipProvider, TooltipTrigger} from '@/components/ui/tooltip'

type MonitoringTab = Readonly<{
  id: string
  label: string
  href: string
  icon: ComponentType<{className?: string}>
  requiresDatadog?: boolean
}>

type MonitoringTabNavProps = Readonly<{
  tabs: readonly MonitoringTab[]
  currentPath: string
}>

type VisibleTabCountOptions = Readonly<{
  availableWidth: number
  tabCount: number
  itemRefs: readonly (HTMLSpanElement | null)[]
  moreWidth?: number
}>

const TAB_BASE_CLASS = 'flex h-8 items-center gap-1.5 whitespace-nowrap px-2.5 text-xs font-medium'
const OVERFLOW_FALLBACK_WIDTH = 80

function isTabActive(tab: MonitoringTab, currentPath: string): boolean {
  if (tab.href === '/monitoring') {
    return currentPath === '/monitoring' || currentPath === '/monitoring/'
  }
  return currentPath.startsWith(tab.href)
}

function computeVisibleTabCount({
  availableWidth,
  tabCount,
  itemRefs,
  moreWidth = OVERFLOW_FALLBACK_WIDTH,
}: VisibleTabCountOptions): number {
  if (availableWidth === 0) return tabCount

  let usedWidth = 0
  for (let index = 0; index < tabCount; index += 1) {
    const width = itemRefs[index]?.offsetWidth ?? 0
    const reserveForMore = index < tabCount - 1 ? moreWidth : 0
    if (usedWidth + width + reserveForMore > availableWidth) {
      return Math.min(tabCount, Math.max(1, index))
    }
    usedWidth += width
  }

  return tabCount
}

function createResizeObserver(onResize: () => void): ResizeObserver | null {
  if (globalThis.ResizeObserver === undefined) return null
  return new globalThis.ResizeObserver(onResize)
}

function useVisibleMonitoringTabs(tabs: readonly MonitoringTab[]) {
  const navRef = useRef<HTMLElement>(null)
  const itemRefs = useRef<Array<HTMLSpanElement | null>>([])
  const moreRef = useRef<HTMLSpanElement | null>(null)
  const [visibleCount, setVisibleCount] = useState(tabs.length)

  useEffect(() => {
    const nav = navRef.current
    if (nav === null) return undefined

    const recompute = () => {
      setVisibleCount(
        computeVisibleTabCount({
          availableWidth: nav.clientWidth,
          tabCount: tabs.length,
          itemRefs: itemRefs.current,
          moreWidth: moreRef.current?.offsetWidth,
        }),
      )
    }

    recompute()
    const observer = createResizeObserver(recompute)
    observer?.observe(nav)
    return () => observer?.disconnect()
  }, [tabs])

  return {
    navRef,
    itemRefs,
    moreRef,
    visibleTabs: tabs.slice(0, visibleCount),
    overflowTabs: tabs.slice(visibleCount),
  }
}

function MonitoringTabNav({tabs, currentPath}: MonitoringTabNavProps) {
  const {navRef, itemRefs, moreRef, visibleTabs, overflowTabs} = useVisibleMonitoringTabs(tabs)
  const overflowHasActive = overflowTabs.some((tab) => isTabActive(tab, currentPath))

  return (
    <div className="relative min-w-0 flex-1 overflow-hidden">
      <div aria-hidden className="pointer-events-none invisible absolute left-0 top-0 flex gap-0.5">
        {tabs.map((tab, index) => {
          const Icon = tab.icon
          return (
            <span
              key={tab.id}
              ref={(element) => {
                itemRefs.current[index] = element
              }}
              className={TAB_BASE_CLASS}
            >
              <Icon className="h-3.5 w-3.5 shrink-0" />
              {tab.label}
            </span>
          )
        })}
        <span ref={moreRef} className={TAB_BASE_CLASS}>
          <MoreHorizontal className="h-3.5 w-3.5 shrink-0" />
          More
        </span>
      </div>

      <nav ref={navRef} className="flex w-full items-stretch gap-0.5 overflow-hidden" aria-label="Monitoring tabs">
        {visibleTabs.map((tab) => (
          <MonitoringTabLink key={tab.id} tab={tab} currentPath={currentPath} />
        ))}
        {overflowTabs.length > 0 && (
          <MonitoringOverflowMenu tabs={overflowTabs} currentPath={currentPath} hasActiveTab={overflowHasActive} />
        )}
      </nav>
    </div>
  )
}

type MonitoringTabLinkProps = Readonly<{
  tab: MonitoringTab
  currentPath: string
}>

function MonitoringTabLink({tab, currentPath}: MonitoringTabLinkProps) {
  const Icon = tab.icon
  const active = isTabActive(tab, currentPath)

  return (
    <Link
      to={tab.href}
      className={cn(
        TAB_BASE_CLASS,
        'border-b-2 transition-colors',
        active ? 'border-primary text-primary' : 'border-transparent text-muted-foreground hover:text-foreground',
      )}
    >
      <Icon className="h-3.5 w-3.5 shrink-0" />
      {tab.label}
    </Link>
  )
}

type MonitoringOverflowMenuProps = Readonly<{
  tabs: readonly MonitoringTab[]
  currentPath: string
  hasActiveTab: boolean
}>

function MonitoringOverflowMenu({tabs, currentPath, hasActiveTab}: MonitoringOverflowMenuProps) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          className={cn(
            TAB_BASE_CLASS,
            'border-b-2 transition-colors',
            hasActiveTab
              ? 'border-primary text-primary'
              : 'border-transparent text-muted-foreground hover:text-foreground',
          )}
        >
          <MoreHorizontal className="h-3.5 w-3.5 shrink-0" />
          More
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-44">
        {tabs.map((tab) => {
          const Icon = tab.icon
          return (
            <DropdownMenuItem key={tab.id} asChild>
              <Link
                to={tab.href}
                className={cn('flex items-center gap-2 text-xs', isTabActive(tab, currentPath) && 'text-primary')}
              >
                <Icon className="h-3.5 w-3.5 shrink-0" />
                {tab.label}
              </Link>
            </DropdownMenuItem>
          )
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

export const Route = createFileRoute('/monitoring')({
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      const hasSession = await api.checkAuth()
      if (!hasSession) throw redirect({to: '/login'})
    }
  },
  component: MonitoringLayout,
})

const TAB_DOCS_URLS: Record<string, string> = {
  hosts: '/docs/datadog-agent/agent-setup',
  map: '/docs/infrastructure-monitoring',
  containers: '/docs/datadog-agent/agent-setup',
  'service-map': '/docs/performance-monitoring#service-map',
  processes: '/docs/datadog-agent/',
  network: '/docs/datadog-agent/',
  events: '/docs/datadog-agent/',
  kubernetes: '/docs/datadog-agent/kubernetes',
  databases: '/docs/datadog-agent/database-monitoring',
  debugger: '/docs/datadog-agent/debugger',
  'network-devices': '/docs/datadog-agent/network-devices',
  sbom: '/docs/datadog-agent/sbom',
}

const allTabs: MonitoringTab[] = [
  {id: 'hosts', label: 'Hosts', href: '/monitoring', icon: HardDrive},
  {id: 'map', label: 'Map', href: '/monitoring/map', icon: MapIcon},
  {id: 'containers', label: 'Containers', href: '/monitoring/containers', icon: Box},
  {id: 'processes', label: 'Processes', href: '/monitoring/processes', icon: Terminal, requiresDatadog: true},
  {id: 'network', label: 'Network', href: '/monitoring/network', icon: Network, requiresDatadog: true},
  {id: 'events', label: 'Events', href: '/monitoring/events', icon: CalendarClock, requiresDatadog: true},
  {id: 'kubernetes', label: 'Kubernetes', href: '/monitoring/kubernetes', icon: Ship, requiresDatadog: true},
  {id: 'databases', label: 'Databases', href: '/monitoring/databases', icon: Database, requiresDatadog: true},
  {id: 'debugger', label: 'Debugger', href: '/monitoring/debugger', icon: Bug, requiresDatadog: true},
  {id: 'network-devices', label: 'Network Devices', href: '/monitoring/network-devices', icon: Router, requiresDatadog: true},
  {id: 'sbom', label: 'SBOM', href: '/monitoring/sbom', icon: Package, requiresDatadog: true},
]

const KNOWN_TAB_PATHS = [
  'hosts', 'map', 'containers', 'processes', 'network', 'events',
  'kubernetes', 'databases', 'debugger', 'network-devices', 'service-map', 'sbom',
]

function MonitoringLayout() {
  const router = useRouterState()
  const currentPath = router.location.pathname
  const {data: features} = useEnterpriseFeatures()

  const pathParts = currentPath.replace(/^\/monitoring\/?/, '').split('/').filter(Boolean)
  const isHostDetailPage =
    pathParts.length >= 2 && pathParts[0] === 'hosts' && !KNOWN_TAB_PATHS.includes(pathParts[1])
  const isSystemDetailPage =
    pathParts.length === 1 && !KNOWN_TAB_PATHS.includes(pathParts[0])
  const tabs = allTabs.filter(
    (tab) => !tab.requiresDatadog || hasEnterpriseModule(features, 'datadog')
  )

  if (isHostDetailPage) {
    return <Outlet />
  }
  if (isSystemDetailPage) {
    return (
      <div>
        <div className="border-b bg-card/50">
          <div className="container mx-auto px-4 py-4">
            <Button variant="ghost" size="sm" asChild className="gap-2 text-muted-foreground hover:text-foreground">
              <Link to="/monitoring">
                <ArrowLeft className="h-4 w-4" />
                Back to Monitoring
              </Link>
            </Button>
          </div>
        </div>
        <Outlet />
      </div>
    )
  }

  const activeTabId = tabs.find((t) =>
    t.href === '/monitoring'
      ? currentPath === '/monitoring' || currentPath === '/monitoring/'
      : currentPath.startsWith(t.href)
  )?.id
  const docsUrl = activeTabId ? TAB_DOCS_URLS[activeTabId] : null

  return (
    <div>
      <div className="border-b bg-card/50">
        <div className="flex h-10 items-end gap-2 px-4">
          <MonitoringTabNav tabs={tabs} currentPath={currentPath} />
          <div className="flex shrink-0 items-center gap-2 self-center">
            {docsUrl ? (
              <TooltipProvider>
                <Tooltip>
                  <TooltipTrigger asChild>
                    <a
                      href={docsUrl}
                      target="_blank"
                      rel="noreferrer"
                      className="inline-flex items-center justify-center p-1.5 rounded-md text-muted-foreground hover:text-foreground hover:bg-muted/50 transition-colors"
                      aria-label="View docs"
                    >
                      <HelpCircle className="h-4 w-4" />
                    </a>
                  </TooltipTrigger>
                  <TooltipContent>
                    <p>View docs</p>
                  </TooltipContent>
                </Tooltip>
              </TooltipProvider>
            ) : null}
          </div>
        </div>
      </div>
      <Outlet />
    </div>
  )
}
