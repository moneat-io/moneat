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
import type {
  ProfileListResponse,
  FlamegraphResponse,
} from '../types'
import { filenameFromContentDisposition } from '../utils'

export function profilesMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getProfiles: (
      params: {
        service?: string
        type?: string
        source?: string
        limit?: number
        offset?: number
      } = {}
    ) => {
      const searchParams = new URLSearchParams()
      if (params.service) searchParams.set('service', params.service)
      if (params.type) searchParams.set('type', params.type)
      if (params.source) searchParams.set('source', params.source)
      if (params.limit != null) searchParams.set('limit', String(params.limit))
      if (params.offset != null) searchParams.set('offset', String(params.offset))
      const qs = searchParams.toString()
      return core.request<ProfileListResponse>(`${base}/profiles${qs ? `?${qs}` : ''}`)
    },

    downloadProfile: async (
      profileId: string,
      filename?: string,
      profileType?: string
    ) => {
      const response = await core.fetchWithAuth(`${base}/profiles/${profileId}/download`)
      if (!response.ok) throw new Error('Profile download failed')
      const blob = await response.blob()
      const dispositionName = filenameFromContentDisposition(
        response.headers.get('content-disposition')
      )
      const defaultExt = profileType?.toLowerCase().includes('jfr')
        ? 'jfr'
        : 'pprof.gz'
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = filename ?? dispositionName ?? `profile-${profileId}.${defaultExt}`
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      URL.revokeObjectURL(url)
    },

    getProfileFlamegraph: (profileId: string) =>
      core.request<FlamegraphResponse>(`${base}/profiles/${profileId}/flamegraph`),
  }
}
