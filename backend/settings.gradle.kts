rootProject.name = "moneat-backend"

// Enterprise module — include when building with enterprise features
include(":enterprise")
project(":enterprise").projectDir = file("../enterprise/backend")
