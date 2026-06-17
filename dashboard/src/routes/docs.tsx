import {createFileRoute, Outlet} from '@tanstack/react-router'
import {LandingNavbar, LandingFooter} from '@/components/landing/LandingNavbar'
import {Helmet} from 'react-helmet-async'
import DocsSidebar from '@/docs/components/DocsSidebar'
import {DocsSearchProvider} from '@/docs/components/DocsSearch'
import {useForceDarkTheme} from '@/components/landing/usePublicPageTheme'

export const Route = createFileRoute('/docs')({
  component: DocsLayout,
})

function DocsLayout() {
  useForceDarkTheme()
  return (
    <>
      <Helmet>
        <meta name="theme-color" content="#0a0b12" />
      </Helmet>
      <div className="docs-surface relative min-h-screen bg-[#0a0b12] font-display text-slate-300 antialiased selection:bg-indigo-500/30 selection:text-white">
        <div aria-hidden className="docs-atmos" />
        <div className="relative z-10">
          <LandingNavbar tone="dark" />
          <DocsSearchProvider>
            <div className="mx-auto flex max-w-[1480px]">
              <DocsSidebar />
              <main className="min-w-0 flex-1">
                <Outlet />
              </main>
            </div>
          </DocsSearchProvider>
          <LandingFooter tone="dark" />
        </div>
      </div>
    </>
  )
}
