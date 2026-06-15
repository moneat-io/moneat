plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    jacoco
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":feature-spi"))

    // Depend on the core Moneat backend
    implementation(project(":"))
    implementation(project(":features:mcp"))
    implementation(project(":features:sso"))

    // Core's implementation dependencies are not transitive — redeclare needed ones
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.koin.core)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlin.logging)
    implementation(libs.lettuce)
    implementation(libs.postgresql)

    // SSO
    implementation(libs.oauth2.oidc.sdk)
    implementation(libs.java.saml.core)
    implementation(libs.java.jwt)

    // On-Call
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sentry.kotlin)
    implementation(libs.temporal.sdk)

    // Detekt formatting (ktlint)
    detektPlugins(libs.detekt.formatting)

    // Unit tests
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.h2)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    executionData.setFrom(
        fileTree(layout.buildDirectory.asFile).include("jacoco/test.exec")
    )
    classDirectories.setFrom(
        sourceSets["main"].output.classesDirs.map { dir ->
            fileTree(dir) {
                include("**/com/moneat/enterprise/sso/**")
            }
        }
    )
    sourceDirectories.setFrom(
        files("$projectDir/src/main/kotlin/com/moneat/enterprise/sso")
    )
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

// Line gate is enforced on the core backend project; ee only publishes SSO-focused coverage HTML/XML.
tasks.jacocoTestCoverageVerification {
    enabled = false
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

detekt {
    config.setFrom(files("$projectDir/detekt.yml"))
    baseline = file("$projectDir/detekt-baseline.xml")
    buildUponDefaultConfig = true
    parallel = true
    source.setFrom(files("src/main/kotlin", "src/test/kotlin"))
}

tasks.register<io.gitlab.arturbosch.detekt.Detekt>("detektFormat") {
    description = "Auto-corrects code style issues using detekt-formatting (ktlint)"
    autoCorrect = true
    config.setFrom(files("$projectDir/detekt-format.yml"))
    buildUponDefaultConfig = false
    parallel = true
    setSource(files("src/main/kotlin", "src/test/kotlin"))
    classpath.setFrom()
    reports {
        html.required.set(false)
        xml.required.set(false)
        txt.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}
