import {createFileRoute, Link, Outlet, useRouterState} from '@tanstack/react-router'
import {useState} from 'react'
import {Logo} from '@/components/logo'
import {cn} from '@/lib/utils'
import {
  BookOpen,
  Rocket,
  Bug,
  ListChecks,
  Bell,
  Activity,
  Globe,
  Terminal,
  Package,
  Plug,
  CreditCard,
  Key,
  Shield,
  Code,
  Menu,
  X,
  ExternalLink,
} from 'lucide-react'

export const Route = createFileRoute('/docs')({
  component: DocsLayout,
})

const navSections = [
  {
    label: 'Overview',
    items: [
      {icon: BookOpen, label: 'Introduction', href: '/docs'},
      {icon: Rocket, label: 'Getting Started', href: '/docs/getting-started'},
    ],
  },
  {
    label: 'Core Features',
    items: [
      {icon: Bug, label: 'Error Monitoring', href: '/docs/error-monitoring'},
      {icon: ListChecks, label: 'Issue Tracking', href: '/docs/issue-tracking'},
      {icon: Terminal, label: 'Structured Logging', href: '/docs/logging'},
      {icon: Package, label: 'Releases & Source Maps', href: '/docs/releases'},
    ],
  },
  {
    label: 'Reliability',
    items: [
      {icon: Bell, label: 'On-Call & Incidents', href: '/docs/on-call'},
      {icon: Activity, label: 'Uptime Monitoring', href: '/docs/uptime-monitoring'},
      {icon: Globe, label: 'Status Pages', href: '/docs/status-pages'},
    ],
  },
  {
    label: 'Configuration',
    items: [
      {icon: Code, label: 'SDK Setup', href: '/docs/sdk-setup'},
      {icon: Plug, label: 'Integrations', href: '/docs/integrations'},
      {icon: Shield, label: 'SSO & Authentication', href: '/docs/sso-authentication'},
      {icon: Key, label: 'API Tokens', href: '/docs/api-tokens'},
    ],
  },
  {
    label: 'Account',
    items: [
      {icon: CreditCard, label: 'Billing & Plans', href: '/docs/billing'},
    ],
  },
]

function DocsLayout() {
  const router = useRouterState()
  const currentPath = router.location.pathname
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)

  const sidebar = (
    <nav className="flex-1 px-2 py-3 space-y-4">
      {navSections.map((section) => (
        <div key={section.label}>
          <p className="px-3 mb-1.5 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground/70">
            {section.label}
          </p>
          <div className="space-y-0.5">
            {section.items.map((item) => {
              const Icon = item.icon
              const isActive =
                item.href === '/docs'
                  ? currentPath === '/docs' || currentPath === '/docs/'
                  : currentPath.startsWith(item.href)
              return (
                <Link
                  key={item.href}
                  to={item.href}
                  onClick={() => setMobileMenuOpen(false)}
                  className={cn(
                    'flex items-center gap-3 px-3 py-2 rounded-lg text-[13px] font-medium transition-all',
                    isActive
                      ? 'bg-primary text-primary-foreground shadow-sm'
                      : 'text-muted-foreground hover:text-foreground hover:bg-accent'
                  )}
                >
                  <Icon className="h-4 w-4 shrink-0" />
                  {item.label}
                </Link>
              )
            })}
          </div>
        </div>
      ))}
    </nav>
  )

  return (
    <div className="min-h-screen bg-background">
      {/* Top Header */}
      <header className="sticky top-0 z-50 border-b bg-background/95 backdrop-blur supports-[backdrop-filter]:bg-background/60">
        <div className="flex items-center justify-between h-14 px-4 lg:px-6">
          <div className="flex items-center gap-4">
            <button
              className="lg:hidden p-2 -ml-2 rounded-md hover:bg-accent"
              onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
            >
              {mobileMenuOpen ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
            </button>
            <Link to="/docs" className="flex items-center gap-3">
              <Logo className="h-6" />
              <span className="text-sm font-semibold text-muted-foreground border-l pl-3">Docs</span>
            </Link>
          </div>
          <div className="flex items-center gap-3">
            <Link
              to="/login"
              className="text-sm text-muted-foreground hover:text-foreground transition-colors"
            >
              Sign In
            </Link>
            <Link
              to="/signup"
              className="text-sm font-medium bg-primary text-primary-foreground px-3 py-1.5 rounded-md hover:bg-primary/90 transition-colors"
            >
              Get Started
            </Link>
          </div>
        </div>
      </header>

      <div className="flex">
        {/* Desktop Sidebar */}
        <aside className="hidden lg:flex w-60 shrink-0 border-r sticky top-14 h-[calc(100vh-3.5rem)] flex-col overflow-y-auto">
          {sidebar}
          <div className="px-2 py-3 border-t">
            <a
              href="https://moneat.io"
              className="flex items-center gap-3 px-3 py-2 rounded-lg text-[13px] text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
            >
              <ExternalLink className="h-4 w-4 shrink-0" />
              moneat.io
            </a>
          </div>
        </aside>

        {/* Mobile Sidebar Overlay */}
        {mobileMenuOpen && (
          <>
            <div className="fixed inset-0 z-40 bg-black/50 lg:hidden" onClick={() => setMobileMenuOpen(false)} />
            <aside className="fixed inset-y-0 left-0 z-50 w-64 bg-background border-r flex flex-col overflow-y-auto lg:hidden pt-14">
              {sidebar}
            </aside>
          </>
        )}

        {/* Content */}
        <main className="flex-1 min-w-0">
          <div className="p-6 lg:p-10 lg:pl-12">
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  )
}
