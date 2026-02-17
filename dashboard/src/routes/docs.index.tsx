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

import {createFileRoute, Link} from '@tanstack/react-router'
import {Helmet} from 'react-helmet-async'
import {
  Bug,
  ListChecks,
  Terminal,
  Package,
  Bell,
  Activity,
  Globe,
  Code,
  Plug,
  Shield,
  Key,
  CreditCard,
  ArrowRight,
  Rocket,
  Brain,
} from 'lucide-react'

export const Route = createFileRoute('/docs/')({
  component: DocsIndex,
})

const featureCards = [
  {
    icon: Bug,
    title: 'Error Monitoring',
    description: 'Capture and track errors in real-time using Sentry-compatible SDKs.',
    href: '/docs/error-monitoring',
    color: 'text-red-500',
  },
  {
    icon: ListChecks,
    title: 'Issue Tracking',
    description: 'Group, triage, and resolve issues with automatic fingerprinting.',
    href: '/docs/issue-tracking',
    color: 'text-orange-500',
  },
  {
    icon: Bell,
    title: 'On-Call & Incidents',
    description: 'Set up schedules, escalation policies, and manage incidents.',
    href: '/docs/on-call',
    color: 'text-blue-500',
  },
  {
    icon: Activity,
    title: 'Uptime Monitoring',
    description: 'Monitor your endpoints with HTTP checks and heartbeats.',
    href: '/docs/uptime-monitoring',
    color: 'text-emerald-500',
  },
  {
    icon: Terminal,
    title: 'Structured Logging',
    description: 'Ingest, search, and tail logs via OTLP and WebSocket.',
    href: '/docs/logging',
    color: 'text-violet-500',
  },
  {
    icon: Globe,
    title: 'Status Pages',
    description: 'Create public status pages to communicate incidents to users.',
    href: '/docs/status-pages',
    color: 'text-cyan-500',
  },
  {
    icon: Package,
    title: 'Releases & Source Maps',
    description: 'Track deployments and upload source maps for readable stack traces.',
    href: '/docs/releases',
    color: 'text-amber-500',
  },
  {
    icon: Plug,
    title: 'Integrations',
    description: 'Connect Slack, Discord, and webhooks for notifications.',
    href: '/docs/integrations',
    color: 'text-pink-500',
  },
  {
    icon: Brain,
    title: 'AI Observability',
    description: 'Monitor LLM applications, trace agent executions, and track token costs.',
    href: '/docs/ai-observability',
    color: 'text-purple-500',
  },
]

const configCards = [
  {icon: Code, title: 'SDK Setup', href: '/docs/sdk-setup', description: 'Install and configure Sentry-compatible SDKs.'},
  {icon: Shield, title: 'SSO & Auth', href: '/docs/sso-authentication', description: 'Set up OAuth and SSO providers.'},
  {icon: Key, title: 'API Tokens', href: '/docs/api-tokens', description: 'Create and manage API tokens.'},
  {icon: CreditCard, title: 'Billing', href: '/docs/billing', description: 'Plans, usage, and billing management.'},
]

function DocsIndex() {
  return (
    <>
      <Helmet>
        <title>Moneat Documentation</title>
        <meta name="description" content="Learn how to use Moneat for error monitoring, incident management, uptime monitoring, and more. Sentry-compatible, self-hosted observability platform." />
        <meta property="og:title" content="Moneat Documentation" />
        <meta property="og:description" content="Learn how to use Moneat for error monitoring, incident management, uptime monitoring, and more." />
        <meta property="og:type" content="website" />
        <meta property="og:site_name" content="Moneat Documentation" />
      </Helmet>

      <div className="max-w-4xl">
        {/* Hero */}
        <div className="mb-12">
          <h1 className="text-4xl font-bold tracking-tight mb-3">Moneat Documentation</h1>
          <p className="text-lg text-muted-foreground max-w-2xl">
            Moneat is a Sentry-compatible observability platform for error monitoring, incident management,
            uptime tracking, and structured logging. Use any existing Sentry SDK to get started in minutes.
          </p>
          <Link
            to="/docs/getting-started"
            className="inline-flex items-center gap-2 mt-6 bg-primary text-primary-foreground px-5 py-2.5 rounded-lg font-medium hover:bg-primary/90 transition-colors"
          >
            <Rocket className="h-4 w-4" />
            Get Started
            <ArrowRight className="h-4 w-4" />
          </Link>
        </div>

        {/* Feature Cards */}
        <section className="mb-12">
          <h2 className="text-xl font-semibold mb-4">Features</h2>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            {featureCards.map((card) => {
              const Icon = card.icon
              return (
                <Link
                  key={card.href}
                  to={card.href}
                  className="group flex items-start gap-3 p-4 rounded-lg border hover:border-primary/30 hover:bg-accent/50 transition-all"
                >
                  <Icon className={`h-5 w-5 shrink-0 mt-0.5 ${card.color}`} />
                  <div>
                    <h3 className="text-sm font-semibold group-hover:text-primary transition-colors">{card.title}</h3>
                    <p className="text-sm text-muted-foreground mt-0.5">{card.description}</p>
                  </div>
                </Link>
              )
            })}
          </div>
        </section>

        {/* Config Cards */}
        <section>
          <h2 className="text-xl font-semibold mb-4">Configuration & Account</h2>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {configCards.map((card) => {
              const Icon = card.icon
              return (
                <Link
                  key={card.href}
                  to={card.href}
                  className="group flex items-center gap-3 p-3 rounded-lg border hover:border-primary/30 hover:bg-accent/50 transition-all"
                >
                  <Icon className="h-4 w-4 shrink-0 text-muted-foreground group-hover:text-primary transition-colors" />
                  <div>
                    <h3 className="text-sm font-medium">{card.title}</h3>
                    <p className="text-xs text-muted-foreground">{card.description}</p>
                  </div>
                </Link>
              )
            })}
          </div>
        </section>
      </div>
    </>
  )
}
