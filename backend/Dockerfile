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
# Copy built email templates from email-builder stage
COPY --from=email-builder /emails/build/templates/email ./build/resources/main/email-templates/
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle shadowJar --no-daemon --build-cache

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S moneat && adduser -S moneat -G moneat
WORKDIR /app
COPY --from=build /app/build/libs/moneat-backend-all.jar app.jar
USER moneat
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget --spider -q http://localhost:8080/health || exit 1
CMD ["java", "-jar", "app.jar"]
