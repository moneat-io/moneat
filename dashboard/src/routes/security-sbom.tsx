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
import {ShieldCheck, Package, AlertTriangle, Search, FileText, Shield} from 'lucide-react'
import {FeaturePageTemplate, type FeaturePageConfig} from '@/components/landing/FeaturePageTemplate'
import {WindowFrame, StatTile} from '@/components/landing/Landing'
import {cn} from '@/lib/utils'
import {getFeaturePageSeoInput} from '@/lib/seo/routes'

const pageSeo = getFeaturePageSeoInput('security-sbom')

// Feature-specific hero: an SBOM vulnerability scan surface — a header with
// package count + timestamp, five dependency rows with severity dots and CVE
// ids, and a stat strip. Built from shared brand primitives (dark, mono, one
// gradient).
function SbomHero() {
  const vulns: Array<{pkg: string; cve: string; severity: string; dot: string; badge: string}> = [
    {pkg: 'log4j-core@2.14.1',   cve: 'CVE-2021-44228', severity: 'Critical', dot: 'bg-rose-400',  badge: 'bg-rose-500/[0.15] text-rose-300 border-rose-500/[0.25]'},
    {pkg: 'openssl@3.0.8',       cve: 'CVE-2023-0286',  severity: 'High',     dot: 'bg-amber-400', badge: 'bg-amber-500/[0.15] text-amber-300 border-amber-500/[0.25]'},
    {pkg: 'lodash@4.17.20',      cve: 'CVE-2021-23337', severity: 'High',     dot: 'bg-amber-400', badge: 'bg-amber-500/[0.15] text-amber-300 border-amber-500/[0.25]'},
    {pkg: 'express@4.17.1',      cve: 'CVE-2022-24999', severity: 'Medium',   dot: 'bg-slate-400', badge: 'bg-slate-500/[0.15] text-slate-300 border-slate-500/[0.25]'},
    {pkg: 'pillow@9.3.0',        cve: 'CVE-2023-44271', severity: 'Medium',   dot: 'bg-slate-400', badge: 'bg-slate-500/[0.15] text-slate-300 border-slate-500/[0.25]'},
  ]
  return (
    <div className="relative">
      <div
        aria-hidden
        className="pointer-events-none absolute -inset-x-10 -bottom-12 top-8 bg-[radial-gradient(closest-side,rgba(16,185,129,0.22),rgba(16,185,129,0.04)_60%,transparent)] blur-2xl"
      />
      <WindowFrame title="moneat · sbom" className="relative">
        <div className="grid gap-3">
          <div className="flex items-center justify-between gap-3">
            <span className="font-brandmono text-[12px] text-slate-200">
              Scanned 1,284 packages
            </span>
            <span className="font-brandmono text-[11px] text-slate-500">2026-06-01 09:14:02 UTC</span>
          </div>
          <div className="overflow-hidden rounded-md border border-white/[0.07]">
            {vulns.map((v, i) => (
              <div
                key={v.pkg}
                className={cn(
                  'flex items-center gap-3 px-3 py-2.5',
                  i > 0 && 'border-t border-white/[0.06]',
                  i === 0 && 'bg-white/[0.03]',
                )}
              >
                <span className={cn('size-2 shrink-0 rounded-full', v.dot)} />
                <div className="min-w-0 flex-1">
                  <div className="truncate font-brandmono text-[12px] text-slate-100">{v.pkg}</div>
                  <div className="truncate font-brandmono text-[10px] text-slate-500">{v.cve}</div>
                </div>
                <span
                  className={cn(
                    'shrink-0 rounded border px-1.5 py-0.5 font-brandmono text-[10px] uppercase tracking-[0.08em]',
                    v.badge,
                  )}
                >
                  {v.severity}
                </span>
              </div>
            ))}
          </div>
          <div className="grid grid-cols-3 gap-2.5">
            <StatTile label="critical" value="2" accent />
            <StatTile label="high" value="7" />
            <StatTile label="packages" value="1.3k" />
          </div>
        </div>
      </WindowFrame>
    </div>
  )
}

const config: FeaturePageConfig = {
  slug: pageSeo.slug,
  title: pageSeo.title,
  tagline: 'Know what you\'re running',
  description:
    'Upload CycloneDX or SPDX SBOMs, inventory the packages running across your services, and surface ' +
    'vulnerability findings with CVSS scores, fix versions, and advisory links.',
  metaDescription: pageSeo.metaDescription,
  icon: ShieldCheck,
  iconColor: 'text-emerald-400',
  iconBg: 'bg-emerald-500/10',
  gradient: 'from-emerald-500 to-green-400',
  accentColor: 'text-emerald-400',
  screenshot: pageSeo.image,
  screenshotAlt: 'Security dashboard concept showing SBOM inventory and CVE tracking',
  subFeatures: [
    {
      icon: Package,
      title: 'Package Inventory',
      description: 'Track packages and versions by service, host, image, or container.',
      iconColor: 'text-emerald-400',
    },
    {
      icon: AlertTriangle,
      title: 'CVE Tracking',
      description: 'Match inventory against a local advisory mirror with severity, CVSS, and fix data.',
      iconColor: 'text-red-400',
    },
    {
      icon: Search,
      title: 'Vulnerability Search',
      description: 'Search by package, target, advisory, or CVE from the Security section.',
      iconColor: 'text-blue-400',
    },
    {
      icon: FileText,
      title: 'SBOM Export',
      description: 'Export the current inventory as CycloneDX or SPDX JSON.',
      iconColor: 'text-violet-400',
    },
    {
      icon: Shield,
      title: 'Compliance',
      description: 'Keep supply-chain evidence close to the findings your team triages.',
      iconColor: 'text-amber-400',
    },
    {
      icon: ShieldCheck,
      title: 'Auto-Discovery',
      description:
        'Ingest SBOM payloads from existing agent-compatible paths as well as direct uploads.',
      iconColor: 'text-cyan-400',
    },
  ],
  compatNote:
    'Direct upload works in core. Agent-compatible SBOM ingest uses the same inventory and vulnerability findings.',
  heroVisual: <SbomHero />,
}

export const Route = createFileRoute('/security-sbom')({
  component: () => <FeaturePageTemplate config={config} />,
})
