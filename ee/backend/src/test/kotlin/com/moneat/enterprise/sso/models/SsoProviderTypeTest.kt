// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.sso.models

import com.moneat.sso.models.SsoProviderType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SsoProviderTypeTest {

    @Test
    fun `fromString accepts saml in any case`() {
        assertEquals(SsoProviderType.SAML, SsoProviderType.fromString("saml"))
        assertEquals(SsoProviderType.SAML, SsoProviderType.fromString("SAML"))
        assertEquals(SsoProviderType.SAML, SsoProviderType.fromString("SaMl"))
    }

    @Test
    fun `fromString accepts oidc in any case`() {
        assertEquals(SsoProviderType.OIDC, SsoProviderType.fromString("oidc"))
        assertEquals(SsoProviderType.OIDC, SsoProviderType.fromString("OIDC"))
    }

    @Test
    fun `fromString rejects unknown provider`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            SsoProviderType.fromString("ldap")
        }
        assertEquals("Invalid SSO provider type: ldap", ex.message)
    }
}
