rootProject.name = "moneat-backend"

include(":feature-spi")
include(":ingest-common")
include(":features:analytics")
include(":features:datadog")
include(":features:monitoring")
include(":features:sso")

// Enterprise modules (SSO, On-Call) — lives in ee/backend/ within the monorepo
include(":ee")
project(":ee").projectDir = file("../ee/backend")
