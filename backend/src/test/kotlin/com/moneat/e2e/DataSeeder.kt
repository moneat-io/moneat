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

package com.moneat.e2e

import com.moneat.config.EnvConfig
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.ProjectKeys
import com.moneat.shared.models.Projects
import com.moneat.shared.models.Users
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID

/**
 * E2E Data Seeder for Moneat
 *
 * Seeds the PostgreSQL database with test data for E2E testing:
 * - Test users
 * - Organizations
 * - Projects (Android E2E, KMP E2E)
 * - Project keys with DSNs
 */
object DataSeeder {

    private fun hashPassword(password: String): String {
        return BCrypt.hashpw(password, BCrypt.gensalt())
    }

    private fun generateKey(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }

    fun seed() {
        println("Starting E2E data seeding...")

        // Initialize environment config
        EnvConfig.initialize()

        // Connect to database
        val dbUrl =
            EnvConfig.get("POSTGRES_URL")
                ?: "jdbc:postgresql://localhost:5499/moneat"
        val dbUser = EnvConfig.get("POSTGRES_USER") ?: "moneat"
        val dbPassword = EnvConfig.get("POSTGRES_PASSWORD") ?: "moneat_dev_password"

        Database.connect(
            url = dbUrl,
            driver = "org.postgresql.Driver",
            user = dbUser,
            password = dbPassword
        )

        println("Connected to database: $dbUrl")

        transaction {
            // Check if already seeded
            val existingUser = Users.selectAll().where { Users.email eq "e2e-test@moneat.dev" }.firstOrNull()
            if (existingUser != null) {
                println("E2E data already exists. Skipping...")
                return@transaction
            }

            println("Creating test users...")
            val passwordHash = hashPassword("e2e-test-password")

            // Create test users
            val user1Id =
                Users.insert {
                    it[email] = "e2e-test@moneat.dev"
                    it[password_hash] = passwordHash
                    it[name] = "E2E Test User"
                    it[email_verified] = true
                    it[onboarding_completed] = true
                } get Users.id

            val user2Id =
                Users.insert {
                    it[email] = "e2e-user2@moneat.dev"
                    it[password_hash] = passwordHash
                    it[name] = "E2E User 2"
                    it[email_verified] = true
                    it[onboarding_completed] = true
                } get Users.id

            val user3Id =
                Users.insert {
                    it[email] = "e2e-user3@moneat.dev"
                    it[password_hash] = passwordHash
                    it[name] = "E2E User 3"
                    it[email_verified] = true
                    it[onboarding_completed] = true
                } get Users.id

            println("Created users: $user1Id, $user2Id, $user3Id")

            // Create organization
            println("Creating organization...")
            val orgId =
                Organizations.insert {
                    it[name] = "E2E Testing Organization"
                    it[slug] = "e2e-testing"
                    it[company_size] = "1-10"
                } get Organizations.id

            println("Created organization: $orgId")

            // Add memberships
            println("Creating memberships...")
            Memberships.insert {
                it[user_id] = user1Id
                it[organization_id] = orgId
                it[role] = "owner"
            }

            Memberships.insert {
                it[user_id] = user2Id
                it[organization_id] = orgId
                it[role] = "member"
            }

            Memberships.insert {
                it[user_id] = user3Id
                it[organization_id] = orgId
                it[role] = "member"
            }

            // Create projects
            println("Creating projects...")
            val androidProjectId =
                Projects.insert {
                    it[organization_id] = orgId
                    it[name] = "Android E2E App"
                    it[slug] = "android-e2e"
                    it[framework] = "android"
                } get Projects.id

            val kmpProjectId =
                Projects.insert {
                    it[organization_id] = orgId
                    it[name] = "KMP E2E App"
                    it[slug] = "kmp-e2e"
                    it[framework] = "kotlin-multiplatform"
                } get Projects.id

            println("Created projects: Android=$androidProjectId, KMP=$kmpProjectId")

            // Create project keys
            println("Creating project keys...")
            val androidPublicKey = generateKey()
            val kmpPublicKey = generateKey()

            ProjectKeys.insert {
                it[project_id] = androidProjectId
                it[public_key] = androidPublicKey
                it[secret_key] = generateKey()
                it[is_active] = true
            }

            ProjectKeys.insert {
                it[project_id] = kmpProjectId
                it[public_key] = kmpPublicKey
                it[secret_key] = generateKey()
                it[is_active] = true
            }

            val backendUrl = EnvConfig.get("BACKEND_URL", "http://localhost:8080")
            val backendHost = backendUrl.removePrefix("http://").removePrefix("https://")

            println("\n=== E2E SETUP COMPLETE ===")
            println("Test Users:")
            println("  - e2e-test@moneat.dev / e2e-test-password (owner)")
            println("  - e2e-user2@moneat.dev / e2e-test-password (member)")
            println("  - e2e-user3@moneat.dev / e2e-test-password (member)")
            println("\nOrganization: E2E Testing Organization (slug: e2e-testing)")
            println("\nProjects:")
            println("  - Android E2E App (ID: $androidProjectId)")
            println("    DSN: http://$androidPublicKey@$backendHost/$androidProjectId")
            println("  - KMP E2E App (ID: $kmpProjectId)")
            println("    DSN: http://$kmpPublicKey@$backendHost/$kmpProjectId")
            println("\nNext steps:")
            println("1. Copy the DSNs above to e2e/Android/local.properties and e2e/KMP/local.properties")
            println("2. Build and run the Android and KMP apps")
            println("3. Trigger errors and check the Moneat dashboard")
            println("=========================\n")
        }
    }
}

fun main() {
    DataSeeder.seed()
}
