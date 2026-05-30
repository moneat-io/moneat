// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.workflows.crypto

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class ConnectionCredentialCipherTest {

    private fun cipher(
        activeKeyId: String = "v1",
        keks: Map<String, String> = mapOf("v1" to "primary-kek-secret-aaaaaaaaaaaaaaaa")
    ) = ConnectionCredentialCipher(activeKeyId, keks.mapValues { ConnectionCredentialCipher.deriveKek(it.value) })

    @Test
    fun `round-trips a secret`() {
        val subject = cipher()
        val secret = "xoxb-super-secret-token-12345"
        val envelope = subject.encrypt(secret, organizationId = 42)
        assertEquals(secret, subject.decrypt(envelope, organizationId = 42))
    }

    @Test
    fun `envelope does not contain the plaintext secret`() {
        val subject = cipher()
        val secret = "totally-secret-value"
        assertFalse(subject.encrypt(secret, organizationId = 1).contains(secret))
    }

    @Test
    fun `encryption is non-deterministic`() {
        val subject = cipher()
        assertNotEquals(subject.encrypt("same", 1), subject.encrypt("same", 1))
    }

    @Test
    fun `decrypting with a different organization fails`() {
        val subject = cipher()
        val envelope = subject.encrypt("secret", organizationId = 1)
        assertFailsWith<Exception> { subject.decrypt(envelope, organizationId = 2) }
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val subject = cipher()
        val envelope = subject.encrypt("secret", organizationId = 1)
        val tampered = envelope.dropLast(2) + "AA"
        assertFailsWith<Exception> { subject.decrypt(tampered, organizationId = 1) }
    }

    @Test
    fun `supports KEK rotation with overlapping key ids`() {
        val old = cipher(activeKeyId = "v1", keks = mapOf("v1" to "old-kek-secret-aaaaaaaaaaaaaaaaaa"))
        val envelope = old.encrypt("secret", organizationId = 7)

        // New active key, old key retained so existing ciphertext still decrypts.
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
    fun `decrypting with an unknown key id fails`() {
        val envelope = cipher(activeKeyId = "v1", keks = mapOf("v1" to "kek-a-aaaaaaaaaaaaaaaaaaaaaaaaaa"))
            .encrypt("secret", organizationId = 1)
        val other = cipher(activeKeyId = "v9", keks = mapOf("v9" to "kek-z-zzzzzzzzzzzzzzzzzzzzzzzzzz"))
        assertFailsWith<IllegalStateException> { other.decrypt(envelope, organizationId = 1) }
    }

    @Test
    fun `fromEnv rejects reusing reserved application secrets`() {
        val reusedSecret = "reserved-secret-value-aaaaaaaaaaaaaaaa"
        withSystemProperties(
            mapOf(
                "WORKFLOWS_CONNECTION_KEK" to reusedSecret,
                "DATA_SOURCE_ENCRYPTION_KEY" to reusedSecret,
                "JWT_SECRET" to "different-secret-value-bbbbbbbbbbb"
            )
        ) {
            assertFailsWith<IllegalArgumentException> { ConnectionCredentialCipher.fromEnv() }
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
