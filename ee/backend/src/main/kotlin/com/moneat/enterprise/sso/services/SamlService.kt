// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.sso.services

import com.moneat.config.RedisConfig
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.SsoConfigurations
import com.moneat.sso.models.SsoCallbackData
import com.moneat.sso.models.SsoInitResponse
import com.moneat.sso.services.SsoService
import com.moneat.utils.UrlValidator
import com.onelogin.saml2.authn.AuthnRequest
import com.onelogin.saml2.authn.SamlResponse
import com.onelogin.saml2.settings.Saml2Settings
import com.onelogin.saml2.settings.SettingsBuilder
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.Properties

private val logger = KotlinLogging.logger {}

/**
 * SAML-specific SSO service. Depends on core SsoService for shared logic
 * (state management, findOrCreateSsoUser, JWT generation, encryption).
 */
class SamlService {
    private val ssoService = SsoService()

    fun initSaml(
        email: String?,
        orgSlug: String?,
    ): SsoInitResponse {
        require(email != null || orgSlug != null) {
            "Either email or orgSlug must be provided"
        }

        return transaction {
            val ssoConfig =
                if (email != null) {
                    val domain = email.substringAfter("@")
                    SsoConfigurations
                        .selectAll()
                        .where {
                            (SsoConfigurations.emailDomain eq domain) and
                                (SsoConfigurations.isEnabled eq true) and
                                (SsoConfigurations.providerType eq "saml")
                        }.firstOrNull()
                } else {
                    val org =
                        Organizations
                            .selectAll()
                            .where { Organizations.slug eq orgSlug!! }
                            .firstOrNull()
                            ?: throw IllegalArgumentException(
                                "Organization not found"
                            )

                    SsoConfigurations
                        .selectAll()
                        .where {
                            (SsoConfigurations.organizationId eq org[Organizations.id]) and
                                (SsoConfigurations.isEnabled eq true) and
                                (SsoConfigurations.providerType eq "saml")
                        }.firstOrNull()
                }

            val config = requireNotNull(ssoConfig) {
                "SAML SSO is not configured for this organization"
            }

            val orgId = config[SsoConfigurations.organizationId]
            val state = ssoService.generateSecureState(orgId)
            val redirectUrl = generateSamlRequest(config, state)
            SsoInitResponse(redirectUrl, "saml", state)
        }
    }

    fun handleSamlResponse(
        samlResponse: String,
        relayState: String?,
    ): SsoCallbackData =
        transaction {
            val stateData =
                if (relayState != null) {
                    ssoService.decodeState(relayState)
                } else {
                    throw IllegalArgumentException(
                        "Missing RelayState parameter"
                    )
                }

            val orgId = stateData.orgId

            val requestId =
                if (RedisConfig.isInitialized()) {
                    val key =
                        "${SsoService.SSO_SAML_AUTHN_REQUEST_KEY_PREFIX}${stateData.nonce}"
                    val rid = RedisConfig.sync().get(key)
                    RedisConfig.sync().del(key)
                    rid
                } else {
                    null
                }
                ?: throw IllegalStateException(
                    "Missing SAML AuthnRequest binding (Redis unavailable or expired). " +
                        "Expected key prefix ${SsoService.SSO_SAML_AUTHN_REQUEST_KEY_PREFIX}"
                )

            val ssoConfig =
                SsoConfigurations
                    .selectAll()
                    .where { SsoConfigurations.organizationId eq orgId }
                    .firstOrNull()
                    ?: throw IllegalArgumentException(
                        "SSO configuration not found"
                    )

            val settings = buildSamlSettings(ssoConfig)

            val samlResponseObj = SamlResponse(settings, null)
            samlResponseObj.loadXmlFromBase64(samlResponse)

            if (!samlResponseObj.isValid(requestId)) {
                val error = samlResponseObj.error
                logger.error { "SAML authentication failed: $error" }
                throw IllegalArgumentException(
                    "SAML authentication failed: $error"
                )
            }

            val attributes = samlResponseObj.attributes
            val email =
                attributes["email"]?.firstOrNull()
                    ?: attributes[EMAIL_CLAIM_URI]?.firstOrNull()
                    ?: throw IllegalArgumentException(
                        "No email found in SAML response"
                    )

            val name =
                attributes["name"]?.firstOrNull()
                    ?: attributes["displayName"]?.firstOrNull()
                    ?: attributes[NAME_CLAIM_URI]?.firstOrNull()
                    ?: email.substringBefore("@")

            val externalId =
                samlResponseObj.nameId
                    ?: throw IllegalArgumentException(
                        "No NameID found in SAML response"
                    )

            val (token, userEmail, userName) =
                ssoService.findOrCreateSsoUser(
                    email = email,
                    name = name,
                    externalId = externalId,
                    ssoConfigId = ssoConfig[SsoConfigurations.id],
                    organizationId = orgId,
                )

            SsoCallbackData(token, userEmail, userName)
        }

    fun getSamlMetadata(orgSlug: String?): String =
        transaction {
            requireNotNull(orgSlug) { "Organization slug is required" }
            val org = Organizations.selectAll()
                .where { Organizations.slug eq orgSlug }
                .firstOrNull()
                ?: throw IllegalArgumentException("Organization not found")

            val ssoConfig = SsoConfigurations.selectAll()
                .where {
                    (SsoConfigurations.organizationId eq org[Organizations.id]) and
                        (SsoConfigurations.providerType eq "saml") and
                        (SsoConfigurations.isEnabled eq true)
                }.firstOrNull()

            val config = requireNotNull(ssoConfig) {
                "SAML SSO is not configured for this organization"
            }

            val settings = buildSamlSettings(config)
            val metadata = settings.spMetadata
            val errors = Saml2Settings.validateMetadata(metadata)

            if (errors.isNotEmpty()) {
                logger.error {
                    "SAML metadata validation errors: " +
                        errors.joinToString(", ")
                }
                throw IllegalArgumentException(
                    "Failed to generate valid SAML metadata"
                )
            }

            metadata
        }

    private fun generateSamlRequest(
        ssoConfig: ResultRow,
        state: String,
    ): String {
        val settings = buildSamlSettings(ssoConfig)

        val authnRequest = AuthnRequest(settings)
        val requestId = authnRequest.id
        val nonceB64 = ssoService.peekVerifiedNonceWithoutConsume(state)
        if (RedisConfig.isInitialized()) {
            RedisConfig.sync().setex(
                "${SsoService.SSO_SAML_AUTHN_REQUEST_KEY_PREFIX}$nonceB64",
                SSO_SAML_AUTHN_TTL_SECONDS,
                requestId,
            )
        }
        val samlRequest = authnRequest.getEncodedAuthnRequest()

        val idpSsoUrl =
            ssoConfig[SsoConfigurations.idpSsoUrl]
                ?: throw IllegalArgumentException(
                    "IDP SSO URL not configured"
                )
        UrlValidator.validateExternalUrl(idpSsoUrl)

        val separator = if (idpSsoUrl.contains("?")) "&" else "?"
        return "$idpSsoUrl${separator}SAMLRequest=" +
            "${java.net.URLEncoder.encode(samlRequest, "UTF-8")}" +
            "&RelayState=" +
            java.net.URLEncoder.encode(state, "UTF-8")
    }

    private fun buildSamlSettings(ssoConfig: ResultRow): Saml2Settings {
        val idpEntityId =
            ssoConfig[SsoConfigurations.idpEntityId]
                ?: throw IllegalArgumentException(
                    "IDP Entity ID not configured"
                )
        val idpSsoUrl =
            ssoConfig[SsoConfigurations.idpSsoUrl]
                ?: throw IllegalArgumentException(
                    "IDP SSO URL not configured"
                )
        val idpCertificate =
            ssoConfig[SsoConfigurations.idpCertificate]
                ?: throw IllegalArgumentException(
                    "IDP Certificate not configured"
                )
        val spEntityId =
            ssoConfig[SsoConfigurations.spEntityId]
                ?: "${ssoService.baseUrl}/auth/sso/saml/metadata"

        val acsUrl = "${ssoService.baseUrl}/auth/sso/saml/acs"

        val samlProperties = Properties()

        samlProperties.setProperty(
            "onelogin.saml2.sp.entityid", spEntityId
        )
        samlProperties.setProperty(
            "onelogin.saml2.sp.assertion_consumer_service.url", acsUrl
        )
        samlProperties.setProperty(
            "onelogin.saml2.sp.nameidformat",
            "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress"
        )

        samlProperties.setProperty(
            "onelogin.saml2.idp.entityid", idpEntityId
        )
        samlProperties.setProperty(
            "onelogin.saml2.idp.single_sign_on_service.url", idpSsoUrl
        )
        samlProperties.setProperty(
            "onelogin.saml2.idp.x509cert",
            cleanCertificate(idpCertificate)
        )

        samlProperties.setProperty("onelogin.saml2.strict", "true")
        samlProperties.setProperty("onelogin.saml2.debug", "false")
        samlProperties.setProperty(
            "onelogin.saml2.security.authnrequest_signed", "false"
        )
        samlProperties.setProperty(
            "onelogin.saml2.security.logoutrequest_signed", "false"
        )
        samlProperties.setProperty(
            "onelogin.saml2.security.logoutresponse_signed", "false"
        )
        samlProperties.setProperty(
            "onelogin.saml2.security.want_messages_signed", "false"
        )
        samlProperties.setProperty(
            "onelogin.saml2.security.want_assertions_signed", "true"
        )
        samlProperties.setProperty(
            "onelogin.saml2.security.want_assertions_encrypted", "false"
        )
        samlProperties.setProperty(
            "onelogin.saml2.security.want_nameid_encrypted", "false"
        )
        samlProperties.setProperty(
            "onelogin.saml2.security.sign_metadata", "false"
        )

        return SettingsBuilder().fromProperties(samlProperties).build()
    }

    private fun cleanCertificate(certificate: String): String =
        certificate
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace("\\s".toRegex(), "")

    companion object {
        private const val SSO_SAML_AUTHN_TTL_SECONDS = 600L

        private const val EMAIL_CLAIM_URI =
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/" +
                "emailaddress"
        private const val NAME_CLAIM_URI =
            "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name"
    }
}
