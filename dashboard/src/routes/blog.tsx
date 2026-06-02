import {createFileRoute, Outlet} from '@tanstack/react-router'
import {LandingNavbar, LandingFooter} from '@/components/landing/LandingNavbar'
import {Helmet} from 'react-helmet-async'
import {useForceDarkTheme} from '@/components/landing/usePublicPageTheme'

export const Route = createFileRoute('/blog')({
  component: BlogLayout,
})

function BlogLayout() {
  useForceDarkTheme()
  return (
    <>
      <Helmet>
        <meta name="theme-color" content="#08090f" />
      </Helmet>
      <div className="min-h-screen bg-[#08090f] font-display text-slate-300">
        <LandingNavbar tone="dark" />
        <Outlet />
        <LandingFooter tone="dark" />
      </div>
    </>
  )
}
