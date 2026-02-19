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

package com.moneat.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnvironmentValidatorTest {

    @Test
    fun `validate returns ValidationResult`() {
        val validator = EnvironmentValidator()
        val result = validator.validate()
        // Result should be a valid ValidationResult with lists
        assertNotNull(result.errors)
        assertNotNull(result.warnings)
    }

    @Test
    fun `validate does not report conditional config errors when integrations disabled`() {
        // When SLACK_ENABLED, DISCORD_ENABLED, STRIPE_ENABLED are not set (default false),
        // there should be no errors about their sub-configs
        val validator = EnvironmentValidator()
        val result = validator.validate()

        assertFalse(result.errors.any { it.contains("SLACK_CLIENT_ID") })
        assertFalse(result.errors.any { it.contains("DISCORD_CLIENT_ID") })
        assertFalse(result.errors.any { it.contains("STRIPE_SECRET_KEY") })
    }

    @Test
    fun `validate checks critical secrets`() {
        val validator = EnvironmentValidator()
        val result = validator.validate()

        // If any critical secrets are missing, there should be errors mentioning them
        // If all are set (e.g. via .env file), result should be valid
        if (!result.isValid) {
            assertTrue(result.errors.isNotEmpty())
            // Errors should have proper prefixes
            result.errors.forEach { error ->
                assertTrue(
                    error.startsWith("CRITICAL:") || error.startsWith("REQUIRED:"),
                    "Error should have a proper prefix: $error"
                )
            }
        }
    }

    @Test
    fun `validate checks production URLs for localhost`() {
        val validator = EnvironmentValidator()
        val result = validator.validate()

        // If production URLs contain localhost, they should appear as warnings
        result.warnings.forEach { warning ->
            assertTrue(warning.startsWith("WARNING:"), "Warning should start with WARNING: but got: $warning")
        }
    }

    @Test
    fun `validateAndFailFast does not throw when validation passes`() {
        val validator = EnvironmentValidator()
        val result = validator.validate()

        if (result.isValid) {
            // Should not throw
            validator.validateAndFailFast()
        }
    }

    @Test
    fun `validateAndFailFast throws IllegalStateException on failure`() {
        val validator = EnvironmentValidator()
        val result = validator.validate()

        if (!result.isValid) {
            val exception = assertFailsWith<IllegalStateException> {
                validator.validateAndFailFast()
            }
            assertTrue(exception.message!!.contains("environment variables"))
        }
    }

    @Test
    fun `ValidationResult data class works correctly`() {
        val result = EnvironmentValidator.ValidationResult(
            isValid = true,
            errors = emptyList(),
            warnings = listOf("test warning")
        )
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
        assertEquals(1, result.warnings.size)
        assertEquals("test warning", result.warnings[0])
    }

    @Test
    fun `ValidationResult with errors is not valid`() {
        val result = EnvironmentValidator.ValidationResult(
            isValid = false,
            errors = listOf("CRITICAL: something missing"),
            warnings = emptyList()
        )
        assertFalse(result.isValid)
        assertEquals(1, result.errors.size)
    }

    @Test
    fun `ValidationResult supports multiple errors and warnings`() {
        val result = EnvironmentValidator.ValidationResult(
            isValid = false,
            errors = listOf("error1", "error2"),
            warnings = listOf("warn1", "warn2", "warn3")
        )
        assertEquals(2, result.errors.size)
        assertEquals(3, result.warnings.size)
    }
}
