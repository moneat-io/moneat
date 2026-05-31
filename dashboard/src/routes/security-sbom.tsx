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
  description: 'A software bill of materials with CVE tracking across your services is on the Moneat roadmap. The plan: surface which packages and versions are deployed, flag the ones with known vulnerabilities, and point to what to patch.',
  metaDescription: pageSeo.metaDescription,
  icon: ShieldCheck,
  iconColor: 'text-emerald-400',
  iconBg: 'bg-emerald-500/10',
  gradient: 'from-emerald-500 to-green-400',
  accentColor: 'text-emerald-400',
  screenshot: pageSeo.image,
  screenshotAlt: 'Security dashboard concept showing SBOM inventory and CVE tracking',
  subFeatures: [
    {icon: Package, title: 'Package Inventory', description: 'Planned: an inventory of the packages and versions running across your services and hosts.', iconColor: 'text-emerald-400'},
    {icon: AlertTriangle, title: 'CVE Tracking', description: 'Planned: matching your packages against known CVEs with severity ratings and remediation links.', iconColor: 'text-red-400'},
    {icon: Search, title: 'Vulnerability Search', description: 'Planned: search for a specific CVE or package across your infrastructure.', iconColor: 'text-blue-400'},
    {icon: FileText, title: 'SBOM Export', description: 'Planned: export a software bill of materials in standard formats for compliance and auditing.', iconColor: 'text-violet-400'},
    {icon: Shield, title: 'Compliance', description: 'Planned: support supply-chain security requirements with SBOM generation and tracking.', iconColor: 'text-amber-400'},
    {
      icon: ShieldCheck,
      title: 'Auto-Discovery',
      description:
        'Planned: discover packages from supported infrastructure agents, so the inventory builds itself ' +
        'with no manual work.',
      iconColor: 'text-cyan-400',
    },
  ],
  compatNote:
    'The plan is to collect SBOM data through the infrastructure agents you already run, so no separate scanning ' +
    'pipeline is needed when this ships.',
}

export const Route = createFileRoute('/security-sbom')({
  component: () => <FeaturePageTemplate config={config} />,
})
