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

import {useQuery} from '@tanstack/react-query'
import {Target} from 'lucide-react'

import {api} from '@/lib/api'
import type {AnalyticsParams, AnalyticsScopeId, ProductEventTrend} from '@/lib/api'

import {ProductActivationFunnel} from './ProductActivationFunnel'
import {ProductActivityChart} from './ProductActivityChart'
import {ProductEventTrends} from './ProductEventTrends'
import {ProductFeatureAdoption} from './ProductFeatureAdoption'
import {ProductKpiBand} from './ProductKpiBand'
import {ProductMovers} from './ProductMovers'
import {ProductRetentionCohort} from './ProductRetentionCohort'
import {ProductSegmentation} from './ProductSegmentation'

const EVENT_QUERY_OPTIONS = {source: 'server', groupBy: 'user_id'} as const

export function ProductAnalyticsView({
  scopeId,
  params,
}: Readonly<{scopeId: AnalyticsScopeId; params: AnalyticsParams}>) {
  const summary = useQuery({
    queryKey: ['product-summary', scopeId, params],
    queryFn: () => api.getProductAnalyticsSummary(scopeId, params),
    retry: false,
  })

  const activity = useQuery({
    queryKey: ['product-activity', scopeId, params],
    queryFn: () => api.getProductActivity(scopeId, params),
    retry: false,
  })

  const movers = useQuery({
    queryKey: ['product-movers', scopeId, params],
    queryFn: () => api.getProductMovers(scopeId, params),
    retry: false,
  })

  const featureAdoption = useQuery({
    queryKey: ['product-feature-adoption', scopeId, params],
    queryFn: () => api.getProductFeatureAdoption(scopeId, params),
    retry: false,
  })

  const segmentation = useQuery({
    queryKey: ['product-segmentation', scopeId, params],
    queryFn: () => api.getProductSegmentation(scopeId, params),
    retry: false,
  })

  const eventTrends = useQuery({
    queryKey: ['product-event-trends', scopeId, params],
    queryFn: async (): Promise<ProductEventTrend[]> => {
      const events = await api.getAnalyticsEvents(scopeId, params, EVENT_QUERY_OPTIONS)
      return events.map((event) => ({name: event.name, count: event.pageviews, users: event.visitors}))
    },
  })

  return (
    <div className="space-y-3">
      <ProductKpiBand data={summary.data} isLoading={summary.isLoading} />

      <div className="grid grid-cols-1 gap-3 lg:grid-cols-[minmax(0,1.85fr)_minmax(0,1fr)]">
        <ProductActivityChart data={activity.data} isLoading={activity.isLoading} />
        <ProductMovers data={movers.data} isLoading={movers.isLoading} />
      </div>

      <div className="grid grid-cols-1 gap-3 lg:grid-cols-[minmax(0,1.2fr)_minmax(0,1fr)]">
        <ProductActivationFunnel scopeId={scopeId} params={params} />
        <ProductEventTrends data={eventTrends.data} isLoading={eventTrends.isLoading} />
      </div>

      <ProductRetentionCohort scopeId={scopeId} params={params} />

      <div className="grid gap-3 lg:grid-cols-2">
        <ProductFeatureAdoption data={featureAdoption.data} isLoading={featureAdoption.isLoading} />
        <ProductSegmentation data={segmentation.data} isLoading={segmentation.isLoading} />
      </div>

      <div className="flex items-start gap-2.5 rounded-md border border-info-border bg-info-bg px-3 py-2.5 text-xs leading-relaxed text-info-fg">
        <Target className="mt-0.5 h-4 w-4 shrink-0" />
        <div>
          Retention counts a return to a <span className="font-semibold">value action</span> (a key action), not a
          login or pageview — switch the return event above to compare. Activation, funnels and cohorts are defined
          per app; these defaults are editable.
        </div>
      </div>
    </div>
  )
}
