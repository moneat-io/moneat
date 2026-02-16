// Moneat - Mobile-First Error Monitoring Platform
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

import { createFileRoute, useNavigate } from '@tanstack/react-router'
import { useEffect, useState } from 'react'
import { Logo } from '@/components/logo'

export const Route = createFileRoute('/auth/sso/callback')({
  component: SsoCallbackPage,
})

function SsoCallbackPage() {
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const errorParam = params.get('error')
    const errorMessage = params.get('message')

    if (errorParam) {
      setError(errorMessage || 'SSO authentication failed. Please try again.')
      return
    }

    // Auth token is now set as httpOnly cookie by the backend redirect
    sessionStorage.setItem('authenticated', 'true')
    navigate({ to: '/' })
  }, [navigate])

  if (error) {
    return (
      <div className="flex items-center justify-center px-4">
        <div className="max-w-md w-full text-center">
          <Logo className="h-10 mx-auto mb-8" />
          <div className="bg-destructive/10 border border-destructive/20 rounded-lg p-6 mb-6">
            <h2 className="text-lg font-semibold text-destructive mb-2">
              SSO Authentication Failed
            </h2>
            <p className="text-sm text-muted-foreground">{error}</p>
          </div>
          <a
            href="/login"
            className="text-sm text-primary hover:underline"
          >
            Return to login
          </a>
        </div>
      </div>
    )
  }

  return (
    <div className="flex items-center justify-center">
      <div className="text-center">
        <Logo className="h-10 mx-auto mb-6" />
        <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary mx-auto"></div>
        <p className="mt-4 text-sm text-muted-foreground">Completing SSO login...</p>
      </div>
    </div>
  )
}
