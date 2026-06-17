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

package com.moneat.auth.repositories

import com.moneat.auth.repositories.models.UserRow
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.SsoConfigurations
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.trim
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

class UserRepositoryImpl : UserRepository {

    override fun findByEmail(email: String): UserRow? =
        transaction {
            Users.selectAll().where { Users.email eq email }.firstOrNull()?.let { toUserRow(it) }
        }

    override fun findById(id: Int): UserRow? =
        transaction {
            Users.selectAll().where { Users.id eq id }.firstOrNull()?.let { toUserRow(it) }
        }

    override fun findByEmailVerificationToken(token: String): UserRow? =
        transaction {
            Users.selectAll().where { Users.email_verification_token eq token }.firstOrNull()?.let { toUserRow(it) }
        }

    override fun findByPasswordResetToken(token: String): UserRow? =
        transaction {
            Users.selectAll().where { Users.password_reset_token eq token }.firstOrNull()?.let { toUserRow(it) }
        }

    override fun existsByEmail(email: String): Boolean =
        transaction {
            Users.selectAll().where { Users.email eq email }.firstOrNull() != null
        }

    override fun create(
        email: String,
        passwordHash: String,
        name: String?,
        emailVerified: Boolean,
        emailVerificationToken: String?,
        emailVerificationExpiresAt: Long?
    ): Int =
        transaction {
            Users.insert {
                it[Users.email] = email
                it[Users.password_hash] = passwordHash
                it[Users.name] = name
                it[Users.email_verified] = emailVerified
                it[Users.email_verification_token] = emailVerificationToken
                it[Users.email_verification_expires_at] = emailVerificationExpiresAt
            }[Users.id]
        }

    override fun updateEmailVerified(id: Int, verified: Boolean) {
        transaction {
            Users.update({ Users.id eq id }) {
                it[Users.email_verified] = verified
            }
        }
    }

    override fun clearEmailVerificationToken(id: Int) {
        transaction {
            Users.update({ Users.id eq id }) {
                it[Users.email_verification_token] = null
                it[Users.email_verification_expires_at] = null
            }
        }
    }

    override fun updateVerificationToken(id: Int, token: String, expiresAt: Long) {
        transaction {
            Users.update({ Users.id eq id }) {
                it[Users.email_verification_token] = token
                it[Users.email_verification_expires_at] = expiresAt
            }
        }
    }

    override fun updatePasswordResetToken(id: Int, token: String, expiresAt: Long) {
        transaction {
            Users.update({ Users.id eq id }) {
                it[Users.password_reset_token] = token
                it[Users.password_reset_expires_at] = expiresAt
            }
        }
    }

    override fun updatePassword(id: Int, passwordHash: String) {
        transaction {
            Users.update({ Users.id eq id }) {
                it[Users.password_hash] = passwordHash
            }
        }
    }

    override fun clearPasswordResetToken(id: Int) {
        transaction {
            Users.update({ Users.id eq id }) {
                it[Users.password_reset_token] = null
                it[Users.password_reset_expires_at] = null
            }
        }
    }

    override fun updateOnboardingCompleted(id: Int) {
        transaction {
            Users.update({ Users.id eq id }) {
                it[Users.onboarding_completed] = true
            }
        }
    }

    override fun requiresSsoForEmail(email: String): Boolean {
        val normalizedEmail = email.lowercase().trim()
        val normalizedDomain = normalizedEmail.substringAfter("@").trim()
        if (normalizedDomain.isBlank()) return false
        return transaction {
            (
                Users
                    .innerJoin(Memberships) { Users.id eq Memberships.user_id }
                    .innerJoin(SsoConfigurations) { Memberships.organization_id eq SsoConfigurations.organizationId }
                )
                .selectAll()
                .where {
                    (Users.email eq normalizedEmail) and
                        (SsoConfigurations.emailDomain.isNotNull()) and
                        (SsoConfigurations.emailDomain.trim().lowerCase() eq normalizedDomain) and
                        (SsoConfigurations.emailDomainVerified eq true) and
                        (SsoConfigurations.isEnabled eq true) and
                        (SsoConfigurations.requireSso eq true)
                }.firstOrNull() != null
        }
    }

    private fun toUserRow(row: ResultRow): UserRow =
        UserRow(
            id = row[Users.id],
            resourceId = row[Users.resource_id].toString(),
            email = row[Users.email],
            passwordHash = row[Users.password_hash],
            name = row[Users.name],
            emailVerified = row[Users.email_verified],
            isAdmin = row[Users.is_admin],
            onboardingCompleted = row[Users.onboarding_completed],
            emailVerificationToken = row[Users.email_verification_token],
            emailVerificationExpiresAt = row[Users.email_verification_expires_at],
            passwordResetToken = row[Users.password_reset_token],
            passwordResetExpiresAt = row[Users.password_reset_expires_at],
            oauthProvider = row[Users.oauth_provider],
            oauthProviderId = row[Users.oauth_provider_id]
        )
}
