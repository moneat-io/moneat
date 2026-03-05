import {createFileRoute, Outlet} from '@tanstack/react-router'
import {useEffect} from 'react'
import {LandingNavbar, LandingFooter} from '@/components/landing/landing-navbar'
import {Helmet} from 'react-helmet-async'

export const Route = createFileRoute('/blog')({
  component: BlogLayout,
})

function BlogLayout() {
  useEffect(() => {
    document.documentElement.classList.add('dark')
    document.documentElement.style.colorScheme = 'dark'
    return () => {
      document.documentElement.classList.remove('dark')
      document.documentElement.style.colorScheme = ''
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
