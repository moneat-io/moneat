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
import { useEffect } from 'react'

export const Route = createFileRoute('/impersonate-callback')({
  component: ImpersonateCallback,
})

function ImpersonateCallback() {
  const navigate = useNavigate()

  useEffect(() => {
    if (!window.opener) {
      navigate({ to: '/' })
      return
    }

    const expectedOrigin = window.location.origin
    const timeoutId = window.setTimeout(() => {
      navigate({ to: '/' })
    }, 10000)

    const handleTokenMessage = (event: MessageEvent) => {
      if (event.origin !== expectedOrigin) return
      if (event.source !== window.opener) return
      if (event.data?.type !== 'MONEAT_IMPERSONATION_TOKEN') return
      if (typeof event.data?.token !== 'string' || event.data.token.length === 0) return

      sessionStorage.setItem('impersonate_token', event.data.token)
      window.clearTimeout(timeoutId)
      window.removeEventListener('message', handleTokenMessage)
      navigate({ to: '/' })
    }

    window.addEventListener('message', handleTokenMessage)
    window.opener.postMessage({ type: 'MONEAT_IMPERSONATION_READY' }, expectedOrigin)

    return () => {
      window.clearTimeout(timeoutId)
      window.removeEventListener('message', handleTokenMessage)
    }
  }, [navigate])

  return (
    <div className="flex items-center justify-center h-screen">
      <p className="text-muted-foreground">Setting up session...</p>
    </div>
  )
}
