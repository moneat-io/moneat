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
  NotificationPreferences,
  NotificationPreference,
  AlertNotificationPreference,
  AlertNotificationPreferencesResponse,
  AlertSource,
} from '../types'

export function notificationsMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getNotificationPreferences: () =>
      core.request<NotificationPreferences>(`${base}/notification-preferences`),

    updateNotificationPreferences: (
      preferences: Partial<NotificationPreference>
    ) =>
      core.request<void>(`${base}/notification-preferences`, {
        method: 'PUT',
        body: JSON.stringify(preferences),
      }),

    updateProjectNotificationPreferences: (
      projectId: number,
      preferences: Partial<NotificationPreference>
    ) =>
      core.request<void>(`${base}/notification-preferences/${projectId}`, {
        method: 'PUT',
        body: JSON.stringify(preferences),
      }),

    deleteProjectNotificationPreferences: (projectId: number) =>
      core.request<void>(`${base}/notification-preferences/${projectId}`, {
        method: 'DELETE',
      }),

    getAlertNotificationPreferences: async () => {
      const response = await core.request<AlertNotificationPreferencesResponse>(
        `${base}/alert-notification-preferences`
      )
      return response.preferences ?? []
    },

    updateAlertNotificationPreference: (
      alertSource: AlertSource,
      preferences: {
        emailEnabled: boolean
        slackEnabled: boolean
        discordEnabled: boolean
      }
    ) =>
      core.request<AlertNotificationPreference>(
        `${base}/alert-notification-preferences/${alertSource}`,
        {
          method: 'PUT',
          body: JSON.stringify(preferences),
        }
      ),
  }
}
