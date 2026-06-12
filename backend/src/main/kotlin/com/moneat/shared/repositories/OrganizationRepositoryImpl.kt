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

package com.moneat.shared.repositories

import com.moneat.shared.models.Organizations
import com.moneat.shared.models.Users
import com.moneat.shared.repositories.models.OrganizationRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

private const val MAX_SLUG_RETRIES = 50
private const val INITIAL_SLUG_SUFFIX = 2

class OrganizationRepositoryImpl : OrganizationRepository {

    override fun findById(id: Int): OrganizationRow? =
        transaction {
            Organizations.selectAll().where { Organizations.id eq id }.firstOrNull()
                ?.let {
                    OrganizationRow(
                        id = it[Organizations.id],
                        resourceId = it[Organizations.resource_id].toString(),
                        name = it[Organizations.name],
                        slug = it[Organizations.slug],
                    )
                }
        }

    override fun findBySlug(slug: String): OrganizationRow? =
        transaction {
            Organizations.selectAll().where { Organizations.slug eq slug }.firstOrNull()
                ?.let {
                    OrganizationRow(
                        id = it[Organizations.id],
                        resourceId = it[Organizations.resource_id].toString(),
                        name = it[Organizations.name],
                        slug = it[Organizations.slug],
                    )
                }
        }

    override fun updateOnboardingOrgAndMarkComplete(update: OrganizationRepository.OnboardingUpdate): String {
        var slug = update.baseSlug
        var suffix = INITIAL_SLUG_SUFFIX
        repeat(MAX_SLUG_RETRIES) {
            try {
                val candidateSlug = slug
                transaction {
                    val orgUpdated = Organizations.update({ Organizations.id eq update.orgId }) {
                        it[name] = update.name
                        it[Organizations.slug] = candidateSlug
                        it[company_size] = update.companySize
                        it[referral_source] = update.referralSource
                        it[utm_source] = update.utmSource
                        it[utm_medium] = update.utmMedium
                        it[utm_campaign] = update.utmCampaign
                        it[utm_content] = update.utmContent
                        it[utm_term] = update.utmTerm
                    }
                    check(orgUpdated == 1) { "Organization with id=${update.orgId} not found" }
                    val userUpdated = Users.update({ Users.id eq update.userId }) {
                        it[onboarding_completed] = true
                    }
                    check(userUpdated == 1) { "User with id=${update.userId} not found" }
                }
                return candidateSlug
            } catch (e: ExposedSQLException) {
                val isSlugConflict = e.cause?.message?.contains("organizations_slug", ignoreCase = true) == true
                if (!isSlugConflict) throw e
                slug = "${update.baseSlug}-$suffix"
                suffix++
            }
        }
        throw IllegalStateException(
            "Could not generate a unique slug for '${update.baseSlug}' after $MAX_SLUG_RETRIES attempts"
        )
    }
}
