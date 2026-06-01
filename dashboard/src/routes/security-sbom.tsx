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
import {getFeaturePageSeoInput} from '@/lib/seo/routes'

const pageSeo = getFeaturePageSeoInput('security-sbom')

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
}

export const Route = createFileRoute('/security-sbom')({
  component: () => <FeaturePageTemplate config={config} />,
})
