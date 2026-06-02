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

import type {ReactNode} from 'react'
import {Link} from '@tanstack/react-router'
import {AlertCircle, Boxes, CheckCircle2, ShieldCheck, Zap, type LucideIcon} from 'lucide-react'
import {Logo} from '@/components/Logo'
import {cn} from '@/lib/utils'
import {useForceDarkTheme} from '@/components/landing/usePublicPageTheme'
import {GRADIENT_BAR, GRADIENT_TEXT, SignalChart, StatTile, WindowFrame} from '@/components/landing/Landing'
import {authLabelClass} from './authStyles'

// Default brand-panel copy for the supporting auth-flow pages (password reset,
// email verification, invites). Login and signup pass their own.
const DEFAULT_PANEL_HEADLINE = (
  <>
    Everything you ship, <span className={GRADIENT_TEXT}>in one place.</span>
  </>
)
const DEFAULT_PANEL_LEDE =
  'Errors, logs, traces, infrastructure, uptime, and on-call — one open-source workspace your team already knows how to read.'

// ── A labeled field: label (+ optional required mark), the control, then a hint
//    or inline error. One field pattern across both auth pages. ─────────────────
export function AuthField({
  id,
  label,
  required,
  hint,
  error,
  children,
}: {
  readonly id: string
  readonly label: string
  readonly required?: boolean
  readonly hint?: ReactNode
  readonly error?: ReactNode
  readonly children: ReactNode
}) {
  return (
    <div className="grid gap-1.5">
      <label htmlFor={id} className={authLabelClass}>
        {label}
        {required ? <span className="ml-0.5 text-rose-300">*</span> : null}
      </label>
      {children}
      {error ? (
        <p className="text-xs text-rose-300">{error}</p>
      ) : hint ? (
        <p className="text-xs text-slate-500">{hint}</p>
      ) : null}
    </div>
  )
}

// ── Status banner — shared status language (info/success/danger) from the
//    internal style guide, tinted for the dark canvas. ─────────────────────────
const ALERT_TONES = {
  danger: {wrap: 'border-rose-500/30 bg-rose-500/10 text-rose-200', icon: AlertCircle},
  success: {wrap: 'border-emerald-500/30 bg-emerald-500/10 text-emerald-200', icon: CheckCircle2},
} as const

export function AuthAlert({tone, children}: {readonly tone: keyof typeof ALERT_TONES; readonly children: ReactNode}) {
  const {wrap, icon: Icon} = ALERT_TONES[tone]
  return (
    <div role="alert" className={cn('flex items-start gap-2.5 rounded-md border px-3 py-2.5 text-sm', wrap)}>
      <Icon className="mt-0.5 size-4 shrink-0" />
      <span className="leading-relaxed">{children}</span>
    </div>
  )
}

// ── "or continue with" rule ──────────────────────────────────────────────────
export function AuthDivider({label}: {readonly label: string}) {
  return (
    <div className="flex items-center gap-3">
      <span className="h-px flex-1 bg-white/10" />
      <span className="font-brandmono text-[10px] uppercase tracking-[0.16em] text-slate-500">{label}</span>
      <span className="h-px flex-1 bg-white/10" />
    </div>
  )
}

// ── Brand panel: a compact, recognizable product surface + proof points. Same
//    furniture on both pages — it's the brand, not page-specific content. ───────
const proofPoints: Array<{icon: LucideIcon; text: string}> = [
  {icon: Boxes, text: 'Drop-in for your Sentry SDK and Datadog Agent'},
  {icon: ShieldCheck, text: 'Open source, self-hostable under AGPL v3'},
  {icon: Zap, text: '1 GB ingest free every month — no credit card'},
]

function AuthShowcase({headline, lede}: {readonly headline: ReactNode; readonly lede: string}) {
  return (
    <section className="hidden flex-col lg:flex">
      <Link to="/" className="inline-flex items-center gap-3 self-start">
        <Logo className="h-8 text-white" />
        <span className="font-brandmono text-[10px] uppercase tracking-[0.16em] text-slate-500">
          Observability platform
        </span>
      </Link>

      <h1 className="mt-12 max-w-md text-balance text-4xl font-bold leading-[1.02] tracking-[-0.04em] text-white">
        {headline}
      </h1>
      <p className="mt-5 max-w-md text-pretty text-base leading-7 text-slate-400">{lede}</p>

      <div className="relative mt-10 max-w-md">
        <div
          aria-hidden
          className="pointer-events-none absolute -inset-x-8 -bottom-10 top-6 bg-[radial-gradient(closest-side,rgba(124,92,246,0.3),rgba(34,211,238,0.05)_60%,transparent)] blur-2xl"
        />
        <WindowFrame title="moneat · api-prod" live className="relative">
          <div className="grid gap-3">
            <div className="flex items-center justify-between">
              <span className="font-brandmono text-[11px] uppercase tracking-[0.12em] text-slate-500">
                Service health
              </span>
              <span className="font-brandmono text-[11px] text-slate-500">last 24h</span>
            </div>
            <SignalChart heightClass="h-28" />
            <div className="grid grid-cols-3 gap-2.5">
              <StatTile label="p95 latency" value="184ms" accent />
              <StatTile label="uptime" value="99.98%" />
              <StatTile label="open errors" value="12" />
            </div>
          </div>
        </WindowFrame>
      </div>

      <ul className="mt-10 grid max-w-md gap-3">
        {proofPoints.map((point) => (
          <li key={point.text} className="flex items-center gap-3 text-sm text-slate-300">
            <span className="flex size-7 shrink-0 items-center justify-center rounded-md border border-white/[0.08] bg-white/[0.03]">
              <point.icon className="size-3.5 text-indigo-300" />
            </span>
            {point.text}
          </li>
        ))}
      </ul>
    </section>
  )
}

// ── The shell: dark public canvas + atmosphere, brand panel on the left, the
//    form card on the right (single column on small screens). ──────────────────
export function AuthShell({
  kicker,
  heading,
  subheading,
  panelHeadline = DEFAULT_PANEL_HEADLINE,
  panelLede = DEFAULT_PANEL_LEDE,
  children,
  footer,
}: {
  readonly kicker: string
  readonly heading: ReactNode
  readonly subheading: ReactNode
  readonly panelHeadline?: ReactNode
  readonly panelLede?: string
  readonly children: ReactNode
  readonly footer?: ReactNode
}) {
  useForceDarkTheme()

  return (
    <div className="relative min-h-screen overflow-hidden bg-[#08090f] font-display text-slate-300">
      <div aria-hidden className="pointer-events-none absolute inset-0">
        <div className="absolute inset-0 bg-[radial-gradient(58rem_30rem_at_78%_-12%,rgba(124,92,246,0.2),transparent_70%)]" />
        <div className="absolute inset-0 bg-[radial-gradient(48rem_28rem_at_12%_110%,rgba(34,211,238,0.08),transparent_70%)]" />
        <div className="absolute inset-0 bg-[linear-gradient(rgba(148,163,184,0.05)_1px,transparent_1px),linear-gradient(90deg,rgba(148,163,184,0.05)_1px,transparent_1px)] bg-[size:56px_56px] [mask-image:radial-gradient(60rem_44rem_at_50%_0%,#000,transparent_78%)]" />
      </div>

      <main className="relative z-10 mx-auto grid min-h-screen max-w-6xl items-center gap-10 px-5 py-12 lg:grid-cols-[1.05fr_456px] lg:gap-16 lg:px-8">
        <AuthShowcase headline={panelHeadline} lede={panelLede} />

        <div className="w-full justify-self-center lg:justify-self-end">
          <Link to="/" className="mb-7 flex justify-center lg:hidden" aria-label="Moneat home">
            <Logo className="h-8 text-white" />
          </Link>

          <div className="rounded-2xl border border-white/[0.08] bg-[#0c0e16]/85 p-7 shadow-[0_30px_80px_-40px_rgba(2,6,23,0.95)] backdrop-blur-sm sm:p-8">
            <div className="inline-flex items-center gap-2.5 font-brandmono text-[11px] font-medium uppercase tracking-[0.18em] text-indigo-300">
              <span className={cn('h-px w-5 rounded', GRADIENT_BAR)} />
              {kicker}
            </div>
            <h2 className="mt-3 text-2xl font-bold tracking-[-0.03em] text-white">{heading}</h2>
            <p className="mt-2 text-sm leading-6 text-slate-400">{subheading}</p>

            <div className="mt-7">{children}</div>

            {footer ? <div className="mt-7 border-t border-white/[0.07] pt-6">{footer}</div> : null}
          </div>
        </div>
      </main>
    </div>
  )
}
