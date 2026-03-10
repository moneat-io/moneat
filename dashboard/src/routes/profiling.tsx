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
import {Flame, Cpu, HardDrive, Clock, Layers, Search} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'

const config: FeaturePageConfig = {
  slug: 'profiling',
  title: 'Continuous Profiling',
  tagline: 'Pinpoint hot paths in production',
  description: 'CPU, heap, and wall-time profiles from your Datadog Agent. Identify hot functions, memory leaks, and resource bottlenecks in production without any performance overhead.',
  metaDescription: 'Continuous profiling with CPU, heap, and wall-time flamegraphs. Pinpoint performance bottlenecks in production. Start free with Moneat.',
  icon: Flame,
  iconColor: 'text-red-400',
  iconBg: 'bg-red-500/10',
  gradient: 'from-red-500 to-orange-400',
  accentColor: 'text-red-400',
  screenshot: '/screenshots/profiles.png',
  screenshotAlt: 'Continuous profiling flamegraph showing CPU and memory hotspots',
  subFeatures: [
    {icon: Flame, title: 'Flamegraphs', description: 'Interactive flamegraphs that visualize exactly where your application spends its time.', iconColor: 'text-red-400'},
    {icon: Cpu, title: 'CPU Profiling', description: 'Identify hot functions and optimize the code paths that consume the most CPU cycles.', iconColor: 'text-orange-400'},
    {icon: HardDrive, title: 'Heap Profiling', description: 'Track memory allocations and find memory leaks before they cause OOM kills.', iconColor: 'text-amber-400'},
    {icon: Clock, title: 'Wall-Time Profiles', description: 'Understand where time is spent including I/O waits, locks, and external calls.', iconColor: 'text-blue-400'},
    {icon: Layers, title: 'Multi-Language', description: 'Support for Go, Java, Python, Ruby, Node.js, .NET, and PHP via the Datadog Agent.', iconColor: 'text-violet-400'},
    {icon: Search, title: 'Compare Profiles', description: 'Diff profiles across deployments to see exactly what changed in your performance.', iconColor: 'text-cyan-400'},
  ],
  compatNote: 'Profiles are collected via the Datadog Agent with near-zero overhead. Enable profiling with a single config flag.',
}

export const Route = createFileRoute('/profiling')({
  component: () => <FeaturePageTemplate config={config} />,
})
