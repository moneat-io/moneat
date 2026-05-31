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
  description: 'Software bill of materials with CVE tracking across all your services. Know exactly what packages are deployed, which versions have known vulnerabilities, and what needs patching.',
  metaDescription: pageSeo.metaDescription,
  icon: ShieldCheck,
  iconColor: 'text-emerald-400',
  iconBg: 'bg-emerald-500/10',
  gradient: 'from-emerald-500 to-green-400',
  accentColor: 'text-emerald-400',
  screenshot: pageSeo.image,
  screenshotAlt: 'Security dashboard showing SBOM inventory and CVE tracking',
  subFeatures: [
    {icon: Package, title: 'Package Inventory', description: 'Full inventory of every package and version running across all your services and hosts.', iconColor: 'text-emerald-400'},
    {icon: AlertTriangle, title: 'CVE Tracking', description: 'Automatic matching of your packages against known CVEs with severity ratings and remediation links.', iconColor: 'text-red-400'},
    {icon: Search, title: 'Vulnerability Search', description: 'Search for specific CVEs or packages across your entire infrastructure instantly.', iconColor: 'text-blue-400'},
    {icon: FileText, title: 'SBOM Export', description: 'Export your software bill of materials in standard formats for compliance and auditing.', iconColor: 'text-violet-400'},
    {icon: Shield, title: 'Compliance', description: 'Meet supply-chain security requirements with automated SBOM generation and tracking.', iconColor: 'text-amber-400'},
    {
      icon: ShieldCheck,
      title: 'Auto-Discovery',
      description:
        'Packages are discovered automatically from supported infrastructure agents with no manual ' +
        'inventory work.',
      iconColor: 'text-cyan-400',
    },
  ],
  compatNote:
    'SBOM data is collected automatically through supported infrastructure agents. No additional scanning pipeline ' +
    'is required.',
}

export const Route = createFileRoute('/security-sbom')({
  component: () => <FeaturePageTemplate config={config} />,
})
