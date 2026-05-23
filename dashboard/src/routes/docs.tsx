import {createFileRoute, Outlet} from '@tanstack/react-router'
import {LandingNavbar, LandingFooter} from '@/components/landing/LandingNavbar'
import {Helmet} from 'react-helmet-async'
import DocsSidebar from '@/docs/components/DocsSidebar'

export const Route = createFileRoute('/docs')({
  component: DocsLayout,
})

function DocsLayout() {
  return (
    <>
      <Helmet>
        <meta name="theme-color" content="#ffffff" />
      </Helmet>
      <div className="min-h-screen bg-white text-slate-950">
        <LandingNavbar tone="light" />
        <div className="mx-auto flex max-w-7xl items-start px-4 sm:px-6 lg:px-8">
          <DocsSidebar />
          <main className="flex-1 min-w-0">
            <Outlet />
          </main>
        </div>
        <LandingFooter tone="light" />
      </div>
    </>
  )
}
