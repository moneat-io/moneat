plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.shadow)
    alias(libs.plugins.detekt)
    jacoco
}

kotlin {
    jvmToolchain(17)
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
    isZip64 = true
    // Required for ServiceFileTransformer to merge Flyway's plugin descriptors.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    manifest {
        attributes("Main-Class" to application.mainClass.get())
    }
    // GraalVM's polyglot language selectors (org.graalvm.polyglot:js, org.graalvm.js:js)
    // are POM-only meta modules; their resolved artifact is a .pom that shadow cannot
    // unzip ("Cannot expand ZIP …js-*.pom"). Exclude the meta artifacts — the actual
    // language jars (js-language, truffle-api, truffle-runtime, regex, icu4j, …) are
    // separate modules and stay on the classpath.
    dependencies {
        exclude(dependency("org.graalvm.polyglot:js:.*"))
        exclude(dependency("org.graalvm.js:js:.*"))
    }
}

val dashboardTemplateSourceDir =
    providers.gradleProperty("dashboardTemplateSource")
        .orElse(providers.environmentVariable("DASHBOARD_TEMPLATE_SOURCE_DIR"))
        .orElse(layout.projectDirectory.dir("../grafana-dashboards").asFile.absolutePath)

tasks.register<JavaExec>("convertDashboardTemplates") {
    group = "dashboard"
    description = "Converts community dashboard JSON into Moneat dashboard template resources."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.moneat.dashboards.tools.DashboardTemplateConverterKt")
    args(
        dashboardTemplateSourceDir.get(),
        layout.projectDirectory.dir("src/main/resources/dashboard-templates").asFile.absolutePath,
        layout.buildDirectory.file("reports/dashboard-template-conversion.json").get().asFile.absolutePath
    )
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
    implementation(project(":feature-spi"))
    implementation(project(":ingest-common"))

    // Ktor Server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.metrics.micrometer)

    // Ktor Client (for ClickHouse HTTP API)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    // Database - PostgreSQL
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)
    implementation(libs.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.shedlock.core)
    implementation(libs.shedlock.provider.exposed)

    // Date/Time
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.core)

    // Redis
    implementation(libs.lettuce)

    // Workflow execution
    implementation(libs.jackson.module.kotlin)
    implementation(libs.temporal.sdk)
    implementation(libs.graalvm.polyglot)
    implementation(libs.graalvm.polyglot.js)

    // JDBC drivers for custom datasources
    runtimeOnly(libs.mysql.connector.j)
    runtimeOnly(libs.mariadb.java.client)
    runtimeOnly(libs.mssql.jdbc)
    runtimeOnly(libs.clickhouse.jdbc)
    runtimeOnly(libs.sqlite.jdbc)
    runtimeOnly(libs.snowflake.jdbc)
    implementation(libs.mongodb.driver.sync)
    implementation(libs.aws.sdk.cloudwatch)
    implementation(libs.google.cloud.bigquery)

    // Email - SMTP
    implementation(libs.jakarta.mail)

    // Security
    implementation(libs.jbcrypt)
    implementation(libs.commons.codec)

    // Billing
    implementation(libs.stripe.java)

    // MessagePack for mobile replay decoding
    implementation(libs.msgpack.core)

    // JSON path query for uptime monitoring
    implementation(libs.jsonpath)

    // YAML parsing for Sigma detection-rule import
    implementation(libs.snakeyaml)

    // Headless Chromium for synthetic browser/E2E tests
    implementation(libs.playwright)

    // Environment variables
    implementation(libs.dotenv.kotlin)

    // Logging
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.janino)
    implementation(libs.kotlin.logging)

    // OpenTelemetry for logging to Moneat
    implementation(libs.opentelemetry.api)
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
    implementation(libs.opentelemetry.logback)

    // Micrometer / Prometheus operational metrics
    implementation(libs.micrometer.registry.prometheus)

    // Sentry - Error monitoring
    implementation(libs.sentry.kotlin)
    implementation(libs.sentry.logback)

    // Detekt formatting (ktlint via Detekt)
    detektPlugins(libs.detekt.formatting)

    // Koin - dependency injection
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    testImplementation(libs.koin.test)

    // Testing
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.h2)
    testImplementation(libs.mockk)
    testImplementation(libs.temporal.testing)
    testImplementation(kotlin("reflect"))

    // Integration testing dependencies
    val integrationTestImplementation by configurations
    integrationTestImplementation(libs.ktor.server.test.host)
    integrationTestImplementation(libs.kotlin.test.junit)
    integrationTestImplementation(libs.junit.jupiter.api)
    integrationTestImplementation(libs.junit.jupiter.engine)
    integrationTestImplementation(libs.testcontainers.core)
    integrationTestImplementation(libs.testcontainers.postgresql)
    integrationTestImplementation(libs.testcontainers.clickhouse)

    // Zstandard decompression for DD agent payloads
    runtimeOnly(libs.zstd.jni)

    // Protobuf for decoding DD process-agent binary payloads
    implementation(libs.protobuf.java)

    // OTLP protobuf definitions (ExportLogsServiceRequest, ExportTraceServiceRequest, etc.)
    implementation(libs.opentelemetry.proto)

    // Enterprise modules (SSO, On-Call) — always included, license-gated at runtime
    runtimeOnly(project(":ee"))
    runtimeOnly(project(":features:ai"))
    runtimeOnly(project(":features:analytics"))
    runtimeOnly(project(":features:billing"))
    runtimeOnly(project(":features:contact"))
    runtimeOnly(project(":features:dashboards"))
    runtimeOnly(project(":features:datadog"))
    runtimeOnly(project(":features:featureflags"))
    runtimeOnly(project(":features:incident"))
    runtimeOnly(project(":features:llm"))
    runtimeOnly(project(":features:logs"))
    runtimeOnly(project(":features:mcp"))
    runtimeOnly(project(":features:monitoring"))
    runtimeOnly(project(":features:otlp"))
    runtimeOnly(project(":features:overview"))
    runtimeOnly(project(":features:sso"))
    runtimeOnly(project(":features:statuspage"))
    runtimeOnly(project(":features:summary"))
    runtimeOnly(project(":features:uptime"))
    runtimeOnly(project(":features:workflows"))
}

// Task to copy email templates into resources
val copyEmailTemplates =
    tasks.register<Copy>("copyEmailTemplates") {
        group = "build"
        description = "Copies built email templates into backend resources"

        from("${project.rootDir}/../emails/build/templates/email")
        into(layout.buildDirectory.dir("resources/main/email-templates"))

        // Only copy if source exists
        onlyIf { file("${project.rootDir}/../emails/build/templates/email").exists() }
    }

// Task to copy email templates into test resources
val copyEmailTemplatesForTest =
    tasks.register<Copy>("copyEmailTemplatesForTest") {
        group = "build"
        description = "Copies built email templates into backend test resources"

        from("${project.rootDir}/../emails/build/templates/email")
        into(layout.buildDirectory.dir("resources/test/email-templates"))

        // Only copy if source exists
        onlyIf { file("${project.rootDir}/../emails/build/templates/email").exists() }
    }

// Ensure email templates are copied before processing resources
tasks.named("processResources") {
    dependsOn(copyEmailTemplates)
}

tasks.named("processTestResources") {
    dependsOn(copyEmailTemplatesForTest)
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

    // Pass --reseed argument if provided: ./gradlew seedDemoData -Preseed
    if (project.hasProperty("reseed")) {
        args("--reseed")
    }

    standardOutput = System.out
    errorOutput = System.err
}

// Integration test task
val integrationTest =
    tasks.register<Test>("integrationTest") {
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
    toolVersion = "0.8.15"
}

val jacocoBackendMainExcludes =
    arrayOf(
        "**/models/**", // data classes — no logic to test
        "**/config/**", // infrastructure wiring (DB clients, Redis, Sentry, env)
        "**/plugins/**", // Ktor plugin bootstrap — framework wiring, not business logic
        "**/logging/**", // log appender setup
        "**/enterprise/**", // on-call/feature-flag integration stubs in core
        "**/sso/**", // OIDC/OAuth routes & service — integration-heavy; gate via detekt + manual/E2E
        "**/Application*", // entry point
        "**/di/**", // Koin module assembly — pure wiring, no business logic
        "**/monitoring/MonitoringModule*", // enterprise monitoring module hook — framework wiring only
        "**/analytics/AnalyticsModule*", // analytics route/worker module assembly
        "**/datadog/DatadogModule*", // datadog route/worker module assembly
        "**/ingestion/queue/RedisQueueWorker*", // blocking Redis worker loop; covered by queue client tests
        "**/workflows/services/WorkflowExecutionWorker*", // Redis worker loop; covered by runtime checks
    )

tasks.jacocoTestReport {
    val eeProject = project(":ee")
    dependsOn(tasks.test, eeProject.tasks.named("test"))

    // Unit tests only — merges core + enterprise SSO execution data. SSO sources live in :ee.
    executionData.setFrom(
        files(
            layout.buildDirectory.file("jacoco/test.exec"),
            eeProject.layout.buildDirectory.file("jacoco/test.exec"),
        )
    )

    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }

    val backendFiltered =
        sourceSets.main.get().output.classesDirs.map { dir ->
            fileTree(dir) {
                exclude(*jacocoBackendMainExcludes)
            }
        }
    val enterpriseClasses =
        fileTree(eeProject.layout.buildDirectory.dir("classes/kotlin/main")) {
            exclude(*jacocoBackendMainExcludes)
        }
    classDirectories.setFrom(files(backendFiltered, enterpriseClasses))

    sourceDirectories.setFrom(
        files(
            sourceSets.main.get().allSource.srcDirs.filter { it.exists() },
            eeProject.file("src/main/kotlin"),
        )
    )
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)

    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                // Set via COVERAGE_GATE_PHASE env var (reporting-only, soft, hard)
                val phase = System.getenv("COVERAGE_GATE_PHASE") ?: "reporting-only"
                val threshold =
                    when(phase) {
                        "reporting-only" -> "0.00"
                        "soft" -> "0.55"
                        "hard" -> "0.65"
                        else -> "0.65"
                    }
                minimum = threshold.toBigDecimal()
            }
        }
    }

    // Gate matches historical scope: core main only (enterprise SSO is reported separately above).
    classDirectories.setFrom(
        files(
            sourceSets.main.get().output.classesDirs.map { dir ->
                fileTree(dir) {
                    exclude(*jacocoBackendMainExcludes)
                }
            }
        )
    )
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)

    // Run test classes in parallel (each class has its own H2 in-memory DB so isolation is safe)
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    jvmArgs("-Xmx1g")

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.check {
    dependsOn(integrationTest)
    dependsOn("detekt")
}

tasks.build {
    dependsOn("detekt")
    dependsOn("installGitHooks")
}

// Install git hooks so contributors get pre-commit formatting automatically
val installGitHooks =
    tasks.register<Exec>("installGitHooks") {
        group = "setup"
        description = "Configures git to use project hooks from .githooks/ (installs on first build)"

        val repoRoot = rootProject.rootDir.parentFile
        onlyIf { File(repoRoot, ".git").exists() }
        workingDir = repoRoot
        commandLine("sh", "-c", "git config core.hooksPath .githooks && chmod +x .githooks/*")
    }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
    }
}

// Detekt - static analysis
detekt {
    config.setFrom(files("$projectDir/detekt.yml"))
    baseline = file("$projectDir/detekt-baseline.xml")
    buildUponDefaultConfig = true
    parallel = true
    source.setFrom(files("src/main/kotlin", "src/test/kotlin", "src/integrationTest/kotlin"))
}

// detektFormat - auto-correct formatting issues via detekt-formatting (ktlint)
// Run with: ./gradlew detektFormat
tasks.register<io.gitlab.arturbosch.detekt.Detekt>("detektFormat") {
    description = "Auto-corrects code style issues using detekt-formatting (ktlint)"
    autoCorrect = true
    // Use a format-only config — disables analysis rules so only ktlint formatting rules run
    config.setFrom(files("$projectDir/detekt-format.yml"))
    buildUponDefaultConfig = false
    parallel = true
    setSource(files("src/main/kotlin", "src/test/kotlin", "src/integrationTest/kotlin"))
    // No type resolution needed for formatting rules — keeps this task fast
    classpath.setFrom()
    reports {
        html.required.set(false)
        xml.required.set(false)
        txt.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}
