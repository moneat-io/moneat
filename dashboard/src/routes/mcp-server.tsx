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

const config: FeaturePageConfig = {
  slug: 'mcp-server',
  title: 'MCP Server',
  tagline: 'Observability in your IDE',
  description: 'Connect Cursor, GitHub Copilot, Claude Code, or your own agents to Moneat via the Model Context Protocol. Query issues, logs, traces, and metrics from your AI workflows without leaving your editor.',
  metaDescription: 'MCP server for AI-powered observability. Query issues, logs, and traces from Cursor, GitHub Copilot, or any MCP client. Start free with Moneat.',
  icon: Bot,
  iconColor: 'text-violet-400',
  iconBg: 'bg-violet-500/10',
  gradient: 'from-violet-500 to-purple-400',
  accentColor: 'text-violet-400',
  screenshot: '/screenshots/dashboard.png',
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
}

export const Route = createFileRoute('/mcp-server')({
  component: () => <FeaturePageTemplate config={config} />,
})
