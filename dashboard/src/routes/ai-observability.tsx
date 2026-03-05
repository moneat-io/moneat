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
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/feature-page-template'

const config: FeaturePageConfig = {
  slug: 'ai-observability',
  title: 'AI & LLM Observability',
  tagline: 'Monitor your AI in production',
  description: 'Monitor LLM calls, token usage, latency, and costs across providers. Track prompts, completions, and model performance to optimize your AI workflows and control spending.',
  metaDescription: 'AI and LLM observability with token tracking, cost analysis, and prompt monitoring across providers. Start free with Moneat.',
  icon: Brain,
  iconColor: 'text-fuchsia-400',
  iconBg: 'bg-fuchsia-500/10',
  gradient: 'from-fuchsia-500 to-pink-400',
  accentColor: 'text-fuchsia-400',
  screenshot: '/screenshots/ai.png',
  screenshotAlt: 'AI observability dashboard showing LLM metrics and token usage',
  subFeatures: [
    {icon: Layers, title: 'Multi-Provider', description: 'Track OpenAI, Anthropic, Google, and any provider in a single unified view.', iconColor: 'text-fuchsia-400'},
    {icon: DollarSign, title: 'Cost Tracking', description: 'See exactly what each model, prompt, and workflow costs in real time. Set cost alerts.', iconColor: 'text-green-400'},
    {icon: Clock, title: 'Latency Monitoring', description: 'Track time-to-first-token, total latency, and throughput across models and endpoints.', iconColor: 'text-amber-400'},
    {icon: BarChart3, title: 'Token Analytics', description: 'Analyze token usage patterns per model, user, and workflow to optimize context windows.', iconColor: 'text-blue-400'},
    {icon: Brain, title: 'Prompt & Completion Logs', description: 'Full prompt and completion logging for debugging, auditing, and quality analysis.', iconColor: 'text-violet-400'},
    {icon: Zap, title: 'Generation Traces', description: 'Distributed traces for AI workflows showing the full chain of LLM calls and tool use.', iconColor: 'text-cyan-400'},
  ],
}

export const Route = createFileRoute('/ai-observability')({
  component: () => <FeaturePageTemplate config={config} />,
})
