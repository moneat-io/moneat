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

import { useState, useEffect } from 'react'
import { api } from '@/lib/api'

interface User {
  id: number
  email: string
  name?: string
  emailVerified: boolean
  onboardingCompleted: boolean
  orgId?: number
  orgRole?: string
}

export function useAuth() {
  const [user, setUser] = useState<User | null>(null)
  const [isLoading, setIsLoading] = useState(true)

  useEffect(() => {
    // Check authentication by calling the backend (token is in httpOnly cookie)
    api.getCurrentUser()
      .then((userData) => {
        setUser({
          id: userData.id,
          email: userData.email,
          name: userData.name,
          emailVerified: userData.emailVerified,
          onboardingCompleted: userData.onboardingCompleted,
          orgRole: userData.orgRole,
        })
        // Keep session flag in sync
        globalThis.sessionStorage?.setItem('authenticated', 'true')
      })
      .catch(() => {
        setUser(null)
        globalThis.sessionStorage?.removeItem('authenticated')
      })
      .finally(() => {
        setIsLoading(false)
      })
  }, [])

  return { user, isLoading }
}
