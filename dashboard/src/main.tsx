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

import React from 'react'
import ReactDOM from 'react-dom/client'
import {createRouter, RouterProvider} from '@tanstack/react-router'
import {QueryClient, QueryClientProvider} from '@tanstack/react-query'
import {routeTree} from './routeTree.gen'
import {TooltipProvider} from './components/ui/tooltip'
import {HelmetProvider} from 'react-helmet-async'
import * as Sentry from '@sentry/react'
import {initAnalytics} from './lib/analytics'
import {shouldRetryQuery} from './lib/query-retry'
import './index.css'

function configuredEnv(value: string | undefined): string | undefined {
  const normalized = value?.trim()
  if (!normalized) return undefined
  if (normalized.startsWith('__') && normalized.endsWith('__')) return undefined
  return normalized
}

function sampleRate(value: string | undefined, fallback: number): number {
  const configured = configuredEnv(value)
  if (!configured) return fallback

  const parsed = Number.parseFloat(configured)
  return Number.isFinite(parsed) ? parsed : fallback
}

const moneatVersion = configuredEnv(import.meta.env.VITE_MONEAT_VERSION)
const telemetryRelease =
  configuredEnv(import.meta.env.VITE_SENTRY_RELEASE) ||
  configuredEnv(import.meta.env.VITE_DD_VERSION) ||
  moneatVersion

// Initialize Sentry (error monitoring)
const sentryDsn = configuredEnv(import.meta.env.VITE_SENTRY_DSN)
if (sentryDsn) {
  Sentry.init({
    dsn: sentryDsn,
    environment: configuredEnv(import.meta.env.VITE_SENTRY_ENVIRONMENT) || 'production',
    release: telemetryRelease,
    integrations: [
      Sentry.browserTracingIntegration(),
      Sentry.replayIntegration({
        maskAllText: false,
        blockAllMedia: false,
      }),
    ],
    // Performance Monitoring
    tracesSampleRate: sampleRate(import.meta.env.VITE_SENTRY_TRACES_SAMPLE_RATE, 0.1),
    // Session Replay
    replaysSessionSampleRate: sampleRate(import.meta.env.VITE_SENTRY_REPLAYS_SESSION_SAMPLE_RATE, 1),
    replaysOnErrorSampleRate: sampleRate(import.meta.env.VITE_SENTRY_REPLAYS_ON_ERROR_SAMPLE_RATE, 1),
  })
}

// Initialize Moneat product analytics (pageviews + custom events)
// Only enabled when VITE_ANALYTICS_KEY is set (self-hosters can omit it)
const analyticsKey = configuredEnv(import.meta.env.VITE_ANALYTICS_KEY)
if (analyticsKey) {
  const backendUrl = configuredEnv(import.meta.env.VITE_BACKEND_URL) || 'https://api.moneat.io'
  initAnalytics({
    domain: globalThis.window.location.hostname,
    apiHost: backendUrl,
    key: analyticsKey,
  })
}

// Initialize Datadog RUM & Browser Logs (enterprise deployments only)
// Data is sent to Moneat's DD-compatible intake via the proxy parameter.
const datadogApplicationId = configuredEnv(import.meta.env.VITE_DD_APPLICATION_ID)
const datadogClientToken = configuredEnv(import.meta.env.VITE_DD_CLIENT_TOKEN)
if (datadogApplicationId && datadogClientToken) {
  const {initDatadog} = await import('./lib/datadog')
  initDatadog({
    applicationId: datadogApplicationId,
    clientToken: datadogClientToken,
    proxyUrl: configuredEnv(import.meta.env.VITE_DD_PROXY_URL),
    backendUrl: configuredEnv(import.meta.env.VITE_BACKEND_URL),
    service: configuredEnv(import.meta.env.VITE_DD_SERVICE),
    env: configuredEnv(import.meta.env.VITE_DD_ENV),
    version: configuredEnv(import.meta.env.VITE_DD_VERSION) || moneatVersion,
  })
}

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {retry: shouldRetryQuery},
  },
})

const router = createRouter({
  routeTree,
  context: { queryClient },
  scrollRestoration: true,
})

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <TooltipProvider>
        <HelmetProvider>
          <RouterProvider router={router} />
        </HelmetProvider>
      </TooltipProvider>
    </QueryClientProvider>
  </React.StrictMode>,
)
