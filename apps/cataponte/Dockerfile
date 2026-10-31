# syntax=docker/dockerfile:1
# OPS-1 — multi-stage build, layered jar, non-root, OpenShift/MicroShift friendly (arbitrary UID)
#
# Built from apps/ (the Maven reactor), so catacommons is compiled with the app:
#   cd apps && docker build -f cataponte/Dockerfile -t cataponte .
# The Maven repository is a BuildKit cache mount, kept between builds (Docker 23+, Podman, Buildah).

# ── Stage 1: build ──────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl cataponte -am package -DskipTests \
 && java -Djarmode=tools -jar cataponte/target/cataponte-*.jar extract --layers --launcher \
        --destination cataponte/target/extracted

# ── Stage 2: runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/cataponte/target/extracted/dependencies/ ./
COPY --from=build /build/cataponte/target/extracted/spring-boot-loader/ ./
COPY --from=build /build/cataponte/target/extracted/snapshot-dependencies/ ./
COPY --from=build /build/cataponte/target/extracted/application/ ./
# Arbitrary UID with group 0 (SEC-5)
RUN chgrp -R 0 /app && chmod -R g=u /app
USER 1001
EXPOSE 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "org.springframework.boot.loader.launch.JarLauncher"]
