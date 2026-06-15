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

import { syncDemoEpochFromUser } from '../../demo'
import type { ApiClientCore } from '../client'
import type {
  AccountDeletionValidation,
  OrganizationAccountSettings,
  OrganizationDeletionValidation,
  PushDevice,
  PushDevicesResponse,
} from '../types'

type CurrentUserResponse = {
  id: string
  email: string
  name?: string
  emailVerified: boolean
  onboardingCompleted: boolean
  isAdmin?: boolean
  orgId?: string
  organizationSlug?: string
  orgRole?: string
  demoEpochMs?: number | null
  sidebarHiddenItems?: string[]
  timezone?: string | null
  density?: string | null
  dateFormat?: string | null
}

export function userMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getCurrentUser: async (): Promise<CurrentUserResponse> => {
      const user = await core.request<CurrentUserResponse>(`${base}/user`)
      syncDemoEpochFromUser(user)
      return user
    },

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

    // Personal display preferences (density, date format). Persisted server-side
    // and returned on getCurrentUser; the CSS/format application layer is FE.
    updateUserPreferences: (
      preferences: Partial<{ density: string; dateFormat: string }>
    ) =>
      core.request<void>(`${base}/user/preferences`, {
        method: 'PUT',
        body: JSON.stringify(preferences),
      }),

    // Registered push devices (display metadata only; never tokens).
    getPushDevices: async (): Promise<PushDevice[]> => {
      const res = await core.request<PushDevicesResponse>(
        `${base}/user/push-devices`
      )
      return res.devices ?? []
    },

    getOrganizations: () =>
      core.request<Array<{ id: string; name: string; slug: string }>>(
        `${base}/organizations`
      ),

    getOrganizationAccountSettings: (organizationId: string) =>
      core.request<OrganizationAccountSettings>(
        `${base}/organizations/${organizationId}`
      ),

    // Update org-level identity/defaults (workspace name, slug, default timezone).
    // Backend contract: PATCH /v1/organizations/{id} accepting any subset of fields.
    updateOrganizationSettings: (
      organizationId: string,
      settings: Partial<{ name: string; slug: string; defaultTimezone: string }>
    ) =>
      core.request<OrganizationAccountSettings>(
        `${base}/organizations/${organizationId}`,
        {
          method: 'PATCH',
          body: JSON.stringify(settings),
        }
      ),

    getAccountDeletionValidation: () =>
      core.request<AccountDeletionValidation>(
        `${base}/account/deletion-validation`
      ),

    getOrganizationDeletionValidation: (organizationId: string) =>
      core.request<OrganizationDeletionValidation>(
        `${base}/organizations/${organizationId}/deletion-validation`
      ),

    deleteAccount: (confirmation: string) =>
      core.request<{ message: string }>(`${base}/account`, {
        method: 'DELETE',
        body: JSON.stringify({ confirmation }),
      }),

    deleteOrganization: (
      organizationId: string,
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
