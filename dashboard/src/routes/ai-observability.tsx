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
import {Brain, DollarSign, Clock, Layers, BarChart3, Zap} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'
import {WindowFrame, StatTile, GRADIENT_TEXT} from '@/components/landing/Landing'
import {cn} from '@/lib/utils'
import {getFeaturePageSeoInput} from '@/lib/seo/routes'

const pageSeo = getFeaturePageSeoInput('ai-observability')

// Feature-specific hero: an LLM trace/spend list showing model calls, token
// throughput, latency, and per-call cost. Built from the shared brand
// primitives (dark only, mono, one gradient on the headline spend value).
function AiObservabilityHero() {
  const calls: Array<{
    dot: string
    model: string
    tokens: string
    latency: string
    cost: string
  }> = [
    {dot: 'bg-emerald-400', model: 'claude-opus-4',     tokens: '2.1k→680', latency: '1 204ms', cost: '$0.091'},
    {dot: 'bg-emerald-400', model: 'gpt-4o',            tokens: '1.6k→512', latency:   '842ms', cost: '$0.043'},
    {dot: 'bg-emerald-400', model: 'gpt-4o-mini',       tokens:   '980→310', latency:   '318ms', cost: '$0.004'},
    {dot: 'bg-amber-400',   model: 'llama-3.1-70b',     tokens: '3.4k→920', latency: '2 871ms', cost: '$0.012'},
    {dot: 'bg-rose-400',    model: 'claude-haiku-3-5',  tokens:   '740→240', latency: 'timeout', cost: '$0.001'},
  ]
  return (
    <div className="relative">
      <div
        aria-hidden
        className="pointer-events-none absolute -inset-x-10 -bottom-12 top-8 bg-[radial-gradient(closest-side,rgba(124,92,246,0.28),rgba(34,211,238,0.05)_60%,transparent)] blur-2xl"
      />
      <WindowFrame title="moneat · ai" className="relative">
        <div className="grid gap-3">
          {/* Toolbar row */}
          <div className="flex items-center justify-between gap-3">
            <div className="flex items-baseline gap-1.5">
              <span className="font-brandmono text-[11px] text-slate-500">Spend today</span>
              <span className={cn('font-brandmono text-[15px] font-semibold', GRADIENT_TEXT)}>$48.20</span>
            </div>
            <span className="font-brandmono text-[11px] text-slate-500">last 24h · 9.4k calls</span>
          </div>

          {/* Column headers */}
          <div className="grid grid-cols-[auto_1fr_auto_auto_auto] items-center gap-x-3 px-3">
            <span /> {/* dot placeholder */}
            <span className="font-brandmono text-[9px] uppercase tracking-[0.12em] text-slate-600">model</span>
            <span className="font-brandmono text-[9px] uppercase tracking-[0.12em] text-slate-600">tokens</span>
            <span className="font-brandmono text-[9px] uppercase tracking-[0.12em] text-slate-600">p50</span>
            <span className="font-brandmono text-[9px] uppercase tracking-[0.12em] text-slate-600">cost</span>
          </div>

          {/* Call rows */}
          <div className="overflow-hidden rounded-md border border-white/[0.07]">
            {calls.map((c, i) => (
              <div
                key={c.model}
                className={cn(
                  'grid grid-cols-[auto_1fr_auto_auto_auto] items-center gap-x-3 px-3 py-2.5',
                  i > 0 && 'border-t border-white/[0.06]',
                  i === 0 && 'bg-white/[0.03]',
                )}
              >
                <span className={cn('size-2 shrink-0 rounded-full', c.dot)} />
                <span className="truncate font-brandmono text-[12px] text-slate-100">{c.model}</span>
                <span className="font-brandmono text-[11px] text-slate-400">{c.tokens}</span>
                <span className="font-brandmono text-[11px] text-slate-400">{c.latency}</span>
                <span className="font-brandmono text-[12px] text-slate-200">{c.cost}</span>
              </div>
            ))}
          </div>

          {/* Stat strip */}
          <div className="grid grid-cols-3 gap-2.5">
            <StatTile label="requests" value="9.4k" accent />
            <StatTile label="tokens" value="3.1M" />
            <StatTile label="spend" value="$48.20" />
          </div>
        </div>
      </WindowFrame>
    </div>
  )
}

const config: FeaturePageConfig = {
  slug: pageSeo.slug,
  title: pageSeo.title,
  tagline: 'Monitor your AI in production',
  description: 'Monitor LLM calls, token usage, latency, and costs across providers. Track prompts, completions, and model performance to optimize your AI workflows and control spending.',
  metaDescription: pageSeo.metaDescription,
  icon: Brain,
  iconColor: 'text-fuchsia-400',
  iconBg: 'bg-fuchsia-500/10',
  gradient: 'from-fuchsia-500 to-pink-400',
  accentColor: 'text-fuchsia-400',
  screenshot: pageSeo.image,
  screenshotAlt: 'AI observability dashboard showing LLM metrics and token usage',
  subFeatures: [
    {icon: Layers, title: 'Multi-Provider', description: 'Track OpenAI, Anthropic, Google, and any provider in a single unified view.', iconColor: 'text-fuchsia-400'},
    {icon: DollarSign, title: 'Cost Tracking', description: 'See exactly what each model, prompt, and workflow costs in real time. Set cost alerts.', iconColor: 'text-green-400'},
    {icon: Clock, title: 'Latency Monitoring', description: 'Track time-to-first-token, total latency, and throughput across models and endpoints.', iconColor: 'text-amber-400'},
    {icon: BarChart3, title: 'Token Analytics', description: 'Analyze token usage patterns per model, user, and workflow to optimize context windows.', iconColor: 'text-blue-400'},
    {icon: Brain, title: 'Prompt & Completion Logs', description: 'Full prompt and completion logging for debugging, auditing, and quality analysis.', iconColor: 'text-violet-400'},
    {icon: Zap, title: 'Generation Traces', description: 'Distributed traces for AI workflows showing the full chain of LLM calls and tool use.', iconColor: 'text-cyan-400'},
  ],
  heroVisual: <AiObservabilityHero />,
}

export const Route = createFileRoute('/ai-observability')({
  component: () => <FeaturePageTemplate config={config} />,
})
