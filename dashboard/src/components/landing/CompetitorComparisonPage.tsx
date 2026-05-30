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
  CircleDollarSign,
  Compass,
  ExternalLink,
  Gauge,
  Lightbulb,
  Route,
  Scale,
  ShieldCheck,
} from 'lucide-react'
import {SeoHead} from '@/components/SeoHead'
import {compareHubSeo, competitorPageSeo} from '@/lib/seo/routes'
import {Button} from '@/components/ui/button'
import {LandingFooter, LandingNavbar} from './LandingNavbar'
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

function SourcesNote({page}: {readonly page: CompetitorPageData}) {
  return (
    <div className="mt-6 rounded-lg border border-slate-200 bg-slate-50 p-4 text-sm leading-6 text-slate-600 lg:mt-8">
      <p className="font-medium text-slate-900">
        Prices and features last reviewed {SOURCE_REVIEW_DATE}.
      </p>
      <div className="mt-2 flex flex-wrap gap-x-4 gap-y-2">
        {page.sources.map((source) => (
          <a
            key={source.href}
            href={source.href}
            target={source.href.startsWith('http') ? '_blank' : undefined}
            rel={source.href.startsWith('http') ? 'noopener noreferrer' : undefined}
            className="inline-flex items-center gap-1 font-medium text-sky-700 hover:text-sky-900"
          >
            {source.label}
            <ExternalLink className="size-3" />
          </a>
        ))}
      </div>
    </div>
  )
}

function ComparisonHero({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="relative isolate overflow-hidden border-b border-slate-200 px-4 pb-8 pt-10 sm:px-6 lg:px-8 lg:pb-12 lg:pt-14">
      <img
        src={page.heroImage}
        alt=""
        aria-hidden="true"
        className="absolute inset-y-0 right-0 -z-20 h-full w-full object-cover object-right opacity-20 lg:hidden"
      />
      <div
        className={
          'absolute inset-0 -z-10 ' +
          'bg-[linear-gradient(90deg,#ffffff_0%,#ffffff_47%,rgba(255,255,255,0.86)_62%,rgba(255,255,255,0.16)_100%)] lg:bg-white'
        }
      />
      <div className="absolute inset-x-0 bottom-0 -z-10 h-40 bg-[linear-gradient(180deg,rgba(255,255,255,0)_0%,#f8fafc_100%)]" />
      <div className="relative mx-auto grid max-w-6xl gap-12 lg:grid-cols-[0.82fr_1.18fr]">
        <div className="max-w-2xl py-2 lg:py-6">
          <h1 className="max-w-xl text-3xl font-semibold leading-[1.05] text-slate-950 sm:text-5xl lg:text-6xl">
            {page.h1}
          </h1>
          <p className="mt-5 max-w-xl text-base leading-7 text-slate-600 sm:text-lg sm:leading-8">
            {page.lede}
          </p>
          <div className="mt-6 grid gap-3">
            {page.bestFor.map((item) => (
              <div key={item} className="flex items-start gap-3 text-sm font-medium text-slate-800">
                <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-emerald-600" />
                <span>{item}</span>
              </div>
            ))}
          </div>
          <div className="mt-6 flex flex-row flex-wrap gap-3 lg:mt-8">
            <Button
              asChild
              size="lg"
              className="h-12 min-w-36 flex-1 bg-slate-950 px-6 text-white hover:bg-slate-800 sm:flex-none"
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
              className={
                'h-12 min-w-36 flex-1 border-slate-400 bg-white px-6 font-semibold text-slate-950 ' +
                'shadow-sm hover:bg-slate-50 sm:flex-none'
              }
            >
              <Link to="/compare">
                Compare all
                <ArrowRight className="ml-2 size-4" />
              </Link>
            </Button>
          </div>
          <SourcesNote page={page} />
        </div>
        <div className="relative hidden min-h-[420px] lg:block">
          <img
            src={page.heroImage}
            alt={page.heroImageAlt}
            className={
              'absolute inset-y-4 right-[-2%] my-auto h-auto w-[98%] max-w-none object-contain ' +
              'opacity-90 contrast-110 drop-shadow-[0_28px_64px_rgba(15,23,42,0.10)]'
            }
          />
          <div className="absolute bottom-7 right-5 w-80 rounded-lg border border-slate-200 bg-white/94 p-4 shadow-lg shadow-slate-200/80 backdrop-blur">
            <div className="flex items-center gap-3">
              <div className="flex size-10 items-center justify-center rounded-md bg-emerald-50 text-emerald-700">
                <ShieldCheck className="size-5" />
              </div>
              <div>
                <p className="text-sm font-semibold text-slate-950">Migration paths stay open</p>
                <p className="mt-1 text-xs leading-5 text-slate-600">Sentry, Datadog Agent, and OTLP inputs.</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}

function ProofStrip({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="border-y border-slate-200 bg-slate-50 px-4 py-8 sm:px-6 lg:px-8">
      <div className="mx-auto grid max-w-6xl gap-4 sm:grid-cols-3">
        {page.proofPoints.map((item) => (
          <div key={item.label} className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
            <p className="text-xs font-medium uppercase text-slate-500">{item.label}</p>
            <p className="mt-2 text-lg font-semibold text-slate-950">{item.value}</p>
          </div>
        ))}
      </div>
    </section>
  )
}

function ShortVersionSection({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="bg-slate-50 px-4 py-16 sm:px-6 lg:px-8">
      <div className="mx-auto grid max-w-6xl gap-8 lg:grid-cols-[0.35fr_1fr]">
        <div>
          <h2 className="text-2xl font-semibold text-slate-950">The short version</h2>
          <p className="mt-3 text-sm leading-6 text-slate-600">
            A quick side-by-side before the deeper feature and pricing analysis.
          </p>
        </div>
        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
          <div className="hidden grid-cols-[0.62fr_1fr_1fr] border-b border-slate-200 bg-white md:grid">
            <div className="px-4 py-3 text-xs font-semibold uppercase text-slate-500">Dimension</div>
            <div className="px-4 py-3 text-xs font-semibold uppercase text-slate-500">Moneat</div>
            <div className="px-4 py-3 text-xs font-semibold uppercase text-slate-500">{page.name}</div>
          </div>
          {page.shortVersionRows.map((row) => (
            <div
              key={row.dimension}
              className="grid border-b border-slate-200 last:border-b-0 md:grid-cols-[0.62fr_1fr_1fr]"
            >
              <div className="bg-slate-50 px-4 py-4 text-sm font-semibold text-slate-950">{row.dimension}</div>
              <div className="px-4 py-4 text-sm leading-6 text-slate-700">
                <p className="mb-1 text-xs font-semibold uppercase text-slate-500 md:hidden">Moneat</p>
                {row.moneat}
              </div>
              <div className="px-4 py-4 text-sm leading-6 text-slate-700">
                <p className="mb-1 text-xs font-semibold uppercase text-slate-500 md:hidden">{page.name}</p>
                {row.competitor}
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}

function ChoiceSection({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="px-4 py-24 sm:px-6 lg:px-8">
      <div className="mx-auto grid max-w-6xl gap-12 lg:grid-cols-[1fr_0.9fr]">
        <div>
          <h2 className="text-3xl font-semibold leading-tight text-slate-950 sm:text-4xl">
            When Moneat is the better fit
          </h2>
          <p className="mt-5 text-base leading-7 text-slate-600">{page.summary}</p>
          <div className="mt-8 grid gap-3">
            {page.bestFor.map((item) => (
              <div key={item} className="flex items-start gap-3 text-sm font-medium text-slate-800">
                <Check className="mt-0.5 size-4 shrink-0 text-emerald-600" />
                <span>{item}</span>
              </div>
            ))}
          </div>
        </div>
        <div>
          <h3 className="text-lg font-semibold text-slate-950">Where {page.name} is strong</h3>
          <div className="mt-5 grid gap-3 border-l border-slate-200 pl-5">
            {page.competitorStrengths.map((item) => (
              <div key={item} className="flex items-start gap-3 text-sm leading-6 text-slate-700">
                <Compass className="mt-1 size-4 shrink-0 text-sky-600" />
                <span>{item}</span>
              </div>
            ))}
          </div>
          <p className="mt-5 text-sm leading-6 text-slate-600">
            This page is a practical comparison, not a blanket claim that one tool is best for every team.
          </p>
        </div>
      </div>
    </section>
  )
}

function FeatureMatrix({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="border-y border-slate-200 bg-slate-50 px-4 py-24 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-6xl">
        <div className="max-w-2xl">
          <h2 className="text-3xl font-semibold leading-tight text-slate-950 sm:text-4xl">
            Feature and pricing model comparison
          </h2>
          <p className="mt-4 text-base leading-7 text-slate-600">
            The table below uses current public pricing pages where possible and keeps estimates explicit.
          </p>
        </div>
        <div className="mt-10 overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
          <div className="hidden grid-cols-[0.8fr_1.1fr_1.1fr] border-b border-slate-200 bg-white text-slate-950 md:grid">
            <div className="px-4 py-3 text-xs font-semibold uppercase text-slate-500">Category</div>
            <div className="px-4 py-3 text-xs font-semibold uppercase text-slate-500">Moneat</div>
            <div className="px-4 py-3 text-xs font-semibold uppercase text-slate-500">{page.name}</div>
          </div>
          {page.featureRows.map((row, idx) => {
            const Icon = matrixIcons[idx % matrixIcons.length]
            return (
              <div
                key={row.label}
                className="grid gap-0 border-b border-slate-200 last:border-b-0 md:grid-cols-[0.8fr_1.1fr_1.1fr]"
              >
                <div className="flex items-center gap-3 bg-slate-50 px-4 py-4 text-sm font-semibold text-slate-950">
                  <Icon className="size-4 text-slate-500" />
                  {row.label}
                </div>
                <div className="px-4 py-4 text-sm leading-6 text-slate-700">
                  <p className="mb-1 text-xs font-semibold uppercase text-slate-500 md:hidden">Moneat</p>
                  {row.moneat}
                </div>
                <div className="px-4 py-4 text-sm leading-6 text-slate-700">
                  <p className="mb-1 text-xs font-semibold uppercase text-slate-500 md:hidden">{page.name}</p>
                  {row.competitor}
                </div>
                {row.note && (
                  <div className="bg-sky-50 px-4 py-3 text-xs leading-5 text-sky-900 md:col-span-3">
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

function CostScenarios({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="px-4 py-24 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-6xl">
        <div className="mb-10 grid gap-6 lg:grid-cols-[0.8fr_1fr] lg:items-end">
          <div>
            <h2 className="text-3xl font-semibold leading-tight text-slate-950 sm:text-4xl">
              Cost scenarios
            </h2>
            <p className="mt-4 text-base leading-7 text-slate-600">
              These are directional comparisons from public pricing, not custom enterprise quotes.
            </p>
          </div>
          <div className="rounded-lg border border-slate-200 bg-slate-50 p-5 text-sm leading-6 text-slate-700">
            {moneatPricingSummary}
          </div>
        </div>
        <div className="grid gap-4 lg:grid-cols-3">
          {page.costRows.map((row) => (
            <div key={row.scenario} className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
              <h3 className="text-base font-semibold text-slate-950">{row.scenario}</h3>
              <div className="mt-5 space-y-4">
                <div>
                  <p className="text-xs font-semibold uppercase text-slate-500">Moneat</p>
                  <p className="mt-1 text-sm leading-6 text-slate-700">{row.moneat}</p>
                </div>
                <div>
                  <p className="text-xs font-semibold uppercase text-slate-500">{page.name}</p>
                  <p className="mt-1 text-sm leading-6 text-slate-700">{row.competitor}</p>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}

function MisconceptionsSection({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="border-y border-slate-200 bg-slate-50 px-4 py-20 sm:px-6 lg:px-8">
      <div className="mx-auto grid max-w-6xl gap-10 lg:grid-cols-[0.45fr_1fr]">
        <div>
          <h2 className="text-3xl font-semibold leading-tight text-slate-950 sm:text-4xl">
            What people get wrong
          </h2>
          <p className="mt-4 text-base leading-7 text-slate-600">
            The useful comparison is not a feature checklist. It is the operational and billing model
            you actually want to live with.
          </p>
        </div>
        <div className="grid gap-4">
          {page.misconceptions.map((item) => (
            <div key={item.myth} className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
              <div className="flex items-start gap-3">
                <Lightbulb className="mt-0.5 size-5 shrink-0 text-amber-500" />
                <div>
                  <h3 className="text-sm font-semibold text-slate-950">{item.myth}</h3>
                  <p className="mt-2 text-sm leading-6 text-slate-600">{item.reality}</p>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}

function DecisionSection({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="px-4 py-24 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-6xl">
        <div className="max-w-2xl">
          <h2 className="text-3xl font-semibold leading-tight text-slate-950 sm:text-4xl">
            Choose the right fit
          </h2>
          <p className="mt-4 text-base leading-7 text-slate-600">
            The right answer depends on whether your constraint is enterprise depth, migration risk,
            data ownership, or the shape of the monthly bill.
          </p>
        </div>
        <div className="mt-10 grid gap-5 lg:grid-cols-2">
          <div className="rounded-lg border border-amber-200 bg-amber-50/60 p-6">
            <h3 className="text-lg font-semibold text-slate-950">Choose {page.name} if...</h3>
            <div className="mt-5 grid gap-3">
              {page.chooseCompetitor.map((item) => (
                <div key={item} className="flex items-start gap-3 text-sm leading-6 text-slate-700">
                  <Check className="mt-1 size-4 shrink-0 text-amber-600" />
                  <span>{item}</span>
                </div>
              ))}
            </div>
          </div>
          <div className="rounded-lg border border-emerald-200 bg-emerald-50/60 p-6">
            <h3 className="text-lg font-semibold text-slate-950">Choose Moneat if...</h3>
            <div className="mt-5 grid gap-3">
              {page.chooseMoneat.map((item) => (
                <div key={item} className="flex items-start gap-3 text-sm leading-6 text-slate-700">
                  <Check className="mt-1 size-4 shrink-0 text-emerald-600" />
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

function MigrationSection({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="border-y border-slate-200 bg-white px-4 py-20 sm:px-6 lg:px-8">
      <div className="mx-auto grid max-w-6xl gap-10 lg:grid-cols-[0.4fr_1fr]">
        <div>
          <Route className="mb-4 size-6 text-sky-600" />
          <h2 className="text-3xl font-semibold leading-tight text-slate-950 sm:text-4xl">
            A practical next step
          </h2>
          <p className="mt-4 text-base leading-7 text-slate-600">
            Treat the switch as a measured migration, not a big-bang replacement.
          </p>
        </div>
        <div className="grid gap-4 md:grid-cols-3">
          {page.migrationSteps.map((step, index) => (
            <div key={step.title} className="rounded-lg border border-slate-200 bg-slate-50 p-5">
              <div className="flex size-9 items-center justify-center rounded-md bg-white text-sm font-semibold text-sky-700 shadow-sm">
                {index + 1}
              </div>
              <h3 className="mt-5 text-base font-semibold text-slate-950">{step.title}</h3>
              <p className="mt-2 text-sm leading-6 text-slate-600">{step.detail}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}

function CaveatsSection({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="border-y border-slate-200 bg-white px-4 py-20 sm:px-6 lg:px-8">
      <div className="mx-auto grid max-w-6xl gap-10 lg:grid-cols-[0.8fr_1.2fr]">
        <div>
          <h2 className="text-3xl font-semibold leading-tight text-slate-950 sm:text-4xl">
            Plain-English caveats
          </h2>
          <p className="mt-4 text-base leading-7 text-slate-600">
            Comparison pages should earn trust. These are the points a buyer should know before switching.
          </p>
        </div>
        <div className="grid gap-3">
          {page.caveats.map((item) => (
            <div
              key={item}
              className="rounded-lg border border-slate-200 bg-slate-50 p-4 text-sm leading-6 text-slate-700"
            >
              {item}
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}

function RelatedComparisons({currentSlug}: {readonly currentSlug?: CompetitorSlug}) {
  const pages = competitorPages.filter((page) => page.slug !== currentSlug)

  return (
    <section className="border-t border-slate-200 bg-white px-4 py-20 sm:px-6 lg:px-8">
      <div className="mx-auto max-w-6xl">
        <div className="mb-8 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h2 className="text-2xl font-semibold text-slate-950">Compare Moneat with other tools</h2>
            <p className="mt-2 text-sm leading-6 text-slate-600">
              Each page uses a canonical SEO URL for alternative searches.
            </p>
          </div>
          <Button asChild variant="outline" className="w-fit border-slate-300 bg-white text-slate-950">
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
              className={
                'group rounded-lg border border-slate-200 bg-white p-5 shadow-sm transition-colors ' +
                'hover:border-slate-300'
              }
            >
              <p className="text-sm font-semibold text-slate-950">{page.title}</p>
              <p className="mt-2 line-clamp-3 text-sm leading-6 text-slate-600">{page.description}</p>
              <span className="mt-4 inline-flex items-center text-sm font-medium text-sky-700 group-hover:text-sky-900">
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

function FinalCtaSection({page}: {readonly page: CompetitorPageData}) {
  return (
    <section className="border-t border-slate-200 bg-slate-50 px-4 py-20 sm:px-6 lg:px-8">
      <div className="mx-auto grid max-w-6xl gap-8 lg:grid-cols-[1fr_0.55fr] lg:items-center">
        <div>
          <h2 className="max-w-3xl text-3xl font-semibold leading-tight text-slate-950 sm:text-4xl">
            Test Moneat against {page.name} with one real service.
          </h2>
          <p className="mt-4 max-w-2xl text-base leading-7 text-slate-600">
            Start with the telemetry you already have, then compare the debugging, cost, and response workflow
            before committing to a wider migration.
          </p>
        </div>
        <div className="flex flex-col gap-3 sm:flex-row lg:justify-end">
          <Button asChild size="lg" className="h-12 bg-slate-950 px-6 text-white hover:bg-slate-800">
            <Link to="/signup">
              Start free
              <ArrowRight className="ml-2 size-4" />
            </Link>
          </Button>
          <Button asChild variant="outline" size="lg" className="h-12 border-slate-300 bg-white px-6 text-slate-950">
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

export function CompetitorAlternativePage({slug}: CompetitorComparisonPageProps) {
  const page = getCompetitorPage(slug)

  return (
    <article className="min-h-screen bg-white text-slate-950">
      <SeoHead seo={competitorPageSeo(page)} />

      <LandingNavbar tone="light" />
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
      <LandingFooter tone="light" />
    </article>
  )
}

export function CompareHubPage() {
  return (
    <article className="min-h-screen bg-white text-slate-950">
      <SeoHead seo={compareHubSeo} />

      <LandingNavbar tone="light" />
      <main>
        <section className="relative overflow-hidden px-4 pb-20 pt-20 sm:px-6 lg:px-8">
          <div
            className={
              'absolute inset-x-0 top-0 h-96 ' +
              'bg-[linear-gradient(180deg,#f8fafc_0%,rgba(248,250,252,0)_100%)]'
            }
          />
          <div className="relative mx-auto max-w-6xl">
            <div className="max-w-3xl">
              <h1 className="text-4xl font-semibold leading-[1.05] text-slate-950 sm:text-5xl lg:text-6xl">
                Compare Moneat with the observability tools teams already know
              </h1>
              <p className="mt-6 text-lg leading-8 text-slate-600">
                Use these evidence-led pages to compare pricing models, migration paths, and operational coverage
                across Datadog, Sentry, Better Stack, and SigNoz.
              </p>
              <div className="mt-8 flex flex-col gap-3 sm:flex-row">
                <Button asChild size="lg" className="h-12 bg-slate-950 px-6 text-white hover:bg-slate-800">
                  <Link to="/signup">
                    Start free
                    <ArrowRight className="ml-2 size-4" />
                  </Link>
                </Button>
                <Button
                  asChild
                  variant="outline"
                  size="lg"
                  className={
                    'h-12 border-slate-400 bg-white px-6 font-semibold text-slate-950 shadow-sm ' +
                    'hover:bg-slate-50'
                  }
                >
                  <Link to="/pricing">
                    View pricing
                    <ArrowRight className="ml-2 size-4" />
                  </Link>
                </Button>
              </div>
              <div className="mt-8 rounded-lg border border-slate-200 bg-slate-50 p-4 text-sm leading-6 text-slate-600">
                <p className="font-medium text-slate-900">
                  Prices and features last reviewed {SOURCE_REVIEW_DATE}.
                </p>
                <div className="mt-2 flex flex-wrap gap-x-4 gap-y-2">
                  {hubSources.map((source) => (
                    <a
                      key={source.href}
                      href={source.href}
                      target={source.href.startsWith('http') ? '_blank' : undefined}
                      rel={source.href.startsWith('http') ? 'noopener noreferrer' : undefined}
                      className="inline-flex items-center gap-1 font-medium text-sky-700 hover:text-sky-900"
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

        <section className="border-y border-slate-200 bg-slate-50 px-4 py-20 sm:px-6 lg:px-8">
          <div className="mx-auto grid max-w-6xl gap-5 md:grid-cols-2">
            {competitorPages.map((page) => (
              <a
                key={page.slug}
                href={page.route}
                className={
                  'group rounded-lg border border-slate-200 bg-white p-6 shadow-sm transition-colors ' +
                  'hover:border-slate-300'
                }
              >
                <div className="flex items-start justify-between gap-4">
                  <div>
                    <h2 className="text-xl font-semibold text-slate-950">{page.title}</h2>
                    <p className="mt-3 text-sm leading-6 text-slate-600">{page.description}</p>
                  </div>
                  <BarChart3 className="size-5 shrink-0 text-slate-400" />
                </div>
                <div className="mt-6 grid gap-2">
                  {page.bestFor.slice(0, 2).map((item) => (
                    <div key={item} className="flex items-start gap-2 text-sm text-slate-700">
                      <Check className="mt-0.5 size-4 shrink-0 text-emerald-600" />
                      {item}
                    </div>
                  ))}
                </div>
                <span
                  className={
                    'mt-6 inline-flex items-center text-sm font-medium text-sky-700 ' +
                    'group-hover:text-sky-900'
                  }
                >
                  Read {page.name} comparison
                  <ArrowRight className="ml-1 size-4 transition-transform group-hover:translate-x-0.5" />
                </span>
              </a>
            ))}
          </div>
        </section>

        <section className="px-4 py-20 sm:px-6 lg:px-8">
          <div className="mx-auto grid max-w-6xl gap-8 lg:grid-cols-[0.8fr_1fr] lg:items-center">
            <div>
              <h2 className="text-3xl font-semibold leading-tight text-slate-950 sm:text-4xl">
                Why these pages use alternative URLs
              </h2>
              <p className="mt-4 text-base leading-7 text-slate-600">
                People search for direct alternatives when they feel pricing pressure, migration pressure,
                or workflow friction. Each canonical page is named for that intent.
              </p>
            </div>
            <div className="rounded-lg border border-slate-200 bg-slate-50 p-6 text-sm leading-6 text-slate-700">
              Canonical pages: /datadog-alternative, /sentry-alternative, /better-stack-alternative,
              and /signoz-alternative. The hub stays at /compare.
            </div>
          </div>
        </section>
      </main>
      <LandingFooter tone="light" />
    </article>
  )
}
