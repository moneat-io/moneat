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

    // Generated with scripts/sign-license.sh using the Moneat test private key.
    // customer="Test Corp", plan=enterprise, features=[sso,oncall], expires=2099-12-31
    private val validKey = "eyJjdXN0b21lciI6IlRlc3QgQ29ycCIsInBsYW4iOiJlbnRlcnByaXNlIiwiZmVhdHVyZXMiOlsic3NvIiwib25jYWxsIl0sImlzc3VlZEF0IjoiMjAyNi0wMy0wNCIsImV4cGlyZXNBdCI6IjIwOTktMTItMzEifQ.EAz4fRmOpGmU7WycrXM42fK5DhmRchsLXsbWzwpaw-kvGT4pSl99xQv2qd-6gXNylSTDwEmH6XrqE70Xa2F4Pl6nuBP711pUWL1VVZe-DwUbr26ez7jo2f72-7V9-0nhx4BI_fMgLVc5pj_l71PgoH8YPvUF0y6Gjx7N9U6WMxgrWkXOlhguy3ChT55c0EkkJ63L-BekJ2n6MijOaLwB7XeIxPon938TaOJEA8EB4yYK40zMax9ehVTjYtMaRAjF9B5YKv53z_Eg5Dz4phCWbzuxtxBCUA1cwpg8GC4r4-QKTtKu3EAfG8mAd_fHctfC0gZp5TeoB1KL01f7o5SH_A"

    // Same as above but expiresAt=2020-01-01 (in the past)
    private val expiredKey = "eyJjdXN0b21lciI6IlRlc3QgQ29ycCIsInBsYW4iOiJwcm8iLCJmZWF0dXJlcyI6WyJzc28iXSwiaXNzdWVkQXQiOiIyMDI2LTAzLTA0IiwiZXhwaXJlc0F0IjoiMjAyMC0wMS0wMSJ9.QicBhk5s50isDfGW2CS1ZKQ4oPC4-NunDFra91UAqAQnoWeznO7CfI9vM6f1Uhvs59skoPBaMUIG2UuDaaornERcn7WhoKEZzTMqiOk4xfSEREE2JrvqTMMvoMRjqIQNg5_4gLjmsTbRipjijUaY2RNFGmDjfRsPXIot2G9zPq7XmmW22LG-47NgIkhf_uLnnyAr8xjn5ojDlMo19FUEQfWBhZTqqm--nDfFRcah10nUV3D5hhJdAjMamMbMP-2Gaw8xqszY1jGFM-axcTNpzp91CH1DCDNIQYYfbrmlxkfZ32VrXZ_uqe5S3Z_6QjRHZQlS2Ll6TnEUKSBaG5Li7g"

    @Test
    fun `valid key returns license info`() {
        val info = LicenseValidator.validate(validKey)
        assertNotNull(info)
        assertEquals("Test Corp", info.customer)
        assertEquals("enterprise", info.plan)
        assertTrue("sso" in info.features)
        assertTrue("oncall" in info.features)
    }

    @Test
    fun `expired key returns null`() {
        assertNull(LicenseValidator.validate(expiredKey))
    }

    @Test
    fun `tampered payload returns null`() {
        // Flip one char in the payload section
        val tampered = validKey.replaceFirst('e', 'f')
        assertNull(LicenseValidator.validate(tampered))
    }

    @Test
    fun `blank key returns null`() {
        assertNull(LicenseValidator.validate(""))
        assertNull(LicenseValidator.validate("   "))
    }

    @Test
    fun `garbage key returns null`() {
        assertNull(LicenseValidator.validate("not.a.real.key"))
    }
}
