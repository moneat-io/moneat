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
import {
  ArrowRight,
  BarChart3,
  Check,
  CheckCircle2,
  Compass,
  ExternalLink,
  Lightbulb,
  Minus,
  Route,
  Scale,
  CircleDollarSign,
  Gauge,
  ShieldCheck,
} from 'lucide-react'
import {SeoHead} from '@/components/SeoHead'
import {compareHubSeo, competitorPageSeo} from '@/lib/seo/routes'
import {Button} from '@/components/ui/button'
import {cn} from '@/lib/utils'
import {LandingFooter, LandingNavbar} from './LandingNavbar'
import {useForceDarkTheme} from './usePublicPageTheme'
import {
  GRADIENT_BG,
  GRADIENT_BAR,
  GRADIENT_TEXT,
  SectionHeading,
} from './Landing'
import {
  SOURCE_REVIEW_DATE,
  competitorPages,
  getCompetitorPage,
  moneatPricingSummary,
  type CompetitorPageData,
  type CompetitorSlug,
} from './competitorComparisonData'

interface CompetitorComparisonPageProps {
  readonly slug: CompetitorSlug
}

const matrixIcons = [Scale, CircleDollarSign, Gauge, ShieldCheck]

const hubSources = Array.from(
  new Map(
    competitorPages
      .flatMap((page) => page.sources)
      .map((source) => [source.href, source]),
  ).values(),
)

// ── Sources note ──────────────────────────────────────────────────────────────
function SourcesNote({page}: {readonly page: CompetitorPageData}) {
  return (
    <div className="mt-6 rounded-lg border border-white/[0.08] bg-white/[0.03] p-4 text-sm leading-6 text-slate-400 lg:mt-8">
      <p className="font-brandmono text-[11px] uppercase tracking-[0.12em] text-slate-500">
        Prices and features last reviewed {SOURCE_REVIEW_DATE}.
      </p>
      <div className="mt-2 flex flex-wrap gap-x-4 gap-y-2">
        {page.sources.map((source) => (
          <a
            key={source.href}
            href={source.href}
            target={source.href.startsWith('http') ? '_blank' : undefined}
            rel={source.href.startsWith('http') ? 'noopener noreferrer' : undefined}
            className="inline-flex items-center gap-1 font-medium text-indigo-300 underline-offset-4 hover:text-white hover:underline"
          >
            {source.label}
            <ExternalLink className="size-3" />
          </a>
        ))}
      </div>
    </div>
  )
}

// ── Hero ──────────────────────────────────────────────────────────────────────
function ComparisonHero({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="relative isolate overflow-hidden border-b border-white/[0.06] px-4 pb-8 pt-10 sm:px-6 lg:px-8 lg:pb-12 lg:pt-14">
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0"
      >
        <div className="absolute inset-0 bg-[radial-gradient(58rem_30rem_at_72%_-10%,rgba(124,92,246,0.2),transparent_70%)]" />
        <div className="absolute inset-0 bg-[linear-gradient(rgba(148,163,184,0.04)_1px,transparent_1px),linear-gradient(90deg,rgba(148,163,184,0.04)_1px,transparent_1px)] bg-[size:56px_56px] [mask-image:radial-gradient(60rem_40rem_at_50%_0%,#000,transparent_75%)]" />
      </div>
      <div className="relative mx-auto max-w-6xl">
        <div className="max-w-2xl py-2 lg:py-6">
          <div className="mb-5 inline-flex items-center gap-2.5 font-brandmono text-[11px] font-medium uppercase tracking-[0.18em] text-indigo-300">
            <span className={cn('h-px w-6 rounded', GRADIENT_BAR)} />
            Moneat vs {page.name}
          </div>
          <h1 className="max-w-xl text-3xl font-bold leading-[1.05] tracking-[-0.04em] text-white sm:text-5xl lg:text-6xl">
            {page.h1}
          </h1>
          <p className="mt-5 max-w-xl text-base leading-7 text-slate-400 sm:text-lg sm:leading-8">
            {page.lede}
          </p>
          <div className="mt-6 grid gap-3">
            {page.bestFor.map((item) => (
              <div key={item} className="flex items-start gap-3 text-sm font-medium text-slate-300">
                <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-emerald-400" />
                <span>{item}</span>
              </div>
            ))}
          </div>
          <div className="mt-6 flex flex-row flex-wrap gap-3 lg:mt-8">
            <Button
              asChild
              size="lg"
              className={cn('border-0 text-white shadow-[0_10px_28px_-10px_#6366F1] hover:brightness-110', GRADIENT_BG)}
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
              className="border-white/15 bg-white/[0.03] text-slate-100 hover:bg-white/[0.07] hover:text-white"
            >
              <Link to="/compare">
                Compare all
                <ArrowRight className="ml-2 size-4" />
              </Link>
            </Button>
          </div>
          <SourcesNote page={page} />
        </div>
      </div>
    </section>
  )
}

// ── Proof strip ───────────────────────────────────────────────────────────────
function ProofStrip({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="border-y border-white/[0.06] bg-[#0a0b12] px-4 py-8 sm:px-6 lg:px-8">
      <div className="mx-auto grid max-w-6xl gap-4 sm:grid-cols-3">
        {page.proofPoints.map((item) => (
          <div key={item.label} className="rounded-lg border border-white/[0.08] bg-[#0c0e16] p-5">
            <p className="font-brandmono text-[10px] uppercase tracking-[0.14em] text-slate-500">{item.label}</p>
            <p className={cn('mt-2 font-brandmono text-lg font-semibold', GRADIENT_TEXT)}>{item.value}</p>
          </div>
        ))}
      </div>
    </section>
  )
}

// ── Short version ─────────────────────────────────────────────────────────────
function ShortVersionSection({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="bg-[#0a0b12] px-4 py-16 sm:px-6 lg:px-8">
      <div className="mx-auto grid max-w-6xl gap-8 lg:grid-cols-[0.35fr_1fr]">
        <div>
          <h2 className="text-2xl font-bold tracking-[-0.03em] text-white">The short version</h2>
          <p className="mt-3 text-sm leading-6 text-slate-400">
            A quick side-by-side before the deeper feature and pricing analysis.
          </p>
        </div>
        <div className="overflow-hidden rounded-lg border border-white/[0.08] bg-[#0c0e16]">
          <div className={cn('h-px w-full', GRADIENT_BAR)} />
          <div className="hidden grid-cols-[0.62fr_1fr_1fr] border-b border-white/[0.08] md:grid">
            <div className="px-4 py-3 font-brandmono text-[11px] uppercase tracking-[0.12em] text-slate-500">Dimension</div>
            <div className="px-4 py-3 font-brandmono text-[11px] uppercase tracking-[0.12em] text-slate-500">Moneat</div>
            <div className="px-4 py-3 font-brandmono text-[11px] uppercase tracking-[0.12em] text-slate-500">{page.name}</div>
          </div>
          {page.shortVersionRows.map((row) => (
            <div
              key={row.dimension}
              className="grid border-b border-white/[0.06] last:border-b-0 md:grid-cols-[0.62fr_1fr_1fr]"
            >
              <div className="bg-white/[0.02] px-4 py-4 text-sm font-semibold text-slate-200">{row.dimension}</div>
              <div className="px-4 py-4 text-sm leading-6 text-slate-400">
                <p className="mb-1 font-brandmono text-[10px] uppercase tracking-[0.12em] text-slate-500 md:hidden">Moneat</p>
                {row.moneat}
              </div>
              <div className="border-t border-white/[0.06] px-4 py-4 text-sm leading-6 text-slate-400 md:border-l md:border-t-0">
                <p className="mb-1 font-brandmono text-[10px] uppercase tracking-[0.12em] text-slate-500 md:hidden">{page.name}</p>
                {row.competitor}
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}

// ── Choice section ────────────────────────────────────────────────────────────
function ChoiceSection({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="px-4 py-24 sm:px-6 lg:px-8">
      <div className="mx-auto grid max-w-6xl gap-12 lg:grid-cols-[1fr_0.9fr]">
        <div>
          <SectionHeading
            kicker="When Moneat fits"
            title="When Moneat is the better fit"
            align="left"
          />
          <p className="mt-5 text-base leading-7 text-slate-400">{page.summary}</p>
          <div className="mt-8 grid gap-3">
            {page.bestFor.map((item) => (
              <div key={item} className="flex items-start gap-3 text-sm font-medium text-slate-300">
                <Check className="mt-0.5 size-4 shrink-0 text-emerald-400" />
                <span>{item}</span>
              </div>
            ))}
          </div>
        </div>
        <div>
          <h3 className="text-lg font-semibold text-slate-100">Where {page.name} is strong</h3>
          <div className="mt-5 grid gap-3 border-l border-white/[0.08] pl-5">
            {page.competitorStrengths.map((item) => (
              <div key={item} className="flex items-start gap-3 text-sm leading-6 text-slate-400">
                <Compass className="mt-1 size-4 shrink-0 text-indigo-300" />
                <span>{item}</span>
              </div>
            ))}
          </div>
          <p className="mt-5 text-sm leading-6 text-slate-500">
            This page is a practical comparison, not a blanket claim that one tool is best for every team.
          </p>
        </div>
      </div>
    </section>
  )
}

// ── Feature matrix ────────────────────────────────────────────────────────────
function FeatureMatrix({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="border-y border-white/[0.06] bg-[#0a0b12] px-4 py-24 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-6xl">
        <div className="max-w-2xl">
          <h2 className="text-3xl font-bold tracking-[-0.03em] text-white sm:text-4xl">
            Feature and pricing model comparison
          </h2>
          <p className="mt-4 text-base leading-7 text-slate-400">
            The table below uses current public pricing pages where possible and keeps estimates explicit.
          </p>
        </div>
        <div className="mt-10 overflow-hidden rounded-lg border border-white/[0.08] bg-[#0c0e16]">
          <div className={cn('h-px w-full', GRADIENT_BAR)} />
          <div className="hidden grid-cols-[0.8fr_1.1fr_1.1fr] border-b border-white/[0.08] md:grid">
            <div className="px-4 py-3 font-brandmono text-[11px] uppercase tracking-[0.12em] text-slate-500">Category</div>
            <div className="px-4 py-3 font-brandmono text-[11px] uppercase tracking-[0.12em] text-slate-500">Moneat</div>
            <div className="px-4 py-3 font-brandmono text-[11px] uppercase tracking-[0.12em] text-slate-500">{page.name}</div>
          </div>
          {page.featureRows.map((row, idx) => {
            const Icon = matrixIcons[idx % matrixIcons.length]
            return (
              <div
                key={row.label}
                className="grid gap-0 border-b border-white/[0.06] last:border-b-0 md:grid-cols-[0.8fr_1.1fr_1.1fr]"
              >
                <div className="flex items-center gap-3 bg-white/[0.02] px-4 py-4 text-sm font-semibold text-slate-200">
                  <Icon className="size-4 text-slate-500" />
                  {row.label}
                </div>
                <div className="px-4 py-4 text-sm leading-6 text-slate-400">
                  <p className="mb-1 font-brandmono text-[10px] uppercase tracking-[0.12em] text-slate-500 md:hidden">Moneat</p>
                  {row.moneat}
                </div>
                <div className="border-t border-white/[0.06] px-4 py-4 text-sm leading-6 text-slate-400 md:border-l md:border-t-0">
                  <p className="mb-1 font-brandmono text-[10px] uppercase tracking-[0.12em] text-slate-500 md:hidden">{page.name}</p>
                  {row.competitor}
                </div>
                {row.note && (
                  <div className="bg-indigo-950/30 px-4 py-3 text-xs leading-5 text-indigo-300 md:col-span-3">
                    {row.note}
                  </div>
                )}
              </div>
            )
          })}
        </div>
      </div>
    </section>
  )
}

// ── Cost scenarios ────────────────────────────────────────────────────────────
function CostScenarios({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="px-4 py-24 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-6xl">
        <div className="mb-10 grid gap-6 lg:grid-cols-[0.8fr_1fr] lg:items-end">
          <div>
            <h2 className="text-3xl font-bold tracking-[-0.03em] text-white sm:text-4xl">
              Cost scenarios
            </h2>
            <p className="mt-4 text-base leading-7 text-slate-400">
              These are directional comparisons from public pricing, not custom enterprise quotes.
            </p>
          </div>
          <div className="rounded-lg border border-white/[0.08] bg-[#0c0e16] p-5 text-sm leading-6 text-slate-400">
            {moneatPricingSummary}
          </div>
        </div>
        <div className="grid gap-4 lg:grid-cols-3">
          {page.costRows.map((row) => (
            <div key={row.scenario} className="rounded-lg border border-white/[0.08] bg-[#0c0e16] p-6">
              <h3 className="text-base font-semibold text-slate-100">{row.scenario}</h3>
              <div className="mt-5 space-y-4">
                <div>
                  <p className="font-brandmono text-[10px] uppercase tracking-[0.12em] text-slate-500">Moneat</p>
                  <p className="mt-1 text-sm leading-6 text-slate-300">{row.moneat}</p>
                </div>
                <div>
                  <p className="font-brandmono text-[10px] uppercase tracking-[0.12em] text-slate-500">{page.name}</p>
                  <p className="mt-1 text-sm leading-6 text-slate-400">{row.competitor}</p>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}

// ── Misconceptions ────────────────────────────────────────────────────────────
function MisconceptionsSection({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="border-y border-white/[0.06] bg-[#0a0b12] px-4 py-20 sm:px-6 lg:px-8">
      <div className="mx-auto grid max-w-6xl gap-10 lg:grid-cols-[0.45fr_1fr]">
        <div>
          <h2 className="text-3xl font-bold tracking-[-0.03em] text-white sm:text-4xl">
            What people get wrong
          </h2>
          <p className="mt-4 text-base leading-7 text-slate-400">
            The useful comparison is not a feature checklist. It is the operational and billing model
            you actually want to live with.
          </p>
        </div>
        <div className="grid gap-4">
          {page.misconceptions.map((item) => (
            <div key={item.myth} className="rounded-lg border border-white/[0.08] bg-[#0c0e16] p-5">
              <div className="flex items-start gap-3">
                <Lightbulb className="mt-0.5 size-5 shrink-0 text-amber-400" />
                <div>
                  <h3 className="text-sm font-semibold text-slate-100">{item.myth}</h3>
                  <p className="mt-2 text-sm leading-6 text-slate-400">{item.reality}</p>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}

// ── Decision section ──────────────────────────────────────────────────────────
function DecisionSection({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="px-4 py-24 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-6xl">
        <div className="max-w-2xl">
          <h2 className="text-3xl font-bold tracking-[-0.03em] text-white sm:text-4xl">
            Choose the right fit
          </h2>
          <p className="mt-4 text-base leading-7 text-slate-400">
            The right answer depends on whether your constraint is enterprise depth, migration risk,
            data ownership, or the shape of the monthly bill.
          </p>
        </div>
        <div className="mt-10 grid gap-5 lg:grid-cols-2">
          <div className="rounded-lg border border-amber-400/20 bg-amber-950/20 p-6">
            <h3 className="text-lg font-semibold text-slate-100">Choose {page.name} if...</h3>
            <div className="mt-5 grid gap-3">
              {page.chooseCompetitor.map((item) => (
                <div key={item} className="flex items-start gap-3 text-sm leading-6 text-slate-400">
                  <Check className="mt-1 size-4 shrink-0 text-amber-400" />
                  <span>{item}</span>
                </div>
              ))}
            </div>
          </div>
          <div className="rounded-lg border border-emerald-400/20 bg-emerald-950/20 p-6">
            <h3 className="text-lg font-semibold text-slate-100">Choose Moneat if...</h3>
            <div className="mt-5 grid gap-3">
              {page.chooseMoneat.map((item) => (
                <div key={item} className="flex items-start gap-3 text-sm leading-6 text-slate-400">
                  <Check className="mt-1 size-4 shrink-0 text-emerald-400" />
                  <span>{item}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}

// ── Migration section ─────────────────────────────────────────────────────────
function MigrationSection({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="border-y border-white/[0.06] bg-[#0a0b12] px-4 py-20 sm:px-6 lg:px-8">
      <div className="mx-auto grid max-w-6xl gap-10 lg:grid-cols-[0.4fr_1fr]">
        <div>
          <div className={cn('mb-4 flex size-10 items-center justify-center rounded-md border border-white/[0.08] bg-white/[0.03]')}>
            <Route className="size-5 text-indigo-300" />
          </div>
          <h2 className="text-3xl font-bold tracking-[-0.03em] text-white sm:text-4xl">
            A practical next step
          </h2>
          <p className="mt-4 text-base leading-7 text-slate-400">
            Treat the switch as a measured migration, not a big-bang replacement.
          </p>
        </div>
        <div className="grid gap-4 md:grid-cols-3">
          {page.migrationSteps.map((step, index) => (
            <div key={step.title} className="rounded-lg border border-white/[0.08] bg-[#0c0e16] p-5">
              <div className={cn('flex size-9 items-center justify-center rounded-md border border-white/[0.08] font-brandmono text-sm font-semibold', GRADIENT_TEXT)}>
                {index + 1}
              </div>
              <h3 className="mt-5 text-base font-semibold text-slate-100">{step.title}</h3>
              <p className="mt-2 text-sm leading-6 text-slate-400">{step.detail}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}

// ── Caveats section ───────────────────────────────────────────────────────────
function CaveatsSection({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="border-y border-white/[0.06] bg-[#0a0b12] px-4 py-20 sm:px-6 lg:px-8">
      <div className="mx-auto grid max-w-6xl gap-10 lg:grid-cols-[0.8fr_1.2fr]">
        <div>
          <h2 className="text-3xl font-bold tracking-[-0.03em] text-white sm:text-4xl">
            Plain-English caveats
          </h2>
          <p className="mt-4 text-base leading-7 text-slate-400">
            Comparison pages should earn trust. These are the points a buyer should know before switching.
          </p>
        </div>
        <div className="grid gap-3">
          {page.caveats.map((item) => (
            <div
              key={item}
              className="rounded-lg border border-white/[0.08] bg-white/[0.02] p-4 text-sm leading-6 text-slate-400"
            >
              {item}
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}

// ── Related comparisons ───────────────────────────────────────────────────────
function RelatedComparisons({currentSlug}: {readonly currentSlug?: CompetitorSlug}) {
  const pages = competitorPages.filter((page) => page.slug !== currentSlug)

  return (
    <section className="border-t border-white/[0.06] bg-[#0a0b12] px-4 py-20 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-6xl">
        <div className="mb-8 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h2 className="text-2xl font-bold tracking-[-0.03em] text-white">Compare Moneat with other tools</h2>
            <p className="mt-2 text-sm leading-6 text-slate-400">
              Each page uses a canonical SEO URL for alternative searches.
            </p>
          </div>
          <Button asChild variant="outline" className="w-fit border-white/15 bg-white/[0.03] text-slate-100 hover:bg-white/[0.07] hover:text-white">
            <Link to="/compare">
              Comparison hub
              <ArrowRight className="ml-2 size-4" />
            </Link>
          </Button>
        </div>
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
          {pages.map((page) => (
            <a
              key={page.slug}
              href={page.route}
              className="group rounded-lg border border-white/[0.08] bg-[#0c0e16] p-5 transition-colors hover:border-white/20"
            >
              <p className="text-sm font-semibold text-slate-100">{page.title}</p>
              <p className="mt-2 line-clamp-3 text-sm leading-6 text-slate-400">{page.description}</p>
              <span className="mt-4 inline-flex items-center text-sm font-medium text-indigo-300 group-hover:text-white">
                Read comparison
                <ArrowRight className="ml-1 size-4 transition-transform group-hover:translate-x-0.5" />
              </span>
            </a>
          ))}
        </div>
      </div>
    </section>
  )
}

// ── Final CTA ─────────────────────────────────────────────────────────────────
function FinalCtaSection({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="relative overflow-hidden border-t border-white/[0.06] px-4 py-20 sm:px-6 lg:px-8">
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 bg-[radial-gradient(48rem_24rem_at_50%_120%,rgba(124,92,246,0.18),transparent_70%)]"
      />
      <div className="relative mx-auto grid max-w-6xl gap-8 lg:grid-cols-[1fr_0.55fr] lg:items-center">
        <div>
          <h2 className="max-w-3xl text-3xl font-bold tracking-[-0.03em] text-white sm:text-4xl">
            Test Moneat against {page.name} with one real service.
          </h2>
          <p className="mt-4 max-w-2xl text-base leading-7 text-slate-400">
            Start with the telemetry you already have, then compare the debugging, cost, and response workflow
            before committing to a wider migration.
          </p>
        </div>
        <div className="flex flex-col gap-3 sm:flex-row lg:justify-end">
          <Button
            asChild
            size="lg"
            className={cn('border-0 text-white shadow-[0_10px_28px_-10px_#6366F1] hover:brightness-110', GRADIENT_BG)}
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
            className="border-white/15 bg-white/[0.03] text-slate-100 hover:bg-white/[0.07] hover:text-white"
          >
            <Link to="/pricing">
              View pricing
              <ArrowRight className="ml-2 size-4" />
            </Link>
          </Button>
        </div>
      </div>
    </section>
  )
}

// ── Comparison table (centerpiece) ────────────────────────────────────────────
type MarkKind = 'yes' | 'partial' | 'no'
type CellValue = MarkKind | string

const COMPARE_ORDER: CompetitorSlug[] = ['datadog', 'sentry', 'signoz', 'better-stack']
const allCompareColumns = COMPARE_ORDER.map((slug) => competitorPages.find((c) => c.slug === slug))
  .filter((p): p is (typeof competitorPages)[number] => Boolean(p))
  .map((p) => ({slug: p.slug, name: p.name, route: p.route}))

const compareRows: Array<{label: string; moneat: CellValue; values: Record<CompetitorSlug, CellValue>}> = [
  {label: 'Error tracking', moneat: 'yes', values: {datadog: 'yes', sentry: 'yes', signoz: 'partial', 'better-stack': 'partial'}},
  {label: 'Log management', moneat: 'yes', values: {datadog: 'yes', sentry: 'partial', signoz: 'yes', 'better-stack': 'yes'}},
  {label: 'APM & distributed tracing', moneat: 'yes', values: {datadog: 'yes', sentry: 'yes', signoz: 'yes', 'better-stack': 'partial'}},
  {label: 'Infrastructure & host metrics', moneat: 'yes', values: {datadog: 'yes', sentry: 'no', signoz: 'yes', 'better-stack': 'partial'}},
  {label: 'Uptime & status pages', moneat: 'yes', values: {datadog: 'partial', sentry: 'partial', signoz: 'partial', 'better-stack': 'yes'}},
  {label: 'On-call & incidents', moneat: 'yes', values: {datadog: 'yes', sentry: 'no', signoz: 'no', 'better-stack': 'yes'}},
  {label: 'Open source & self-host', moneat: 'yes', values: {datadog: 'no', sentry: 'partial', signoz: 'yes', 'better-stack': 'no'}},
  {
    label: 'Pricing model',
    moneat: 'Tiered — no per-host fee',
    values: {
      datadog: 'Per-host + per-product',
      sentry: 'Per-event volume',
      signoz: 'Usage or self-host',
      'better-stack': 'Per-monitor + seat',
    },
  },
]

function CompareValue({value, highlight}: {readonly value: CellValue; readonly highlight: boolean}) {
  if (value === 'yes') {
    return <Check className={cn('mx-auto size-4', highlight ? 'text-cyan-300' : 'text-emerald-400')} aria-label="Yes" />
  }
  if (value === 'no') {
    return <Minus className="mx-auto size-4 text-slate-600" aria-label="No" />
  }
  if (value === 'partial') {
    return (
      <span className="inline-flex items-center gap-1.5">
        <span className="size-1.5 rounded-full bg-amber-400" />
        <span className="font-brandmono text-[11px] text-amber-300/90">Partial</span>
      </span>
    )
  }
  return <span className={cn('text-xs', highlight ? 'text-slate-200' : 'text-slate-400')}>{value}</span>
}

function FullComparisonTable() {
  return (
    <section className="px-4 py-24 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-6xl">
        <SectionHeading
          kicker="Where Moneat fits"
          title="One platform against four point tools."
          lead="Each alternative is strong somewhere. Moneat covers the whole operational path in one product you can self-host."
        />
        <div className="mt-12 overflow-x-auto rounded-lg border border-white/[0.08] bg-[#0c0e16]">
          <div className={cn('h-px w-full', GRADIENT_BAR)} />
          <table className="w-full min-w-[700px] border-collapse text-sm">
            <thead>
              <tr className="border-b border-white/[0.08]">
                <th className="px-4 py-4 text-left font-brandmono text-[11px] uppercase tracking-[0.12em] text-slate-500">
                  Capability
                </th>
                {/* Moneat column — gradient keyline + bg glow */}
                <th className="relative border-x border-indigo-500/30 bg-indigo-950/30 px-4 py-4 text-center">
                  <span className={cn('text-base font-bold', GRADIENT_TEXT)}>Moneat</span>
                </th>
                {allCompareColumns.map((col) => (
                  <th key={col.slug} className="px-4 py-4 text-center">
                    <a
                      href={col.route}
                      className="text-sm font-semibold text-slate-400 underline-offset-4 transition-colors hover:text-white hover:underline"
                    >
                      {col.name}
                    </a>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {compareRows.map((row) => (
                <tr key={row.label} className="border-t border-white/[0.06]">
                  <td className="px-4 py-3.5 text-left font-medium text-slate-300">{row.label}</td>
                  {/* Moneat cell — highlighted column */}
                  <td className="border-x border-indigo-500/30 bg-indigo-950/20 px-4 py-3.5 text-center">
                    <CompareValue value={row.moneat} highlight />
                  </td>
                  {allCompareColumns.map((col) => (
                    <td key={col.slug} className="px-4 py-3.5 text-center">
                      <CompareValue value={row.values[col.slug]} highlight={false} />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <p className="mt-4 font-brandmono text-[11px] leading-5 text-slate-500">
          Drawn from each vendor&apos;s public docs and pricing, reviewed {SOURCE_REVIEW_DATE}.{' '}
          <a href="/compare" className="text-indigo-300 underline-offset-4 hover:underline">
            See the full per-vendor breakdowns →
          </a>
        </p>
      </div>
    </section>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// CompetitorAlternativePage — per-vendor comparison page
// ─────────────────────────────────────────────────────────────────────────────
export function CompetitorAlternativePage({slug}: CompetitorComparisonPageProps) {
  useForceDarkTheme()
  const page = getCompetitorPage(slug)

  return (
    <article className="min-h-screen bg-[#08090f] font-display text-slate-300">
      <SeoHead seo={competitorPageSeo(page)} />

      <LandingNavbar tone="dark" />
      <main>
        <ComparisonHero page={page} />
        <ShortVersionSection page={page} />
        <ProofStrip page={page} />
        <ChoiceSection page={page} />
        <FeatureMatrix page={page} />
        <CostScenarios page={page} />
        <MisconceptionsSection page={page} />
        <DecisionSection page={page} />
        <MigrationSection page={page} />
        <CaveatsSection page={page} />
        <RelatedComparisons currentSlug={page.slug} />
        <FinalCtaSection page={page} />
      </main>
      <LandingFooter tone="dark" />
    </article>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// CompareHubPage — /compare landing
// ─────────────────────────────────────────────────────────────────────────────
export function CompareHubPage() {
  useForceDarkTheme()

  return (
    <article className="min-h-screen bg-[#08090f] font-display text-slate-300">
      <SeoHead seo={compareHubSeo} />

      <LandingNavbar tone="dark" />
      <main>
        {/* Hub hero */}
        <section className="relative overflow-hidden px-4 pb-20 pt-20 sm:px-6 lg:px-8">
          <div aria-hidden className="pointer-events-none absolute inset-0">
            <div className="absolute inset-0 bg-[radial-gradient(58rem_30rem_at_72%_-10%,rgba(124,92,246,0.2),transparent_70%)]" />
            <div className="absolute inset-0 bg-[linear-gradient(rgba(148,163,184,0.04)_1px,transparent_1px),linear-gradient(90deg,rgba(148,163,184,0.04)_1px,transparent_1px)] bg-[size:56px_56px] [mask-image:radial-gradient(60rem_40rem_at_50%_0%,#000,transparent_75%)]" />
          </div>
          <div className="relative mx-auto max-w-6xl">
            <div className="max-w-3xl">
              <div className="mb-6 inline-flex items-center gap-2.5 font-brandmono text-[11px] font-medium uppercase tracking-[0.18em] text-indigo-300">
                <span className={cn('h-px w-6 rounded', GRADIENT_BAR)} />
                Comparison hub
              </div>
              <h1 className="text-4xl font-bold leading-[1.05] tracking-[-0.04em] text-white sm:text-5xl lg:text-6xl">
                Compare Moneat with the observability tools teams already know
              </h1>
              <p className="mt-6 text-lg leading-8 text-slate-400">
                Use these evidence-led pages to compare pricing models, migration paths, and operational coverage
                across Datadog, Sentry, Better Stack, and SigNoz.
              </p>
              <div className="mt-8 flex flex-col gap-3 sm:flex-row">
                <Button
                  asChild
                  size="lg"
                  className={cn('border-0 text-white shadow-[0_10px_28px_-10px_#6366F1] hover:brightness-110', GRADIENT_BG)}
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
                  className="border-white/15 bg-white/[0.03] text-slate-100 hover:bg-white/[0.07] hover:text-white"
                >
                  <Link to="/pricing">
                    View pricing
                    <ArrowRight className="ml-2 size-4" />
                  </Link>
                </Button>
              </div>
              <div className="mt-8 rounded-lg border border-white/[0.08] bg-white/[0.03] p-4 text-sm leading-6 text-slate-400">
                <p className="font-brandmono text-[11px] uppercase tracking-[0.12em] text-slate-500">
                  Prices and features last reviewed {SOURCE_REVIEW_DATE}.
                </p>
                <div className="mt-2 flex flex-wrap gap-x-4 gap-y-2">
                  {hubSources.map((source) => (
                    <a
                      key={source.href}
                      href={source.href}
                      target={source.href.startsWith('http') ? '_blank' : undefined}
                      rel={source.href.startsWith('http') ? 'noopener noreferrer' : undefined}
                      className="inline-flex items-center gap-1 font-medium text-indigo-300 underline-offset-4 hover:text-white hover:underline"
                    >
                      {source.label}
                      <ExternalLink className="size-3" />
                    </a>
                  ))}
                </div>
              </div>
            </div>
          </div>
        </section>

        {/* Full comparison table */}
        <FullComparisonTable />

        {/* Per-vendor cards */}
        <section className="border-y border-white/[0.06] bg-[#0a0b12] px-4 py-20 sm:px-6 lg:px-8">
          <div className="mx-auto grid max-w-6xl gap-5 md:grid-cols-2">
            {competitorPages.map((page) => (
              <a
                key={page.slug}
                href={page.route}
                className="group rounded-lg border border-white/[0.08] bg-[#0c0e16] p-6 transition-colors hover:border-white/20"
              >
                <div className="flex items-start justify-between gap-4">
                  <div>
                    <h2 className="text-xl font-semibold text-slate-100">{page.title}</h2>
                    <p className="mt-3 text-sm leading-6 text-slate-400">{page.description}</p>
                  </div>
                  <BarChart3 className="size-5 shrink-0 text-slate-600" />
                </div>
                <div className="mt-6 grid gap-2">
                  {page.bestFor.slice(0, 2).map((item) => (
                    <div key={item} className="flex items-start gap-2 text-sm text-slate-400">
                      <Check className="mt-0.5 size-4 shrink-0 text-emerald-400" />
                      {item}
                    </div>
                  ))}
                </div>
                <span className="mt-6 inline-flex items-center text-sm font-medium text-indigo-300 group-hover:text-white">
                  Read {page.name} comparison
                  <ArrowRight className="ml-1 size-4 transition-transform group-hover:translate-x-0.5" />
                </span>
              </a>
            ))}
          </div>
        </section>

        {/* Why alternative URLs */}
        <section className="px-4 py-20 sm:px-6 lg:px-8">
          <div className="mx-auto grid max-w-6xl gap-8 lg:grid-cols-[0.8fr_1fr] lg:items-center">
            <div>
              <h2 className="text-3xl font-bold tracking-[-0.03em] text-white sm:text-4xl">
                Why these pages use alternative URLs
              </h2>
              <p className="mt-4 text-base leading-7 text-slate-400">
                People search for direct alternatives when they feel pricing pressure, migration pressure,
                or workflow friction. Each canonical page is named for that intent.
              </p>
            </div>
            <div className="rounded-lg border border-white/[0.08] bg-[#0c0e16] p-6 text-sm leading-6 text-slate-400">
              Canonical pages: /datadog-alternative, /sentry-alternative, /better-stack-alternative,
              and /signoz-alternative. The hub stays at /compare.
            </div>
          </div>
        </section>
      </main>
      <LandingFooter tone="dark" />
    </article>
  )
}
