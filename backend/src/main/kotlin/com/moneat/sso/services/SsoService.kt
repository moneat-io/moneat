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

package com.moneat.sso.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.billing.services.PricingTierService
import com.moneat.config.EnvConfig
import com.moneat.config.RedisConfig
import com.moneat.enterprise.FeatureRegistry
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.OrgInvitations
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.SsoConfigurations
import com.moneat.shared.models.UserSsoLinks
import com.moneat.shared.models.Users
import com.moneat.sso.SsoForbiddenException
import com.moneat.sso.models.SsoCallbackData
import com.moneat.sso.models.SsoConfigRequest
import com.moneat.sso.models.SsoConfigResponse
import com.moneat.sso.models.SsoInitResponse
import com.moneat.sso.models.SsoProviderType
import com.moneat.utils.UrlValidator
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant
import com.nimbusds.oauth2.sdk.TokenRequest
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic
import com.nimbusds.oauth2.sdk.auth.Secret
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.openid.connect.sdk.OIDCTokenResponse
import com.nimbusds.openid.connect.sdk.OIDCTokenResponseParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.lettuce.core.RedisException
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.plugins.BadRequestException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.net.URI
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.naming.NamingException
import javax.naming.directory.InitialDirContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

private const val DISCOVERY_CACHE_TTL_MS = 3_600_000L // 1 hour
private const val ERROR_OIDC_ISSUER_URL_NOT_CONFIGURED = "OIDC issuer URL not configured"
private const val OIDC_DISCOVERY_PATH = "/.well-known/openid-configuration"
private const val OIDC_DISCOVERY_ERROR =
    "OIDC discovery failed. Use the provider issuer URL from its openid-configuration document. " +
        "For Authentik this looks like https://auth.example.com/application/o/<application-slug>/, " +
        "not just the Authentik domain."
private const val SSO_DNS_RECORD_PREFIX = "_moneat-sso"
private const val SSO_DNS_RECORD_VALUE_PREFIX = "moneat-sso="
private const val DOMAIN_VERIFICATION_TOKEN_BYTES = 32
private const val MAX_DOMAIN_LENGTH = 253
private const val MAX_DOMAIN_LABEL_LENGTH = 63
private val IPV4_DOMAIN_PATTERN = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
private val DOMAIN_LABEL_PATTERN = Regex("""^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$""")
private val PUBLIC_EMAIL_DOMAINS =
    setOf(
        "gmail.com",
        "googlemail.com",
        "outlook.com",
        "hotmail.com",
        "live.com",
        "msn.com",
        "yahoo.com",
        "icloud.com",
        "me.com",
        "mac.com",
        "proton.me",
        "protonmail.com",
        "aol.com",
        "gmx.com",
        "mail.com",
        "zoho.com",
        "yandex.com",
        "qq.com",
        "163.com",
        "126.com",
    )

@Serializable
data class SsoStateData(
    val nonce: String,
    val orgId: Int,
    val timestamp: Long,
)

/**
 * Cached OIDC discovery endpoints with expiry.
 */
private data class OidcDiscoveryCache(
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val fetchedAt: Long,
)

open class SsoService {
    private val config = ApplicationConfig("application.conf")
    private val jwtSecret = config.property("jwt.secret").getString()
    private val jwtIssuer = config.property("jwt.issuer").getString()
    private val jwtAudience = config.property("jwt.audience").getString()
    val baseUrl = config.propertyOrNull("app.baseUrl")?.getString()
        ?: "https://api.moneat.io"
    private val secureRandom = SecureRandom()
    private val pricingTierService = PricingTierService()
    private val httpClient = HttpClient(CIO)
    private val discoveryCache = ConcurrentHashMap<String, OidcDiscoveryCache>()

    private val encryptionKey: ByteArray by lazy {
        deriveAesKeyFromJwtSecret(jwtSecret)
    }

    private fun deriveAesKeyFromJwtSecret(secret: String): ByteArray {
        val salt = "moneat-sso-encryption-v1".toByteArray(Charsets.UTF_8)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(secret.toCharArray(), salt, PBKDF2_ITERATIONS, AES_KEY_LENGTH * BITS_PER_BYTE)
        val key = factory.generateSecret(spec)
        val encoded = key.encoded
        require(encoded.size >= AES_KEY_LENGTH) {
            "Derived key length mismatch"
        }
        return encoded.copyOf(AES_KEY_LENGTH)
    }

    suspend fun initSso(
        email: String?,
        orgSlug: String?,
    ): SsoInitResponse {
        require(email != null || orgSlug != null) {
            "Either email or orgSlug must be provided"
        }

        val pending =
            transaction {
                val ssoConfig =
                    if (email != null) {
                        findEnabledVerifiedConfigForDomain(domainFromEmail(email))
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
                                    (SsoConfigurations.isEnabled eq true)
                            }.firstOrNull()
                    }

                val config = requireNotNull(ssoConfig) {
                    "SSO is not configured for this email domain or organization"
                }

                val providerType = SsoProviderType.fromString(
                    config[SsoConfigurations.providerType]
                )
                val orgId = config[SsoConfigurations.organizationId]
                val state = generateSecureState(orgId)

                when (providerType) {
                    SsoProviderType.SAML -> {
                        require(FeatureRegistry.hasModule("SAML")) {
                            "SAML SSO requires an enterprise license"
                        }
                        PendingSsoInit.Saml(state)
                    }

                    SsoProviderType.OIDC -> {
                        PendingSsoInit.Oidc(config, state)
                    }
                }
            }

        return when (pending) {
            is PendingSsoInit.Saml ->
                SsoInitResponse("", "saml", pending.state)
            is PendingSsoInit.Oidc -> {
                val redirectUrl = generateOidcRequest(pending.config, pending.state)
                SsoInitResponse(redirectUrl, "oidc", pending.state)
            }
        }
    }

    suspend fun handleOidcCallback(
        code: String,
        state: String,
    ): SsoCallbackData {
        val stateData = decodeState(state)
        val orgId = stateData.orgId

        val oidc =
            transaction {
                val ssoConfig =
                    SsoConfigurations
                        .selectAll()
                        .where { SsoConfigurations.organizationId eq orgId }
                        .firstOrNull()
                        ?: throw IllegalArgumentException(
                            "SSO configuration not found"
                        )

                val issuerRaw =
                    ssoConfig[SsoConfigurations.oidcIssuerUrl]
                        ?: throw IllegalArgumentException(
                            ERROR_OIDC_ISSUER_URL_NOT_CONFIGURED
                        )
                val issuerUrl = issuerRaw.trim()
                require(issuerUrl.isNotEmpty()) {
                    ERROR_OIDC_ISSUER_URL_NOT_CONFIGURED
                }
                UrlValidator.validateExternalUrl(issuerUrl)
                val clientId =
                    ssoConfig[SsoConfigurations.oidcClientId]
                        ?: throw IllegalArgumentException(
                            "OIDC client ID not configured"
                        )
                val clientSecret =
                    decryptSecret(ssoConfig[SsoConfigurations.oidcClientSecret])
                        ?: throw IllegalArgumentException(
                            "OIDC client secret not configured"
                        )

                OidcCallbackConfig(
                    issuerUrl = issuerUrl,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    ssoConfigId = ssoConfig[SsoConfigurations.id],
                    organizationId = orgId,
                )
            }

        val endpoints = discoverOidcEndpoints(oidc.issuerUrl)
        val tokenEndpoint = URI(endpoints.tokenEndpoint)
        val redirectUri = URI("$baseUrl/auth/sso/oidc/callback")

        val authCode =
            com.nimbusds.oauth2.sdk
                .AuthorizationCode(code)
        val codeGrant = AuthorizationCodeGrant(authCode, redirectUri)

        @Suppress("DEPRECATION")
        val tokenRequest =
            TokenRequest(
                tokenEndpoint,
                ClientSecretBasic(
                    ClientID(oidc.clientId),
                    Secret(oidc.clientSecret),
                ),
                codeGrant,
            )

        val tokenResponse = OIDCTokenResponseParser.parse(
            tokenRequest.toHTTPRequest().send()
        )

        if (!tokenResponse.indicatesSuccess()) {
            val errorResponse = tokenResponse.toErrorResponse()
            logger.error {
                "OIDC token exchange failed: ${errorResponse.errorObject}"
            }
            throw IllegalArgumentException("OIDC token exchange failed")
        }

        val successResponse =
            tokenResponse.toSuccessResponse() as OIDCTokenResponse
        val idToken = successResponse.oidcTokens.idToken

        val email =
            idToken.jwtClaimsSet.getStringClaim("email")
                ?: throw IllegalArgumentException(
                    "No email found in ID token"
                )
        val emailVerified = idToken.jwtClaimsSet.getBooleanClaim("email_verified") == true
        require(emailVerified) {
            "OIDC email claim is not verified"
        }
        val name = idToken.jwtClaimsSet.getStringClaim("name")
            ?: email.substringBefore("@")
        val externalId = idToken.jwtClaimsSet.subject

        return transaction {
            val (token, userEmail, userName) =
                findOrCreateSsoUser(
                    email = email,
                    name = name,
                    externalId = externalId,
                    ssoConfigId = oidc.ssoConfigId,
                    organizationId = oidc.organizationId,
                )

            SsoCallbackData(token, userEmail, userName)
        }
    }

    private data class OidcCallbackConfig(
        val issuerUrl: String,
        val clientId: String,
        val clientSecret: String,
        val ssoConfigId: Int,
        val organizationId: Int,
    )

    private data class DomainVerificationPending(
        val domain: String,
        val token: String,
    )

    private sealed class PendingSsoInit {
        data class Saml(
            val state: String,
        ) : PendingSsoInit()

        data class Oidc(
            val config: ResultRow,
            val state: String,
        ) : PendingSsoInit()
    }

    fun configureSso(
        organizationId: Int,
        userId: Int,
        request: SsoConfigRequest,
    ): SsoConfigResponse {
        val providerType =
            try {
                SsoProviderType.fromString(request.providerType)
            } catch (e: IllegalArgumentException) {
                throw BadRequestException(e.message ?: "Invalid SSO provider type")
            }
        validateSsoAccess(organizationId, providerType)
        if (request.requireSso && !FeatureRegistry.hasModule("SAML")) {
            throw SsoForbiddenException(
                "SSO enforcement (Require SSO) requires an enterprise license"
            )
        }
        if (!isOrganizationOwner(organizationId, userId)) {
            throw SsoForbiddenException("Only organization owners can configure SSO")
        }
        validateSsoConfigRequest(providerType, request)
        val normalizedEmailDomain = request.emailDomain?.let { validateAndNormalizeEmailDomain(it) }

        return transaction {
            val spEntityId = "$baseUrl/auth/sso/saml/metadata"
            val encryptedSecret = request.oidcClientSecret?.let { encryptSecret(it) }
            val effectiveRequireSso = request.requireSso && FeatureRegistry.hasModule("SAML")
            persistSsoConfig(
                organizationId = organizationId,
                request = request,
                normalizedEmailDomain = normalizedEmailDomain,
                spEntityId = spEntityId,
                encryptedSecret = encryptedSecret,
                effectiveRequireSso = effectiveRequireSso,
            )
            getSsoConfig(organizationId)
                ?: throw IllegalStateException("Failed to retrieve SSO config after save")
        }
    }

    private fun isOrganizationOwner(organizationId: Int, userId: Int): Boolean =
        transaction {
            Memberships.selectAll()
                .where {
                    (Memberships.organization_id eq organizationId) and
                        (Memberships.user_id eq userId) and
                        (Memberships.role eq "owner")
                }.firstOrNull() != null
        }

    private fun validateSsoConfigRequest(
        providerType: SsoProviderType,
        request: SsoConfigRequest,
    ) {
        when (providerType) {
            SsoProviderType.SAML -> {
                val idpSsoUrl = request.idpSsoUrl
                if (
                    request.idpEntityId.isNullOrBlank() ||
                    idpSsoUrl.isNullOrBlank() ||
                    request.idpCertificate.isNullOrBlank()
                ) {
                    throw BadRequestException(
                        "SAML requires idpEntityId, idpSsoUrl, and idpCertificate"
                    )
                }
                try {
                    UrlValidator.validateExternalUrl(idpSsoUrl)
                } catch (e: UrlValidator.SsrfException) {
                    throw BadRequestException(e.message ?: "Invalid IdP SSO URL")
                }
            }
            SsoProviderType.OIDC -> {
                val oidcIssuerUrl = request.oidcIssuerUrl
                if (
                    oidcIssuerUrl.isNullOrBlank() ||
                    request.oidcClientId.isNullOrBlank() ||
                    request.oidcClientSecret.isNullOrBlank()
                ) {
                    throw BadRequestException(
                        "OIDC requires oidcIssuerUrl, oidcClientId, and oidcClientSecret"
                    )
                }
                try {
                    UrlValidator.validateExternalUrl(oidcIssuerUrl)
                } catch (e: UrlValidator.SsrfException) {
                    throw BadRequestException(e.message ?: "Invalid OIDC issuer URL")
                }
            }
        }
    }

    private fun persistSsoConfig(
        organizationId: Int,
        request: SsoConfigRequest,
        normalizedEmailDomain: String?,
        spEntityId: String,
        encryptedSecret: String?,
        effectiveRequireSso: Boolean,
    ) {
        val existing = SsoConfigurations.selectAll()
            .where { SsoConfigurations.organizationId eq organizationId }
            .firstOrNull()
        val existingDomain = existing?.get(SsoConfigurations.emailDomain)
        val domainChanged = existingDomain != normalizedEmailDomain
        val verificationToken = when {
            normalizedEmailDomain == null -> null
            domainChanged -> generateVerificationToken()
            existing?.get(SsoConfigurations.emailDomainVerificationToken) == null -> generateVerificationToken()
            else -> existing[SsoConfigurations.emailDomainVerificationToken]
        }

        if (existing != null) {
            SsoConfigurations.update({ SsoConfigurations.organizationId eq organizationId }) {
                it[SsoConfigurations.providerType] = request.providerType.lowercase()
                it[SsoConfigurations.isEnabled] = request.isEnabled
                it[SsoConfigurations.idpEntityId] = request.idpEntityId
                it[SsoConfigurations.idpSsoUrl] = request.idpSsoUrl
                it[SsoConfigurations.idpCertificate] = request.idpCertificate
                it[SsoConfigurations.spEntityId] = spEntityId
                it[SsoConfigurations.oidcIssuerUrl] = request.oidcIssuerUrl
                it[SsoConfigurations.oidcClientId] = request.oidcClientId
                encryptedSecret?.let { secret -> it[SsoConfigurations.oidcClientSecret] = secret }
                it[SsoConfigurations.emailDomain] = normalizedEmailDomain
                it[SsoConfigurations.emailDomainVerificationToken] = verificationToken
                if (domainChanged || normalizedEmailDomain == null) {
                    it[SsoConfigurations.emailDomainVerified] = false
                    it[SsoConfigurations.emailDomainVerifiedAt] = null
                    it[SsoConfigurations.emailDomainVerifiedBy] = null
                }
                it[SsoConfigurations.requireSso] = effectiveRequireSso
                it[SsoConfigurations.updatedAt] = Clock.System.now()
            }
        } else {
            SsoConfigurations.insert {
                it[SsoConfigurations.organizationId] = organizationId
                it[SsoConfigurations.providerType] = request.providerType.lowercase()
                it[SsoConfigurations.isEnabled] = request.isEnabled
                it[SsoConfigurations.idpEntityId] = request.idpEntityId
                it[SsoConfigurations.idpSsoUrl] = request.idpSsoUrl
                it[SsoConfigurations.idpCertificate] = request.idpCertificate
                it[SsoConfigurations.spEntityId] = spEntityId
                it[SsoConfigurations.oidcIssuerUrl] = request.oidcIssuerUrl
                it[SsoConfigurations.oidcClientId] = request.oidcClientId
                it[SsoConfigurations.oidcClientSecret] = encryptedSecret
                it[SsoConfigurations.emailDomain] = normalizedEmailDomain
                it[SsoConfigurations.emailDomainVerified] = false
                it[SsoConfigurations.emailDomainVerificationToken] = verificationToken
                it[SsoConfigurations.emailDomainVerifiedAt] = null
                it[SsoConfigurations.emailDomainVerifiedBy] = null
                it[SsoConfigurations.requireSso] = effectiveRequireSso
            }
        }
    }

    fun getSsoConfig(organizationId: Int): SsoConfigResponse? =
        transaction {
            SsoConfigurations
                .selectAll()
                .where { SsoConfigurations.organizationId eq organizationId }
                .firstOrNull()
                ?.let { row ->
                    val token = ensureDomainVerificationToken(row)
                    val emailDomain = row[SsoConfigurations.emailDomain]
                    SsoConfigResponse(
                        id = row[SsoConfigurations.id],
                        organizationId = row[SsoConfigurations.organizationId],
                        providerType = row[SsoConfigurations.providerType],
                        isEnabled = row[SsoConfigurations.isEnabled],
                        idpEntityId = row[SsoConfigurations.idpEntityId],
                        idpSsoUrl = row[SsoConfigurations.idpSsoUrl],
                        idpCertificate = row[SsoConfigurations.idpCertificate],
                        spEntityId = row[SsoConfigurations.spEntityId],
                        oidcIssuerUrl = row[SsoConfigurations.oidcIssuerUrl],
                        oidcClientId = row[SsoConfigurations.oidcClientId],
                        hasClientSecret = row[SsoConfigurations.oidcClientSecret] != null,
                        emailDomain = emailDomain,
                        emailDomainVerified = row[SsoConfigurations.emailDomainVerified],
                        emailDomainVerificationRecordName = emailDomain?.let { domainVerificationRecordName(it) },
                        emailDomainVerificationToken = token,
                        requireSso = row[SsoConfigurations.requireSso],
                        createdAt = row[SsoConfigurations.createdAt].toString(),
                        updatedAt = row[SsoConfigurations.updatedAt].toString(),
                    )
                }
        }

    fun verifyEmailDomain(
        organizationId: Int,
        userId: Int,
    ): SsoConfigResponse {
        if (!isOrganizationOwner(organizationId, userId)) {
            throw SsoForbiddenException("Only organization owners can verify SSO domains")
        }

        val pending = transaction {
            val config = SsoConfigurations
                .selectAll()
                .where { SsoConfigurations.organizationId eq organizationId }
                .firstOrNull()
                ?: throw BadRequestException("SSO is not configured")
            val domain = config[SsoConfigurations.emailDomain]
                ?: throw BadRequestException("SSO email domain is not configured")
            val token = ensureDomainVerificationToken(config)
                ?: throw BadRequestException("SSO email domain is not configured")
            DomainVerificationPending(domain, token)
        }

        if (!verifyDnsTxtRecord(pending.domain, pending.token)) {
            throw BadRequestException("DNS TXT record was not found for this SSO email domain")
        }

        return transaction {
            ensureVerifiedDomainIsUnique(organizationId, pending.domain)
            SsoConfigurations.update({ SsoConfigurations.organizationId eq organizationId }) {
                it[SsoConfigurations.emailDomainVerified] = true
                it[SsoConfigurations.emailDomainVerifiedAt] = Clock.System.now()
                it[SsoConfigurations.emailDomainVerifiedBy] = userId
                it[SsoConfigurations.updatedAt] = Clock.System.now()
            }
            getSsoConfig(organizationId)
                ?: throw IllegalStateException("Failed to retrieve SSO config after domain verification")
        }
    }

    fun deleteSsoConfig(
        organizationId: Int,
        userId: Int,
    ): Boolean {
        if (!isOrganizationOwner(organizationId, userId)) {
            throw SsoForbiddenException("Only organization owners can delete SSO configuration")
        }

        return transaction {
            val deleted =
                SsoConfigurations.deleteWhere {
                    SsoConfigurations.organizationId eq organizationId
                }
            deleted > 0
        }
    }

    fun checkSsoRequired(email: String): Boolean =
        transaction {
            val domain = normalizeDomain(email.substringAfter("@"))
            // Only enforce "Require SSO" when enterprise license is active
            if (!FeatureRegistry.hasModule("SAML")) return@transaction false
            SsoConfigurations
                .selectAll()
                .where {
                    (SsoConfigurations.emailDomain eq domain) and
                        (SsoConfigurations.emailDomainVerified eq true) and
                        (SsoConfigurations.isEnabled eq true) and
                        (SsoConfigurations.requireSso eq true)
                }.firstOrNull() != null
        }

    fun findOrCreateSsoUser(
        email: String,
        name: String,
        externalId: String,
        ssoConfigId: Int,
        organizationId: Int,
    ): Triple<String, String, String> {
        val normalizedEmail = email.lowercase().trim()

        return transaction {
            val existingLink =
                UserSsoLinks
                    .selectAll()
                    .where {
                        (UserSsoLinks.ssoConfigurationId eq ssoConfigId) and
                            (UserSsoLinks.externalId eq externalId)
                    }.firstOrNull()

            val userId =
                if (existingLink != null) {
                    existingLink[UserSsoLinks.userId]
                } else {
                    linkOrCreateUser(
                        normalizedEmail,
                        name,
                        externalId,
                        ssoConfigId,
                        organizationId,
                    )
                }

            val user =
                Users
                    .selectAll()
                    .where { Users.id eq userId }
                    .first()

            val token = generateToken(userId, user[Users.email])
            Triple(token, user[Users.email], user[Users.name] ?: email)
        }
    }

    fun generateSecureState(orgId: Int): String {
        val nonce = ByteArray(NONCE_LENGTH)
        secureRandom.nextBytes(nonce)
        val nonceB64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(nonce)
        val timestamp = System.currentTimeMillis()
        val payload = "$orgId:$nonceB64:$timestamp"

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(jwtSecret.toByteArray(), "HmacSHA256"))
        val sig = Base64.getUrlEncoder().withoutPadding().encodeToString(
            mac.doFinal(payload.toByteArray())
        )

        val state = "$payload:$sig"
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(state.toByteArray())

        if (RedisConfig.isInitialized()) {
            try {
                RedisConfig.sync().setex(
                    "$SSO_NONCE_PREFIX$nonceB64",
                    SSO_NONCE_TTL_SECONDS,
                    orgId.toString()
                )
            } catch (e: RedisException) {
                throw IllegalStateException("Failed to store SSO nonce in Redis", e)
            }
        }

        return encoded
    }

    fun decodeState(state: String): SsoStateData {
        try {
            val decoded = String(Base64.getUrlDecoder().decode(state))
            val parts = decoded.split(":")
            require(parts.size == STATE_PARTS_COUNT) { "Invalid state format" }

            val orgId = parts[0].toInt()
            val nonceB64 = parts[1]
            val timestamp = parts[2].toLong()
            val signature = parts[STATE_SIGNATURE_INDEX]

            verifyStateSignature(parts, signature)
            verifyStateExpiry(timestamp)
            consumeNonce(nonceB64)

            return SsoStateData(nonceB64, orgId, timestamp)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: IllegalStateException) {
            throw IllegalArgumentException("Invalid state parameter", e)
        } catch (e: GeneralSecurityException) {
            throw IllegalArgumentException("Invalid state parameter", e)
        }
    }

    fun encryptSecret(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(IV_LENGTH)
        secureRandom.nextBytes(iv)

        val keySpec = SecretKeySpec(encryptionKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val ciphertext = cipher.doFinal(plaintext.toByteArray())
        val combined = iv + ciphertext
        return Base64.getEncoder().encodeToString(combined)
    }

    fun decryptSecret(encrypted: String?): String? {
        if (encrypted == null) return null

        try {
            val combined = Base64.getDecoder().decode(encrypted)
            if (combined.size <= IV_LENGTH) {
                logger.error { "Failed to decrypt SSO secret: ciphertext too short" }
                return null
            }
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(encryptionKey, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

            return String(cipher.doFinal(ciphertext))
        } catch (e: IllegalArgumentException) {
            logger.error { "Failed to decrypt SSO secret: ${e.message}" }
            return null
        } catch (e: GeneralSecurityException) {
            logger.error { "Failed to decrypt SSO secret: ${e.message}" }
            return null
        }
    }

    /**
     * Discovers OIDC endpoints from the provider's well-known configuration.
     * Results are cached in-memory with a 1-hour TTL.
     */
    private suspend fun discoverOidcEndpoints(issuerUrl: String): OidcDiscoveryCache {
        val discoveryUrl = oidcDiscoveryUrl(issuerUrl)
        val cached = discoveryCache[discoveryUrl]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.fetchedAt) < DISCOVERY_CACHE_TTL_MS) {
            return cached
        }

        logger.info { "Fetching OIDC discovery from $discoveryUrl" }

        val authEndpoint: String
        val tokenEndpoint: String
        try {
            val response = httpClient.get(discoveryUrl).bodyAsText()
            val json = Json.parseToJsonElement(response) as JsonObject
            authEndpoint = json["authorization_endpoint"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException(
                    "OIDC discovery missing authorization_endpoint"
                )
            tokenEndpoint = json["token_endpoint"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException(
                    "OIDC discovery missing token_endpoint"
                )
            UrlValidator.validateExternalUrl(authEndpoint)
            UrlValidator.validateExternalUrl(tokenEndpoint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "OIDC discovery failed for $discoveryUrl" }
            throw IllegalArgumentException(OIDC_DISCOVERY_ERROR)
        }

        val entry = OidcDiscoveryCache(authEndpoint, tokenEndpoint, now)
        discoveryCache[discoveryUrl] = entry
        return entry
    }

    private fun oidcDiscoveryUrl(issuerUrl: String): String {
        val trimmed = issuerUrl.trim()
        return if (trimmed.endsWith(OIDC_DISCOVERY_PATH)) {
            trimmed
        } else {
            trimmed.trimEnd('/') + OIDC_DISCOVERY_PATH
        }
    }

    private suspend fun generateOidcRequest(
        ssoConfig: ResultRow,
        state: String,
    ): String {
        val issuerRaw =
            ssoConfig[SsoConfigurations.oidcIssuerUrl]
                ?: throw IllegalArgumentException(ERROR_OIDC_ISSUER_URL_NOT_CONFIGURED)
        val issuerUrl = issuerRaw.trim()
        require(issuerUrl.isNotEmpty()) { ERROR_OIDC_ISSUER_URL_NOT_CONFIGURED }
        UrlValidator.validateExternalUrl(issuerUrl)
        val clientId =
            ssoConfig[SsoConfigurations.oidcClientId]
                ?: throw IllegalArgumentException("OIDC client ID not configured")
        val redirectUri = "$baseUrl/auth/sso/oidc/callback"

        val endpoints = discoverOidcEndpoints(issuerUrl)
        return "${endpoints.authorizationEndpoint}?" +
            "client_id=$clientId&" +
            "redirect_uri=$redirectUri&" +
            "response_type=code&" +
            "scope=openid%20email%20profile&" +
            "state=$state"
    }

    private fun validateSsoAccess(
        organizationId: Int,
        providerType: SsoProviderType,
    ) {
        val isSelfHosted = EnvConfig.SelfHost.enabled

        if (isSelfHosted) {
            if (providerType == SsoProviderType.SAML && !FeatureRegistry.hasModule("SAML")) {
                throw SsoForbiddenException("SAML SSO requires an enterprise license")
            }
        } else {
            val tierContext = pricingTierService
                .getEffectiveTierForOrganization(organizationId)
            val tierName = tierContext.tier.tierName
            if (tierName != "TEAM" && tierName != "BUSINESS") {
                throw SsoForbiddenException("SSO is only available on Team and Business plans")
            }
        }
    }

    private fun generateToken(
        userId: Int,
        email: String,
    ): String =
        JWT
            .create()
            .withAudience(jwtAudience)
            .withIssuer(jwtIssuer)
            .withClaim("userId", userId)
            .withClaim("email", email)
            .withExpiresAt(Date(System.currentTimeMillis() + TOKEN_TTL_MS))
            .sign(Algorithm.HMAC256(jwtSecret))

    private fun linkOrCreateUser(
        normalizedEmail: String,
        name: String,
        externalId: String,
        ssoConfigId: Int,
        organizationId: Int,
    ): Int {
        ensureSsoEmailDomainAllowsNewLink(normalizedEmail, ssoConfigId)
        val existingUser =
            Users
                .selectAll()
                .where { Users.email eq normalizedEmail }
                .firstOrNull()

        return if (existingUser != null) {
            val uid = existingUser[Users.id]
            val invitationRole = pendingInvitationRole(normalizedEmail, organizationId)
            if (!isOrganizationMember(uid, organizationId) && invitationRole == null) {
                throw IllegalArgumentException("SSO account linking requires an existing membership or invitation")
            }
            UserSsoLinks.insert {
                it[UserSsoLinks.userId] = uid
                it[UserSsoLinks.ssoConfigurationId] = ssoConfigId
                it[UserSsoLinks.externalId] = externalId
            }
            addToOrgIfNeeded(uid, organizationId, invitationRole)
            acceptPendingInvitation(normalizedEmail, organizationId)
            uid
        } else {
            val invitationRole = pendingInvitationRole(normalizedEmail, organizationId)
            val uid =
                Users.insert {
                    it[Users.email] = normalizedEmail
                    it[Users.name] = name
                    it[password_hash] = ""
                    it[email_verified] = true
                    it[onboarding_completed] = false
                }[Users.id]
            UserSsoLinks.insert {
                it[UserSsoLinks.userId] = uid
                it[UserSsoLinks.ssoConfigurationId] = ssoConfigId
                it[UserSsoLinks.externalId] = externalId
            }
            Memberships.insert {
                it[user_id] = uid
                it[organization_id] = organizationId
                it[role] = invitationRole ?: "member"
            }
            acceptPendingInvitation(normalizedEmail, organizationId)
            uid
        }
    }

    private fun addToOrgIfNeeded(
        userId: Int,
        organizationId: Int,
        invitationRole: String?,
    ) {
        val isMember =
            isOrganizationMember(userId, organizationId)

        if (!isMember) {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = organizationId
                it[role] = invitationRole ?: "member"
            }
        }
    }

    private fun findEnabledVerifiedConfigForDomain(domain: String): ResultRow? {
        val configs = SsoConfigurations
            .selectAll()
            .where {
                (SsoConfigurations.emailDomain eq domain) and
                    (SsoConfigurations.emailDomainVerified eq true) and
                    (SsoConfigurations.isEnabled eq true)
            }.toList()
        require(configs.size <= 1) {
            "SSO is not configured for this email domain or organization"
        }
        return configs.firstOrNull()
    }

    private fun ensureSsoEmailDomainAllowsNewLink(
        normalizedEmail: String,
        ssoConfigId: Int,
    ) {
        val domain = domainFromEmail(normalizedEmail)
        val config = SsoConfigurations
            .selectAll()
            .where { SsoConfigurations.id eq ssoConfigId }
            .firstOrNull()
            ?: throw IllegalArgumentException("SSO configuration not found")
        val configuredDomain = config[SsoConfigurations.emailDomain]
        require(configuredDomain == domain && config[SsoConfigurations.emailDomainVerified]) {
            "SSO email domain is not verified"
        }
    }

    private fun isOrganizationMember(
        userId: Int,
        organizationId: Int,
    ): Boolean =
        Memberships
            .selectAll()
            .where {
                (Memberships.user_id eq userId) and
                    (Memberships.organization_id eq organizationId)
            }.firstOrNull() != null

    private fun pendingInvitationRole(
        normalizedEmail: String,
        organizationId: Int,
    ): String? =
        OrgInvitations
            .selectAll()
            .where {
                (OrgInvitations.organization_id eq organizationId) and
                    (OrgInvitations.email eq normalizedEmail) and
                    (OrgInvitations.status eq "pending") and
                    (OrgInvitations.expires_at greater System.currentTimeMillis())
            }.firstOrNull()
            ?.get(OrgInvitations.role)

    private fun acceptPendingInvitation(
        normalizedEmail: String,
        organizationId: Int,
    ) {
        OrgInvitations.update({
            (OrgInvitations.organization_id eq organizationId) and
                (OrgInvitations.email eq normalizedEmail) and
                (OrgInvitations.status eq "pending") and
                (OrgInvitations.expires_at greater System.currentTimeMillis())
        }) {
            it[OrgInvitations.status] = "accepted"
        }
    }

    private fun ensureVerifiedDomainIsUnique(
        organizationId: Int,
        domain: String,
    ) {
        val existing = SsoConfigurations
            .selectAll()
            .where {
                (SsoConfigurations.organizationId neq organizationId) and
                    (SsoConfigurations.emailDomain eq domain) and
                    (SsoConfigurations.emailDomainVerified eq true)
            }.firstOrNull()
        if (existing != null) {
            throw BadRequestException("SSO email domain is already verified")
        }
    }

    private fun ensureDomainVerificationToken(row: ResultRow): String? {
        if (row[SsoConfigurations.emailDomain] == null) return null
        val existingToken = row[SsoConfigurations.emailDomainVerificationToken]
        if (existingToken != null) return existingToken

        val token = generateVerificationToken()
        SsoConfigurations.update({ SsoConfigurations.id eq row[SsoConfigurations.id] }) {
            it[SsoConfigurations.emailDomainVerificationToken] = token
            it[SsoConfigurations.updatedAt] = Clock.System.now()
        }
        return token
    }

    private fun generateVerificationToken(): String {
        val bytes = ByteArray(DOMAIN_VERIFICATION_TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun validateAndNormalizeEmailDomain(rawDomain: String): String {
        val domain = normalizeDomain(rawDomain)
        when {
            domain.isBlank() -> throw BadRequestException("SSO email domain is required")
            domain.length > MAX_DOMAIN_LENGTH -> throw BadRequestException("SSO email domain is too long")
            !domain.contains(".") -> throw BadRequestException("SSO email domain must be a registrable domain")
            domain.contains(":") -> throw BadRequestException("SSO email domain must not be an IP address")
            IPV4_DOMAIN_PATTERN.matches(domain) -> {
                throw BadRequestException("SSO email domain must not be an IP address")
            }
            domain in PUBLIC_EMAIL_DOMAINS -> {
                throw BadRequestException("SSO email domain must be an organization-owned domain")
            }
        }
        val labels = domain.split(".")
        if (labels.any { label -> !isValidDomainLabel(label) }) {
            throw BadRequestException("SSO email domain is invalid")
        }
        return domain
    }

    private fun isValidDomainLabel(label: String): Boolean =
        label.length <= MAX_DOMAIN_LABEL_LENGTH && DOMAIN_LABEL_PATTERN.matches(label)

    private fun domainVerificationRecordName(domain: String): String =
        "$SSO_DNS_RECORD_PREFIX.$domain"

    open fun verifyDnsTxtRecord(
        domain: String,
        expectedToken: String,
    ): Boolean {
        return try {
            val expectedValue = "$SSO_DNS_RECORD_VALUE_PREFIX$expectedToken"
            val attrs = InitialDirContext().getAttributes(
                "dns:/${domainVerificationRecordName(domain)}",
                arrayOf("TXT"),
            )
            val txtRecords = attrs.get("TXT") ?: return false
            val records = (0 until txtRecords.size()).map { index ->
                txtRecords.get(index).toString().trim('"')
            }
            records.any { record -> record == expectedValue }
        } catch (e: NamingException) {
            logger.warn(e) { "Failed to verify SSO DNS TXT record for $domain" }
            false
        }
    }

    private fun verifyStateSignature(
        parts: List<String>,
        signature: String,
    ) {
        val payload = "${parts[0]}:${parts[1]}:${parts[2]}"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(jwtSecret.toByteArray(), "HmacSHA256"))
        val expected = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(payload.toByteArray()))

        require(
            java.security.MessageDigest.isEqual(
                expected.toByteArray(),
                signature.toByteArray()
            )
        ) { "State signature invalid" }
    }

    private fun verifyStateExpiry(timestamp: Long) {
        require(
            System.currentTimeMillis() - timestamp <=
                SSO_NONCE_TTL_SECONDS * MILLIS_PER_SECOND
        ) { "State expired" }
    }

    private fun consumeNonce(nonceB64: String) {
        try {
            if (!RedisConfig.isInitialized()) {
                return
            }
            val redisKey = "$SSO_NONCE_PREFIX$nonceB64"
            val stored = RedisConfig.sync().get(redisKey)
            requireNotNull(stored) { "State already used or expired" }
            RedisConfig.sync().del(redisKey)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: RedisException) {
            throw IllegalStateException(
                "Failed to verify SSO nonce ($SSO_NONCE_PREFIX$nonceB64)",
                e
            )
        }
    }

    /**
     * Verifies SSO state signature and expiry without consuming the nonce.
     * Used by SAML to bind [AuthnRequest] IDs to the same state string later
     * validated by [decodeState].
     */
    fun peekVerifiedNonceWithoutConsume(state: String): String {
        val decoded = String(Base64.getUrlDecoder().decode(state))
        val parts = decoded.split(":")
        require(parts.size == STATE_PARTS_COUNT) { "Invalid state format" }

        val nonceB64 = parts[1]
        val timestamp = parts[2].toLong()
        val signature = parts[STATE_SIGNATURE_INDEX]

        verifyStateSignature(parts, signature)
        verifyStateExpiry(timestamp)
        return nonceB64
    }

    companion object {
        const val SSO_SAML_AUTHN_REQUEST_KEY_PREFIX = "sso:saml:authnreq:"

        private const val SSO_NONCE_PREFIX = "sso:nonce:"
        private const val SSO_NONCE_TTL_SECONDS = 600L
        private const val AES_KEY_LENGTH = 32
        private const val NONCE_LENGTH = 16
        private const val IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val STATE_PARTS_COUNT = 4
        private const val STATE_SIGNATURE_INDEX = 3
        private const val TOKEN_TTL_MS = 3_600_000L
        private const val MILLIS_PER_SECOND = 1000L
        private const val PBKDF2_ITERATIONS = 100_000
        private const val BITS_PER_BYTE = 8

        fun normalizeDomain(domain: String): String = domain.trim().lowercase()

        fun domainFromEmail(email: String): String {
            val normalizedEmail = email.trim().lowercase()
            val atIndex = normalizedEmail.lastIndexOf("@")
            require(atIndex > 0 && atIndex < normalizedEmail.lastIndex) {
                "Invalid email address"
            }
            return normalizeDomain(normalizedEmail.substring(atIndex + 1))
        }
    }
}
