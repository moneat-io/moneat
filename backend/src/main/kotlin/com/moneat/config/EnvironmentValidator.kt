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

package com.moneat.config

import com.moneat.runtime.MoneatProcessRole
import com.moneat.runtime.RuntimeMode
import com.moneat.secrets.SecretVaultPurpose
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class EnvironmentValidator {

    companion object {
        const val MIN_JWT_SECRET_LENGTH = 32
    }

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>,
        val warnings: List<String>
    )

    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val isSelfHost = EnvConfig.SelfHost.enabled

        // CRITICAL: These must be set
        validateCriticalSecret("JWT_SECRET", errors)
        validateCriticalSecret("DATABASE_PASSWORD", errors)
        validateCriticalSecret("CLICKHOUSE_PASSWORD", errors)
        validateCriticalSecret("DATA_SOURCE_ENCRYPTION_KEY", errors)
        validateSecretVaultConfig(errors)

        // CRITICAL: Production URLs must be set correctly (relaxed for self-host)
        if (!isSelfHost) {
            validateProductionUrl("FRONTEND_URL", errors, warnings)
            validateProductionUrl("BACKEND_URL", errors, warnings)
        }

        // CONDITIONAL: Required when features are enabled
        validateConditionalConfig(errors)

        return ValidationResult(errors.isEmpty(), errors, warnings)
    }

    private fun validateCriticalSecret(
        envVar: String,
        errors: MutableList<String>
    ) {
        val value = getConfigValue(envVar)

        if (value.isNullOrBlank()) {
            errors.add("CRITICAL: $envVar environment variable is not set. This is required.")
        } else if (
            (envVar == "JWT_SECRET" || envVar == "DATA_SOURCE_ENCRYPTION_KEY") &&
            value.length < MIN_JWT_SECRET_LENGTH
        ) {
            errors.add(
                "CRITICAL: $envVar must be at least $MIN_JWT_SECRET_LENGTH characters for security."
            )
        }
    }

    private fun validateProductionUrl(
        envVar: String,
        errors: MutableList<String>,
        warnings: MutableList<String>
    ) {
        val value = getConfigValue(envVar)

        if (value.isNullOrBlank()) {
            errors.add("CRITICAL: $envVar environment variable is not set. This is required for production.")
        } else if (value.contains("localhost") || value.contains("127.0.0.1")) {
            warnings.add(
                "WARNING: $envVar is set to '$value' which contains localhost. This should be a production URL in " +
                    "production environments."
            )
        }
    }

    private fun validateConditionalConfig(errors: MutableList<String>) {
        // Validate Slack configuration when enabled
        val slackEnabled = getConfigValue("SLACK_ENABLED")?.toBoolean() ?: false
        if (slackEnabled) {
            validateRequired("SLACK_CLIENT_ID", "Slack integration is enabled", errors)
            validateRequired("SLACK_CLIENT_SECRET", "Slack integration is enabled", errors)
            validateRequired("SLACK_REDIRECT_URI", "Slack integration is enabled", errors)
        }

        // Validate Discord configuration when enabled
        val discordEnabled = getConfigValue("DISCORD_ENABLED")?.toBoolean() ?: false
        if (discordEnabled) {
            validateRequired("DISCORD_CLIENT_ID", "Discord integration is enabled", errors)
            validateRequired("DISCORD_CLIENT_SECRET", "Discord integration is enabled", errors)
            validateRequired("DISCORD_REDIRECT_URI", "Discord integration is enabled", errors)
            validateRequired("DISCORD_BOT_TOKEN", "Discord integration is enabled", errors)
        }

        // Validate Stripe configuration when enabled
        val stripeEnabled = getConfigValue("STRIPE_ENABLED")?.toBoolean() ?: false
        if (stripeEnabled) {
            validateRequired("STRIPE_SECRET_KEY", "Stripe billing is enabled", errors)
            validateRequired("STRIPE_WEBHOOK_SECRET", "Stripe billing is enabled", errors)
        }

        // Validate On-Call configuration when enabled
        val onCallEnabled = getConfigValue("ONCALL_ENABLED")?.toBoolean() ?: false
        if (onCallEnabled) {
            validateRequired("EXPO_TOKEN", "On-Call mobile push notifications are enabled", errors)
        }

        // Validate AI Chat configuration when API key is present
        val openAiApiKey = getConfigValue("OPENAI_API_KEY")
        if (!openAiApiKey.isNullOrBlank()) {
            logger.info { "OpenAI API key detected - AI chat will be available for admin users" }
        }

        // Validate Datadog Agent configuration when enabled
        val ddAgentHost = getConfigValue("DD_AGENT_HOST")
        if (!ddAgentHost.isNullOrBlank()) {
            validateRequired("DD_SERVICE", "DD_AGENT_HOST is set (Datadog Agent enabled)", errors)
            validateRequired("DD_ENV", "DD_AGENT_HOST is set (Datadog Agent enabled)", errors)
        }

        // Validate profile payload limit (optional, defaults in code)
        val profileMaxPayloadBytes = getConfigValue("PROFILE_MAX_PAYLOAD_BYTES")
        if (!profileMaxPayloadBytes.isNullOrBlank()) {
            val parsed = profileMaxPayloadBytes.toIntOrNull()
            if (parsed == null || parsed <= 0) {
                errors.add(
                    "REQUIRED: PROFILE_MAX_PAYLOAD_BYTES must be a positive integer when set."
                )
            }
        }

        validateProcessRole(errors)
        validateWorkflowRuntimeConfig(errors)
    }

    private fun validateProcessRole(errors: MutableList<String>) {
        val configuredRole = getConfigValue(RuntimeMode.PROCESS_ROLE_ENV)
        try {
            MoneatProcessRole.from(configuredRole)
        } catch (e: IllegalArgumentException) {
            errors.add("REQUIRED: ${e.message}")
        }
    }

    private fun validateWorkflowRuntimeConfig(errors: MutableList<String>) {
        val workflowsEnabledRaw = getConfigValue("WORKFLOWS_ENABLED")
        if (workflowsEnabledRaw == null) {
            validateEnabledWorkflowRuntimeConfig(errors)
            return
        }

        val workflowsEnabled = workflowsEnabledRaw.toBooleanStrictOrNull()
        if (workflowsEnabled == null) {
            errors.add("REQUIRED: WORKFLOWS_ENABLED must be either 'true' or 'false' when set.")
            return
        }

        if (workflowsEnabled) {
            validateEnabledWorkflowRuntimeConfig(errors)
        }
    }

    private fun validateEnabledWorkflowRuntimeConfig(errors: MutableList<String>) {
        validateRequired("TEMPORAL_TARGET", "workflows are enabled", errors)
        validateRequired("TEMPORAL_NAMESPACE", "workflows are enabled", errors)
        validateWorkflowSecret("WORKFLOWS_CONNECTION_KEK", errors)
        validateWorkflowSecret("WORKFLOWS_SIGNING_KEY", errors)
        validateWorkflowSecret("WORKFLOWS_TEMPORAL_PAYLOAD_KEY", errors)
    }

    private fun validateWorkflowSecret(
        envVar: String,
        errors: MutableList<String>
    ) {
        val value = getConfigValue(envVar)
        if (value.isNullOrBlank()) {
            errors.add("REQUIRED: $envVar is not set, but it's required because workflows are enabled.")
        } else if (value.length < MIN_JWT_SECRET_LENGTH) {
            errors.add("REQUIRED: $envVar must be at least $MIN_JWT_SECRET_LENGTH characters for security.")
        }
    }

    private fun validateSecretVaultConfig(errors: MutableList<String>) {
        SecretVaultPurpose.entries.forEach { purpose ->
            validateOptionalSecret(purpose.activeSecretEnvVar, errors)
            validateOptionalPreviousSecrets(purpose.previousSecretsEnvVar, errors)
        }
        validateApplicationSecretsDistinct(errors)
    }

    private fun validateOptionalSecret(
        envVar: String,
        errors: MutableList<String>
    ) {
        val value = getConfigValue(envVar)
        if (!value.isNullOrBlank() && value.length < MIN_JWT_SECRET_LENGTH) {
            errors.add("REQUIRED: $envVar must be at least $MIN_JWT_SECRET_LENGTH characters for security.")
        }
    }

    private fun validateOptionalPreviousSecrets(
        envVar: String,
        errors: MutableList<String>
    ) {
        val value = getConfigValue(envVar)?.takeIf { it.isNotBlank() } ?: return
        value.split(",").forEach { entry ->
            val pair = entry.split("=", limit = 2)
            if (pair.size != 2) {
                errors.add("REQUIRED: $envVar entries must be 'keyId=secret'.")
                return@forEach
            }
            val keyId = pair[0].trim()
            val secret = pair[1].trim()
            if (keyId.isBlank()) {
                errors.add("REQUIRED: $envVar keyId must not be blank.")
            }
            if (secret.isBlank()) {
                errors.add("REQUIRED: $envVar secret must not be blank.")
                return@forEach
            }
            if (secret.length < MIN_JWT_SECRET_LENGTH) {
                errors.add("REQUIRED: $envVar secrets must be at least $MIN_JWT_SECRET_LENGTH characters.")
            }
        }
    }

    private fun validateApplicationSecretsDistinct(errors: MutableList<String>) {
        val coreSecretKeys =
            listOf(
                "JWT_SECRET",
                "DATA_SOURCE_ENCRYPTION_KEY",
                "WORKFLOWS_SIGNING_KEY",
                "WORKFLOWS_TEMPORAL_PAYLOAD_KEY"
            )
        val directValues =
            (coreSecretKeys + SecretVaultPurpose.secretEnvVars).distinct().mapNotNull { key ->
                getConfigValue(key)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { value -> ConfigSecretValue(key, value) }
            }
        val values = directValues + previousSecretValues()
        values
            .groupBy { it.value }
            .filterValues { entries -> entries.size > 1 }
            .values
            .forEach { duplicates ->
                errors.add(
                    "REQUIRED: Application secrets must be distinct; reused by " +
                        duplicates.joinToString(", ") { it.name } + "."
                )
            }
    }

    private fun previousSecretValues(): List<ConfigSecretValue> =
        SecretVaultPurpose.entries.flatMap { purpose ->
            getConfigValue(purpose.previousSecretsEnvVar)
                ?.takeIf { it.isNotBlank() }
                ?.split(",")
                .orEmpty()
                .mapNotNull { entry ->
                    val pair = entry.split("=", limit = 2)
                    if (pair.size != 2) return@mapNotNull null
                    val keyId = pair[0].trim()
                    val secret = pair[1].trim()
                    if (keyId.isBlank() || secret.isBlank()) return@mapNotNull null
                    ConfigSecretValue("${purpose.previousSecretsEnvVar}[$keyId]", secret)
                }
        }

    private fun validateRequired(
        envVar: String,
        reason: String,
        errors: MutableList<String>
    ) {
        val value = getConfigValue(envVar)
        if (value.isNullOrBlank()) {
            errors.add("REQUIRED: $envVar is not set, but it's required because $reason.")
        }
    }

    private fun getConfigValue(key: String): String? {
        return EnvConfig.get(key)
            ?: System.getProperty(key)
    }

    private data class ConfigSecretValue(
        val name: String,
        val value: String
    )

    fun validateAndFailFast() {
        logger.info { "Validating environment variables..." }

        val result = validate()

        result.warnings.forEach { warning ->
            logger.warn { "  - $warning" }
        }

        if (!result.isValid) {
            logger.error { "Environment validation failed with ${result.errors.size} error(s):" }
            result.errors.forEach { error ->
                logger.error { "  - $error" }
            }

            // Fail fast - terminate the application
            throw IllegalStateException(
                "Application cannot start due to missing or invalid environment variables. " +
                    "See error messages above for details."
            )
        }

        logger.info { "Environment validation passed ✓" }
    }
}
