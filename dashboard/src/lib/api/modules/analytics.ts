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
  AnalyticsParams,
  AnalyticsOverview,
  AnalyticsTimeseriesPoint,
  AnalyticsBreakdownItem,
  AnalyticsRealtimeResponse,
  AnalyticsFunnelResponse,
  AnalyticsEventQueryOptions,
  AnalyticsOverviewApiResponse,
  AnalyticsTimeseriesApiPoint,
  AnalyticsBreakdownApiResponse,
  AnalyticsRealtimeApiResponse,
  AnalyticsRetentionQuery,
  AnalyticsRetentionResponse,
} from '../types'

function appendAnalyticsParams(qs: URLSearchParams, params?: AnalyticsParams) {
  if (params?.period) qs.append('period', params.period)
  if (params?.from) qs.append('date_from', params.from)
  if (params?.to) qs.append('date_to', params.to)
  if (params?.comparison && params.comparison !== 'none') {
    qs.append('comparison', params.comparison)
  }
  if (params?.filters) {
    for (const f of params.filters) {
      qs.append('filters[]', `${f.property}:${f.operator}:${f.value}`)
    }
  }
}

function appendEventQueryOptions(
  qs: URLSearchParams,
  options?: AnalyticsEventQueryOptions
) {
  if (options?.source) qs.append('source', options.source)
  if (options?.groupBy) qs.append('group_by', options.groupBy)
  if (options?.limit != null) qs.append('limit', String(options.limit))
}

function toQueryString(qs: URLSearchParams): string {
  const s = qs.toString()
  return s ? `?${s}` : ''
}

function buildAnalyticsQuery(
  params?: AnalyticsParams,
  options?: AnalyticsEventQueryOptions
): string {
  const qs = new URLSearchParams()
  appendAnalyticsParams(qs, params)
  appendEventQueryOptions(qs, options)
  return toQueryString(qs)
}

function buildRetentionQuery(params: AnalyticsRetentionQuery): string {
  const qs = new URLSearchParams()
  appendAnalyticsParams(qs, params)
  qs.append('start_event', params.startEvent)
  qs.append('return_event', params.returnEvent)
  params.periods?.forEach((period) => qs.append('periods[]', String(period)))
  return toQueryString(qs)
}

function normalizeAnalyticsOverview(
  data: AnalyticsOverviewApiResponse
): AnalyticsOverview {
  const uniqueVisitors = data.uniqueVisitors ?? data.visitors ?? 0
  const totalPageviews = data.totalPageviews ?? data.pageviews ?? 0
  const bounceRate = data.bounceRate ?? 0
  const avgVisitDuration = data.avgVisitDuration ?? 0
  const viewsPerVisit = data.viewsPerVisit ?? 0
  const hasComparison =
    data.compVisitors != null ||
    data.compPageviews != null ||
    data.compBounceRate != null ||
    data.compAvgVisitDuration != null ||
    data.compViewsPerVisit != null

  return {
    uniqueVisitors,
    totalPageviews,
    bounceRate,
    avgVisitDuration,
    viewsPerVisit,
    comparison: hasComparison
      ? {
          uniqueVisitors: data.compVisitors ?? 0,
          totalPageviews: data.compPageviews ?? 0,
          bounceRate: data.compBounceRate ?? 0,
          avgVisitDuration: data.compAvgVisitDuration ?? 0,
          viewsPerVisit: data.compViewsPerVisit ?? 0,
        }
      : undefined,
  }
}

function normalizeAnalyticsTimeseries(
  data: AnalyticsTimeseriesApiPoint[]
): AnalyticsTimeseriesPoint[] {
  return data.map((point) => ({
    timestamp: point.timestamp ?? point.date ?? '',
    visitors: point.visitors ?? 0,
    pageviews: point.pageviews ?? 0,
  }))
}

function normalizeAnalyticsBreakdown(
  data: AnalyticsBreakdownItem[] | AnalyticsBreakdownApiResponse
): AnalyticsBreakdownItem[] {
  if (Array.isArray(data)) return data
  return Array.isArray(data?.results) ? data.results : []
}

export function analyticsMethods(core: ApiClientCore) {
  const base = core.API_BASE

  const fetchAnalyticsBreakdown = async (
    endpoint: string
  ): Promise<AnalyticsBreakdownItem[]> => {
    const response = await core.request<
      AnalyticsBreakdownItem[] | AnalyticsBreakdownApiResponse
    >(endpoint)
    return normalizeAnalyticsBreakdown(response)
  }

  return {
    getAnalyticsOverview: async (
      projectId: string | number,
      params?: AnalyticsParams
    ) => {
      const response = await core.request<AnalyticsOverviewApiResponse>(
        `${base}/analytics/${projectId}/overview${buildAnalyticsQuery(params)}`
      )
      return normalizeAnalyticsOverview(response)
    },

    getAnalyticsTimeseries: async (
      projectId: string | number,
      params?: AnalyticsParams
    ) => {
      const response = await core.request<AnalyticsTimeseriesApiPoint[]>(
        `${base}/analytics/${projectId}/timeseries${buildAnalyticsQuery(params)}`
      )
      return normalizeAnalyticsTimeseries(response)
    },

    getAnalyticsPages: (projectId: string | number, params?: AnalyticsParams) =>
      fetchAnalyticsBreakdown(
        `${base}/analytics/${projectId}/pages${buildAnalyticsQuery(params)}`
      ),

    getAnalyticsEntryPages: (projectId: string | number, params?: AnalyticsParams) =>
      fetchAnalyticsBreakdown(
        `${base}/analytics/${projectId}/entry-pages${buildAnalyticsQuery(params)}`
      ),

    getAnalyticsExitPages: (projectId: string | number, params?: AnalyticsParams) =>
      fetchAnalyticsBreakdown(
        `${base}/analytics/${projectId}/exit-pages${buildAnalyticsQuery(params)}`
      ),

    getAnalyticsSources: (projectId: string | number, params?: AnalyticsParams) =>
      fetchAnalyticsBreakdown(
        `${base}/analytics/${projectId}/sources${buildAnalyticsQuery(params)}`
      ),

    getAnalyticsUtm: (
      projectId: string | number,
      utmParam: string,
      params?: AnalyticsParams
    ) =>
      fetchAnalyticsBreakdown(
        `${base}/analytics/${projectId}/utm/${encodeURIComponent(utmParam)}${buildAnalyticsQuery(params)}`
      ),

    getAnalyticsLocations: (projectId: string | number, params?: AnalyticsParams) =>
      fetchAnalyticsBreakdown(
        `${base}/analytics/${projectId}/locations${buildAnalyticsQuery(params)}`
      ),

    getAnalyticsDevices: (
      projectId: string | number,
      type: 'browser' | 'os' | 'device',
      params?: AnalyticsParams
    ) => {
      const qs = buildAnalyticsQuery(params)
      return fetchAnalyticsBreakdown(
        `${base}/analytics/${projectId}/devices${qs}${qs ? '&' : '?'}type=${type}`
      )
    },

    getAnalyticsEvents: (
      projectId: string | number,
      params?: AnalyticsParams,
      options?: AnalyticsEventQueryOptions
    ) =>
      fetchAnalyticsBreakdown(
        `${base}/analytics/${projectId}/events${buildAnalyticsQuery(params, options)}`
      ),

    getAnalyticsRealtime: async (projectId: string | number) => {
      const response = await core.request<AnalyticsRealtimeApiResponse>(
        `${base}/analytics/${projectId}/realtime`
      )
      return {
        currentVisitors: response.currentVisitors ?? response.visitors ?? 0,
      } as AnalyticsRealtimeResponse
    },

    getAnalyticsFunnel: (
      projectId: string | number,
      steps: string[],
      params?: AnalyticsParams,
      options?: AnalyticsEventQueryOptions
    ) => {
      const qs = buildAnalyticsQuery(params, options)
      const sep = qs ? '&' : '?'
      const stepsParam = steps
        .map((s) => `steps[]=${encodeURIComponent(s)}`)
        .join('&')
      return core.request<AnalyticsFunnelResponse>(
        `${base}/analytics/${projectId}/funnel${qs}${sep}${stepsParam}`
      )
    },

    getAnalyticsRetention: (
      projectId: string | number,
      params: AnalyticsRetentionQuery
    ) =>
      core.request<AnalyticsRetentionResponse>(
        `${base}/analytics/${projectId}/retention${buildRetentionQuery(params)}`
      ),
  }
}
