# syntax=docker/dockerfile:1.21
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
# Copy enterprise backend sources if present in build context (used by moneat-deploy CI/CD)
ARG ENTERPRISE=false
RUN --mount=type=bind,source=.,target=/repo,readonly \
    --mount=type=cache,target=/home/gradle/.gradle \
    if [ "$ENTERPRISE" = "true" ] && [ -d /repo/enterprise/backend ]; then \
      cp -r /repo/enterprise/backend /enterprise/backend && \
      gradle shadowJar -Penterprise -PenterprisePath=/enterprise/backend --no-daemon --build-cache; \
    else \
      gradle shadowJar --no-daemon --build-cache; \
    fi

FROM eclipse-temurin:25.0.2_10-jre
RUN groupadd --system moneat && useradd --system --gid moneat --home /app --create-home moneat
WORKDIR /app
COPY --from=build /app/build/libs/moneat-backend-all.jar app.jar
RUN mkdir -p /app/storage/chunks /app/storage/artifact_bundles && chown -R moneat:moneat /app/storage
USER moneat
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget -q -O /dev/null http://localhost:8080/health/ready || exit 1
CMD ["java", "-jar", "app.jar"]
