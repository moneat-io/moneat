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

export interface OrganizationAccountSettings {
  id: string
  name: string
  role: string
}

export interface AccountDeletionValidation {
  canDelete: boolean
  error?: string | null
  organizationsAsLastOwner: string[]
}

export interface OrganizationDeletionValidation {
  canDelete: boolean
  error?: string | null
}

export interface OrgMember {
  userId: string
  email: string
  name?: string
  role: string
  joinedAt?: string
}

export interface OrgInvitation {
  id: string
  email: string
  role: string
  status: string
  invitedBy: string
  invitedByEmail: string
  createdAt: string
  expiresAt: string
}

export interface OrgMembersResponse {
  members: OrgMember[]
  pendingInvitations: OrgInvitation[]
}

export interface InvitationDetailsResponse {
  orgName: string
  role: string
  invitedBy: string
  expiresAt: string
  valid: boolean
}

export interface BulkInviteResult {
  success: string[]
  failed: Array<{ email: string; reason: string }>
}
