// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.license

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LicenseValidatorTest {

    // Test RSA public key — paired with the test private key used to sign the keys below.
    // This is NOT the production key; it is safe to commit and exists only for testing.
    private val testPublicKeyPem = """
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxYXq+6vkPZVpKmKeeoxD
SUHRcW+JzilCvN56uw/EX0152K+6AllpdAJbzrVpEVUhpmYiJ4voQREZ6FXHlP5M
Aw5W7Wc0LjOoa31eGfCKD36C0jzNanEaVjCN7adGEjFg3xW4/nitvBLPfr0ONXd4
ulKAV0EWeXo2e3NQsgpBkh0oQ0ftIMSjm6zMPRk8bs/lKs2G8ljgcuXhEKTfCPIf
BOeY/nFVSV8VMucranSkAfjIaP6naulv1Du2Oe/HtFzy6hkvMFJJaUbXH/WoLWpM
9LwW2JEssiQdETNGhaq2ljFzgeuaB6oKjx9ekVzl6OxZOXMYvjJ2JV9qkIrpGKVz
aQIDAQAB
-----END PUBLIC KEY-----
    """.trimIndent()

    private val validator = LicenseValidator(testPublicKeyPem)

    // Generated with scripts/sign-license.sh using the test private key.
    // customer="Test Corp", plan=enterprise, features=[sso,oncall], expires=2099-12-31
    private val validKey =
        "eyJjdXN0b21lciI6IlRlc3QgQ29ycCIsInBsYW4iOiJlbnRlcnByaXNlIiwiZmVhdHVyZXMiOlsic3NvIiwib25jYWxsIl0s" +
            "Imlzc3VlZEF0IjoiMjAyNi0wMy0wNCIsImV4cGlyZXNBdCI6IjIwOTktMTItMzEifQ.JpMAkRnHbxQKde_sgbmugxRLa_bdT6r" +
            "58P807ooxFXtG1qT9_lwtjniINkh7dFah2EVMIeU2gIYQhry33hbdSZygmcvn87H78nBRdu_iJq27WYyTVRBZMu9dWkXoRHTz-0" +
            "okHULYa-oAZX5pEKL6xPh6uC9TUgef8bgUdvSJA-FaSAiCZM08yHPuFY1A8Q838tQJkQuFqHrA2FcAbO_1NGsO891y_jndVZf6" +
            "WrEGTH0ywGwrbXnlewr7uMurtWiBM53pflyHX1fNT5nxH0jYfmOxbxXWSKM9BGlhZ7wSANEA8Z1YfS7lGCsBqyxnC1iAA0yps2" +
            "2uMPw4MZSnx2hVHw"

    // Same payload but expiresAt=2020-01-01 (in the past), signed with the test private key
    private val expiredKey =
        "eyJjdXN0b21lciI6IlRlc3QgQ29ycCIsInBsYW4iOiJwcm8iLCJmZWF0dXJlcyI6WyJzc28iXSwiaXNzdWVkQXQiOiIyMDI2" +
            "LTAzLTA0IiwiZXhwaXJlc0F0IjoiMjAyMC0wMS0wMSJ9.hlkHBUjqQZt3nR9TaGV_WSWAcRCRH2RDW6hQvSpvz1ZglLaSCa9O0" +
            "8Xh3ywEx0_Tvdca9cDu2tY5YaO_qNVJhZelfPYtp6JyAmTeq9DB9dMuo-KncL156saL16NAByJPGJg1cQRJtkd-5Ke7a2cwSH9E" +
            "bhHYNP6uyxqiDOvRJG9gtWtDp6USjneZswQZZ7hmg8sEQs4SuB7415V1JiH5AYq0DAljT4dYIr0gOy0gg3NkfKyuqT8jcfR8ya" +
            "NxR_6EDSMV4ZJX6cbaytHFS2i4YpQlEXreR735N_QVdg7Qix48HQFMq1bdcUOJVeQbDqfPxSxqOaneKTitZZNe4oaM1A"

    @Test
    fun `valid key returns license info`() {
        val info = validator.validate(validKey)
        assertNotNull(info)
        assertEquals("Test Corp", info.customer)
        assertEquals("enterprise", info.plan)
        assertTrue("sso" in info.features)
        assertTrue("oncall" in info.features)
    }

    @Test
    fun `expired key returns null`() {
        assertNull(validator.validate(expiredKey))
    }

    @Test
    fun `tampered payload returns null`() {
        // Flip one char in the payload section
        val tampered = validKey.replaceFirst('e', 'f')
        assertNull(validator.validate(tampered))
    }

    @Test
    fun `blank key returns null`() {
        assertNull(validator.validate(""))
        assertNull(validator.validate("   "))
    }

    @Test
    fun `garbage key returns null`() {
        assertNull(validator.validate("not.a.real.key"))
    }
}
