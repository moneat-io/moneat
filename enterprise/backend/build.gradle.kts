plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.moneat.enterprise"
version = "0.0.1"

repositories {
    mavenCentral()
}

dependencies {
    // Depend on the core backend (root project)
    implementation(rootProject)

    // Core's implementation dependencies are not transitive — redeclare needed ones
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlin.logging)
    implementation(libs.lettuce)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.auth0:java-jwt:4.4.0")
    implementation(libs.oauth2.oidc.sdk)
    implementation(libs.java.saml.core)
    implementation(libs.postgresql)
}
