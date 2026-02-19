rootProject.name = "moneat-backend"

// Enterprise module — include when the enterprise checkout exists.
// Default path resolves to ../moneat-enterprise/backend relative to the repo root.
// Override: ./gradlew build -PenterprisePath=/abs/path/to/enterprise/backend
val enterpriseDir = file(
    providers.gradleProperty("enterprisePath")
        .getOrElse("../../moneat-enterprise/backend")
)

if (enterpriseDir.resolve("build.gradle.kts").exists()) {
    include(":enterprise")
    project(":enterprise").projectDir = enterpriseDir
}
