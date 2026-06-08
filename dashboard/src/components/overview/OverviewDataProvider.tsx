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

import {useMemo, type ReactNode} from 'react'
import {useQuery} from '@tanstack/react-query'
import {api} from '@/lib/api'
import type {OverviewResponse} from '@/lib/api/types'
import {EMPTY_OVERVIEW, OverviewDataContext, type OverviewDataState} from './overviewData'

type OverviewDataProviderProps = Readonly<{
  children: ReactNode
  data?: OverviewResponse
}>

export function OverviewDataProvider({children, data}: OverviewDataProviderProps) {
  const query = useQuery({
    queryKey: ['overview'],
    queryFn: api.getOverview,
    enabled: data === undefined,
    staleTime: 15_000,
  })

  const value = useMemo<OverviewDataState>(() => ({
    overview: data ?? query.data ?? EMPTY_OVERVIEW,
    isLoading: data === undefined && query.isLoading,
    isError: data === undefined && query.isError,
  }), [data, query.data, query.isError, query.isLoading])

  return (
    <OverviewDataContext.Provider value={value}>
      {children}
    </OverviewDataContext.Provider>
  )
}
