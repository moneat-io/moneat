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

import type { ApiClientCore } from '../client'
import { urlWithQuery } from '../utils'
import type {
  UptimeMonitor,
  CreateUptimeMonitorRequest,
  UpdateUptimeMonitorRequest,
  UptimeHeartbeat,
} from '../types'

export function uptimeMethods(core: ApiClientCore) {
  const base = core.API_BASE
  const monitorsBase = `${base}/uptime/monitors`

  return {
    getUptimeMonitors: () =>
      core.request<UptimeMonitor[]>(monitorsBase),

    getUptimeMonitor: (monitorId: string) =>
      core.request<UptimeMonitor>(`${monitorsBase}/${encodeURIComponent(monitorId)}`),

    createUptimeMonitor: (data: CreateUptimeMonitorRequest) =>
      core.request<UptimeMonitor>(monitorsBase, {
        method: 'POST',
        body: JSON.stringify(data),
      }),

    updateUptimeMonitor: (
      monitorId: string,
      data: UpdateUptimeMonitorRequest
    ) =>
      core.request<UptimeMonitor>(`${monitorsBase}/${encodeURIComponent(monitorId)}`, {
        method: 'PUT',
        body: JSON.stringify(data),
      }),

    deleteUptimeMonitor: (monitorId: string) =>
      core.request<void>(`${monitorsBase}/${encodeURIComponent(monitorId)}`, {
        method: 'DELETE',
      }),

    pauseUptimeMonitor: (monitorId: string) =>
      core.request<void>(`${monitorsBase}/${encodeURIComponent(monitorId)}/pause`, {
        method: 'POST',
      }),

    resumeUptimeMonitor: (monitorId: string) =>
      core.request<void>(`${monitorsBase}/${encodeURIComponent(monitorId)}/resume`, {
        method: 'POST',
      }),

    getUptimeHeartbeats: (
      monitorId: string,
      from?: number,
      to?: number
    ) => {
      const params = new URLSearchParams()
      if (from !== undefined && from !== null) params.append('from', from.toString())
      if (to !== undefined && to !== null) params.append('to', to.toString())
      const query = params.toString()
      return core.request<UptimeHeartbeat[]>(
        urlWithQuery(`${monitorsBase}/${encodeURIComponent(monitorId)}/heartbeats`, query)
      )
    },
  }
}
