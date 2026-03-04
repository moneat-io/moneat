// Moneat Enterprise - proprietary module
// Copyright (c) 2026 Moneat. All rights reserved.
// See ee/LICENSE for license terms.

package com.moneat.enterprise.license

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate
import java.util.Base64

private val logger = KotlinLogging.logger {}

@Serializable
data class LicensePayload(
    val customer: String,
    val plan: String,
    /** Feature names granted by this license, e.g. ["sso", "oncall"] */
    val features: List<String>,
    val issuedAt: String,
    /** ISO date (yyyy-MM-dd). Null = never expires. */
    val expiresAt: String? = null,
)

data class LicenseInfo(
    val customer: String,
    val plan: String,
    val features: Set<String>,
    val expiresAt: LocalDate?,
)

/**
 * Validates RSA-signed Moneat license keys.
 *
 * Key format: <base64url(payloadJson)>.<base64url(RSA-SHA256 signature)>
 * The signature is computed over the exact bytes of the base64url-encoded payload.
 *
 * To issue a key, sign with scripts/sign-license.sh using the Moneat private key.
 */
object LicenseValidator {

    // Public key corresponding to the Moneat license signing private key.
    // The private key is held only by Moneat and is never distributed.
    private val PUBLIC_KEY_PEM = """
        MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAkF4TXH+JjeaHHtMZDbNN
        11PjLuxg/4RWDfdSc2FpQ1iPOIThzj7nO62Yk3yvDotsB/dqxIZdP72kRlEfy8Gg
        VQnhcKXOJP+t4wOqGCkfl4zah1NvAKm5pvbmokNyFbLeEIhDuxK+J+tVe+Si5ChF
        fKjy3D8lPHxERAyJuFMY6m9v4KM99AfkUlmffJM3BYn+iz27kEihRn2+H2E4vNYv
        CLq/u+Oqnb7BrDyz6ZbkKKPURPduL+uflsim410sS40te63LWldZ/BX+WzSZfR+Q
        mwHqp3zbotWrKhbG3YqLXF7Zb83Vbwd6Wcv0Uyu2qWB/dOfqb2AHHVnXU181wSMO
        gwIDAQAB
    """.trimIndent().replace("\n", "").replace(" ", "")

    private val publicKey by lazy {
        val decoded = Base64.getDecoder().decode(PUBLIC_KEY_PEM)
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decoded))
    }

    private val decoder = Base64.getUrlDecoder()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Validates a license key and returns the license info if valid, null otherwise.
     * Returns null for expired, tampered, or malformed keys.
     */
    fun validate(key: String): LicenseInfo? = runCatching {
        val dot = key.lastIndexOf('.')
        require(dot > 0) { "Invalid key format" }

        val encodedPayload = key.substring(0, dot)
        val encodedSig = key.substring(dot + 1)

        // Verify RSA-SHA256 signature over the encoded payload bytes
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initVerify(publicKey)
        sig.update(encodedPayload.toByteArray(Charsets.UTF_8))
        val sigBytes = decoder.decode(padBase64Url(encodedSig))
        require(sig.verify(sigBytes)) { "Invalid signature" }

        // Decode and parse payload
        val payloadJson = String(decoder.decode(padBase64Url(encodedPayload)), Charsets.UTF_8)
        val payload = json.decodeFromString<LicensePayload>(payloadJson)

        // Check expiry
        val expiresAt = payload.expiresAt?.let { LocalDate.parse(it) }
        require(expiresAt == null || !LocalDate.now().isAfter(expiresAt)) {
            "License expired on $expiresAt"
        }

        LicenseInfo(
            customer = payload.customer,
            plan = payload.plan,
            features = payload.features.toSet(),
            expiresAt = expiresAt,
        )
    }.onFailure { e ->
        logger.warn { "License validation failed: ${e.message}" }
    }.getOrNull()

    // Base64url strings from openssl may omit padding — add it back for Java's decoder
    private fun padBase64Url(s: String): String {
        val pad = (4 - s.length % 4) % 4
        return s + "=".repeat(pad)
    }
}
