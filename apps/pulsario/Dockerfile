# ── Stage 1: build ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /build

# Cache dependencies first
COPY pom.xml .
RUN mvn -B dependency:go-offline -q

# Build
COPY src ./src
RUN mvn -B package -DskipTests -q

# ── Stage 2: runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# OpenShift runs as an arbitrary UID — make sure group has write access
RUN chown -R 1001:0 /app && chmod -R g=u /app

COPY --from=build /build/target/pulsar-client-*.jar app.jar

USER 1001
EXPOSE 8081

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
