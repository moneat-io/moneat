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
import {useEffect, useState, type ReactNode} from 'react'
import {Loader2} from 'lucide-react'
import {api} from '@/lib/api'
import {trackEvent} from '@/lib/analytics'
import {useAuth} from '@/hooks/useAuth'
import {APP_OVERVIEW_SEARCH} from '@/lib/overview-route'
import {Button} from '@/components/ui/button'
import {AuthAlert, AuthShell} from '@/components/auth/AuthShell'
import {authPrimaryButtonClass, authSecondaryButtonClass} from '@/components/auth/authStyles'
import {Helmet} from 'react-helmet-async'

export const Route = createFileRoute('/accept-invite')({
  component: AcceptInvitePage,
})

function InviteCard({children}: {readonly children: ReactNode}) {
  return <div className="grid gap-2.5 rounded-lg border border-white/[0.08] bg-white/[0.02] p-4">{children}</div>
}

function InviteRow({label, value, capitalize}: {readonly label: string; readonly value: string; readonly capitalize?: boolean}) {
  return (
    <div className="flex items-center justify-between gap-3 text-sm">
      <span className="text-slate-400">{label}</span>
      <span className={capitalize ? 'font-medium capitalize text-slate-100' : 'font-medium text-slate-100'}>{value}</span>
    </div>
  )
}

function AcceptInvitePage() {
  const navigate = useNavigate()
  const { user, isLoading: authLoading } = useAuth()
  const token = new URLSearchParams(window.location.search).get('token') || undefined

  const [inviteDetails, setInviteDetails] = useState<{
    orgName: string
    role: string
    invitedBy: string
    expiresAt: string
    valid: boolean
  } | null>(null)
  const [loading, setLoading] = useState(() => !!token)
  const [accepting, setAccepting] = useState(false)
  const [error, setError] = useState<string | null>(() => !token ? 'No invitation token provided' : null)
  const [success, setSuccess] = useState(false)

  useEffect(() => {
    if (!token) {
      return
    }

    // Load invitation details
    api.getInvitationDetails(token)
      .then(details => {
        setInviteDetails(details)
        setLoading(false)
      })
      .catch(err => {
        setError(err.message || 'Failed to load invitation')
        setLoading(false)
      })
  }, [token])

  const handleAccept = async () => {
    if (!token) return

    setAccepting(true)
    setError(null)

    try {
      await api.acceptInvitation(token)
      trackEvent('Invite Accept', { role: inviteDetails?.role || 'unknown' })
      setSuccess(true)

      // Redirect to dashboard after a short delay
      setTimeout(() => {
        navigate({ to: '/', search: APP_OVERVIEW_SEARCH })
      }, 2000)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to accept invitation')
      setAccepting(false)
    }
  }

  let body: ReactNode
  let kicker = 'Invitation'
  let heading: ReactNode
  let subheading: ReactNode

  if (loading || authLoading) {
    heading = 'Loading invitation'
    subheading = 'One moment while we pull up the details.'
    body = (
      <div className="flex items-center gap-3 text-sm text-slate-400">
        <Loader2 className="size-4 animate-spin text-indigo-300" />
        Loading invitation…
      </div>
    )
  } else if (error || !inviteDetails) {
    heading = 'Invalid invitation'
    subheading = 'This link can no longer be used.'
    body = (
      <div className="grid gap-5">
        <AuthAlert tone="danger">{error || 'This invitation link is not valid.'}</AuthAlert>
        <Button className={authPrimaryButtonClass} onClick={() => navigate({ to: '/login' })}>
          Go to sign in
        </Button>
      </div>
    )
  } else if (!inviteDetails.valid) {
    heading = 'Invitation expired'
    subheading = 'This invitation has expired or has already been used.'
    body = (
      <div className="grid gap-5">
        <AuthAlert tone="danger">
          Ask {inviteDetails.invitedBy} to send you a new invitation.
        </AuthAlert>
        <Button className={authPrimaryButtonClass} onClick={() => navigate({ to: '/login' })}>
          Go to sign in
        </Button>
      </div>
    )
  } else if (success) {
    kicker = 'Welcome aboard'
    heading = "You're in"
    subheading = (
      <>
        You&apos;ve joined <span className="font-medium text-slate-200">{inviteDetails.orgName}</span>.
      </>
    )
    body = <AuthAlert tone="success">Redirecting you to the dashboard…</AuthAlert>
  } else if (!user) {
    kicker = "You're invited"
    heading = `Join ${inviteDetails.orgName}`
    subheading = (
      <>
        <span className="font-medium text-slate-200">{inviteDetails.invitedBy}</span> invited you to join their
        organization.
      </>
    )
    body = (
      <div className="grid gap-5">
        <InviteCard>
          <InviteRow label="Organization" value={inviteDetails.orgName} />
          <InviteRow label="Role" value={inviteDetails.role} capitalize />
          <InviteRow label="Invited by" value={inviteDetails.invitedBy} />
        </InviteCard>
        <p className="text-sm text-slate-400">Sign in or create an account to accept this invitation.</p>
        <div className="grid gap-2.5">
          <Button
            className={authPrimaryButtonClass}
            onClick={() => navigate({ to: '/login', search: { inviteToken: token } })}
          >
            Sign in
          </Button>
          <Button
            variant="outline"
            className={authSecondaryButtonClass}
            onClick={() => navigate({ to: '/signup', search: { inviteToken: token } })}
          >
            Create account
          </Button>
        </div>
      </div>
    )
  } else {
    kicker = "You're invited"
    heading = `Join ${inviteDetails.orgName}?`
    subheading = (
      <>
        <span className="font-medium text-slate-200">{inviteDetails.invitedBy}</span> invited you to their organization.
      </>
    )
    body = (
      <div className="grid gap-5">
        <InviteCard>
          <InviteRow label="Organization" value={inviteDetails.orgName} />
          <InviteRow label="Role" value={inviteDetails.role} capitalize />
          <InviteRow label="Your email" value={user.email} />
        </InviteCard>
        {error && <AuthAlert tone="danger">{error}</AuthAlert>}
        <Button className={authPrimaryButtonClass} onClick={handleAccept} disabled={accepting}>
          {accepting ? (
            <>
              <Loader2 className="size-4 animate-spin" />
              Accepting…
            </>
          ) : (
            'Accept invitation'
          )}
        </Button>
      </div>
    )
  }

  return (
    <>
      <Helmet>
        <title>Accept Invitation | Moneat</title>
        <meta name="robots" content="noindex" />
      </Helmet>
      <AuthShell kicker={kicker} heading={heading} subheading={subheading}>
        {body}
      </AuthShell>
    </>
  )
}
