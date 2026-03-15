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
import com.moneat.sso.models.SsoCallbackData
import com.moneat.sso.models.SsoConfigRequest
import com.moneat.sso.models.SsoConfigResponse
import com.moneat.sso.models.SsoInitResponse
import com.moneat.sso.models.SsoProviderType
import com.moneat.shared.models.Memberships
import com.moneat.shared.models.Organizations
import com.moneat.shared.models.SsoConfigurations
import com.moneat.shared.models.UserSsoLinks
import com.moneat.shared.models.Users
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
import io.ktor.server.config.ApplicationConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import mu.KotlinLogging
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock

private val logger = KotlinLogging.logger {}

private const val DISCOVERY_CACHE_TTL_MS = 3_600_000L // 1 hour

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
        val key = jwtSecret.toByteArray()
        key.copyOf(AES_KEY_LENGTH)
    }

    fun initSso(
        email: String?,
        orgSlug: String?,
    ): SsoInitResponse {
        require(!(email == null && orgSlug == null)) {
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
                                (SsoConfigurations.isEnabled eq true)
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
                    // SAML init is handled by SamlModule; delegate via SsoInitResponse
                    SsoInitResponse("", "saml", state)
                }

                SsoProviderType.OIDC -> {
                    val redirectUrl = generateOidcRequest(config, state)
                    SsoInitResponse(redirectUrl, "oidc", state)
                }
            }
        }
    }

    fun handleOidcCallback(
        code: String,
        state: String,
    ): SsoCallbackData =
        transaction {
            val stateData = decodeState(state)
            val orgId = stateData.orgId

            val ssoConfig =
                SsoConfigurations
                    .selectAll()
                    .where { SsoConfigurations.organizationId eq orgId }
                    .firstOrNull()
                    ?: throw IllegalArgumentException(
                        "SSO configuration not found"
                    )

            val issuerUrl =
                ssoConfig[SsoConfigurations.oidcIssuerUrl]
                    ?: throw IllegalArgumentException(
                        "OIDC issuer URL not configured"
                    )
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

            val endpoints = discoverOidcEndpoints(issuerUrl)
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
                        ClientID(clientId),
                        Secret(clientSecret),
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
            val name = idToken.jwtClaimsSet.getStringClaim("name")
                ?: email.substringBefore("@")
            val externalId = idToken.jwtClaimsSet.subject

            val (token, userEmail, userName) =
                findOrCreateSsoUser(
                    email = email,
                    name = name,
                    externalId = externalId,
                    ssoConfigId = ssoConfig[SsoConfigurations.id],
                    organizationId = orgId,
                )

            SsoCallbackData(token, userEmail, userName)
        }

    fun configureSso(
        organizationId: Int,
        userId: Int,
        request: SsoConfigRequest,
    ): SsoConfigResponse {
        val providerType = SsoProviderType.fromString(request.providerType)
        validateSsoAccess(organizationId, providerType)
        require(!(request.requireSso && !FeatureRegistry.hasModule("SAML"))) {
            "SSO enforcement (Require SSO) requires an enterprise license"
        }
        require(isOrganizationOwner(organizationId, userId)) {
            "Only organization owners can configure SSO"
        }
        validateSsoConfigRequest(providerType, request)

        return transaction {
            val spEntityId = "$baseUrl/auth/sso/saml/metadata"
            val encryptedSecret = request.oidcClientSecret?.let { encryptSecret(it) }
            val effectiveRequireSso = request.requireSso && FeatureRegistry.hasModule("SAML")
            persistSsoConfig(organizationId, request, spEntityId, encryptedSecret, effectiveRequireSso)
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
                require(
                    !request.idpEntityId.isNullOrBlank() &&
                        !request.idpSsoUrl.isNullOrBlank() &&
                        !request.idpCertificate.isNullOrBlank()
                ) {
                    "SAML requires idpEntityId, idpSsoUrl, and idpCertificate"
                }
                UrlValidator.validateExternalUrl(request.idpSsoUrl)
            }
            SsoProviderType.OIDC -> {
                require(
                    !request.oidcIssuerUrl.isNullOrBlank() &&
                        !request.oidcClientId.isNullOrBlank() &&
                        !request.oidcClientSecret.isNullOrBlank()
                ) {
                    "OIDC requires oidcIssuerUrl, oidcClientId, and oidcClientSecret"
                }
                UrlValidator.validateExternalUrl(request.oidcIssuerUrl)
            }
        }
    }

    private fun persistSsoConfig(
        organizationId: Int,
        request: SsoConfigRequest,
        spEntityId: String,
        encryptedSecret: String?,
        effectiveRequireSso: Boolean,
    ) {
        val existing = SsoConfigurations.selectAll()
            .where { SsoConfigurations.organizationId eq organizationId }
            .firstOrNull()

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
                it[SsoConfigurations.emailDomain] = request.emailDomain
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
                it[SsoConfigurations.emailDomain] = request.emailDomain
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
                        emailDomain = row[SsoConfigurations.emailDomain],
                        requireSso = row[SsoConfigurations.requireSso],
                        createdAt = row[SsoConfigurations.createdAt].toString(),
                        updatedAt = row[SsoConfigurations.updatedAt].toString(),
                    )
                }
        }

    fun deleteSsoConfig(
        organizationId: Int,
        userId: Int,
    ): Boolean {
        require(isOrganizationOwner(organizationId, userId)) {
            "Only organization owners can delete SSO configuration"
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
            val domain = email.substringAfter("@")
            // Only enforce "Require SSO" when enterprise license is active
            if (!FeatureRegistry.hasModule("SAML")) return@transaction false
            SsoConfigurations
                .selectAll()
                .where {
                    (SsoConfigurations.emailDomain eq domain) and
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

        try {
            if (RedisConfig.isInitialized()) {
                RedisConfig.sync().setex(
                    "$SSO_NONCE_PREFIX$nonceB64",
                    SSO_NONCE_TTL_SECONDS,
                    orgId.toString()
                )
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to store SSO nonce in Redis" }
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
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid state parameter")
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
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(encryptionKey, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

            return String(cipher.doFinal(ciphertext))
        } catch (e: Exception) {
            logger.error(e) { "Failed to decrypt SSO secret" }
            return null
        }
    }

    /**
     * Discovers OIDC endpoints from the provider's well-known configuration.
     * Results are cached in-memory with a 1-hour TTL.
     */
    private fun discoverOidcEndpoints(issuerUrl: String): OidcDiscoveryCache {
        val cached = discoveryCache[issuerUrl]
        val now = System.currentTimeMillis()
        if (cached != null && (now - cached.fetchedAt) < DISCOVERY_CACHE_TTL_MS) {
            return cached
        }

        val discoveryUrl = "${issuerUrl.trimEnd('/')}/.well-known/openid-configuration"
        logger.info { "Fetching OIDC discovery from $discoveryUrl" }

        val response = kotlinx.coroutines.runBlocking {
            httpClient.get(discoveryUrl).bodyAsText()
        }
        val json = Json.parseToJsonElement(response) as JsonObject
        val authEndpoint = json["authorization_endpoint"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException(
                "OIDC discovery missing authorization_endpoint"
            )
        val tokenEndpoint = json["token_endpoint"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException(
                "OIDC discovery missing token_endpoint"
            )

        val entry = OidcDiscoveryCache(authEndpoint, tokenEndpoint, now)
        discoveryCache[issuerUrl] = entry
        return entry
    }

    private fun generateOidcRequest(
        ssoConfig: org.jetbrains.exposed.v1.core.ResultRow,
        state: String,
    ): String {
        val issuerUrl = ssoConfig[SsoConfigurations.oidcIssuerUrl]
        if (!issuerUrl.isNullOrBlank()) {
            UrlValidator.validateExternalUrl(issuerUrl)
        }
        val clientId = ssoConfig[SsoConfigurations.oidcClientId]
        val redirectUri = "$baseUrl/auth/sso/oidc/callback"

        val endpoints = discoverOidcEndpoints(issuerUrl!!)
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
            require(
                providerType != SsoProviderType.SAML ||
                    FeatureRegistry.hasModule("SAML")
            ) {
                "SAML SSO requires an enterprise license"
            }
        } else {
            val tierContext = pricingTierService
                .getEffectiveTierForOrganization(organizationId)
            val tierName = tierContext.tier.tierName
            require(!(tierName != "TEAM" && tierName != "BUSINESS")) {
                "SSO is only available on Team and Business plans"
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
        val existingUser =
            Users
                .selectAll()
                .where { Users.email eq normalizedEmail }
                .firstOrNull()

        return if (existingUser != null) {
            val uid = existingUser[Users.id]
            UserSsoLinks.insert {
                it[UserSsoLinks.userId] = uid
                it[UserSsoLinks.ssoConfigurationId] = ssoConfigId
                it[UserSsoLinks.externalId] = externalId
            }
            addToOrgIfNeeded(uid, organizationId)
            uid
        } else {
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
                it[role] = "member"
            }
            uid
        }
    }

    private fun addToOrgIfNeeded(userId: Int, organizationId: Int) {
        val isMember =
            Memberships
                .selectAll()
                .where {
                    (Memberships.user_id eq userId) and
                        (Memberships.organization_id eq organizationId)
                }.firstOrNull() != null

        if (!isMember) {
            Memberships.insert {
                it[user_id] = userId
                it[organization_id] = organizationId
                it[role] = "member"
            }
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
            if (RedisConfig.isInitialized()) {
                val redisKey = "$SSO_NONCE_PREFIX$nonceB64"
                val stored = RedisConfig.sync().get(redisKey)
                requireNotNull(stored) { "State already used or expired" }
                RedisConfig.sync().del(redisKey)
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to verify SSO nonce in Redis" }
        }
    }

    companion object {
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
    }
}
