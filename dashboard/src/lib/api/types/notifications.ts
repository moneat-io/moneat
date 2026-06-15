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

export interface NotificationPreference {
  issueAlerts: boolean
  errorAlerts: boolean
  weeklySummary: boolean
  alertFrequencyMinutes: number
  // Account-level delivery channel toggles. Present on `preferences.global`;
  // optional because per-project rows don't carry them.
  emailEnabled?: boolean
  pushEnabled?: boolean
}

export interface ProjectNotificationPreference extends NotificationPreference {
  projectId: string
  projectName: string
}

export interface NotificationPreferences {
  global: NotificationPreference
  projects: ProjectNotificationPreference[]
}

export type AlertSource =
  | 'HOST_ALERT'
  | 'HOST_DOWN'
  | 'UPTIME_MONITOR'
  | 'SYNTHETIC_TEST'
  | 'ERROR_ALERT'
  | 'DASHBOARD_ALERT'

export interface AlertNotificationPreference {
  alertSource: AlertSource
  emailEnabled: boolean
  slackEnabled: boolean
  discordEnabled: boolean
}

export interface AlertNotificationPreferencesResponse {
  preferences: AlertNotificationPreference[]
}

// Registered mobile devices for push delivery (GET /v1/user/push-devices).
// The API returns display metadata only — never device tokens.
export interface PushDevice {
  id: string
  label: string
  platform?: string | null
  lastActiveAt?: string | null
}

export interface PushDevicesResponse {
  devices: PushDevice[]
}
