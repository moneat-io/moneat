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

import {createFileRoute} from '@tanstack/react-router'
import {
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  ClipboardCheck,
  FileJson,
  Gauge,
  LayoutDashboard,
  PlugZap,
  Upload,
} from 'lucide-react'
import {useEffect} from 'react'
import {Helmet} from 'react-helmet-async'
import {Button} from '@/components/ui/button'
import {LandingFooter, LandingNavbar} from '@/components/landing/LandingNavbar'
import {trackEvent} from '@/lib/analytics'
import {api} from '@/lib/api'
import {
  DATADOG_IMPORT_DASHBOARDS_URL,
  DATADOG_IMPORT_SIGNUP_URL,
  markDatadogImportSignupIntent,
} from '@/lib/datadogImportFunnel'

export const Route = createFileRoute('/datadog-dashboard-import')({
  component: DatadogDashboardImportPage,
})

const reportItems = [
  {
    icon: CheckCircle2,
    title: 'Supported widgets',
    body: 'See which timeseries, query value, table, and group widgets converted cleanly.',
  },
  {
    icon: AlertTriangle,
    title: 'Conversion warnings',
    body: 'Unsupported widgets and query gaps become explicit follow-up items instead of hidden migration risk.',
  },
  {
    icon: Gauge,
    title: 'Telemetry needed',
    body: 'Mapped metrics show whether the dashboard needs Datadog Agent data, OTLP metrics, traces, or logs.',
  },
]

const exportSteps = [
  'Open the dashboard you want to test in Datadog.',
  'Use the dashboard settings menu to export or copy the dashboard JSON.',
  'Create a free Moneat account, then paste or upload the JSON in the dashboard importer.',
]

const cutoverSteps = [
  {
    title: 'Import the view',
    body: 'Dashboard import checks whether Moneat can preserve the operational surface your team watches.',
  },
  {
    title: 'Review the report',
    body: 'Warnings show what converted, what needs manual cleanup, and which source data the widgets expect.',
  },
  {
    title: 'Feed live telemetry',
    body: 'Point the Datadog Agent or OTLP exporters at Moneat only after the imported view is worth testing.',
  },
]

function trackImportStart(source: string): void {
  markDatadogImportSignupIntent()
  trackEvent('datadog_import_started', {source})
}

function DatadogDashboardImportPage() {
  const importHref = api.isAuthenticated() ? DATADOG_IMPORT_DASHBOARDS_URL : DATADOG_IMPORT_SIGNUP_URL

  useEffect(() => {
    trackEvent('datadog_import_page_view')
  }, [])

  return (
    <article className="min-h-screen bg-white text-slate-950">
      <Helmet>
        <title>Datadog Dashboard Import | Moneat</title>
        <meta
          name="description"
          content={
            'Import a Datadog dashboard export into Moneat, review supported widgets and warnings, ' +
            'then cut over live telemetry with the Datadog Agent or OTLP.'
          }
        />
        <link rel="canonical" href="https://moneat.io/datadog-dashboard-import" />
      </Helmet>

      <LandingNavbar tone="light" />

      <main>
        <section className="relative isolate overflow-hidden border-b border-slate-200 px-4 py-16 sm:px-6 lg:px-8">
          <img
            src="/marketing/observability-comparison-hero-a.webp"
            alt=""
            aria-hidden="true"
            className="absolute inset-y-0 right-0 -z-20 h-full w-full object-cover object-right opacity-20"
          />
          <div
            className={
              'absolute inset-0 -z-10 ' +
              'bg-[linear-gradient(90deg,#ffffff_0%,#ffffff_48%,rgba(255,255,255,0.84)_72%,rgba(255,255,255,0.4)_100%)]'
            }
          />

          <div className="mx-auto grid max-w-6xl gap-10 lg:grid-cols-[0.95fr_1.05fr] lg:items-center">
            <div className="max-w-2xl">
              <div className="inline-flex items-center gap-2 rounded-full border border-sky-200 bg-sky-50 px-3 py-1">
                <FileJson className="size-3.5 text-sky-700" />
                <span className="text-xs font-semibold uppercase text-sky-800">
                  Datadog dashboard import
                </span>
              </div>

              <h1 className="mt-6 text-4xl font-semibold leading-[1.04] text-slate-950 sm:text-5xl lg:text-6xl">
                Import your Datadog dashboards before committing to a migration.
              </h1>

              <p className="mt-6 max-w-xl text-base leading-7 text-slate-600 sm:text-lg sm:leading-8">
                Upload a Datadog dashboard export. Moneat converts supported widgets, reports warnings,
                and shows the telemetry needed to make the dashboard live.
              </p>

              <div className="mt-8 flex flex-col gap-3 sm:flex-row">
                <Button asChild size="lg" className="h-12 bg-slate-950 px-6 text-white hover:bg-slate-800">
                  <a href={importHref} onClick={() => trackImportStart('hero_primary')}>
                    Import a Datadog dashboard
                    <ArrowRight className="ml-2 size-4" />
                  </a>
                </Button>
                <Button
                  asChild
                  variant="outline"
                  size="lg"
                  className="h-12 border-slate-400 bg-white px-6 font-semibold text-slate-950 hover:bg-slate-50"
                >
                  <a href="/docs/datadog-agent/dashboard-import">
                    View import docs
                    <ArrowRight className="ml-2 size-4" />
                  </a>
                </Button>
              </div>

              <p className="mt-5 max-w-xl text-sm leading-6 text-slate-600">
                You do not need a paid Datadog account to test Moneat with bundled or user-provided
                dashboard exports. Live data still requires pointing the Datadog Agent or OTLP exporters at Moneat.
              </p>
            </div>

            <div className="rounded-lg border border-slate-200 bg-white/92 p-5 shadow-xl shadow-slate-200/70">
              <div className="flex items-center justify-between border-b border-slate-200 pb-4">
                <div>
                  <p className="text-xs font-semibold uppercase text-slate-500">Conversion report</p>
                  <h2 className="mt-1 text-lg font-semibold text-slate-950">Kubernetes capacity dashboard</h2>
                </div>
                <span className="rounded-md bg-emerald-50 px-2.5 py-1 text-xs font-semibold text-emerald-700">
                  JSON parsed
                </span>
              </div>
              <div className="mt-5 grid gap-3">
                {reportItems.map((item) => (
                  <div key={item.title} className="rounded-lg border border-slate-200 bg-slate-50 p-4">
                    <div className="flex items-start gap-3">
                      <div
                        className={
                          'flex size-9 shrink-0 items-center justify-center rounded-md ' +
                          'bg-white text-sky-700'
                        }
                      >
                        <item.icon className="size-4" />
                      </div>
                      <div>
                        <h3 className="text-sm font-semibold text-slate-950">{item.title}</h3>
                        <p className="mt-1 text-sm leading-6 text-slate-600">{item.body}</p>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </section>

        <section className="px-4 py-20 sm:px-6 lg:px-8">
          <div className="mx-auto grid max-w-6xl gap-10 lg:grid-cols-[0.4fr_1fr]">
            <div>
              <div className="flex size-11 items-center justify-center rounded-lg bg-sky-50 text-sky-700">
                <Upload className="size-5" />
              </div>
              <h2 className="mt-5 text-3xl font-semibold text-slate-950">Use your own artifact</h2>
              <p className="mt-4 text-base leading-7 text-slate-600">
                A dashboard export gives your team a concrete way to test Moneat with an operational view
                they already trust.
              </p>
            </div>
            <div className="grid gap-4 md:grid-cols-3">
              {exportSteps.map((step, index) => (
                <div key={step} className="rounded-lg border border-slate-200 bg-white p-5 shadow-sm">
                  <div
                    className={
                      'flex size-8 items-center justify-center rounded-md bg-slate-950 ' +
                      'text-sm font-semibold text-white'
                    }
                  >
                    {index + 1}
                  </div>
                  <p className="mt-4 text-sm leading-6 text-slate-700">{step}</p>
                </div>
              ))}
            </div>
          </div>
        </section>

        <section className="border-y border-slate-200 bg-slate-50 px-4 py-20 sm:px-6 lg:px-8">
          <div className="mx-auto max-w-6xl">
            <div className="max-w-3xl">
              <h2 className="text-3xl font-semibold text-slate-950">
                Dashboard import and Agent cutover are different proofs.
              </h2>
              <p className="mt-4 text-base leading-7 text-slate-600">
                Importing the dashboard proves the view can move. Live charts require telemetry.
                The practical loop is to import first, then cut over one low-risk Agent workload.
              </p>
            </div>
            <div className="mt-10 grid gap-5 md:grid-cols-3">
              {cutoverSteps.map((step, index) => {
                const Icon = index === 0 ? LayoutDashboard : index === 1 ? ClipboardCheck : PlugZap
                return (
                  <div key={step.title} className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
                    <Icon className="size-5 text-sky-700" />
                    <h3 className="mt-4 text-lg font-semibold text-slate-950">{step.title}</h3>
                    <p className="mt-3 text-sm leading-6 text-slate-600">{step.body}</p>
                  </div>
                )
              })}
            </div>
          </div>
        </section>

        <section className="px-4 py-20 sm:px-6 lg:px-8">
          <div className="mx-auto max-w-6xl rounded-lg border border-slate-200 bg-slate-950 p-8 text-white sm:p-10">
            <div className="grid gap-8 lg:grid-cols-[1fr_auto] lg:items-center">
              <div>
                <p className="text-sm font-semibold uppercase text-sky-300">Free conversion report</p>
                <h2 className="mt-3 text-3xl font-semibold">Bring one Datadog dashboard export.</h2>
                <p className="mt-4 max-w-2xl text-sm leading-6 text-slate-300">
                  Moneat will show supported widgets, warnings, mapped metrics, and the next telemetry source
                  to connect. Use that report before changing any production Agent configuration.
                </p>
              </div>
              <Button asChild size="lg" className="h-12 bg-sky-500 px-6 text-white hover:bg-sky-600">
                <a href={importHref} onClick={() => trackImportStart('bottom_cta')}>
                  Get a free conversion report
                  <ArrowRight className="ml-2 size-4" />
                </a>
              </Button>
            </div>
          </div>
        </section>
      </main>

      <LandingFooter tone="light" />
    </article>
  )
}
