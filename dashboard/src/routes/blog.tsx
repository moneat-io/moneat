import {createFileRoute, Outlet} from '@tanstack/react-router'
import {LandingNavbar, LandingFooter} from '@/components/landing/LandingNavbar'
import {Helmet} from 'react-helmet-async'

export const Route = createFileRoute('/blog')({
  component: BlogLayout,
})

function BlogLayout() {
  return (
    <>
      <Helmet>
        <meta name="theme-color" content="#ffffff" />
      </Helmet>
      <div className="min-h-screen bg-white text-slate-950">
        <LandingNavbar tone="light" />
        <Outlet />
        <LandingFooter tone="light" />
      </div>
    </>
  )
}
