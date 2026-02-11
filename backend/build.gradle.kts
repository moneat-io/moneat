import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val kotlin_version: String by project
val ktor_version: String by project
val logback_version: String by project
val exposed_version: String by project
val hikari_version: String by project

plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
    id("io.ktor.plugin") version "2.3.7"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    jacoco
}

group = "com.moneat"
version = "0.0.1"

application {
    mainClass.set("com.moneat.ApplicationKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

tasks.shadowJar {
    archiveBaseName.set("moneat-backend")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest {
        attributes("Main-Class" to application.mainClass.get())
    }
}

repositories {
    mavenCentral()
}

// Configure integration test source set
sourceSets {
    create("integrationTest") {
        kotlin {
            srcDir("src/integrationTest/kotlin")
        }
        resources {
            srcDir("src/integrationTest/resources")
        }
        compileClasspath += sourceSets["main"].output + configurations["testRuntimeClasspath"]
        runtimeClasspath += output + compileClasspath
    }
}

// Fix duplicate resources issue
tasks.named<ProcessResources>("processIntegrationTestResources") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

dependencies {
    // Ktor Server
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-cors-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-auth-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-rate-limit-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-call-logging-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor_version")

    // Ktor Client (for ClickHouse HTTP API)
    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("io.ktor:ktor-client-cio:$ktor_version")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")

    // Database - PostgreSQL
    implementation("org.jetbrains.exposed:exposed-core:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-dao:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposed_version")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:$exposed_version")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("com.zaxxer:HikariCP:$hikari_version")
    implementation("org.flywaydb:flyway-core:10.6.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.6.0")
    
    // Date/Time
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.1")

    // Redis
    implementation("io.lettuce:lettuce-core:6.3.1.RELEASE")
    
    // Email - SMTP
    implementation("com.sun.mail:jakarta.mail:2.0.2")
    
    // Security
    implementation("org.mindrot:jbcrypt:0.4")
    implementation("commons-codec:commons-codec:1.16.0")
    
    // SSO
    implementation("com.onelogin:java-saml-core:2.9.0")
    implementation("com.nimbusds:oauth2-oidc-sdk:11.10")

    // Billing
    implementation("com.stripe:stripe-java:29.5.0")
    
    // MessagePack for mobile replay decoding
    implementation("org.msgpack:msgpack-core:0.9.8")

    // JSON path query for uptime monitoring
    implementation("com.jayway.jsonpath:json-path:2.9.0")

    // Environment variables
    implementation("io.github.cdimascio:dotenv-kotlin:6.4.1")

    // Logging
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    
    // OpenTelemetry for logging to Moneat
    implementation("io.opentelemetry:opentelemetry-api:1.34.0")
    implementation("io.opentelemetry:opentelemetry-sdk:1.34.0")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.34.0")
    implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.1.0-alpha")
    
    // Sentry - Error monitoring
    implementation("io.sentry:sentry-kotlin-extensions:7.6.0")
    implementation("io.sentry:sentry-logback:7.6.0")

    // Testing
    testImplementation("io.ktor:ktor-server-tests-jvm:$ktor_version")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:$kotlin_version")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    testImplementation("com.h2database:h2:2.2.224")
    
    // Integration testing dependencies
    val integrationTestImplementation by configurations
    integrationTestImplementation("io.ktor:ktor-server-tests-jvm:$ktor_version")
    integrationTestImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
    integrationTestImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
    integrationTestImplementation("org.junit.jupiter:junit-jupiter-engine:5.10.1")
    integrationTestImplementation("org.testcontainers:testcontainers:1.19.3")
    integrationTestImplementation("org.testcontainers:postgresql:1.19.3")
    integrationTestImplementation("org.testcontainers:clickhouse:1.19.3")
}

// Task to run the E2E data seeder
tasks.register<JavaExec>("seedE2EData") {
    group = "e2e"
    description = "Seeds E2E test data into the database"
    
    classpath = sourceSets["main"].runtimeClasspath + sourceSets["test"].runtimeClasspath
    mainClass.set("com.moneat.e2e.DataSeederKt")
    
    standardOutput = System.out
    errorOutput = System.err
}

// Task to run the Demo data seeder
tasks.register<JavaExec>("seedDemoData") {
    group = "demo"
    description = "Seeds realistic demo data for screenshots and demos"
    
    classpath = sourceSets["main"].runtimeClasspath + sourceSets["test"].runtimeClasspath
    mainClass.set("com.moneat.demo.DemoDataSeederKt")
    
    standardOutput = System.out
    errorOutput = System.err
}

// Integration test task
val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests with Testcontainers"
    group = "verification"
    
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    
    shouldRunAfter(tasks.test)
    
    useJUnitPlatform()
    
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// JaCoCo configuration
jacoco {
    toolVersion = "0.8.11"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test, integrationTest)
    
    executionData.setFrom(
        fileTree(layout.buildDirectory.asFile).include("jacoco/*.exec")
    )
    
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
    
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude(
                    "**/models/**",
                    "**/config/**",
                    "**/Application*",
                    "**/e2e/**",
                    "**/demo/**"
                )
            }
        })
    )
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    
    violationRules {
        rule {
            limit {
                // Initial soft limit - will increase to 65% by Week 6
                minimum = "0.45".toBigDecimal()
            }
        }
    }
    
    classDirectories.setFrom(tasks.jacocoTestReport.get().classDirectories)
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
    
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.check {
    dependsOn(integrationTest)
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        allWarningsAsErrors = true
    }
}
