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

export function describeEmailDelivery(email?: string, verified?: boolean): string {
  if (!email) return 'Your account email.'
  return `${email} - ${verified ? 'verified' : 'unverified'}`
}

export function describePushDelivery(deviceCount: number): string {
  if (deviceCount === 0) return 'No devices registered yet - install the Moneat mobile app to receive pushes.'
  const suffix = deviceCount === 1 ? '' : 's'
  return `Deliver alert and on-call pushes to your ${deviceCount} registered device${suffix}.`
}

export function formatAlertFrequency(minutes: number): string {
  if (minutes >= 60) return `${minutes / 60}h`
  return `${minutes}m`
}

export function isValidOnCallPhone(phone: string): boolean {
  return /^\+[1-9]\d{1,14}$/.test(phone.trim())
}
