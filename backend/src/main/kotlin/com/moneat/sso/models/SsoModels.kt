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

package com.moneat.sso.models

import kotlinx.serialization.Serializable

// SsoConfigurations and UserSsoLinks table objects are defined in core
// (OnCallSharedModels.kt) to allow core code to query SSO tables.

// Enums
enum class SsoProviderType {
    SAML,
    OIDC,
    ;

    companion object {
        fun fromString(value: String): SsoProviderType =
            when (value.lowercase()) {
                "saml" -> SAML
                "oidc" -> OIDC
                else -> throw IllegalArgumentException("Invalid SSO provider type: $value")
            }
    }
}

// DTOs
@Serializable
data class SsoInitRequest(
    val email: String? = null,
    val orgSlug: String? = null,
)

@Serializable
data class SsoInitResponse(
    val redirectUrl: String,
    val providerType: String,
    val state: String? = null,
)

@Serializable
data class SsoConfigRequest(
    val providerType: String,
    val isEnabled: Boolean = true,
    // SAML fields
    val idpEntityId: String? = null,
    val idpSsoUrl: String? = null,
    val idpCertificate: String? = null,
    // OIDC fields
    val oidcIssuerUrl: String? = null,
    val oidcClientId: String? = null,
    val oidcClientSecret: String? = null,
    // Shared
    val emailDomain: String? = null,
    val requireSso: Boolean = false,
)

@Serializable
data class SsoConfigResponse(
    val id: Int,
    val organizationId: Int,
    val providerType: String,
    val isEnabled: Boolean,
    // SAML fields
    val idpEntityId: String? = null,
    val idpSsoUrl: String? = null,
    val idpCertificate: String? = null,
    val spEntityId: String? = null,
    // OIDC fields (excluding client secret for security)
    val oidcIssuerUrl: String? = null,
    val oidcClientId: String? = null,
    val hasClientSecret: Boolean = false,
    // Shared
    val emailDomain: String? = null,
    val emailDomainVerified: Boolean = false,
    val emailDomainVerificationRecordName: String? = null,
    val emailDomainVerificationToken: String? = null,
    val requireSso: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class SsoCallbackData(
    val token: String,
    val email: String,
    val name: String,
)
