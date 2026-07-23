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

import {createFileRoute, useNavigate} from '@tanstack/react-router'
import {useEffect, useMemo} from 'react'
import {Logo} from '@/components/Logo'
import {APP_OVERVIEW_SEARCH} from '@/lib/overview-route'
import {setDemoEpoch} from '@/lib/demo'
import {api} from '@/lib/api'
import {consumeMobileAuthCallback, mobileAuthCallbackUrl} from '@/lib/mobile-auth'

export const Route = createFileRoute('/auth/oauth/callback')({
  component: OAuthCallbackPage,
})

function OAuthCallbackPage() {
  const navigate = useNavigate()
  
  // Parse error from URL params once, not in effect
  const error = useMemo(() => {
    const searchParams = new URLSearchParams(window.location.search)
    const errorParam = searchParams.get('error')
    const message = searchParams.get('message')
    return errorParam ? (message || 'Authentication failed. Please try again.') : null
  }, [])

  useEffect(() => {
    if (error) {
      const timer = setTimeout(() => {
        navigate({ to: '/login' })
      }, 3000)
      return () => clearTimeout(timer)
    }

    let active = true
    let timer: ReturnType<typeof setTimeout> | undefined
    const completeLogin = async () => {
      // Auth token is now set as an HTTP-only cookie by the backend redirect.
      setDemoEpoch(null)
      globalThis.sessionStorage?.setItem('authenticated', 'true')
      const mobileCallback = consumeMobileAuthCallback()
      if (mobileCallback) {
        try {
          const {token, refreshToken} = await api.createMobileSession()
          if (active) {
            globalThis.window.location.assign(
              mobileAuthCallbackUrl(mobileCallback, token, refreshToken)
            )
          }
          return
        } catch {
          if (active) navigate({to: '/login'})
          return
        }
      }
      timer = setTimeout(() => {
        navigate({to: '/', search: APP_OVERVIEW_SEARCH})
      }, 500)
    }
    void completeLogin()
    return () => {
      active = false
      if (timer) clearTimeout(timer)
    }
  }, [navigate, error])

  return (
    <div className="flex min-h-screen items-center justify-center bg-background">
      <div className="text-center">
        <Logo className="h-12 mx-auto mb-6" />
        {error ? (
          <>
            <h1 className="text-xl font-semibold text-destructive mb-2">Authentication Failed</h1>
            <p className="text-muted-foreground mb-4">{error}</p>
            <p className="text-sm text-muted-foreground">Redirecting to login...</p>
          </>
        ) : (
          <>
            <h1 className="text-xl font-semibold mb-2">Completing sign in...</h1>
            <div className="flex items-center justify-center gap-2 text-muted-foreground">
              <div className="h-4 w-4 animate-spin rounded-full border-2 border-primary border-t-transparent"></div>
              <span>Please wait</span>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
