rootProject.name = "moneat-backend"

// Enterprise modules (SSO, On-Call) — lives in ee/backend/ within the monorepo
include(":ee")
project(":ee").projectDir = file("../ee/backend")
