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

import { setDemoEpoch } from '../../demo'
import type { ApiClientCore } from '../client'
import type {
  AuthResponse,
  MobileAuthResponse,
  SignupLegalConsent,
  SsoConfig,
} from '../types'

export function authMethods(core: ApiClientCore) {
  const base = core.API_BASE
  const authBase = base.replace('/v1', '')

  return {
    signup: async (
      email: string,
      password: string,
      name: string | undefined,
      legalConsent: SignupLegalConsent,
      inviteToken?: string
    ): Promise<AuthResponse> => {
      const signupUrl = inviteToken
        ? `${authBase}/auth/signup?inviteToken=${encodeURIComponent(inviteToken)}`
        : `${authBase}/auth/signup`
      const response = await core.request<AuthResponse>(signupUrl, {
        method: 'POST',
        body: JSON.stringify({ email, password, name, ...legalConsent }),
      })
      setDemoEpoch(null)
      globalThis.sessionStorage?.setItem('authenticated', 'true')
      return response
    },

    login: async (
      email: string,
      password: string
    ): Promise<AuthResponse> => {
      const response = await core.request<AuthResponse>(`${authBase}/auth/login`, {
        method: 'POST',
        body: JSON.stringify({ email, password }),
      })
      setDemoEpoch(null)
      globalThis.sessionStorage?.setItem('authenticated', 'true')
      return response
    },

    mobileLogin: async (
      email: string,
      password: string
    ): Promise<MobileAuthResponse> =>
      core.request<MobileAuthResponse>(`${authBase}/auth/mobile/login`, {
        method: 'POST',
        body: JSON.stringify({ email, password }),
      }),

    initSso: async (
      email?: string,
      orgSlug?: string
    ): Promise<{ redirectUrl: string; providerType: string; state?: string }> =>
      core.request(`${authBase}/auth/sso/init`, {
        method: 'POST',
        body: JSON.stringify({ email, orgSlug }),
      }),

    checkSsoRequired: async (
      email: string
    ): Promise<{ required: boolean }> =>
      core.request(`${base}/sso/check-required`, {
        method: 'POST',
        body: JSON.stringify({ email }),
      }),

    getSsoConfig: async (
      organizationId: string
    ): Promise<SsoConfig> =>
      core.request<SsoConfig>(
        `${base}/sso/config?organizationId=${organizationId}`
      ),

    configureSso: async (
      organizationId: string,
      config: SsoConfig
    ): Promise<SsoConfig> =>
      core.request<SsoConfig>(
        `${base}/sso/config?organizationId=${organizationId}`,
        { method: 'PUT', body: JSON.stringify(config) }
      ),

    verifySsoDomain: async (
      organizationId: string
    ): Promise<SsoConfig> =>
      core.request<SsoConfig>(
        `${base}/sso/config/domain/verify?organizationId=${organizationId}`,
        { method: 'POST' }
      ),

    deleteSsoConfig: async (
      organizationId: string
    ): Promise<void> =>
      core.request(`${base}/sso/config?organizationId=${organizationId}`, {
        method: 'DELETE',
      }),

    impersonateUser: async (
      userId: string
    ): Promise<{ token: string }> =>
      core.request(`${base}/admin/impersonate/${userId}`, {
        method: 'POST',
      }),

    logout: () => core.logout(),

    isAuthenticated: () => core.isAuthenticated(),

    checkAuth: () => core.checkAuth(),

    completeOnboarding: async (options: {
      organizationName: string
      companySize: string
      slug: string
      referralSource: string
      utmSource?: string
      utmMedium?: string
      utmCampaign?: string
      utmContent?: string
      utmTerm?: string
      sidebarHiddenItems?: string[]
    }) =>
      core.request(`${authBase}/auth/complete-onboarding`, {
        method: 'POST',
        body: JSON.stringify(options),
      }),

    demoLogin: async (): Promise<{ token: string; demoEpochMs: number }> => {
      const response = await core.request<{
        token: string
        demoEpochMs: number
      }>(`${authBase}/auth/demo-login`, { method: 'POST' })
      globalThis.sessionStorage?.setItem('authenticated', 'true')
      setDemoEpoch(response.demoEpochMs)
      return response
    },

    checkSlugAvailability: async (
      slug: string
    ): Promise<{ available: boolean }> =>
      core.request(
        `${authBase}/auth/check-slug?slug=${encodeURIComponent(slug)}`
      ),

    resendVerificationEmail: async (
      email: string
    ): Promise<{ message: string }> =>
      core.request(`${authBase}/auth/resend-verification`, {
        method: 'POST',
        body: JSON.stringify({ email }),
      }),

    verifyEmail: async (token: string): Promise<{ message: string }> =>
      core.request(`${authBase}/auth/verify-email`, {
        method: 'POST',
        body: JSON.stringify({ token }),
      }),

    forgotPassword: async (email: string): Promise<{ message: string }> =>
      core.request(`${authBase}/auth/forgot-password`, {
        method: 'POST',
        body: JSON.stringify({ email }),
      }),

    resetPassword: async (
      token: string,
      newPassword: string
    ): Promise<{ message: string }> =>
      core.request(`${authBase}/auth/reset-password`, {
        method: 'POST',
        body: JSON.stringify({ token, newPassword }),
      }),
  }
}
