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

const BYTES_PER_GB = 1024 * 1024 * 1024
const UNLIMITED_SYSTEMS_SENTINEL = 2147483647

export type BillingInterval = 'monthly' | 'yearly'

export interface PricingCardTierInput {
  tierName: string
  monthlyPriceCents: number
  yearlyPriceCents: number
  trialDays?: number | null
  monthlyGbLimit: number
  retentionDays: number
  maxProjects: number | null
  maxSystems: number
  monitorIntervalSeconds: number
  sessionReplayEnabled: boolean
  statusPagesEnabled: boolean
  statusPageCustomDomainEnabled: boolean
  slackEnabled: boolean
  discordEnabled?: boolean
  incidentIoEnabled: boolean
  samlEnabled: boolean
  oidcEnabled: boolean
  prioritySupportEnabled: boolean
  slaEnabled: boolean
  customRetentionEnabled: boolean
  oncallPerUserMonthlyCents?: number
  oncallEnabled?: boolean
}

export interface PricingCardModel {
  tierName: string
  name: string
  description: string
  monthlyPrice: number
  yearlyMonthlyPrice: number
  yearlyTotalPrice: number
  displayPrice: number
  features: string[]
  cta: string
  ctaLink: string
  highlight: boolean
}

function formatCompactNumber(value: number): string {
  if (Number.isInteger(value)) return String(value)
  return value.toFixed(1).replace(/\.0$/, '')
}

function formatDataLimit(bytesLimit: number): string {
  const gbLimit = Math.max(0, bytesLimit) / BYTES_PER_GB
  if (gbLimit >= 1024) {
    return `${formatCompactNumber(gbLimit / 1024)} TB`
  }
  return `${formatCompactNumber(gbLimit)} GB`
}

function formatMonitorLimit(maxSystems: number): string {
  if (maxSystems >= UNLIMITED_SYSTEMS_SENTINEL) return 'Unlimited monitors'
  return `${maxSystems} monitors`
}

function tierDescription(tierName: string): string {
  switch (tierName) {
    case 'FREE':
      return 'Perfect for side projects and getting started'
    case 'PRO':
      return 'For growing teams shipping production apps'
    case 'TEAM':
      return 'For teams that need scale and compliance'
    case 'BUSINESS':
      return 'For enterprises with custom requirements'
    default:
      return 'Flexible observability for every team'
  }
}

function buildTierFeatures(tier: PricingCardTierInput): string[] {
  const features = [
    `${formatDataLimit(tier.monthlyGbLimit)}/mo data`,
    `${tier.retentionDays}-day retention`,
    tier.maxProjects == null ? 'Unlimited projects' : `${tier.maxProjects} project${tier.maxProjects === 1 ? '' : 's'}`,
    `${formatMonitorLimit(tier.maxSystems)} (${tier.monitorIntervalSeconds}s interval)`,
  ]

  if (tier.sessionReplayEnabled) features.push('Session replays and events')

  if (tier.statusPagesEnabled && tier.statusPageCustomDomainEnabled) {
    features.push('Custom status pages with custom domains')
  } else if (tier.statusPagesEnabled) {
    features.push('Custom status pages')
  }

  if (tier.slackEnabled) features.push('Slack integration')
  if (tier.discordEnabled) features.push('Discord integration')
  if (tier.samlEnabled) features.push('SAML SSO integration')
  if (tier.oidcEnabled) features.push('OIDC SSO integration')
  if (tier.prioritySupportEnabled) features.push('Priority support')
  if (tier.slaEnabled) features.push('SLA guarantee')
  if (tier.customRetentionEnabled) features.push('Custom retention')
  if (tier.oncallEnabled && tier.oncallPerUserMonthlyCents) {
    features.push(`On-call scheduling (+$${tier.oncallPerUserMonthlyCents / 100}/user)`)
  }

  return features
}

function ctaForTier(tier: PricingCardTierInput): string {
  if (tier.monthlyPriceCents === 0) return 'Start Free'
  if (typeof tier.trialDays === 'number' && tier.trialDays > 0) return `Start ${tier.trialDays}-Day Trial`
  return 'Start Trial'
}

export function buildPricingCardModel(
  tier: PricingCardTierInput,
  billingInterval: BillingInterval,
  ctaLink = '/signup',
): PricingCardModel {
  const monthlyPrice = tier.monthlyPriceCents / 100
  const yearlyMonthlyPrice = tier.yearlyPriceCents / (100 * 12)
  const yearlyTotalPrice = tier.yearlyPriceCents / 100

  return {
    tierName: tier.tierName,
    name: tier.tierName.charAt(0) + tier.tierName.slice(1).toLowerCase(),
    description: tierDescription(tier.tierName),
    monthlyPrice,
    yearlyMonthlyPrice,
    yearlyTotalPrice,
    displayPrice: billingInterval === 'yearly' ? yearlyMonthlyPrice : monthlyPrice,
    features: buildTierFeatures(tier),
    cta: ctaForTier(tier),
    ctaLink,
    highlight: tier.tierName === 'PRO',
  }
}
