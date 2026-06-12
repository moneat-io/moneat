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

package com.moneat.auth.repositories.models

/**
 * Domain model for a user row from the database.
 */
data class UserRow(
    val id: Int,
    val resourceId: String,
    val email: String,
    val passwordHash: String,
    val name: String?,
    val emailVerified: Boolean,
    val isAdmin: Boolean,
    val onboardingCompleted: Boolean,
    val emailVerificationToken: String?,
    val emailVerificationExpiresAt: Long?,
    val passwordResetToken: String?,
    val passwordResetExpiresAt: Long?,
    val oauthProvider: String?,
    val oauthProviderId: String?
)
