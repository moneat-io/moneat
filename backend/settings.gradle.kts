rootProject.name = "moneat-backend"

include(":feature-spi")
include(":ingest-common")
include(":features:analytics")
include(":features:contact")
include(":features:datadog")
include(":features:featureflags")
include(":features:llm")
include(":features:mcp")
include(":features:monitoring")
include(":features:overview")
include(":features:sso")
include(":features:statuspage")
include(":features:summary")

// Enterprise modules (SSO, On-Call) — lives in ee/backend/ within the monorepo
include(":ee")
project(":ee").projectDir = file("../ee/backend")
