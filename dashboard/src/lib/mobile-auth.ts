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

const MOBILE_AUTH_CALLBACK_URL = 'moneat://auth'
const MOBILE_AUTH_CALLBACK_KEY = 'moneat_mobile_auth_callback'

export function normalizeMobileAuthCallback(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined
  return value.trim() === MOBILE_AUTH_CALLBACK_URL ? MOBILE_AUTH_CALLBACK_URL : undefined
}

export function mobileAuthCallbackUrl(
  redirectUri: string,
  token: string,
  refreshToken: string
): string {
  const params = new URLSearchParams({token, refreshToken})
  return `${redirectUri}#${params.toString()}`
}

export function storeMobileAuthCallback(redirectUri: string | undefined): void {
  const callback = normalizeMobileAuthCallback(redirectUri)
  if (callback) {
    globalThis.sessionStorage?.setItem(MOBILE_AUTH_CALLBACK_KEY, callback)
  }
}

export function consumeMobileAuthCallback(): string | undefined {
  const value = globalThis.sessionStorage?.getItem(MOBILE_AUTH_CALLBACK_KEY)
  globalThis.sessionStorage?.removeItem(MOBILE_AUTH_CALLBACK_KEY)
  return normalizeMobileAuthCallback(value)
}
