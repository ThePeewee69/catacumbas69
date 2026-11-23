# Technologies

What catacumbas69 is built with and runs on, layer by layer: each technology, its version and the file that pins it.
The architecture is in [`orch.md`](orch.md) §1; why each choice was made is in its decisions and ADRs.

**Keep it current (DOC-6):** a change that adds, removes or upgrades a technology updates this file in the same
commit. A version written as **`*`** is not pinned: ArgoCD follows the newest chart on its own, and the version in
brackets is the one seen running on the date given.

## Apps (`apps/`)

| Technology | Version | Pinned in | Used for |
|---|---|---|---|
| Java (Eclipse Temurin) | 21 | `java.version` in each `pom.xml`; `apps/*/Dockerfile` (`eclipse-temurin:21-jre-alpine`) | Every app and catacommons |
| Spring Boot | 3.5.0 | `spring-boot-starter-parent` in each `pom.xml` | Web (Tomcat), validation, Data MongoDB, Actuator; manages the versions of Spring, Tomcat, Jackson, Micrometer, the MongoDB driver and Testcontainers |
| Spring Security + OAuth2 client | managed by Spring Boot | `apps/catapulsar/pom.xml`, `apps/catasonarr/pom.xml` | Login through Dex (SEC-2) for Catapulsar and Catasonarr's page; Sonarr's API key for Catasonarr's API v3 |
| Apache Pulsar client and admin client | 4.0.3 | `pulsar.version` in each `pom.xml` | Producers, consumers, TableViews and the admin API |
| MongoDB Java driver | managed by Spring Boot (5.4) | `spring-boot-starter-data-mongodb` | Every collection (§4.3) |
| Micrometer + Prometheus registry | managed by Spring Boot | each `pom.xml` | `/actuator/prometheus` (OBS-1) |
| springdoc-openapi | 2.8.9 | `springdoc.version` in each app's `pom.xml` | OpenAPI 3 and Swagger UI (API-6) |
| Lombok | managed by Spring Boot | `apps/cataponte/pom.xml` | Boilerplate in a few of Cataponte's classes |
| catacommons | the repository's version | `apps/catacommons/pom.xml` | Shared library: CloudEvents, publisher, provisioning, settings, consumer control |
| CloudEvents | 1.0 (JSON, our own envelope) | `catacommons` | Every event (§4.4, ADR 0008) |
| TVmaze API | public REST, no key | `catasonarr.metadata.tvmaze-url` in `apps/catasonarr/src/main/resources/application.properties` | Catasonarr's series, episodes and other titles (decision 112); CC BY-SA, credited in its page |
| TMDB API | v3, API Read Access Token | `catasonarr.metadata.tmdb-url` (same file); Secret `catasonarr-tmdb` | Catasonarr's pictures and pt-PT summaries, optional (decision 112) |
| JDK `HttpClient` (Spring `RestClient`) | Java 21 | `CoreConfig` in `apps/catasonarr` | Catasonarr's calls to TVmaze and TMDB, following TVmaze's redirects |
| Spring Boot structured logging | ECS | `LOGGING_STRUCTURED_FORMAT_CONSOLE` in each app's ConfigMap | JSON logs on stdout (OBS-3) |

## Frontend (Catapulsar)

| Technology | Version | Pinned in | Used for |
|---|---|---|---|
| Alpine.js | 3.14.8 | `alpinejs.version` in `apps/catapulsar/pom.xml` (WebJar) | The UI's reactivity, no build step (§6) |
| Poppins (Light, Bold) | OFL, as outlines | `apps/catasonarr/src/main/resources/static/catasonarr-*.svg` | The letters of Catasonarr's logo (decision 115) |
| HTML, CSS, JavaScript | plain, no framework or bundler | `apps/catapulsar/src/main/resources/static/`, `apps/catasonarr/src/main/resources/static/` | Catapulsar's and Catasonarr's pages |

## Data and messaging (meloes)

| Technology | Version | Pinned in | Used for |
|---|---|---|---|
| Apache Pulsar (standalone) | 4.2.2 | `microshift/apps/pulsar/broker-deployment.yaml` | Every topic: events, audit, tasks, settings, consumer status, user bridges |
| Pulsar Manager | v0.4.0 | `microshift/apps/pulsar/manager-deployment.yaml` | Pulsar's own web admin |
| DataStax Pulsar Admin Console | 2.2.0 | `microshift/apps/pulsar/console-deployment.yaml` | Second Pulsar admin UI, with the WebSocket test client |
| MongoDB | 7.0.0 (ReplicaSet, 3 members) | `version` in `microshift/apps/mongodb/mongodb-cluster.yaml` | Every app's data |
| MongoDB Community Operator | `*` | `microshift/argocd/mongodb.yaml` | Runs the ReplicaSet, its users and their connection strings |
| mongo-express | 1.0.0-alpha.4 | `microshift/apps/mongodb/mongo-express.yaml` | MongoDB's web UI (decision 101) |

## Platform (meloes)

| Technology | Version | Pinned in | Used for |
|---|---|---|---|
| Red Hat Enterprise Linux | 9.8 | the host | meloes' operating system |
| MicroShift | 4.22 | the host (RPM) | Kubernetes/OpenShift for everything below; its router serves the Routes, LVMS (TopoLVM) the volumes |
| CRI-O | MicroShift's | the host | Container runtime |
| Argo CD | v3.4.4 (`quay.io/argoproj/argocd`, seen on 2026-11-21) | installed by hand, not in git; its configuration is in `microshift/apps/argocd-config/` and the root Application in `microshift/bootstrap/microshift.yaml` | GitOps: every Application in `microshift/argocd/` |
| Docker Registry | 2.8.3 | `microshift/apps/registry/registry-deployment.yaml` | The apps' images (`registry.catacumbas69.com`) |
| cert-manager | `*` | `microshift/argocd/cert-manager.yaml` | Our CA and the router's wildcard certificate (decision 97) |
| trust-manager | `*` | `microshift/argocd/trust-manager.yaml` | The CA bundle (`catacumbas69-ca`) in the namespaces that need it |
| Dex | `*` (2.46) | `microshift/argocd/dex.yaml` | OpenID Connect login for Catapulsar, Grafana and Argo CD (decision 98) |
| kube-prometheus-stack (Prometheus Operator, Prometheus, Grafana, kube-state-metrics, node-exporter) | chart 93.2.2 | `microshift/argocd/monitoring.yaml` | Metrics and dashboards (decision 90) |
| Grafana Loki | `*` (chart 21.4.1, Loki 3.7.10 on 2026-11-20) | `microshift/argocd/loki.yaml` (grafana-community) | Every log of meloes, 30 days (decision 104) |
| Grafana Tempo | `*` | `microshift/argocd/tempo.yaml` (grafana-community) | The apps' traces, 14 days (decision 104) |
| Grafana Alloy | `*` (v1.22 on 2026-11-17) | `microshift/argocd/alloy.yaml` | Ships pod logs and the journal to Loki, traces to Tempo |
| OpenTelemetry Java agent | `2` = newest 2.x (2.34.0 on 2026-11-20) | image tag in each app's Deployment (`autoinstrumentation-java:2`) | Traces of HTTP, MongoDB and Pulsar (OBS-11, decision 105) |

## Build, tests and CI

| Technology | Version | Pinned in | Used for |
|---|---|---|---|
| Maven | 3.9 | `apps/*/Dockerfile` (`maven:3.9-eclipse-temurin-21-alpine`) | Build of every module (`apps/pom.xml` aggregates them) |
| JUnit 5, Mockito, AssertJ, Awaitility | managed by Spring Boot | each `pom.xml` | Unit tests |
| Testcontainers | managed by Spring Boot | each `pom.xml` | Integration tests (`*IT`) against real Pulsar 4.2.2 and MongoDB 7.0 (decision 91) |
| Playwright for Java | 1.63.0 | `playwright.version` in `apps/catapulsar/pom.xml` | Catapulsar's UI smoke test (decision 93) |
| Checkstyle | 10.18.2 | `checkstyle.version` in each `pom.xml` | Java style in `mvn verify` |
| Node.js | v26.13.1 | `node.version` in `apps/catapulsar/pom.xml` and `apps/catasonarr/pom.xml` (frontend-maven-plugin) | Runs the frontend linters in `mvn verify` |
| ESLint, Stylelint, html-validate, Prettier | 10.15.0, 18.0.0, 11.21.1, 3.10.1 | `apps/catapulsar/package.json`, `apps/catasonarr/package.json` | JS, CSS and HTML lint and format (§3.7), for both pages |
| Podman (Podman Desktop) | 5.8 | the Mac | Builds and pushes the images (`apps/build/push.sh`) |
| GitHub Actions | — | `.github/workflows/` | `apps` (`mvn verify`, Temurin 21), `manifests`, `scripts` (decision 88) |
| kustomize, kubeconform | 5.8.3, 0.8.0 | `.github/scripts/install-manifest-tools.sh` | Building and validating every manifest in `microshift/` |
| ShellCheck | the runner's | `.github/scripts/shellcheck.sh` | Every `*.sh` in the repository |
