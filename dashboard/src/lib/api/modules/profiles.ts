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
import { urlWithQuery, filenameFromContentDisposition } from '../utils'
import type {
  ProfileListResponse,
  ProfileResponse,
  ProfileServicesResponse,
  ProfileTimeseriesResponse,
  FlamegraphResponse,
  MergedFlamegraphResponse,
} from '../types'

function normalizedServices(services?: string[]): string[] {
  return services?.map((service) => service.trim()).filter((service) => service !== '') ?? []
}

export function profilesMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getProfiles: (
      params: {
        service?: string
        services?: string[]
        type?: string
        source?: string
        env?: string
        host?: string
        version?: string
        from?: number
        to?: number
        limit?: number
        offset?: number
      } = {}
    ) => {
      const searchParams = new URLSearchParams()
      if (params.service) searchParams.set('service', params.service)
      const services = normalizedServices(params.services)
      if (services.length > 0) searchParams.set('services', services.join(','))
      if (params.type) searchParams.set('type', params.type)
      if (params.source) searchParams.set('source', params.source)
      if (params.env) searchParams.set('env', params.env)
      if (params.host) searchParams.set('host', params.host)
      if (params.version) searchParams.set('version', params.version)
      if (params.from != null) searchParams.set('from', String(params.from))
      if (params.to != null) searchParams.set('to', String(params.to))
      if (params.limit != null) searchParams.set('limit', String(params.limit))
      if (params.offset != null) searchParams.set('offset', String(params.offset))
      const qs = searchParams.toString()
      return core.request<ProfileListResponse>(urlWithQuery(`${base}/profiles`, qs))
    },

    getProfile: (profileId: string) =>
      core.request<ProfileResponse>(`${base}/profiles/${profileId}`),

    getProfileServices: (params: {from?: number; to?: number} = {}) => {
      const sp = new URLSearchParams()
      if (params.from != null) sp.set('from', String(params.from))
      if (params.to != null) sp.set('to', String(params.to))
      return core.request<ProfileServicesResponse>(
        urlWithQuery(`${base}/profiles/services`, sp.toString())
      )
    },

    getProfileTimeseries: (params: {
      service?: string
      services?: string[]
      type?: string
      env?: string
      host?: string
      from: number
      to: number
      buckets?: number
    }) => {
      const sp = new URLSearchParams()
      if (params.service) sp.set('service', params.service)
      const services = normalizedServices(params.services)
      if (services.length > 0) sp.set('services', services.join(','))
      if (params.type) sp.set('type', params.type)
      if (params.env) sp.set('env', params.env)
      if (params.host) sp.set('host', params.host)
      sp.set('from', String(params.from))
      sp.set('to', String(params.to))
      if (params.buckets != null) sp.set('buckets', String(params.buckets))
      return core.request<ProfileTimeseriesResponse>(
        urlWithQuery(`${base}/profiles/timeseries`, sp.toString())
      )
    },

    getMergedFlamegraph: (params: {
      service?: string
      services?: string[]
      type?: string
      env?: string
      host?: string
      version?: string
      from?: number
      to?: number
      sampleType?: string | null
      thread?: string | null
      maxProfiles?: number
    }) => {
      const sp = new URLSearchParams()
      if (params.service) sp.set('service', params.service)
      const services = normalizedServices(params.services)
      if (services.length > 0) sp.set('services', services.join(','))
      if (params.type) sp.set('type', params.type)
      if (params.env) sp.set('env', params.env)
      if (params.host) sp.set('host', params.host)
      if (params.version) sp.set('version', params.version)
      if (params.from != null) sp.set('from', String(params.from))
      if (params.to != null) sp.set('to', String(params.to))
      if (params.sampleType) sp.set('sampleType', params.sampleType)
      if (params.thread) sp.set('thread', params.thread)
      if (params.maxProfiles != null) sp.set('maxProfiles', String(params.maxProfiles))
      return core.request<MergedFlamegraphResponse>(
        urlWithQuery(`${base}/profiles/merged-flamegraph`, sp.toString())
      )
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
      try {
        a.click()
      } finally {
        a.remove()
        queueMicrotask(() => URL.revokeObjectURL(url))
      }
    },

    getProfileFlamegraph: (
      profileId: string,
      params: {sampleType?: string | null; thread?: string | null} = {}
    ) => {
      const searchParams = new URLSearchParams()
      if (params.sampleType) searchParams.set('sampleType', params.sampleType)
      if (params.thread) searchParams.set('thread', params.thread)
      return core.request<FlamegraphResponse>(
        urlWithQuery(`${base}/profiles/${profileId}/flamegraph`, searchParams.toString())
      )
    },
  }
}
