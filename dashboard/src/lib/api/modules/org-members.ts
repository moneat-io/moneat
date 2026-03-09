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
  BulkInviteResult,
  InvitationDetailsResponse,
  OrgInvitation,
  OrgMembersResponse,
} from '../types'

export function orgMembersMethods(core: ApiClientCore) {
  const base = core.API_BASE

  return {
    getOrgMembers: () =>
      core.request<OrgMembersResponse>(`${base}/org/members`),

    inviteMember: (email: string, role = 'member') =>
      core.request<OrgInvitation>(`${base}/org/invitations`, {
        method: 'POST',
        body: JSON.stringify({ email, role }),
      }),

    bulkInviteMembers: (
      emails: string[],
      role = 'member'
    ) =>
      core.request<BulkInviteResult>(`${base}/org/invitations/bulk`, {
        method: 'POST',
        body: JSON.stringify({ emails, role }),
      }),

    updateMemberRole: (userId: number, role: string) =>
      core.request<{ success: boolean }>(
        `${base}/org/members/${userId}/role`,
        { method: 'PUT', body: JSON.stringify({ role }) }
      ),

    removeMember: (userId: number) =>
      core.request<{ success: boolean }>(
        `${base}/org/members/${userId}`,
        { method: 'DELETE' }
      ),

    revokeInvitation: (invitationId: number) =>
      core.request<{ success: boolean }>(
        `${base}/org/invitations/${invitationId}`,
        { method: 'DELETE' }
      ),

    resendInvitation: (invitationId: number) =>
      core.request<{ success: boolean }>(
        `${base}/org/invitations/${invitationId}/resend`,
        { method: 'POST' }
      ),

    getInvitationDetails: (token: string) =>
      core.request<InvitationDetailsResponse>(
        `${base}/org/invitations/details?token=${encodeURIComponent(token)}`
      ),

    acceptInvitation: (token: string) =>
      core.request<{ success: boolean }>(`${base}/org/invitations/accept`, {
        method: 'POST',
        body: JSON.stringify({ token }),
      }),
  }
}
