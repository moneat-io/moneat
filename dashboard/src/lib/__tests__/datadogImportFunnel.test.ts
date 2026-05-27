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

import {beforeEach, describe, expect, it} from 'vitest'
import {
  DATADOG_IMPORT_CAMPAIGN,
  DATADOG_IMPORT_DASHBOARDS_URL,
  DATADOG_IMPORT_DEFAULT_UTM_PARAMS,
  clearDatadogImportSignupIntent,
  hasDatadogImportSignupIntent,
  isDatadogImportCampaign,
  markDatadogDashboardImportSuccess,
  markDatadogImportSignupIntent,
  markFirstSignalAfterDatadogImportTracked,
  readDatadogPendingImportSignal,
} from '../datadogImportFunnel'

describe('datadog import funnel helpers', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('points authenticated users to the dashboard import entrypoint', () => {
    expect(DATADOG_IMPORT_DASHBOARDS_URL).toBe('/dashboards?import=datadog')
  })

  it('recognizes the Datadog dashboard import campaign from query params and stored intent', () => {
    const params = new URLSearchParams(`utm_campaign=${DATADOG_IMPORT_CAMPAIGN}`)

    expect(isDatadogImportCampaign(params)).toBe(true)
    expect(hasDatadogImportSignupIntent(new URLSearchParams())).toBe(false)

    markDatadogImportSignupIntent()

    expect(hasDatadogImportSignupIntent(new URLSearchParams())).toBe(true)
    expect(JSON.parse(localStorage.getItem('utm_params') ?? '{}')).toEqual(
      DATADOG_IMPORT_DEFAULT_UTM_PARAMS
    )

    clearDatadogImportSignupIntent()

    expect(hasDatadogImportSignupIntent(new URLSearchParams())).toBe(false)
  })

  it('stores the pending import context until first telemetry is tracked', () => {
    markDatadogDashboardImportSuccess(42, 3)

    expect(readDatadogPendingImportSignal()).toMatchObject({
      dashboardId: 42,
      warningCount: 3,
    })

    markFirstSignalAfterDatadogImportTracked()

    expect(readDatadogPendingImportSignal()).toBeNull()
  })
})
