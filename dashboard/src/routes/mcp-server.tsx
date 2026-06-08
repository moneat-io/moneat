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
import {Bot, Code2, Search, BarChart3, Bug, Terminal} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'
import {TerminalBlock, Prompt, Comment, Ok, Accent, StatTile} from '@/components/landing/Landing'
import {cn} from '@/lib/utils'
import {getFeaturePageSeoInput} from '@/lib/seo/routes'

const pageSeo = getFeaturePageSeoInput('mcp-server')

// Feature-specific hero: an MCP session in a terminal showing tool calls and
// results — the surface an agent-connected workflow actually produces.
function McpServerHero() {
  return (
    <div className="relative">
      <div
        aria-hidden
        className="pointer-events-none absolute -inset-x-10 -bottom-12 top-8 bg-[radial-gradient(closest-side,rgba(124,92,246,0.28),rgba(34,211,238,0.05)_60%,transparent)] blur-2xl"
      />
      <TerminalBlock title="bash — moneat-mcp" className="relative">
        <div>
          <Comment># connect your agent to Moneat over MCP</Comment>
        </div>
        <div>
          <Prompt /> moneat mcp connect
        </div>
        <div>
          <Ok /> <span className="text-slate-300">47 tools available</span>
        </div>
        <br />
        <div>
          <Accent>list_issues</Accent>
          <span className="text-slate-500">(status:open)</span>
          <span className="text-slate-500"> → </span>
          <span className="text-slate-300">24 results</span>
        </div>
        <div>
          <Accent>get_trace</Accent>
          <span className="text-slate-500">(id:9f2a…)</span>
          <span className="text-slate-500"> → </span>
          <span className="text-slate-300">312 spans</span>
        </div>
        <div>
          <Accent>create_alert</Accent>
          <span className="text-slate-500">(cpu&gt;80%)</span>
          <span className="text-slate-500"> → </span>
          <Ok /> <span className="text-emerald-400">ok</span>
        </div>
        <div>
          <Accent>query_logs</Accent>
          <span className="text-slate-500">(level:error)</span>
          <span className="text-slate-500"> → </span>
          <span className="text-slate-300">1.4k lines</span>
        </div>
      </TerminalBlock>
      <div className={cn('mt-3 grid grid-cols-2 gap-2.5')}>
        <StatTile label="tools" value="47" accent />
        <StatTile label="calls / min" value="128" />
      </div>
    </div>
  )
}

const config: FeaturePageConfig = {
  slug: pageSeo.slug,
  title: pageSeo.title,
  tagline: 'Observability in your IDE',
  description: 'Connect Cursor, GitHub Copilot, Claude Code, or your own agents to Moneat via the Model Context Protocol. Query issues, logs, traces, and metrics from your AI workflows without leaving your editor.',
  metaDescription: pageSeo.metaDescription,
  icon: Bot,
  iconColor: 'text-violet-400',
  iconBg: 'bg-violet-500/10',
  gradient: 'from-violet-500 to-purple-400',
  accentColor: 'text-violet-400',
  screenshot: pageSeo.image,
  screenshotAlt: 'Moneat dashboard showing observability data accessible via MCP',
  subFeatures: [
    {icon: Terminal, title: '30+ Tools', description: 'List issues, query logs, get traces, search hosts, create dashboards, and more — all via MCP.', iconColor: 'text-violet-400'},
    {icon: Code2, title: 'IDE Integration', description: 'Works natively with Cursor, GitHub Copilot, Claude Code, and any MCP-compatible client.', iconColor: 'text-blue-400'},
    {icon: Bug, title: 'Incident Investigation', description: 'Ask your AI assistant to investigate errors, find related logs, and trace the root cause.', iconColor: 'text-rose-400'},
    {icon: Search, title: 'Natural Language Queries', description: 'Query your observability data in plain English through your AI agent.', iconColor: 'text-cyan-400'},
    {icon: BarChart3, title: 'Dashboard Creation', description: 'Create and customize dashboards through natural language prompts in your IDE.', iconColor: 'text-amber-400'},
    {icon: Bot, title: 'Automated Triage', description: 'Let AI agents triage new issues by pulling context from logs, traces, and metrics.', iconColor: 'text-green-400'},
  ],
  compatNote: 'One-line setup: add the MCP server URL to your .cursor/mcp.json or IDE config. No additional packages required.',
  heroVisual: <McpServerHero />,
}

export const Route = createFileRoute('/mcp-server')({
  component: () => <FeaturePageTemplate config={config} />,
})
