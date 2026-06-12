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

import type {ReactNode} from 'react'
import {Link} from '@tanstack/react-router'
import {ArrowRight, Check, Database, Play, ShieldCheck, type LucideIcon} from 'lucide-react'
import {Button} from '@/components/ui/button'
import {cn} from '@/lib/utils'
import {
  GRADIENT_BG,
  SectionHeading,
  WindowFrame,
  SignalChart,
  StatTile,
} from './Landing'
import {LandingNavbar, LandingFooter} from './LandingNavbar'
import {SeoHead} from '@/components/SeoHead'
import {featurePageSeo} from '@/lib/seo/routes'
import {useForceDarkTheme} from '@/components/landing/usePublicPageTheme'

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
  /** Feature-specific faux product UI for the hero. Falls back to the generic
   *  dashboard when a page hasn't supplied its own. */
  heroVisual?: ReactNode
}

// ─────────────────────────────────────────────────────────────────────────────
// Faux hero product surface — built UI using the shared WindowFrame/SignalChart/
// StatTile primitives. Title comes from the page config so each feature page
// gets a distinct label while staying on-system visually.
// ─────────────────────────────────────────────────────────────────────────────
function FeatureHeroDashboard({config}: {readonly config: FeaturePageConfig}) {
  return (
    <div className="relative">
      <div
        aria-hidden
        className="pointer-events-none absolute -inset-x-10 -bottom-12 top-8 bg-[radial-gradient(closest-side,rgba(124,92,246,0.28),rgba(34,211,238,0.05)_60%,transparent)] blur-2xl"
      />
      <WindowFrame title={`moneat · ${config.tagline}`} className="relative">
        <div className="grid gap-3">
          <div className="flex items-center justify-between">
            <span className="font-brandmono text-[11px] uppercase tracking-[0.12em] text-slate-500">
              {config.title}
            </span>
          </div>
          <SignalChart heightClass="h-36 sm:h-44" />
          <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-4">
            <StatTile label="p95" value="148ms" accent />
            <StatTile label="uptime" value="99.97%" />
            <StatTile label="events" value="4.1k/s" />
            <StatTile label="errors" value="3 open" />
          </div>
          <div className="grid gap-px overflow-hidden rounded-md border border-white/[0.07] sm:grid-cols-3">
            {[
              ['Errors', '3 open', 'bg-rose-400'],
              ['Traces', '148ms p95', 'bg-cyan-400'],
              ['Uptime', '99.97%', 'bg-emerald-400'],
            ].map(([label, value, dot]) => (
              <div key={label} className="flex items-center justify-between gap-3 bg-white/[0.02] px-3 py-2.5">
                <span className="flex items-center gap-2 text-sm text-slate-400">
                  <span className={cn('size-2 rounded-full', dot)} />
                  {label}
                </span>
                <span className="font-brandmono text-sm text-slate-100">{value}</span>
              </div>
            ))}
          </div>
        </div>
      </WindowFrame>
    </div>
  )
}

export function FeaturePageTemplate({config}: {readonly config: FeaturePageConfig}) {
  useForceDarkTheme()

  const proofItems: {icon: LucideIcon; label: string; value: string}[] = [
    {icon: config.icon, label: 'Workflow', value: config.tagline},
    {icon: ShieldCheck, label: 'Deployment', value: 'Cloud or self-hosted'},
    {icon: Database, label: 'Pricing', value: '1 GB free to start'},
  ]

  return (
    <article className="min-h-screen bg-[#08090f] font-display text-slate-300">
      <SeoHead
        seo={featurePageSeo({
          slug: config.slug,
          title: config.title,
          metaDescription: config.metaDescription,
          image: config.screenshot,
        })}
      />

      <LandingNavbar tone="dark" />

      <main>
        {/* ── Hero ─────────────────────────────────────────────────────────── */}
        <section className="relative overflow-hidden px-4 pb-20 pt-20 sm:px-6 lg:px-8 lg:pb-24">
          <div aria-hidden className="pointer-events-none absolute inset-0">
            <div className="absolute inset-0 bg-[radial-gradient(58rem_30rem_at_72%_-10%,rgba(124,92,246,0.18),transparent_70%)]" />
            <div className="absolute inset-0 bg-[linear-gradient(rgba(148,163,184,0.05)_1px,transparent_1px),linear-gradient(90deg,rgba(148,163,184,0.05)_1px,transparent_1px)] bg-[size:56px_56px] [mask-image:radial-gradient(60rem_40rem_at_50%_0%,#000,transparent_75%)]" />
          </div>
          <div className="relative mx-auto grid max-w-6xl items-center gap-12 lg:grid-cols-[0.9fr_1.1fr] lg:gap-16">
            <div>
              {/* Feature icon tile — gradient background, white icon */}
              <div className={cn('mb-6 inline-flex rounded-lg p-3', GRADIENT_BG)}>
                <config.icon className="size-6 text-white" />
              </div>
              <h1 className="max-w-xl text-4xl font-bold leading-[1.05] tracking-[-0.04em] text-white sm:text-5xl lg:text-6xl">
                {config.title}
              </h1>
              <p className="mt-6 max-w-xl text-lg leading-8 text-slate-400">{config.description}</p>
              <div className="mt-8 flex flex-col gap-3 sm:flex-row">
                <Button
                  asChild
                  size="lg"
                  className={cn('border-0 text-white shadow-[0_10px_28px_-10px_#6366F1] hover:brightness-110', GRADIENT_BG)}
                >
                  <Link to="/signup">
                    Start free
                    <ArrowRight data-icon="inline-end" />
                  </Link>
                </Button>
                <Button
                  asChild
                  variant="outline"
                  size="lg"
                  className="border-white/15 bg-white/[0.03] text-slate-100 hover:bg-white/[0.07] hover:text-white"
                >
                  <Link to="/demo">
                    <Play data-icon="inline-start" />
                    Live demo
                  </Link>
                </Button>
              </div>
            </div>
            <div className="w-full">{config.heroVisual ?? <FeatureHeroDashboard config={config} />}</div>
          </div>
        </section>

        {/* ── Proof strip ──────────────────────────────────────────────────── */}
        <section className="border-y border-white/[0.06] bg-[#0a0b12]">
          <div className="mx-auto grid max-w-6xl gap-px px-4 sm:grid-cols-3 lg:px-8">
            {proofItems.map((item) => (
              <div key={item.label} className="px-5 py-6">
                <div className="flex items-start gap-3">
                  <div className="flex size-9 shrink-0 items-center justify-center rounded-md border border-white/[0.08] bg-white/[0.03]">
                    <item.icon className="size-4 text-indigo-300" />
                  </div>
                  <div>
                    <p className="font-brandmono text-[10px] uppercase tracking-[0.14em] text-slate-500">{item.label}</p>
                    <p className="mt-1 text-sm font-semibold text-slate-100">{item.value}</p>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </section>

        {/* ── Sub-features ─────────────────────────────────────────────────── */}
        <section className="px-4 py-24 sm:px-6 lg:px-8">
          <div className="mx-auto max-w-6xl">
            <SectionHeading
              kicker={config.tagline}
              title="Built for production debugging without the enterprise tax"
              lead="Each capability is designed to work with the rest of the platform, so teams can move from signal to root cause without switching tools."
            />
            <div className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {config.subFeatures.map((sf) => (
                <div
                  key={sf.title}
                  className="rounded-lg border border-white/10 bg-white/[0.02] p-6 transition-colors hover:border-white/[0.16]"
                >
                  <div className="mb-5 flex size-10 items-center justify-center rounded-md border border-white/[0.08] bg-white/[0.03]">
                    <sf.icon className="size-5 text-indigo-300" />
                  </div>
                  <h3 className="text-base font-semibold text-white">{sf.title}</h3>
                  <p className="mt-3 text-sm leading-6 text-slate-400">{sf.description}</p>
                </div>
              ))}
            </div>
          </div>
        </section>

        {/* ── Compat note ──────────────────────────────────────────────────── */}
        {config.compatNote && (
          <section className="border-y border-white/[0.06] bg-[#0a0b12] px-4 py-16 sm:px-6 lg:px-8">
            <div className="mx-auto max-w-4xl rounded-lg border border-white/10 bg-[#0c0e16] p-6 sm:p-8">
              <div className="flex flex-col gap-4 sm:flex-row sm:items-start">
                <div className="flex size-10 shrink-0 items-center justify-center rounded-md border border-white/[0.08] bg-white/[0.03]">
                  <Check className="size-5 text-emerald-400" />
                </div>
                <div>
                  <h2 className="text-lg font-semibold text-white">Works with your existing setup</h2>
                  <p className="mt-2 text-base leading-7 text-slate-400">{config.compatNote}</p>
                </div>
              </div>
            </div>
          </section>
        )}

        {/* ── Final CTA ────────────────────────────────────────────────────── */}
        <section className="relative overflow-hidden px-4 py-24 sm:px-6 lg:px-8">
          <div
            aria-hidden
            className="pointer-events-none absolute inset-0 bg-[radial-gradient(48rem_24rem_at_50%_120%,rgba(124,92,246,0.18),transparent_70%)]"
          />
          <div className="relative mx-auto max-w-3xl text-center">
            <h2 className="text-3xl font-bold tracking-[-0.03em] text-white sm:text-4xl">
              Start with production telemetry today
            </h2>
            <p className="mx-auto mt-4 max-w-2xl text-base leading-7 text-slate-400">
              Start with 1 GB free, invite the team, and keep the option to self-host when you need it.
            </p>
            <div className="mt-8 flex flex-col items-center justify-center gap-3 sm:flex-row">
              <Button
                asChild
                size="lg"
                className={cn('border-0 text-white shadow-[0_10px_28px_-10px_#6366F1] hover:brightness-110', GRADIENT_BG)}
              >
                <Link to="/signup">
                  Start free
                  <ArrowRight data-icon="inline-end" />
                </Link>
              </Button>
              <Button
                asChild
                variant="outline"
                size="lg"
                className="border-white/15 bg-white/[0.03] text-slate-100 hover:bg-white/[0.07] hover:text-white"
              >
                <Link to="/demo">
                  <Play data-icon="inline-start" />
                  Live demo
                </Link>
              </Button>
            </div>
          </div>
        </section>
      </main>

      <LandingFooter tone="dark" />
    </article>
  )
}
