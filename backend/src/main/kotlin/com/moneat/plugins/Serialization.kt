package com.moneat.plugins

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = true
            // CRITICAL: Enable classDiscriminator to avoid Map serialization issues
            // This allows Map<String, Any> to serialize without requiring @Serializable
            classDiscriminator = "#type"
            // Use lenient mode to allow unquoted keys and trailing commas
            // This helps with backwards compatibility
            coerceInputValues = true
        })
    }
}
