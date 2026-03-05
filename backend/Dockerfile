# syntax=docker/dockerfile:1.22
FROM node:24-alpine AS email-builder
WORKDIR /emails
COPY emails/package*.json ./
RUN npm ci
COPY emails/ ./
RUN npm run build:production

FROM gradle:9-jdk17 AS build
WORKDIR /app
COPY backend/build.gradle.kts backend/settings.gradle.kts backend/gradle.properties ./
COPY backend/gradle ./gradle
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle dependencies --no-daemon --build-cache || true
COPY backend/src ./src
# Copy built email templates from email-builder stage into src so they're processed as resources
COPY --from=email-builder /emails/build/templates/email ./src/main/resources/email-templates/
# Copy enterprise (ee/) backend sources
COPY ee/backend /ee/backend
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle shadowJar --no-daemon --build-cache

FROM eclipse-temurin:25.0.2_10-jre
RUN apt-get update && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/*
RUN groupadd --system moneat && useradd --system --gid moneat --home /app --create-home moneat
WORKDIR /app
COPY --from=build /app/build/libs/moneat-backend-all.jar app.jar

# Download Datadog Java agent (profiling, APM, dynamic instrumentation)
ARG DD_JAVA_AGENT_VERSION=1.59.0
ARG DD_JAVA_AGENT_SHA256=f2aa729581b60af266cc6555cf9d9701e98ee71c194b4f25d97de354f46fc533
RUN wget -q -O /app/dd-java-agent.jar \
      "https://repo1.maven.org/maven2/com/datadoghq/dd-java-agent/${DD_JAVA_AGENT_VERSION}/dd-java-agent-${DD_JAVA_AGENT_VERSION}.jar" && \
    echo "${DD_JAVA_AGENT_SHA256}  /app/dd-java-agent.jar" | sha256sum -c -

COPY backend/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
RUN mkdir -p /app/storage/chunks /app/storage/artifact_bundles && chown -R moneat:moneat /app/storage
USER moneat
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget -q -O /dev/null http://localhost:8080/health/ready || exit 1
ENTRYPOINT ["/app/entrypoint.sh"]
