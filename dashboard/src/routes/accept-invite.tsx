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

import { createFileRoute, useNavigate } from '@tanstack/react-router'
import { useEffect, useState } from 'react'
import { api } from '../lib/api'
import { useAuth } from '../hooks/useAuth'
import { Button } from '../components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card'
import { Alert, AlertDescription } from '../components/ui/alert'
import { Check, Loader2, AlertCircle, Users } from 'lucide-react'

export const Route = createFileRoute('/accept-invite')({
  component: AcceptInvitePage,
})

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
  const [loading, setLoading] = useState(true)
  const [accepting, setAccepting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState(false)

  useEffect(() => {
    if (!token) {
      setError('No invitation token provided')
      setLoading(false)
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
      setSuccess(true)
      
      // Redirect to dashboard after a short delay
      setTimeout(() => {
        navigate({ to: '/' })
      }, 2000)
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to accept invitation')
      setAccepting(false)
    }
  }

  if (loading || authLoading) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-gray-50">
        <div className="text-center">
          <Loader2 className="h-8 w-8 animate-spin text-gray-400 mx-auto mb-4" />
          <p className="text-gray-600">Loading invitation...</p>
        </div>
      </div>
    )
  }

  if (error || !inviteDetails) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-gray-50 p-4">
        <Card className="max-w-md w-full">
          <CardHeader>
            <div className="flex items-center justify-center mb-4">
              <div className="h-12 w-12 rounded-full bg-red-100 flex items-center justify-center">
                <AlertCircle className="h-6 w-6 text-red-600" />
              </div>
            </div>
            <CardTitle className="text-center">Invalid Invitation</CardTitle>
            <CardDescription className="text-center">
              {error || 'This invitation link is not valid'}
            </CardDescription>
          </CardHeader>
          <CardContent>
            <Button
              onClick={() => navigate({ to: '/login' })}
              className="w-full"
            >
              Go to Login
            </Button>
          </CardContent>
        </Card>
      </div>
    )
  }

  if (!inviteDetails.valid) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-gray-50 p-4">
        <Card className="max-w-md w-full">
          <CardHeader>
            <div className="flex items-center justify-center mb-4">
              <div className="h-12 w-12 rounded-full bg-yellow-100 flex items-center justify-center">
                <AlertCircle className="h-6 w-6 text-yellow-600" />
              </div>
            </div>
            <CardTitle className="text-center">Invitation Expired</CardTitle>
            <CardDescription className="text-center">
              This invitation has expired or has already been used.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <p className="text-sm text-gray-600 text-center">
              Please contact {inviteDetails.invitedBy} to send you a new invitation.
            </p>
            <Button
              onClick={() => navigate({ to: '/login' })}
              className="w-full"
            >
              Go to Login
            </Button>
          </CardContent>
        </Card>
      </div>
    )
  }

  if (success) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-gray-50 p-4">
        <Card className="max-w-md w-full">
          <CardHeader>
            <div className="flex items-center justify-center mb-4">
              <div className="h-12 w-12 rounded-full bg-green-100 flex items-center justify-center">
                <Check className="h-6 w-6 text-green-600" />
              </div>
            </div>
            <CardTitle className="text-center">Welcome!</CardTitle>
            <CardDescription className="text-center">
              You've successfully joined {inviteDetails.orgName}
            </CardDescription>
          </CardHeader>
          <CardContent>
            <p className="text-sm text-gray-600 text-center">
              Redirecting to dashboard...
            </p>
          </CardContent>
        </Card>
      </div>
    )
  }

  if (!user) {
    return (
      <div className="flex items-center justify-center min-h-screen bg-gray-50 p-4">
        <Card className="max-w-md w-full">
          <CardHeader>
            <div className="flex items-center justify-center mb-4">
              <div className="h-12 w-12 rounded-full bg-blue-100 flex items-center justify-center">
                <Users className="h-6 w-6 text-blue-600" />
              </div>
            </div>
            <CardTitle className="text-center">You've Been Invited!</CardTitle>
            <CardDescription className="text-center">
              {inviteDetails.invitedBy} invited you to join {inviteDetails.orgName}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="bg-gray-50 rounded-lg p-4 space-y-2">
              <div className="flex justify-between text-sm">
                <span className="text-gray-600">Organization:</span>
                <span className="font-medium">{inviteDetails.orgName}</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-gray-600">Role:</span>
                <span className="font-medium capitalize">{inviteDetails.role}</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-gray-600">Invited by:</span>
                <span className="font-medium">{inviteDetails.invitedBy}</span>
              </div>
            </div>

            <Alert>
              <AlertDescription className="text-sm">
                To accept this invitation, you need to sign in or create an account.
              </AlertDescription>
            </Alert>

            <div className="space-y-2">
              <Button
                onClick={() => navigate({ to: '/login', search: { inviteToken: token } })}
                className="w-full"
              >
                Sign In
              </Button>
              <Button
                onClick={() => navigate({ to: '/signup', search: { inviteToken: token } })}
                variant="outline"
                className="w-full"
              >
                Create Account
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>
    )
  }

  // User is logged in - show accept button
  return (
    <div className="flex items-center justify-center min-h-screen bg-gray-50 p-4">
      <Card className="max-w-md w-full">
        <CardHeader>
          <div className="flex items-center justify-center mb-4">
            <div className="h-12 w-12 rounded-full bg-blue-100 flex items-center justify-center">
              <Users className="h-6 w-6 text-blue-600" />
            </div>
          </div>
          <CardTitle className="text-center">Join {inviteDetails.orgName}?</CardTitle>
          <CardDescription className="text-center">
            {inviteDetails.invitedBy} invited you to join their organization
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="bg-gray-50 rounded-lg p-4 space-y-2">
            <div className="flex justify-between text-sm">
              <span className="text-gray-600">Organization:</span>
              <span className="font-medium">{inviteDetails.orgName}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-gray-600">Role:</span>
              <span className="font-medium capitalize">{inviteDetails.role}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-gray-600">Your email:</span>
              <span className="font-medium">{user.email}</span>
            </div>
          </div>

          {error && (
            <Alert variant="destructive">
              <AlertDescription>{error}</AlertDescription>
            </Alert>
          )}

          <Button
            onClick={handleAccept}
            disabled={accepting}
            className="w-full"
          >
            {accepting ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                Accepting...
              </>
            ) : (
              'Accept Invitation'
            )}
          </Button>
        </CardContent>
      </Card>
    </div>
  )
}
