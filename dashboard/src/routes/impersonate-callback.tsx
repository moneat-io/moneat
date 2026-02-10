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
