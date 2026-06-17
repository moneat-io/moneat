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

import {createFileRoute, Navigate} from '@tanstack/react-router'
import {LandingPage} from '@/components/landing/LandingPage'
import {useState, useEffect} from 'react'
import {api} from '@/lib/api'
import {useEnterpriseFeatures} from '@/hooks/useEnterpriseFeatures'
import {APP_OVERVIEW_SEARCH, APP_OVERVIEW_VIEW, normalizeAppOverviewSearch} from '@/lib/overview-route'
import {OverviewDashboard} from '@/components/overview/OverviewDashboard'

export const Route = createFileRoute('/')({
  validateSearch: normalizeAppOverviewSearch,
  component: IndexPage,
})

function IndexPage() {
  const {view} = Route.useSearch()
  const [isAuthenticated, setIsAuthenticated] = useState(api.isAuthenticated())
  const [isChecking, setIsChecking] = useState(true)
  const {data: features, isLoading: featuresLoading} = useEnterpriseFeatures()

  useEffect(() => {
    let mounted = true
    async function checkAuth() {
      try {
        if (!api.isAuthenticated()) {
          await api.checkAuth()
        }
        if (mounted) {
          setIsAuthenticated(api.isAuthenticated())
        }
      } catch {
        if (mounted) {
          setIsAuthenticated(false)
        }
      } finally {
        if (mounted) {
          setIsChecking(false)
        }
      }
    }
    void checkAuth()
    return () => {
      mounted = false
    }
  }, [])

  if (isChecking || (!isAuthenticated && featuresLoading)) {
    return null
  }

  const isOverviewView = view === APP_OVERVIEW_VIEW
  const showPublicLandingPage = !isAuthenticated || !isOverviewView
  if (showPublicLandingPage) {
    if (features?.selfHost) {
      if (isAuthenticated) {
        return <Navigate to="/" search={APP_OVERVIEW_SEARCH} />
      }
      return <Navigate to="/login" />
    }
    return <LandingPage />
  }
  return <OverviewDashboard />
}
