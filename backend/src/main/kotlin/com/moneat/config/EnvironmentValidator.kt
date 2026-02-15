package com.moneat.config

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class EnvironmentValidator {
    
    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>,
        val warnings: List<String>
    )
    
    fun validate(): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // CRITICAL: These must be set
        validateCriticalSecret("JWT_SECRET", errors)
        validateCriticalSecret("DATABASE_PASSWORD", errors)
        validateCriticalSecret("CLICKHOUSE_PASSWORD", errors)
        
        // CRITICAL: Production URLs must be set correctly
        validateProductionUrl("FRONTEND_URL", errors, warnings)
        validateProductionUrl("BACKEND_URL", errors, warnings)
        
        // CONDITIONAL: Required when features are enabled
        validateConditionalConfig(errors)
        
        return ValidationResult(errors.isEmpty(), errors, warnings)
    }
    
    private fun validateCriticalSecret(envVar: String, errors: MutableList<String>) {
        val value = getConfigValue(envVar)
        
        if (value.isNullOrBlank()) {
            errors.add("CRITICAL: $envVar environment variable is not set. This is required.")
        }
    }
    
    private fun validateProductionUrl(envVar: String, errors: MutableList<String>, warnings: MutableList<String>) {
        val value = getConfigValue(envVar)
        
        if (value.isNullOrBlank()) {
            errors.add("CRITICAL: $envVar environment variable is not set. This is required for production.")
        } else if (value.contains("localhost") || value.contains("127.0.0.1")) {
            warnings.add("WARNING: $envVar is set to '$value' which contains localhost. This should be a production URL in production environments.")
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
    }
    
    private fun validateRequired(envVar: String, reason: String, errors: MutableList<String>) {
        val value = getConfigValue(envVar)
        if (value.isNullOrBlank()) {
            errors.add("REQUIRED: $envVar is not set, but it's required because $reason.")
        }
    }

    private fun getConfigValue(key: String): String? {
        return EnvConfig.get(key)
            ?: System.getProperty(key)
    }
    
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
