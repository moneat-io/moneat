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

// Initialize Sentry (error monitoring)
if (import.meta.env.VITE_SENTRY_DSN) {
  Sentry.init({
    dsn: import.meta.env.VITE_SENTRY_DSN,
    environment: import.meta.env.VITE_SENTRY_ENVIRONMENT || 'production',
    integrations: [
      Sentry.browserTracingIntegration(),
      Sentry.replayIntegration({
        maskAllText: false,
        blockAllMedia: false,
      }),
    ],
    // Performance Monitoring
    tracesSampleRate: parseFloat(import.meta.env.VITE_SENTRY_TRACES_SAMPLE_RATE) || 0.1,
    // Session Replay
    replaysSessionSampleRate: 0.1,
    replaysOnErrorSampleRate: 1.0,
  })
}

// Initialize Moneat product analytics (pageviews + custom events)
// Only enabled when VITE_ANALYTICS_KEY is set (self-hosters can omit it)
if (import.meta.env.VITE_ANALYTICS_KEY) {
  const backendUrl = import.meta.env.VITE_BACKEND_URL || 'https://api.moneat.io'
  initAnalytics({
    domain: window.location.hostname,
    apiHost: backendUrl,
    key: import.meta.env.VITE_ANALYTICS_KEY,
  })
}

// Initialize Datadog RUM & Browser Logs (enterprise deployments only)
// Data is sent to Moneat's DD-compatible intake via the proxy parameter.
if (import.meta.env.VITE_DD_APPLICATION_ID && import.meta.env.VITE_DD_CLIENT_TOKEN) {
  const {initDatadog} = await import('./lib/datadog')
  initDatadog({
    applicationId: import.meta.env.VITE_DD_APPLICATION_ID,
    clientToken: import.meta.env.VITE_DD_CLIENT_TOKEN,
    proxyUrl: import.meta.env.VITE_DD_PROXY_URL,
    backendUrl: import.meta.env.VITE_BACKEND_URL,
    service: import.meta.env.VITE_DD_SERVICE,
    env: import.meta.env.VITE_DD_ENV,
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
