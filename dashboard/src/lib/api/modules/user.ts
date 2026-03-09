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

import type { ApiClientCore } from '../client'
import type {
  AccountDeletionValidation,
  OrganizationAccountSettings,
  OrganizationDeletionValidation,
} from '../types'

export function userMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getCurrentUser: () =>
      core.request<{
        id: number
        email: string
        name?: string
        emailVerified: boolean
        onboardingCompleted: boolean
        isAdmin?: boolean
        organizationSlug?: string
        demoEpochMs?: number
        sidebarHiddenItems?: string[]
        timezone?: string | null
      }>(`${base}/user`),

    updateSidebarPreferences: (hiddenItems: string[]) =>
      core.request<{ hiddenItems: string[] }>(
        `${base}/user/sidebar-preferences`,
        {
          method: 'PUT',
          body: JSON.stringify({ hiddenItems }),
        }
      ),

    updateUserTimezone: (timezone: string | null) =>
      core.request<{ timezone: string | null }>(`${base}/user/timezone`, {
        method: 'PUT',
        body: JSON.stringify({ timezone }),
      }),

    getOrganizations: () =>
      core.request<Array<{ id: number; name: string; slug: string }>>(
        `${base}/organizations`
      ),

    getOrganizationAccountSettings: (organizationId: number) =>
      core.request<OrganizationAccountSettings>(
        `${base}/organizations/${organizationId}`
      ),

    getAccountDeletionValidation: () =>
      core.request<AccountDeletionValidation>(
        `${base}/account/deletion-validation`
      ),

    getOrganizationDeletionValidation: (organizationId: number) =>
      core.request<OrganizationDeletionValidation>(
        `${base}/organizations/${organizationId}/deletion-validation`
      ),

    deleteAccount: (confirmation: string) =>
      core.request<{ message: string }>(`${base}/account`, {
        method: 'DELETE',
        body: JSON.stringify({ confirmation }),
      }),

    deleteOrganization: (
      organizationId: number,
      confirmation: string
    ) =>
      core.request<{ message: string }>(
        `${base}/organizations/${organizationId}`,
        {
          method: 'DELETE',
          body: JSON.stringify({ confirmation }),
        }
      ),

    getSubscription: async (): Promise<{
      tier: { tierName: string }
    } | null> => {
      try {
        return await core.request(`${base}/subscription`)
      } catch (err) {
        const status = (err as { status?: number })?.status
        if (status === 404) return null
        throw err
      }
    },
  }
}
