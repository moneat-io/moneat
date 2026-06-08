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
import {WindowFrame, StatTile, GRADIENT_BAR} from '@/components/landing/Landing'
import {cn} from '@/lib/utils'
import {getFeaturePageSeoInput} from '@/lib/seo/routes'

const pageSeo = getFeaturePageSeoInput('session-replay')

// Feature-specific hero: a session-replay player surface — faux viewport,
// timeline scrubber with gradient fill, and an event list. Built from the
// shared brand primitives (dark, mono, one gradient).
function SessionReplayHero() {
  const events: Array<{kind: string; detail: string; error?: boolean}> = [
    {kind: 'click', detail: 'button.checkout'},
    {kind: 'navigate', detail: '/cart'},
    {kind: 'console.error', detail: 'undefined is not a function', error: true},
    {kind: 'rage click', detail: '×3'},
  ]
  return (
    <div className="relative">
      <div
        aria-hidden
        className="pointer-events-none absolute -inset-x-10 -bottom-12 top-8 bg-[radial-gradient(closest-side,rgba(124,92,246,0.28),rgba(34,211,238,0.05)_60%,transparent)] blur-2xl"
      />
      <WindowFrame title="moneat · replay" live className="relative">
        <div className="grid gap-3">
          {/* Faux recorded viewport */}
          <div className="relative overflow-hidden rounded-md border border-white/[0.08] bg-white/[0.02] px-4 pb-4 pt-3">
            {/* REC badge */}
            <div className="mb-3 flex items-center gap-1.5">
              <span className="size-2 rounded-full bg-rose-400/80" />
              <span className="font-brandmono text-[10px] uppercase tracking-[0.14em] text-slate-400">
                REC 02:14
              </span>
            </div>
            {/* Skeleton page content */}
            <div className="grid gap-2">
              <div className="h-3 w-3/5 rounded bg-white/[0.06]" />
              <div className="h-3 w-4/5 rounded bg-white/[0.04]" />
              <div className="mt-1 flex gap-2">
                <div className="h-8 w-20 rounded bg-white/[0.06]" />
                <div className="h-8 w-14 rounded bg-white/[0.03]" />
              </div>
              <div className="h-3 w-2/5 rounded bg-white/[0.04]" />
              <div className="h-3 w-3/4 rounded bg-white/[0.03]" />
            </div>
          </div>

          {/* Timeline scrubber */}
          <div className="flex items-center gap-2 px-0.5">
            {/* Playhead icon */}
            <span className="shrink-0 font-brandmono text-[11px] text-slate-500">0:00</span>
            <div className="relative flex-1">
              {/* Track */}
              <div className="h-1.5 w-full overflow-hidden rounded-full bg-white/[0.07]">
                {/* Progress fill — ~60% */}
                <div className={cn('h-full w-[60%] rounded-full', GRADIENT_BAR)} />
              </div>
              {/* Playhead knob at ~60% */}
              <div
                className="absolute top-1/2 size-3 -translate-y-1/2 rounded-full border border-white/20 bg-slate-100 shadow"
                style={{left: 'calc(60% - 6px)'}}
              />
              {/* Event tick marks */}
              {[18, 38, 60, 74].map((pct) => (
                <div
                  key={pct}
                  className={cn(
                    'absolute bottom-0 top-0 my-auto h-2.5 w-px rounded-full',
                    pct === 60 ? 'bg-rose-400/70' : 'bg-white/[0.20]',
                  )}
                  style={{left: `${pct}%`}}
                />
              ))}
            </div>
            <span className="shrink-0 font-brandmono text-[11px] text-slate-500">4:12</span>
          </div>

          {/* Event list */}
          <div className="overflow-hidden rounded-md border border-white/[0.07]">
            {events.map((ev, i) => (
              <div
                key={i}
                className={cn(
                  'flex items-center gap-3 px-3 py-2',
                  i > 0 && 'border-t border-white/[0.06]',
                  i === 0 && 'bg-white/[0.03]',
                )}
              >
                <span
                  className={cn(
                    'w-24 shrink-0 font-brandmono text-[10px] uppercase tracking-[0.1em]',
                    ev.error ? 'text-rose-400' : 'text-slate-500',
                  )}
                >
                  {ev.kind}
                </span>
                <span
                  className={cn(
                    'truncate font-brandmono text-[11px]',
                    ev.error ? 'text-rose-300' : 'text-slate-300',
                  )}
                >
                  {ev.detail}
                </span>
              </div>
            ))}
          </div>

          {/* Stat strip */}
          <div className="grid grid-cols-3 gap-2.5">
            <StatTile label="duration" value="4:12" />
            <StatTile label="events" value="38" accent />
            <StatTile label="errors" value="2" />
          </div>
        </div>
      </WindowFrame>
    </div>
  )
}

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
  heroVisual: <SessionReplayHero />,
}

export const Route = createFileRoute('/session-replay')({
  component: () => <FeaturePageTemplate config={config} />,
})
