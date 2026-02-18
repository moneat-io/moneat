plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
}

group = "com.moneat.enterprise"
version = "0.0.1"

repositories {
    mavenCentral()
}

val exposed_version = "0.46.0"
val ktor_version = "2.3.7"

dependencies {
    // Depend on the core backend (root project)
    implementation(rootProject)

    // Core's implementation dependencies are not transitive — redeclare needed ones
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-auth-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor_version")
    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("io.ktor:ktor-client-cio:$ktor_version")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-json:$exposed_version")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("com.auth0:java-jwt:4.4.0")
    implementation("com.nimbusds:oauth2-oidc-sdk:11.10")
    implementation("com.onelogin:java-saml-core:2.9.0")
    implementation("org.postgresql:postgresql:42.7.7")
}
