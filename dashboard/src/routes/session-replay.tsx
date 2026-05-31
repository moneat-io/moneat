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
import {Play, MousePointerClick, Monitor, Bug, Clock, Layers} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'
import {getFeaturePageSeoInput} from '@/lib/seo/routes'

const pageSeo = getFeaturePageSeoInput('session-replay')

const config: FeaturePageConfig = {
  slug: pageSeo.slug,
  title: pageSeo.title,
  tagline: 'See what your users see',
  description: 'Watch exactly what users did before an error. See clicks, navigation, and console output reconstructed in real-time. No more guessing — replay the exact session that triggered the bug.',
  metaDescription: pageSeo.metaDescription,
  icon: Play,
  iconColor: 'text-violet-400',
  iconBg: 'bg-violet-500/10',
  gradient: 'from-violet-500 to-purple-400',
  accentColor: 'text-violet-400',
  screenshot: pageSeo.image,
  screenshotAlt: 'Session replay showing user interactions before errors occurred',
  subFeatures: [
    {icon: MousePointerClick, title: 'Click & Scroll Tracking', description: 'See every click, scroll, and navigation event in the exact order the user performed them.', iconColor: 'text-violet-400'},
    {icon: Bug, title: 'Error Correlation', description: 'Jump directly from an error to the session replay that triggered it. See the full context.', iconColor: 'text-rose-400'},
    {icon: Monitor, title: 'DOM Reconstruction', description: 'Pixel-perfect replay of the page as the user saw it, including dynamic content and animations.', iconColor: 'text-blue-400'},
    {icon: Clock, title: 'Playback Controls', description: 'Skip pauses, play at 2x speed, and jump to specific events in the timeline.', iconColor: 'text-amber-400'},
    {icon: Layers, title: 'Console & Network', description: 'View console logs, network requests, and JavaScript errors alongside the visual replay.', iconColor: 'text-cyan-400'},
    {icon: Play, title: 'Privacy Controls', description: 'Mask sensitive fields, exclude elements, and respect user privacy preferences automatically.', iconColor: 'text-green-400'},
  ],
  compatNote:
    'Capture replays through compatible SDKs. Enable session replay with a single config flag — no additional ' +
    'integration required.',
}

export const Route = createFileRoute('/session-replay')({
  component: () => <FeaturePageTemplate config={config} />,
})
