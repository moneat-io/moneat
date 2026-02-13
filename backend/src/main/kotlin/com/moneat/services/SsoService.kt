package com.moneat.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moneat.models.*
import com.nimbusds.oauth2.sdk.*
import com.nimbusds.oauth2.sdk.auth.ClientSecretBasic
import com.nimbusds.oauth2.sdk.auth.Secret
import com.nimbusds.oauth2.sdk.id.ClientID
import com.nimbusds.openid.connect.sdk.*
import com.onelogin.saml2.authn.AuthnRequest
import com.onelogin.saml2.authn.SamlResponse
import com.onelogin.saml2.settings.Saml2Settings
import com.onelogin.saml2.settings.SettingsBuilder
import io.ktor.server.config.*
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import mu.KotlinLogging
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.net.URI
import java.security.SecureRandom
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private val logger = KotlinLogging.logger {}

@Serializable
data class SsoStateData(
    val nonce: String,
    val orgId: Int,
    val timestamp: Long
)

class SsoService {
    private val config = ApplicationConfig("application.conf")
    private val jwtSecret = config.property("jwt.secret").getString()
    private val jwtIssuer = config.property("jwt.issuer").getString()
    private val jwtAudience = config.property("jwt.audience").getString()
    private val baseUrl = config.propertyOrNull("app.baseUrl")?.getString() ?: "https://api.moneat.io"
    private val dashboardUrl = config.propertyOrNull("app.dashboardUrl")?.getString() ?: "https://moneat.io"
    private val secureRandom = SecureRandom()
    private val pricingTierService = PricingTierService()
    
    private val encryptionKey: ByteArray by lazy {
        val key = jwtSecret.toByteArray()
        key.copyOf(32) // AES-256 requires 32 bytes
    }
    
    fun initSso(email: String?, orgSlug: String?): SsoInitResponse {
        if (email == null && orgSlug == null) {
            throw IllegalArgumentException("Either email or orgSlug must be provided")
        }
        
        return transaction {
            val ssoConfig = if (email != null) {
                val domain = email.substringAfter("@")
                SsoConfigurations.selectAll()
                    .where { 
                        (SsoConfigurations.emailDomain eq domain) and 
                        (SsoConfigurations.isEnabled eq true) 
                    }
                    .firstOrNull()
            } else {
                val org = Organizations.selectAll()
                    .where { Organizations.slug eq orgSlug!! }
                    .firstOrNull()
                    ?: throw IllegalArgumentException("Organization not found")
                
                SsoConfigurations.selectAll()
                    .where { 
                        (SsoConfigurations.organizationId eq org[Organizations.id]) and 
                        (SsoConfigurations.isEnabled eq true) 
                    }
                    .firstOrNull()
            }
            
            if (ssoConfig == null) {
                throw IllegalArgumentException("SSO is not configured for this email domain or organization")
            }
            
            val providerType = SsoProviderType.fromString(ssoConfig[SsoConfigurations.providerType])
            val orgId = ssoConfig[SsoConfigurations.organizationId]
            val state = generateSecureState(orgId)
            
            when (providerType) {
                SsoProviderType.SAML -> {
                    val redirectUrl = generateSamlRequest(ssoConfig, state)
                    SsoInitResponse(redirectUrl, "saml", state)
                }
                SsoProviderType.OIDC -> {
                    val redirectUrl = generateOidcRequest(ssoConfig, state)
                    SsoInitResponse(redirectUrl, "oidc", state)
                }
            }
        }
    }
    
    fun handleSamlResponse(samlResponse: String, relayState: String?): SsoCallbackData {
        return transaction {
            // Decode state to get organization ID
            val stateData = if (relayState != null) {
                decodeState(relayState)
            } else {
                throw IllegalArgumentException("Missing RelayState parameter")
            }
            
            val orgId = stateData.orgId
            
            // Load SSO configuration
            val ssoConfig = SsoConfigurations.selectAll()
                .where { SsoConfigurations.organizationId eq orgId }
                .firstOrNull()
                ?: throw IllegalArgumentException("SSO configuration not found")
            
            // Build SAML settings
            val settings = buildSamlSettings(ssoConfig)
            
            // Process SAML response
            val samlResponseObj = SamlResponse(settings, null)
            samlResponseObj.loadXmlFromBase64(samlResponse)
            
            // Validate the response
            if (!samlResponseObj.isValid(relayState)) {
                val error = samlResponseObj.error
                logger.error { "SAML authentication failed: $error" }
                throw IllegalArgumentException("SAML authentication failed: $error")
            }
            
            // Extract user attributes
            val attributes = samlResponseObj.attributes
            val email = attributes["email"]?.firstOrNull() 
                ?: attributes["http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress"]?.firstOrNull()
                ?: throw IllegalArgumentException("No email found in SAML response")
            
            val name = attributes["name"]?.firstOrNull()
                ?: attributes["displayName"]?.firstOrNull()
                ?: attributes["http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name"]?.firstOrNull()
                ?: email.substringBefore("@")
            
            val externalId = samlResponseObj.nameId
                ?: throw IllegalArgumentException("No NameID found in SAML response")
            
            // Find or create user with SSO link
            val (token, userEmail, userName) = findOrCreateSsoUser(
                email = email,
                name = name,
                externalId = externalId,
                ssoConfigId = ssoConfig[SsoConfigurations.id],
                organizationId = orgId
            )
            
            SsoCallbackData(token, userEmail, userName)
        }
    }
    
    fun handleOidcCallback(code: String, state: String): SsoCallbackData {
        return transaction {
            val stateData = decodeState(state)
            val orgId = stateData.orgId
            
            val ssoConfig = SsoConfigurations.selectAll()
                .where { SsoConfigurations.organizationId eq orgId }
                .firstOrNull()
                ?: throw IllegalArgumentException("SSO configuration not found")
            
            val issuerUrl = ssoConfig[SsoConfigurations.oidcIssuerUrl]
                ?: throw IllegalArgumentException("OIDC issuer URL not configured")
            val clientId = ssoConfig[SsoConfigurations.oidcClientId]
                ?: throw IllegalArgumentException("OIDC client ID not configured")
            val clientSecret = decryptSecret(ssoConfig[SsoConfigurations.oidcClientSecret])
                ?: throw IllegalArgumentException("OIDC client secret not configured")
            
            val tokenEndpoint = URI("$issuerUrl/protocol/openid-connect/token")
            val redirectUri = URI("$baseUrl/auth/sso/oidc/callback")
            
            val authCode = com.nimbusds.oauth2.sdk.AuthorizationCode(code)
            val codeGrant = AuthorizationCodeGrant(authCode, redirectUri)
            
            val tokenRequest = TokenRequest(
                tokenEndpoint,
                ClientSecretBasic(ClientID(clientId), Secret(clientSecret)),
                codeGrant
            )
            
            val tokenResponse = OIDCTokenResponseParser.parse(tokenRequest.toHTTPRequest().send())
            
            if (!tokenResponse.indicatesSuccess()) {
                val errorResponse = tokenResponse.toErrorResponse()
                logger.error { "OIDC token exchange failed: ${errorResponse.errorObject}" }
                throw IllegalArgumentException("OIDC token exchange failed")
            }
            
            val successResponse = tokenResponse.toSuccessResponse() as OIDCTokenResponse
            val idToken = successResponse.oidcTokens.idToken
            
            val email = idToken.jwtClaimsSet.getStringClaim("email")
                ?: throw IllegalArgumentException("No email found in ID token")
            val name = idToken.jwtClaimsSet.getStringClaim("name") ?: email.substringBefore("@")
            val externalId = idToken.jwtClaimsSet.subject
            
            val (token, userEmail, userName) = findOrCreateSsoUser(
                email = email,
                name = name,
                externalId = externalId,
                ssoConfigId = ssoConfig[SsoConfigurations.id],
                organizationId = orgId
            )
            
            SsoCallbackData(token, userEmail, userName)
        }
    }
    
    fun configureSso(organizationId: Int, userId: Int, request: SsoConfigRequest): SsoConfigResponse {
        // Check tier eligibility
        val tierContext = pricingTierService.getEffectiveTierForOrganization(organizationId)
        if (tierContext.tier.tierName != "TEAM" && tierContext.tier.tierName != "BUSINESS") {
            throw IllegalArgumentException("SSO is only available on Team and Business plans")
        }
        
        // Verify user is owner of the organization
        val isOwner = transaction {
            Memberships.selectAll()
                .where {
                    (Memberships.organization_id eq organizationId) and
                    (Memberships.user_id eq userId) and
                    (Memberships.role eq "owner")
                }
                .firstOrNull() != null
        }
        
        if (!isOwner) {
            throw IllegalArgumentException("Only organization owners can configure SSO")
        }
        
        return transaction {
            val providerType = SsoProviderType.fromString(request.providerType)
            
            // Validate required fields
            when (providerType) {
                SsoProviderType.SAML -> {
                    if (request.idpEntityId.isNullOrBlank() || 
                        request.idpSsoUrl.isNullOrBlank() || 
                        request.idpCertificate.isNullOrBlank()) {
                        throw IllegalArgumentException("SAML requires idpEntityId, idpSsoUrl, and idpCertificate")
                    }
                }
                SsoProviderType.OIDC -> {
                    if (request.oidcIssuerUrl.isNullOrBlank() || 
                        request.oidcClientId.isNullOrBlank() || 
                        request.oidcClientSecret.isNullOrBlank()) {
                        throw IllegalArgumentException("OIDC requires oidcIssuerUrl, oidcClientId, and oidcClientSecret")
                    }
                }
            }
            
            val spEntityId = "$baseUrl/auth/sso/saml/metadata"
            val encryptedSecret = if (request.oidcClientSecret != null) {
                encryptSecret(request.oidcClientSecret)
            } else null
            
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
                    if (encryptedSecret != null) {
                        it[SsoConfigurations.oidcClientSecret] = encryptedSecret
                    }
                    it[SsoConfigurations.emailDomain] = request.emailDomain
                    it[SsoConfigurations.requireSso] = request.requireSso
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
                    it[SsoConfigurations.requireSso] = request.requireSso
                }
            }
            
            getSsoConfig(organizationId) ?: throw IllegalStateException("Failed to retrieve SSO config after save")
        }
    }
    
    fun getSsoConfig(organizationId: Int): SsoConfigResponse? {
        return transaction {
            SsoConfigurations.selectAll()
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
                        updatedAt = row[SsoConfigurations.updatedAt].toString()
                    )
                }
        }
    }
    
    fun deleteSsoConfig(organizationId: Int, userId: Int): Boolean {
        // Verify user is owner
        val isOwner = transaction {
            Memberships.selectAll()
                .where {
                    (Memberships.organization_id eq organizationId) and
                    (Memberships.user_id eq userId) and
                    (Memberships.role eq "owner")
                }
                .firstOrNull() != null
        }
        
        if (!isOwner) {
            throw IllegalArgumentException("Only organization owners can delete SSO configuration")
        }
        
        return transaction {
            val deleted = SsoConfigurations.deleteWhere {
                SsoConfigurations.organizationId eq organizationId
            }
            deleted > 0
        }
    }
    
    fun checkSsoRequired(email: String): Boolean {
        return transaction {
            val domain = email.substringAfter("@")
            SsoConfigurations.selectAll()
                .where {
                    (SsoConfigurations.emailDomain eq domain) and
                    (SsoConfigurations.isEnabled eq true) and
                    (SsoConfigurations.requireSso eq true)
                }
                .firstOrNull() != null
        }
    }
    
    fun getSamlMetadata(orgSlug: String?): String {
        return transaction {
            // Find SSO config by organization slug
            val ssoConfig = if (orgSlug != null) {
                val org = Organizations.selectAll()
                    .where { Organizations.slug eq orgSlug }
                    .firstOrNull()
                    ?: throw IllegalArgumentException("Organization not found")
                
                SsoConfigurations.selectAll()
                    .where { 
                        (SsoConfigurations.organizationId eq org[Organizations.id]) and
                        (SsoConfigurations.providerType eq "saml") and
                        (SsoConfigurations.isEnabled eq true)
                    }
                    .firstOrNull()
            } else {
                throw IllegalArgumentException("Organization slug is required")
            }
            
            if (ssoConfig == null) {
                throw IllegalArgumentException("SAML SSO is not configured for this organization")
            }
            
            val settings = buildSamlSettings(ssoConfig)
            val metadata = settings.spMetadata
            val errors = Saml2Settings.validateMetadata(metadata)
            
            if (errors.isNotEmpty()) {
                logger.error { "SAML metadata validation errors: ${errors.joinToString(", ")}" }
                throw IllegalArgumentException("Failed to generate valid SAML metadata")
            }
            
            metadata
        }
    }
    
    private fun findOrCreateSsoUser(
        email: String, 
        name: String, 
        externalId: String, 
        ssoConfigId: Int,
        organizationId: Int
    ): Triple<String, String, String> {
        return transaction {
            // Check if user already linked via SSO
            val existingLink = UserSsoLinks.selectAll()
                .where {
                    (UserSsoLinks.ssoConfigurationId eq ssoConfigId) and
                    (UserSsoLinks.externalId eq externalId)
                }
                .firstOrNull()
            
            val userId = if (existingLink != null) {
                existingLink[UserSsoLinks.userId]
            } else {
                // Check if user exists by email
                val existingUser = Users.selectAll()
                    .where { Users.email eq email }
                    .firstOrNull()
                
                if (existingUser != null) {
                    val uid = existingUser[Users.id]
                    
                    // Link existing user to SSO
                    UserSsoLinks.insert {
                        it[UserSsoLinks.userId] = uid
                        it[UserSsoLinks.ssoConfigurationId] = ssoConfigId
                        it[UserSsoLinks.externalId] = externalId
                    }
                    
                    // Add to organization if not already a member
                    val isMember = Memberships.selectAll()
                        .where {
                            (Memberships.user_id eq uid) and
                            (Memberships.organization_id eq organizationId)
                        }
                        .firstOrNull() != null
                    
                    if (!isMember) {
                        Memberships.insert {
                            it[user_id] = uid
                            it[organization_id] = organizationId
                            it[role] = "member"
                        }
                    }
                    
                    uid
                } else {
                    // Create new user (JIT provisioning)
                    val uid = Users.insert {
                        it[Users.email] = email
                        it[Users.name] = name
                        it[password_hash] = "" // No password for SSO users
                        it[email_verified] = true // SSO users are pre-verified
                        it[onboarding_completed] = false // Require onboarding for SSO users
                    }[Users.id]
                    
                    // Link to SSO
                    UserSsoLinks.insert {
                        it[UserSsoLinks.userId] = uid
                        it[UserSsoLinks.ssoConfigurationId] = ssoConfigId
                        it[UserSsoLinks.externalId] = externalId
                    }
                    
                    // Add to organization
                    Memberships.insert {
                        it[user_id] = uid
                        it[organization_id] = organizationId
                        it[role] = "member"
                    }
                    
                    uid
                }
            }
            
            // Get user details for response
            val user = Users.selectAll()
                .where { Users.id eq userId }
                .first()
            
            val token = generateToken(userId, user[Users.email])
            Triple(token, user[Users.email], user[Users.name] ?: email)
        }
    }
    
    private fun generateToken(userId: Int, email: String): String {
        return JWT.create()
            .withAudience(jwtAudience)
            .withIssuer(jwtIssuer)
            .withClaim("userId", userId)
            .withClaim("email", email)
            .withExpiresAt(Date(System.currentTimeMillis() + 3600000))
            .sign(Algorithm.HMAC256(jwtSecret))
    }
    
    private fun generateSecureState(orgId: Int): String {
        val nonce = ByteArray(16)
        secureRandom.nextBytes(nonce)
        val state = "$orgId:${Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)}:${System.currentTimeMillis()}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(state.toByteArray())
    }
    
    private fun decodeState(state: String): SsoStateData {
        try {
            val decoded = String(Base64.getUrlDecoder().decode(state))
            val parts = decoded.split(":")
            if (parts.size != 3) {
                throw IllegalArgumentException("Invalid state format")
            }
            
            val orgId = parts[0].toInt()
            val timestamp = parts[2].toLong()
            
            // Check if state is expired (10 minutes)
            if (System.currentTimeMillis() - timestamp > 10 * 60 * 1000) {
                throw IllegalArgumentException("State expired")
            }
            
            return SsoStateData(parts[1], orgId, timestamp)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid state parameter")
        }
    }
    
    private fun generateSamlRequest(ssoConfig: ResultRow, state: String): String {
        val settings = buildSamlSettings(ssoConfig)
        
        // Generate SAML authentication request
        val authnRequest = AuthnRequest(settings)
        val samlRequest = authnRequest.getEncodedAuthnRequest()
        
        // Get IDP SSO URL
        val idpSsoUrl = ssoConfig[SsoConfigurations.idpSsoUrl] 
            ?: throw IllegalArgumentException("IDP SSO URL not configured")
        
        // Construct redirect URL with SAMLRequest and RelayState parameters
        val separator = if (idpSsoUrl.contains("?")) "&" else "?"
        return "$idpSsoUrl${separator}SAMLRequest=${java.net.URLEncoder.encode(samlRequest, "UTF-8")}&RelayState=${java.net.URLEncoder.encode(state, "UTF-8")}"
    }
    
    private fun buildSamlSettings(ssoConfig: ResultRow): Saml2Settings {
        val idpEntityId = ssoConfig[SsoConfigurations.idpEntityId]
            ?: throw IllegalArgumentException("IDP Entity ID not configured")
        val idpSsoUrl = ssoConfig[SsoConfigurations.idpSsoUrl]
            ?: throw IllegalArgumentException("IDP SSO URL not configured")
        val idpCertificate = ssoConfig[SsoConfigurations.idpCertificate]
            ?: throw IllegalArgumentException("IDP Certificate not configured")
        val spEntityId = ssoConfig[SsoConfigurations.spEntityId]
            ?: "$baseUrl/auth/sso/saml/metadata"
        
        val acsUrl = "$baseUrl/auth/sso/saml/acs"
        
        // Build settings using Properties
        val samlProperties = Properties()
        
        // SP settings
        samlProperties.setProperty("onelogin.saml2.sp.entityid", spEntityId)
        samlProperties.setProperty("onelogin.saml2.sp.assertion_consumer_service.url", acsUrl)
        samlProperties.setProperty("onelogin.saml2.sp.nameidformat", "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress")
        
        // IDP settings
        samlProperties.setProperty("onelogin.saml2.idp.entityid", idpEntityId)
        samlProperties.setProperty("onelogin.saml2.idp.single_sign_on_service.url", idpSsoUrl)
        samlProperties.setProperty("onelogin.saml2.idp.x509cert", cleanCertificate(idpCertificate))
        
        // Security settings
        samlProperties.setProperty("onelogin.saml2.strict", "true")
        samlProperties.setProperty("onelogin.saml2.debug", "false")
        samlProperties.setProperty("onelogin.saml2.security.authnrequest_signed", "false")
        samlProperties.setProperty("onelogin.saml2.security.logoutrequest_signed", "false")
        samlProperties.setProperty("onelogin.saml2.security.logoutresponse_signed", "false")
        samlProperties.setProperty("onelogin.saml2.security.want_messages_signed", "false")
        samlProperties.setProperty("onelogin.saml2.security.want_assertions_signed", "true")
        samlProperties.setProperty("onelogin.saml2.security.want_assertions_encrypted", "false")
        samlProperties.setProperty("onelogin.saml2.security.want_nameid_encrypted", "false")
        samlProperties.setProperty("onelogin.saml2.security.sign_metadata", "false")
        
        return SettingsBuilder().fromProperties(samlProperties).build()
    }
    
    private fun cleanCertificate(certificate: String): String {
        // Remove PEM headers/footers and whitespace
        return certificate
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace("\\s".toRegex(), "")
    }
    
    private fun generateOidcRequest(ssoConfig: ResultRow, state: String): String {
        val issuerUrl = ssoConfig[SsoConfigurations.oidcIssuerUrl]
        val clientId = ssoConfig[SsoConfigurations.oidcClientId]
        val redirectUri = "$baseUrl/auth/sso/oidc/callback"
        
        return "$issuerUrl/protocol/openid-connect/auth?" +
            "client_id=$clientId&" +
            "redirect_uri=$redirectUri&" +
            "response_type=code&" +
            "scope=openid%20email%20profile&" +
            "state=$state"
    }
    
    private fun encryptSecret(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)
        
        val keySpec = SecretKeySpec(encryptionKey, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        
        val ciphertext = cipher.doFinal(plaintext.toByteArray())
        val combined = iv + ciphertext
        return Base64.getEncoder().encodeToString(combined)
    }
    
    private fun decryptSecret(encrypted: String?): String? {
        if (encrypted == null) return null
        
        try {
            val combined = Base64.getDecoder().decode(encrypted)
            val iv = combined.copyOfRange(0, 12)
            val ciphertext = combined.copyOfRange(12, combined.size)
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(encryptionKey, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            
            return String(cipher.doFinal(ciphertext))
        } catch (e: Exception) {
            logger.error(e) { "Failed to decrypt SSO secret" }
            return null
        }
    }
}
