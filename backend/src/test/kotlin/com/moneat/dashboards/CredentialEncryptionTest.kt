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

package com.moneat.dashboards

import com.moneat.dashboards.services.CredentialEncryption
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class CredentialEncryptionTest {

    @BeforeTest
    fun setup() {
        // Set a test encryption key for the tests
        System.setProperty("DATA_SOURCE_ENCRYPTION_KEY", "test-encryption-key-for-credential-tests-32chars-minimum")
    }

    @Test
    fun `encrypt and decrypt roundtrip`() {
        val plaintext = """{"username":"admin","password":"s3cr3t!@#$%"}"""
        val encrypted = CredentialEncryption.encrypt(plaintext)
        val decrypted = CredentialEncryption.decrypt(encrypted)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `encrypted value differs from plaintext`() {
        val plaintext = "my-password-123"
        val encrypted = CredentialEncryption.encrypt(plaintext)
        assertNotEquals(plaintext, encrypted)
    }

    @Test
    fun `each encryption produces different ciphertext due to random IV`() {
        val plaintext = "same-input"
        val encrypted1 = CredentialEncryption.encrypt(plaintext)
        val encrypted2 = CredentialEncryption.encrypt(plaintext)
        assertNotEquals(encrypted1, encrypted2)
        // But both decrypt to same value
        assertEquals(plaintext, CredentialEncryption.decrypt(encrypted1))
        assertEquals(plaintext, CredentialEncryption.decrypt(encrypted2))
    }

    @Test
    fun `encrypted value is base64 encoded`() {
        val encrypted = CredentialEncryption.encrypt("test")
        // Should be valid base64
        val decoded = java.util.Base64.getDecoder().decode(encrypted)
        assertTrue(decoded.size > 12) // At least IV_LENGTH + some ciphertext
    }

    @Test
    fun `decrypt with tampered data fails`() {
        val encrypted = CredentialEncryption.encrypt("test-value")
        val bytes = java.util.Base64.getDecoder().decode(encrypted)
        // Tamper with a byte in the ciphertext portion
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0xFF).toByte()
        val tampered = java.util.Base64.getEncoder().encodeToString(bytes)
        assertFailsWith<Exception> { CredentialEncryption.decrypt(tampered) }
    }

    @Test
    fun `decrypt with invalid base64 fails`() {
        assertFailsWith<Exception> { CredentialEncryption.decrypt("not-valid-base64!!!") }
    }

    @Test
    fun `decrypt with too short data fails`() {
        val shortData = java.util.Base64.getEncoder().encodeToString(ByteArray(5))
        assertFailsWith<Exception> { CredentialEncryption.decrypt(shortData) }
    }

    @Test
    fun `handles empty string`() {
        val plaintext = ""
        val encrypted = CredentialEncryption.encrypt(plaintext)
        assertEquals(plaintext, CredentialEncryption.decrypt(encrypted))
    }

    @Test
    fun `handles unicode characters`() {
        val plaintext = "пароль密码パスワード🔑"
        val encrypted = CredentialEncryption.encrypt(plaintext)
        assertEquals(plaintext, CredentialEncryption.decrypt(encrypted))
    }

    @Test
    fun `handles long credentials`() {
        val plaintext = "a".repeat(10000)
        val encrypted = CredentialEncryption.encrypt(plaintext)
        assertEquals(plaintext, CredentialEncryption.decrypt(encrypted))
    }

    @Test
    fun `handles JSON with special characters`() {
        val json = """{"api_key":"sk-abc123==","password":"p@ss\"w0rd\\n"}"""
        val encrypted = CredentialEncryption.encrypt(json)
        assertEquals(json, CredentialEncryption.decrypt(encrypted))
    }
}
