import {createFileRoute, Outlet} from '@tanstack/react-router'
import {LandingNavbar, LandingFooter} from '@/components/landing/LandingNavbar'
import {Helmet} from 'react-helmet-async'
import DocsSidebar from '@/docs/components/DocsSidebar'
import {useForceDarkTheme} from '@/components/landing/usePublicPageTheme'

export const Route = createFileRoute('/docs')({
  component: DocsLayout,
})

function DocsLayout() {
  useForceDarkTheme()
  return (
    <>
      <Helmet>
        <meta name="theme-color" content="#08090f" />
      </Helmet>
      <div className="min-h-screen bg-[#08090f] font-display text-slate-300">
        <LandingNavbar tone="dark" />
        <div className="mx-auto flex max-w-7xl items-start px-4 sm:px-6 lg:px-8">
          <DocsSidebar />
          <main className="flex-1 min-w-0">
            <Outlet />
          </main>
        </div>
        <LandingFooter tone="dark" />
      </div>
    </>
  )
}
