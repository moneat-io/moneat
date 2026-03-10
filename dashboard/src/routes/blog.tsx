import {createFileRoute, Outlet} from '@tanstack/react-router'
import {useEffect} from 'react'
import {LandingNavbar, LandingFooter} from '@/components/landing/LandingNavbar'
import {Helmet} from 'react-helmet-async'

export const Route = createFileRoute('/blog')({
  component: BlogLayout,
})

function BlogLayout() {
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
        <Outlet />
        <LandingFooter />
      </div>
    </>
  )
}
