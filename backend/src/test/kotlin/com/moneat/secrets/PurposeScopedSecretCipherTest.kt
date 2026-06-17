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

package com.moneat.secrets

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import javax.crypto.AEADBadTagException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class PurposeScopedSecretCipherTest {
    private fun cipher(
        purpose: SecretVaultPurpose = SecretVaultPurpose.DATA_IMPORT,
        activeKeyId: String = "v1",
        keks: Map<String, String> = mapOf("v1" to "primary-kek-secret-aaaaaaaaaaaaaaaa")
    ) = PurposeScopedSecretCipher(
        purpose,
        activeKeyId,
        keks.mapValues { PurposeScopedSecretCipher.deriveKek(it.value) }
    )

    @Test
    fun `round-trips a secret for a purpose`() {
        val subject = cipher()
        val secret = "revenuecat-api-key-12345"

        val envelope = subject.encrypt(secret, organizationId = 42)

        assertEquals(secret, subject.decrypt(envelope, organizationId = 42))
    }

    @Test
    fun `envelope does not contain plaintext`() {
        val subject = cipher()
        val secret = "totally-secret-value"

        val envelope = subject.encrypt(secret, organizationId = 1)

        assertFalse(envelope.contains(secret))
    }

    @Test
    fun `encryption is non-deterministic`() {
        val subject = cipher()

        assertNotEquals(subject.encrypt("same", 1), subject.encrypt("same", 1))
    }

    @Test
    fun `decrypting with a different purpose fails`() {
        val secret = "same-secret-material"
        val keks = mapOf("v1" to secret)
        val dataImport = cipher(SecretVaultPurpose.DATA_IMPORT, keks = keks)
        val notification = cipher(SecretVaultPurpose.NOTIFICATION, keks = keks)

        val envelope = dataImport.encrypt("secret", organizationId = 1)

        assertFailsWith<AEADBadTagException> { notification.decrypt(envelope, organizationId = 1) }
    }

    @Test
    fun `decrypting with a different organization fails`() {
        val subject = cipher()
        val envelope = subject.encrypt("secret", organizationId = 1)

        assertFailsWith<AEADBadTagException> { subject.decrypt(envelope, organizationId = 2) }
    }

    @Test
    fun `supports KEK rotation with overlapping key ids`() {
        val old = cipher(
            activeKeyId = "v1",
            keks = mapOf("v1" to "old-kek-secret-aaaaaaaaaaaaaaaaaa")
        )
        val envelope = old.encrypt("secret", organizationId = 7)
        val rotated = cipher(
            activeKeyId = "v2",
            keks = mapOf(
                "v2" to "new-kek-secret-bbbbbbbbbbbbbbbbbb",
                "v1" to "old-kek-secret-aaaaaaaaaaaaaaaaaa"
            )
        )

        assertEquals("v1", rotated.keyIdOf(envelope))
        assertEquals("secret", rotated.decrypt(envelope, organizationId = 7))

        val rewrapped = rotated.rewrapToActive(envelope, organizationId = 7)
        assertEquals("v2", rotated.keyIdOf(rewrapped))
        assertEquals("secret", rotated.decrypt(rewrapped, organizationId = 7))
    }

    @Test
    fun `rejects key ids containing envelope separator`() {
        assertFailsWith<IllegalArgumentException> {
            cipher(activeKeyId = "v1:bad", keks = mapOf("v1:bad" to "primary-kek-secret-aaaaaaaaaaaaaaaa"))
        }

        assertFailsWith<IllegalArgumentException> {
            cipher(keks = mapOf("v1" to "primary-kek-secret-aaaaaaaaaaaaaaaa", "old:bad" to "old-secret-aaaaaaaaaa"))
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    fun `fromEnv rejects reusing reserved application secrets`() {
        val reusedSecret = "reserved-secret-value-aaaaaaaaaaaaaaaa"
        withSystemProperties(
            mapOf(
                "DATA_IMPORT_CONNECTOR_KEK" to reusedSecret,
                "JWT_SECRET" to reusedSecret
            )
        ) {
            assertFailsWith<IllegalArgumentException> {
                PurposeScopedSecretCipher.fromEnv(SecretVaultPurpose.DATA_IMPORT)
            }
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    fun `fromEnv rejects malformed previous secrets`() {
        withSystemProperties(
            mapOf(
                "DATA_IMPORT_CONNECTOR_KEK" to "primary-secret-value-aaaaaaaaaaaaaaaa",
                "DATA_IMPORT_CONNECTOR_KEK_PREVIOUS" to "v1=previous-secret-value-aaaaaaaaaaaaa"
            )
        ) {
            assertFailsWith<IllegalArgumentException> {
                PurposeScopedSecretCipher.fromEnv(SecretVaultPurpose.DATA_IMPORT)
            }
        }

        withSystemProperties(
            mapOf(
                "DATA_IMPORT_CONNECTOR_KEK" to "primary-secret-value-bbbbbbbbbbbbbbbb",
                "DATA_IMPORT_CONNECTOR_KEK_PREVIOUS" to "=previous-secret-value-bbbbbbbbbbbbb"
            )
        ) {
            assertFailsWith<IllegalArgumentException> {
                PurposeScopedSecretCipher.fromEnv(SecretVaultPurpose.DATA_IMPORT)
            }
        }
    }

    private fun withSystemProperties(properties: Map<String, String>, block: () -> Unit) {
        val previousValues = properties.keys.associateWith { key -> System.getProperty(key) }
        properties.forEach { (key, value) -> System.setProperty(key, value) }
        try {
            block()
        } finally {
            previousValues.forEach { (key, value) ->
                if (value == null) {
                    System.clearProperty(key)
                } else {
                    System.setProperty(key, value)
                }
            }
        }
    }
}
