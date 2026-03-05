import {createFileRoute, Outlet} from '@tanstack/react-router'
import {useEffect} from 'react'
import {LandingNavbar, LandingFooter} from '@/components/landing/landing-navbar'
import {Helmet} from 'react-helmet-async'
import DocsSidebar from '@/docs/components/DocsSidebar'

export const Route = createFileRoute('/docs')({
  component: DocsLayout,
})

function DocsLayout() {
  useEffect(() => {
    const root = document.documentElement
    const hadDark = root.classList.contains('dark')
    const prevColorScheme = root.style.colorScheme
    root.classList.add('dark')
    root.style.colorScheme = 'dark'
    return () => {
      if (!hadDark) root.classList.remove('dark')
      root.style.colorScheme = prevColorScheme
    }
  }, [])

  return (
    <>
      <Helmet>
        <meta name="theme-color" content="#020617" />
      </Helmet>
      <div className="min-h-screen bg-slate-950 text-slate-100">
        <LandingNavbar />
        <div className="flex max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <DocsSidebar />
          <main className="flex-1 min-w-0">
            <Outlet />
          </main>
        </div>
        <LandingFooter />
      </div>
    </>
  )
}
