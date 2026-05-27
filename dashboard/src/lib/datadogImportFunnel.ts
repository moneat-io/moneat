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

export const DATADOG_IMPORT_CAMPAIGN = 'datadog_dashboard_import'
export const DATADOG_IMPORT_SIGNUP_URL =
  '/signup?utm_source=moneat_site&utm_medium=landing_page&' +
  `utm_campaign=${DATADOG_IMPORT_CAMPAIGN}&utm_content=dashboard_import_cta`
export const DATADOG_IMPORT_DEFAULT_UTM_PARAMS = {
  utmSource: 'moneat_site',
  utmMedium: 'landing_page',
  utmCampaign: DATADOG_IMPORT_CAMPAIGN,
  utmContent: 'dashboard_import_cta',
}

const SIGNUP_INTENT_KEY = 'moneat:datadog-import-signup-intent'
const PENDING_SIGNAL_KEY = 'moneat:datadog-import-pending-signal'
const FIRST_SIGNAL_TRACKED_KEY = 'moneat:datadog-import-first-signal-tracked'
const UTM_PARAMS_KEY = 'utm_params'

export interface DatadogImportSignalContext {
  dashboardId: number
  warningCount: number
  importedAt: string
}

function getStorage(): Storage | null {
  try {
    return globalThis.localStorage ?? null
  } catch {
    return null
  }
}

export function isDatadogImportCampaign(searchParams: URLSearchParams): boolean {
  return (
    searchParams.get('utm_campaign') === DATADOG_IMPORT_CAMPAIGN ||
    searchParams.get('intent') === DATADOG_IMPORT_CAMPAIGN
  )
}

export function markDatadogImportSignupIntent(): void {
  const storage = getStorage()
  try {
    storage?.setItem(SIGNUP_INTENT_KEY, new Date().toISOString())
    if (storage && storage.getItem(UTM_PARAMS_KEY) == null) {
      storage.setItem(UTM_PARAMS_KEY, JSON.stringify(DATADOG_IMPORT_DEFAULT_UTM_PARAMS))
    }
  } catch {
    // Ignore storage failures so the navigation can still continue.
  }
}

export function hasDatadogImportSignupIntent(searchParams: URLSearchParams): boolean {
  const storage = getStorage()
  try {
    return isDatadogImportCampaign(searchParams) || storage?.getItem(SIGNUP_INTENT_KEY) != null
  } catch {
    return isDatadogImportCampaign(searchParams)
  }
}

export function clearDatadogImportSignupIntent(): void {
  const storage = getStorage()
  try {
    storage?.removeItem(SIGNUP_INTENT_KEY)
  } catch {
    // Ignore storage failures so core auth flows can continue.
  }
}

export function markDatadogDashboardImportSuccess(dashboardId: number, warningCount: number): void {
  const storage = getStorage()
  const context: DatadogImportSignalContext = {
    dashboardId,
    warningCount,
    importedAt: new Date().toISOString(),
  }
  try {
    storage?.setItem(PENDING_SIGNAL_KEY, JSON.stringify(context))
    storage?.removeItem(FIRST_SIGNAL_TRACKED_KEY)
  } catch {
    // Ignore storage failures so import success UX can continue.
  }
}

export function readDatadogPendingImportSignal(): DatadogImportSignalContext | null {
  const storage = getStorage()
  let rawContext: string | null | undefined
  try {
    rawContext = storage?.getItem(PENDING_SIGNAL_KEY)
  } catch {
    return null
  }
  if (!rawContext) return null

  try {
    return JSON.parse(rawContext) as DatadogImportSignalContext
  } catch {
    try {
      storage?.removeItem(PENDING_SIGNAL_KEY)
    } catch {
      // Ignore storage failures while recovering from malformed funnel state.
    }
    return null
  }
}

export function markFirstSignalAfterDatadogImportTracked(): void {
  const storage = getStorage()
  try {
    storage?.setItem(FIRST_SIGNAL_TRACKED_KEY, new Date().toISOString())
    storage?.removeItem(PENDING_SIGNAL_KEY)
  } catch {
    // Ignore storage failures after telemetry succeeds.
  }
}
