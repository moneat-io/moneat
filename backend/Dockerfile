# syntax=docker/dockerfile:1.7
FROM node:20-alpine AS email-builder
WORKDIR /emails
COPY emails/package*.json ./
RUN npm ci
COPY emails/ ./
RUN npm run build:production

FROM gradle:8-jdk17 AS build
WORKDIR /app
COPY backend/build.gradle.kts backend/settings.gradle.kts backend/gradle.properties ./
COPY backend/gradle ./gradle
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle dependencies --no-daemon --build-cache || true
COPY backend/src ./src
COPY enterprise/backend /enterprise/backend
# Copy built email templates from email-builder stage into src so they're processed as resources
COPY --from=email-builder /emails/build/templates/email ./src/main/resources/email-templates/
ARG ENTERPRISE=false
RUN --mount=type=cache,target=/home/gradle/.gradle \
    if [ "$ENTERPRISE" = "true" ]; then \
      gradle shadowJar -Penterprise -PenterprisePath=/enterprise/backend --no-daemon --build-cache; \
    else \
      gradle shadowJar --no-daemon --build-cache; \
    fi

FROM eclipse-temurin:17-jre
RUN groupadd --system moneat && useradd --system --gid moneat --home /app --create-home moneat
WORKDIR /app
COPY --from=build /app/build/libs/moneat-backend-all.jar app.jar
RUN mkdir -p /app/storage/chunks /app/storage/artifact_bundles && chown -R moneat:moneat /app/storage
USER moneat
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget -q -O /dev/null http://localhost:8080/health/ready || exit 1
CMD ["java", "-jar", "app.jar"]
