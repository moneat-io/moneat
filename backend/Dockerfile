FROM gradle:8-jdk17 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true
COPY src ./src
RUN gradle shadowJar --no-daemon

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S moneat && adduser -S moneat -G moneat
WORKDIR /app
COPY --from=build /app/build/libs/moneat-backend-all.jar app.jar
USER moneat
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD wget --spider -q http://localhost:8080/health || exit 1
CMD ["java", "-jar", "app.jar"]
