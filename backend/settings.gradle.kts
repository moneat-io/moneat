rootProject.name = "moneat-backend"

// Enterprise module — include when the enterprise checkout exists.
// Default path resolves to ../moneat-enterprise/backend relative to the repo root.
// Override: ./gradlew build -PenterprisePath=/abs/path/to/enterprise/backend
val configuredEnterprisePath = providers.gradleProperty("enterprisePath").orNull
val enterpriseDirCandidates =
    listOfNotNull(
        configuredEnterprisePath,
        "../../moneat-enterprise/backend",
        "/enterprise/backend",
    ).map { file(it).canonicalFile }

val enterpriseDir = enterpriseDirCandidates.firstOrNull { it.resolve("build.gradle.kts").exists() }

if (enterpriseDir != null) {
    include(":enterprise")
    project(":enterprise").projectDir = enterpriseDir
}
