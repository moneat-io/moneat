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

const UNLIMITED_QUOTA_SENTINEL = 9_007_199_254_740_000

function finiteInteger(value: number | null | undefined, fallback: number): number {
  if (value == null || !Number.isFinite(value)) return fallback
  return Math.round(value)
}

export function isUnlimitedQuota(value: number | null | undefined): boolean {
  return value != null && value >= UNLIMITED_QUOTA_SENTINEL
}

export function isUnlimitedReplayQuota(value: number | null | undefined): boolean {
  return value != null && (value < 0 || isUnlimitedQuota(value))
}

export function normalizeQuotaForForm(value: number | null | undefined, fallback = 0): number {
  const integer = finiteInteger(value, fallback)
  if (integer >= UNLIMITED_QUOTA_SENTINEL) return UNLIMITED_QUOTA_SENTINEL
  return Math.max(0, integer)
}

export function normalizeReplayQuotaForForm(value: number | null | undefined, fallback = 0): number {
  const integer = finiteInteger(value, fallback)
  if (integer < 0) return -1
  return normalizeQuotaForForm(integer, fallback)
}

export function normalizeQuotaForRequest(value: number): number {
  return normalizeQuotaForForm(value)
}

export function normalizeReplayQuotaForRequest(value: number): number {
  return normalizeReplayQuotaForForm(value)
}

export function totalQuotaForRequest(limits: number[]): number {
  if (limits.some((limit) => limit < 0 || isUnlimitedQuota(limit))) {
    return UNLIMITED_QUOTA_SENTINEL
  }
  const total = limits.reduce((sum, limit) => sum + normalizeQuotaForRequest(limit), 0)
  return total >= UNLIMITED_QUOTA_SENTINEL ? UNLIMITED_QUOTA_SENTINEL : total
}

export function formatQuotaLimit(value: number | null | undefined): string {
  if (isUnlimitedReplayQuota(value)) return 'Unlimited'
  return normalizeQuotaForForm(value).toLocaleString()
}

export function formatTotalQuotaLimit(limits: number[]): string {
  if (limits.some((limit) => isUnlimitedReplayQuota(limit))) return 'Unlimited'
  const total = limits.reduce((sum, limit) => sum + normalizeQuotaForForm(limit), 0)
  return total >= UNLIMITED_QUOTA_SENTINEL ? 'Unlimited' : total.toLocaleString()
}
