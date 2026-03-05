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

import {useEffect} from 'react'
import {Link} from '@tanstack/react-router'
import {ArrowRight, type LucideIcon} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {ScreenshotFrame} from './variant-a'
import {LandingNavbar, LandingFooter} from './landing-navbar'
import {Helmet} from 'react-helmet-async'

interface SubFeature {
  icon: LucideIcon
  title: string
  description: string
  iconColor: string
}

export interface FeaturePageConfig {
  slug: string
  title: string
  tagline: string
  description: string
  metaDescription: string
  icon: LucideIcon
  iconColor: string
  iconBg: string
  gradient: string
  accentColor: string
  screenshot: string
  screenshotAlt: string
  subFeatures: SubFeature[]
  compatNote?: string
}

export function FeaturePageTemplate({config}: {config: FeaturePageConfig}) {
  useEffect(() => {
    const root = document.documentElement
    const addedDark = !root.classList.contains('dark')
    if (addedDark) root.classList.add('dark')
    return () => { if (addedDark) root.classList.remove('dark') }
  }, [])

  return (
    <article className="min-h-screen bg-[#0a0b14]">
      <Helmet>
        <title>{config.title} | Moneat</title>
        <meta name="description" content={config.metaDescription} />
        <link rel="canonical" href={`https://moneat.io/${config.slug}`} />
      </Helmet>

      <LandingNavbar />

      <main>
        {/* Hero */}
        <section className="relative overflow-hidden pt-20 pb-24 px-4 sm:px-6 lg:px-8">
          <div className="absolute inset-0 overflow-hidden pointer-events-none">
            <div className={`absolute -top-40 -right-40 w-[600px] h-[600px] rounded-full bg-gradient-to-br ${config.gradient} opacity-15 blur-[120px]`} />
            <div className="absolute -bottom-40 -left-40 w-[400px] h-[400px] rounded-full bg-violet-500/10 blur-[100px]" />
          </div>

          <div className="max-w-6xl mx-auto relative z-10">
            <div className="flex flex-col lg:flex-row items-center gap-12 lg:gap-16">
              <div className="lg:w-[42%] text-center lg:text-left">
                <div className={`inline-flex rounded-lg ${config.iconBg} p-3 mb-6 ring-1 ring-inset ring-white/5`}>
                  <config.icon className={`h-6 w-6 ${config.iconColor}`} />
                </div>
                <p className={`text-sm font-semibold ${config.accentColor} tracking-wide uppercase mb-3`}>
                  {config.tagline}
                </p>
                <h1 className="text-4xl sm:text-5xl font-bold tracking-tight text-white mb-6">
                  {config.title}
                </h1>
                <p className="text-lg text-slate-400 leading-relaxed mb-8">
                  {config.description}
                </p>
                <div className="flex flex-col sm:flex-row gap-4 justify-center lg:justify-start">
                  <Button
                    asChild
                    size="lg"
                    className="bg-sky-500 hover:bg-sky-400 text-white shadow-lg shadow-sky-500/30 hover:shadow-sky-400/40 transition-all duration-300 text-base px-8 h-12"
                  >
                    <Link to="/signup">
                      Start Free
                      <ArrowRight className="ml-2 h-4 w-4" />
                    </Link>
                  </Button>
                  <Button
                    asChild
                    variant="outline"
                    size="lg"
                    className="border-white/[0.1] text-slate-200 hover:bg-white/[0.05] hover:border-white/[0.15] transition-all duration-300 text-base px-8 h-12"
                  >
                    <Link to="/demo">
                      Live Demo
                    </Link>
                  </Button>
                </div>
              </div>
              <div className="lg:w-[58%] w-full">
                <ScreenshotFrame gradient={config.gradient} fade="bottom">
                  <img src={config.screenshot} alt={config.screenshotAlt} className="w-full h-full object-cover" />
                </ScreenshotFrame>
              </div>
            </div>
          </div>
        </section>

        {/* Sub-features grid */}
        <section className="py-24 px-4 sm:px-6 lg:px-8 border-t border-white/[0.06]">
          <div className="max-w-5xl mx-auto">
            <div className="text-center mb-16">
              <h2 className="text-2xl sm:text-3xl font-bold text-white mb-3">
                Everything you need
              </h2>
              <p className="text-slate-400 max-w-xl mx-auto">
                Built-in capabilities that work together out of the box.
              </p>
            </div>
            <div className="grid sm:grid-cols-2 lg:grid-cols-3 gap-8">
              {config.subFeatures.map(sf => (
                <div key={sf.title} className="rounded-xl border border-white/[0.06] bg-white/[0.02] p-6 hover:bg-white/[0.04] hover:border-white/[0.1] transition-colors">
                  <sf.icon className={`h-5 w-5 ${sf.iconColor} mb-4`} />
                  <h3 className="font-semibold text-white mb-2">{sf.title}</h3>
                  <p className="text-sm text-slate-400 leading-relaxed">{sf.description}</p>
                </div>
              ))}
            </div>
          </div>
        </section>

        {config.compatNote && (
          <section className="py-16 px-4 sm:px-6 lg:px-8 border-t border-white/[0.06]">
            <div className="max-w-3xl mx-auto text-center">
              <p className="text-slate-400 text-lg leading-relaxed">{config.compatNote}</p>
            </div>
          </section>
        )}

        {/* CTA */}
        <section className="relative overflow-hidden py-24 px-4 sm:px-6 lg:px-8 border-t border-white/[0.06]">
          <div className="absolute inset-0 overflow-hidden pointer-events-none">
            <div className="absolute top-0 left-1/4 w-[400px] h-[400px] rounded-full bg-sky-500/10 blur-[100px]" />
            <div className="absolute bottom-0 right-1/4 w-[300px] h-[300px] rounded-full bg-violet-500/10 blur-[80px]" />
          </div>
          <div className="max-w-3xl mx-auto text-center relative z-10">
            <h2 className="text-3xl sm:text-4xl font-bold text-white mb-4">
              Ready to get started?
            </h2>
            <p className="text-lg text-slate-400 mb-8 max-w-xl mx-auto">
              Start with 1 GB free. No credit card required.
            </p>
            <Button
              asChild
              size="lg"
              className="bg-sky-500 hover:bg-sky-400 text-white shadow-lg shadow-sky-500/30 hover:shadow-sky-400/40 transition-all duration-300 text-base px-8 h-12"
            >
              <Link to="/signup">
                Start Free
                <ArrowRight className="ml-2 h-4 w-4" />
              </Link>
            </Button>
          </div>
        </section>
      </main>

      <LandingFooter />
    </article>
  )
}
