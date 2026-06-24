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

import {createFileRoute, redirect} from '@tanstack/react-router'
import {useQuery} from '@tanstack/react-query'
import {useMemo} from 'react'
import {api} from '@/lib/api'
import {LogExplorer} from '@/components/logs/LogExplorer'
import {
  parseLogViewFacetFilters,
  parseLogViewSearch,
  type LogViewSearch,
} from '@/components/logs/logViewUrlState'

export const Route = createFileRoute('/logs')({
  validateSearch: (search: Record<string, unknown>): LogViewSearch => {
    return parseLogViewSearch(search)
  },
  beforeLoad: async () => {
    if (!api.isAuthenticated()) {
      throw redirect({to: '/login'})
    }
  },
  component: LogsPage,
})

function LogsPage() {
  const search = Route.useSearch()
  const urlSearch = useMemo<LogViewSearch>(
    () => ({...search, facets: parseLogViewFacetFilters(search)}),
    [search]
  )
  const {data: sdkVersionsResponse} = useQuery({
    queryKey: ['sdk-versions'],
    queryFn: () => api.getSdkVersions(),
    staleTime: 30 * 60 * 1000,
  })

  return (
    <div
      className="fixed flex flex-col overflow-hidden"
      style={{
        top: 'var(--header-height, 0px)',
        left: 'var(--sidebar-width, 0px)',
        right: 0,
        bottom: 0,
      }}
    >
      <LogExplorer
        sdkVersions={sdkVersionsResponse?.versions}
        className="h-full"
        enableUrlSync={true}
        urlSearch={urlSearch}
      />
    </div>
  )
}
