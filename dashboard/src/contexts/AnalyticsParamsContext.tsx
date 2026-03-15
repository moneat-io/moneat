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

import {createContext, useContext, useState, useCallback, type ReactNode} from 'react'
import type {AnalyticsPeriod} from '@/lib/api'

interface AnalyticsParamsContextValue {
  period: AnalyticsPeriod
  setPeriod: (p: AnalyticsPeriod) => void
  customFrom: string
  customTo: string
  onCustomRangeChange: (from: string, to: string) => void
}

const AnalyticsParamsContext = createContext<AnalyticsParamsContextValue | null>(null)

export function AnalyticsParamsProvider({children}: {children: ReactNode}) {
  const [period, setPeriod] = useState<AnalyticsPeriod>('30d')
  const [customFrom, setCustomFrom] = useState('')
  const [customTo, setCustomTo] = useState('')
  const onCustomRangeChange = useCallback((from: string, to: string) => {
    setCustomFrom(from)
    setCustomTo(to)
  }, [])
  return (
    <AnalyticsParamsContext.Provider
      value={{period, setPeriod, customFrom, customTo, onCustomRangeChange}}
    >
      {children}
    </AnalyticsParamsContext.Provider>
  )
}

export function useAnalyticsParams() {
  const ctx = useContext(AnalyticsParamsContext)
  if (!ctx) throw new Error('useAnalyticsParams must be used within AnalyticsParamsProvider')
  return ctx
}
