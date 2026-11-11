# syntax=docker/dockerfile:1
# OPS-1 — multi-stage build, layered jar, non-root, OpenShift/MicroShift friendly (arbitrary UID)
#
# Built from apps/ (the Maven reactor), so catacommons is compiled with the app:
#   cd apps && docker build -f catavigia/Dockerfile -t catavigia .
# For the cluster: apps/build/push.sh catavigia (Podman, linux/amd64, pushed to the registry on meloes).
# The Maven repository is a BuildKit cache mount, kept between builds (Docker 23+, Podman, Buildah).

# ── Stage 1: build ──────────────────────────────────────────────────────────
# Runs on the build machine's own platform (an ARM Mac builds natively): the jar is the same everywhere.
# Only the runtime stage is the target platform (linux/amd64 for MicroShift on meloes, orch.md OPS-1).
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -pl catavigia -am package -DskipTests \
 && java -Djarmode=tools -jar catavigia/target/catavigia-*.jar extract --layers --launcher \
        --destination catavigia/target/extracted

# ── Stage 2: runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/catavigia/target/extracted/dependencies/ ./
COPY --from=build /build/catavigia/target/extracted/spring-boot-loader/ ./
COPY --from=build /build/catavigia/target/extracted/snapshot-dependencies/ ./
COPY --from=build /build/catavigia/target/extracted/application/ ./
# Arbitrary UID with group 0 (SEC-5)
RUN chgrp -R 0 /app && chmod -R g=u /app
USER 1001
EXPOSE 8085
# JVM options (heap size, exit on OutOfMemoryError) come from JAVA_TOOL_OPTIONS in the Deployment, so they change
# without a new image (orch.md decision 89); without it the JVM uses its defaults.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
