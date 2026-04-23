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
 *
 * @param publicKeyPem PEM-encoded RSA public key (with or without -----BEGIN/END----- headers).
 *   Defaults to the production Moneat signing key. Override in tests to use a test key pair.
 */
class LicenseValidator(publicKeyPem: String = PRODUCTION_PUBLIC_KEY_PEM) {

    private val publicKey by lazy {
        // Strip PEM headers/footers and whitespace, then decode the base64 DER body
        val der = publicKeyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\n", "")
            .replace(" ", "")
        val decoded = Base64.getDecoder().decode(der)
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
        val pad = (BASE64_PADDING_SIZE - s.length % BASE64_PADDING_SIZE) % BASE64_PADDING_SIZE
        return s + "=".repeat(pad)
    }

    companion object {
        private const val BASE64_PADDING_SIZE = 4

        // Public key corresponding to the Moneat license signing private key.
        // The private key is held only by Moneat and is never distributed.
        private val PRODUCTION_PUBLIC_KEY_PEM = """
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0HN6RtHQG3iX2h7gua64
zSsSAgXl8p5eNzCjXqKabwN5jUXjupQ35zTxOIOjBMPcQ4/VsEACStYu0LqzcKgT
RWAB1tLQyj69LykA9fDtRnyhG8NxppVlMHTQcOynbWMMlJQFyjYF5vLIFkQ5/u58
s0ucVvOMRH0BvvQPo+PN7EON7iLCiid/GGj9gpF7uMRLhu2+rP/uAODRTqo16qmQ
wiXvsl4kcuc2tKAjQK89DE2WYyEnNNjCZIsPmNIGBUkgndz2cNHnvXAlqmvNXecU
EcLgKwrzDafmttqSyuKzXBXoGOrnj5d3Gl9Je/Oo3jZmUJt7ntdCWJsj4pBf0h0o
RwIDAQAB
-----END PUBLIC KEY-----
        """.trimIndent()

        private val defaultInstance by lazy { LicenseValidator() }

        /** Validates using the production public key. Used by [FeatureRegistry]. */
        fun validate(key: String): LicenseInfo? = defaultInstance.validate(key)
    }
}
