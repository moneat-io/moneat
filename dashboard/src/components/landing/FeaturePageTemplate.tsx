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

import {Link} from '@tanstack/react-router'
import {ArrowRight, Check, Database, ShieldCheck, type LucideIcon} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {ScreenshotFrame} from './VariantA'
import {LandingNavbar, LandingFooter} from './LandingNavbar'
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

export function FeaturePageTemplate({config}: {readonly config: FeaturePageConfig}) {
  const proofItems: {
    icon: LucideIcon
    label: string
    value: string
  }[] = [
    {icon: config.icon, label: 'Workflow', value: config.tagline},
    {icon: ShieldCheck, label: 'Deployment', value: 'Cloud or self-hosted'},
    {icon: Database, label: 'Pricing', value: '1 GB free to start'},
  ]

  return (
    <article className="min-h-screen bg-white text-slate-950">
      <Helmet>
        <title>{config.title} | Moneat</title>
        <meta name="description" content={config.metaDescription} />
        <link rel="canonical" href={`https://moneat.io/${config.slug}`} />
      </Helmet>

      <LandingNavbar tone="light" />

      <main>
        <section className="relative overflow-hidden px-4 pb-20 pt-20 sm:px-6 lg:px-8 lg:pb-24">
          <div className="absolute inset-x-0 top-0 h-96 bg-[linear-gradient(180deg,#f8fafc_0%,rgba(248,250,252,0)_100%)]" />
          <div className="relative mx-auto grid max-w-6xl items-center gap-12 lg:grid-cols-[0.9fr_1.1fr] lg:gap-16">
            <div>
              <div className={`mb-6 inline-flex rounded-lg ${config.iconBg} p-3 ring-1 ring-slate-950/5`}>
                <config.icon className={`size-6 ${config.iconColor}`} />
              </div>
              <h1 className="max-w-xl text-4xl font-semibold leading-[1.05] text-slate-950 sm:text-5xl lg:text-6xl">
                {config.title}
              </h1>
              <p className="mt-6 max-w-xl text-lg leading-8 text-slate-600">
                {config.description}
              </p>
              <div className="mt-8 flex flex-col gap-3 sm:flex-row">
                <Button
                  asChild
                  size="lg"
                  className="h-12 bg-slate-950 px-6 text-white hover:bg-slate-800"
                >
                  <Link to="/signup">
                    Start free
                    <ArrowRight className="ml-2 size-4" />
                  </Link>
                </Button>
                <Button
                  asChild
                  variant="outline"
                  size="lg"
                  className="h-12 border-slate-400 !bg-white px-6 font-semibold !text-slate-950 shadow-sm hover:!bg-slate-50 hover:!text-slate-950 dark:!bg-white dark:!text-slate-950 dark:hover:!bg-slate-50"
                >
                  <Link to="/demo">
                    Live demo
                    <ArrowRight className="ml-2 size-4" />
                  </Link>
                </Button>
              </div>
            </div>
            <div className="w-full">
              <ScreenshotFrame gradient={config.gradient} fade="bottom">
                <img src={config.screenshot} alt={config.screenshotAlt} className="size-full object-cover" />
              </ScreenshotFrame>
            </div>
          </div>
        </section>

        <section className="border-y border-slate-200 bg-slate-50 px-4 py-8 sm:px-6 lg:px-8">
          <div className="mx-auto grid max-w-6xl gap-4 sm:grid-cols-3">
            {proofItems.map((item) => (
              <div key={item.label} className="flex items-start gap-3 rounded-lg border border-slate-200 bg-white p-4">
                <div className="flex size-9 shrink-0 items-center justify-center rounded-md bg-slate-100 text-slate-800">
                  <item.icon className="size-4" />
                </div>
                <div>
                  <p className="text-xs font-medium text-slate-500">{item.label}</p>
                  <p className="mt-1 text-sm font-semibold text-slate-950">{item.value}</p>
                </div>
              </div>
            ))}
          </div>
        </section>

        <section className="px-4 py-24 sm:px-6 lg:px-8">
          <div className="mx-auto max-w-6xl">
            <div className="max-w-2xl">
              <h2 className="text-3xl font-semibold leading-tight text-slate-950 sm:text-4xl">
                Built for production debugging without the enterprise tax
              </h2>
              <p className="mt-4 text-base leading-7 text-slate-600">
                Each capability is designed to work with the rest of the platform, so teams can move from signal to root cause without switching tools.
              </p>
            </div>
            <div className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {config.subFeatures.map((sf) => (
                <div key={sf.title} className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm transition-colors hover:border-slate-300">
                  <div className="mb-5 flex size-10 items-center justify-center rounded-md bg-slate-50 ring-1 ring-slate-200">
                    <sf.icon className={`size-5 ${sf.iconColor}`} />
                  </div>
                  <h3 className="text-base font-semibold text-slate-950">{sf.title}</h3>
                  <p className="mt-3 text-sm leading-6 text-slate-600">{sf.description}</p>
                </div>
              ))}
            </div>
          </div>
        </section>

        {config.compatNote && (
          <section className="bg-slate-50 px-4 py-16 sm:px-6 lg:px-8">
            <div className="mx-auto max-w-4xl rounded-lg border border-slate-200 bg-white p-6 shadow-sm sm:p-8">
              <div className="flex flex-col gap-4 sm:flex-row sm:items-start">
                <div className="flex size-10 shrink-0 items-center justify-center rounded-md bg-emerald-50 text-emerald-700 ring-1 ring-emerald-200">
                  <Check className="size-5" />
                </div>
                <div>
                  <h2 className="text-lg font-semibold text-slate-950">Works with your existing setup</h2>
                  <p className="mt-2 text-base leading-7 text-slate-600">{config.compatNote}</p>
                </div>
              </div>
            </div>
          </section>
        )}

        <section className="bg-slate-950 px-4 py-24 text-white sm:px-6 lg:px-8">
          <div className="mx-auto grid max-w-5xl gap-8 md:grid-cols-[1fr_auto] md:items-center">
            <div>
              <h2 className="text-3xl font-semibold leading-tight sm:text-4xl">
                Start with production telemetry today
              </h2>
              <p className="mt-4 max-w-2xl text-base leading-7 text-slate-300">
                Start with 1 GB free, invite the team, and keep the option to self-host when you need it.
              </p>
            </div>
            <Button
              asChild
              size="lg"
              className="h-12 bg-white px-6 text-slate-950 hover:bg-slate-100"
            >
              <Link to="/signup">
                Start free
                <ArrowRight className="ml-2 size-4" />
              </Link>
            </Button>
          </div>
        </section>
      </main>

      <LandingFooter tone="light" />
    </article>
  )
}
