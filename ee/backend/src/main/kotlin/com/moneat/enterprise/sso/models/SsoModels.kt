// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See enterprise/LICENSE for license terms.

package com.moneat.enterprise.sso.models

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
