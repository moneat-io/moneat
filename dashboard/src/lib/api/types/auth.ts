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

export interface AuthResponse {
  token: string
  expiresIn?: number
  user: {
    id: number
    email: string
    name?: string
    emailVerified: boolean
    onboardingCompleted: boolean
  }
}

export interface SignupLegalConsent {
  acceptTerms: boolean
  acceptPrivacy: boolean
  termsVersion: string
  privacyVersion: string
}

export interface AuthToken {
  id: number
  name: string
  token?: string | null
  scopes: string[]
  lastUsedAt?: string | null
  expiresAt?: string | null
  createdAt: string
}

export interface SsoConfig {
  providerType: string
  isEnabled: boolean
  idpEntityId?: string
  idpSsoUrl?: string
  idpCertificate?: string
  oidcIssuerUrl?: string
  oidcClientId?: string
  oidcClientSecret?: string
  emailDomain?: string
  emailDomainVerified?: boolean
  emailDomainVerificationRecordName?: string
  emailDomainVerificationToken?: string
  requireSso?: boolean
  spEntityId?: string
  hasClientSecret?: boolean
}
