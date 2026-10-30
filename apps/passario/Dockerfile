# OPS-1 — multi-stage build, layered jar, non-root, OpenShift/MicroShift friendly (arbitrary UID)

# ── Stage 1: build ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/passario-*.jar extract --layers --launcher --destination target/extracted

# ── Stage 2: runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/target/extracted/dependencies/ ./
COPY --from=build /build/target/extracted/spring-boot-loader/ ./
COPY --from=build /build/target/extracted/snapshot-dependencies/ ./
COPY --from=build /build/target/extracted/application/ ./
# Arbitrary UID with group 0 (SEC-5)
RUN chgrp -R 0 /app && chmod -R g=u /app
USER 1001
EXPOSE 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "org.springframework.boot.loader.launch.JarLauncher"]
