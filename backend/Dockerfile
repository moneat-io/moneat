# syntax=docker/dockerfile:1.27
# Build artifacts are architecture-neutral, so keep build stages on the native runner platform.
FROM --platform=$BUILDPLATFORM node:24-alpine AS email-builder
WORKDIR /emails
COPY emails/package*.json ./
RUN npm ci
COPY emails/ ./
RUN npm run build:production

FROM --platform=$BUILDPLATFORM gradle:9-jdk17 AS build
WORKDIR /app
COPY backend/build.gradle.kts backend/settings.gradle.kts backend/gradle.properties ./
COPY backend/gradle ./gradle
# settings.gradle.kts includes :ee at ../ee/backend, so the project directory
# must exist before any Gradle invocation configures the build.
COPY ee/backend/build.gradle.kts \
     ee/backend/detekt.yml \
     ee/backend/detekt-format.yml \
     ee/backend/license-header.txt \
     /ee/backend/
# settings.gradle.kts also includes :feature-spi, :ingest-common and :features:*;
# their project directories must exist before Gradle configures the build.
COPY backend/feature-spi/build.gradle.kts ./feature-spi/
COPY backend/ingest-common/build.gradle.kts ./ingest-common/
COPY backend/features ./features
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle dependencies --no-daemon --build-cache || true
COPY backend/src ./src
COPY backend/feature-spi/src ./feature-spi/src
COPY backend/ingest-common/src ./ingest-common/src
# Copy built email templates from email-builder stage into src so they're processed as resources
COPY --from=email-builder /emails/build/templates/email ./src/main/resources/email-templates/
# Copy enterprise (ee/) backend sources
COPY ee/backend /ee/backend
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle shadowJar --no-daemon --build-cache

FROM eclipse-temurin:25.0.3_9-jre
# Keep wget from the pinned Temurin base repository; exact distro package versions drift across image rebuilds.
# hadolint ignore=DL3008
RUN apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/*
RUN groupadd --system moneat && useradd --system --gid moneat --home /app --create-home moneat
WORKDIR /app
COPY --from=build /app/build/libs/moneat-backend-all.jar app.jar

# Synthetic browser/E2E tests: install Chromium + its OS deps via Playwright's CLI
# (bundled in the shaded jar). Browsers land in PLAYWRIGHT_BROWSERS_PATH; enable the
# probe to run them. Concurrency is capped to one run at a time inside the app.
ENV PLAYWRIGHT_BROWSERS_PATH=/app/ms-playwright
ENV SYNTHETICS_BROWSER_ENABLED=true
RUN java -cp app.jar com.microsoft.playwright.CLI install --with-deps chromium && \
    chown -R moneat:moneat /app/ms-playwright

# Download Datadog Java agent (profiling, APM, dynamic instrumentation)
ARG DD_JAVA_AGENT_VERSION=1.59.0
ARG DD_JAVA_AGENT_SHA256=f2aa729581b60af266cc6555cf9d9701e98ee71c194b4f25d97de354f46fc533
RUN wget -q -O /app/dd-java-agent.jar \
      "https://repo1.maven.org/maven2/com/datadoghq/dd-java-agent/${DD_JAVA_AGENT_VERSION}/dd-java-agent-${DD_JAVA_AGENT_VERSION}.jar" && \
    printf "%s  /app/dd-java-agent.jar\n" "${DD_JAVA_AGENT_SHA256}" > /tmp/dd-java-agent.sha256 && \
    sha256sum -c /tmp/dd-java-agent.sha256 && \
    rm /tmp/dd-java-agent.sha256

ARG MONEAT_VERSION=dev
ENV MONEAT_VERSION=${MONEAT_VERSION}

COPY backend/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
RUN mkdir -p /app/storage/chunks /app/storage/artifact_bundles && chown -R moneat:moneat /app/storage
USER moneat
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget -q -O /dev/null http://localhost:8080/health/ready || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]
