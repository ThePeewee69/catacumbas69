# Glossary

The acronyms, rule prefixes and names used in [`orch.md`](orch.md), the READMEs and the ADRs. Versions are in
[`TECHNOLOGIES.md`](TECHNOLOGIES.md).

**Keep it current (DOC-6):** a change that brings a new acronym, rule prefix or name into the docs adds it here in
the same commit.

## Project names

| Name | Meaning |
|---|---|
| **catacumbas69** | The project and this repository; also the Pulsar tenant, the MongoDB database and the domain `*.catacumbas69.com` |
| **Catapulsar** | Web UI for Pulsar and BFF for the other apps (port 8081; formerly Pulsário) |
| **Cataponte** | Runs the bridges: consumes a topic and fans each message out to other topics (8082; formerly Passário) |
| **Catametricas** | Collects every topic's statistics and serves them (8083; formerly Contário) |
| **Cataudit** | Owns the audit trail (8084) |
| **Catavigia** | Watches the platform and keeps the history of every alert (8085) |
| **Catasonarr** | TV series manager that speaks Sonarr's API v3 (8086; decision 109); GPL-3.0 |
| **Sonarr** | The open-source TV series manager Catasonarr replaces; its API v3 is what Prowlarr, Bazarr, Overseerr, Jellyseerr and Homarr speak |
| **catacommons** | Shared Java library of the apps (not a service) |
| **meloes** | The NUC that runs MicroShift, where everything in `microshift/` is deployed |
| **necromancer** | The Mac (Intel, macOS 13) where the code is written, built and pushed |
| **K3s cluster** | The other cluster, synced by Flux from `clusters/` and `apps/<group>/`; not part of these apps |
| **system bridge** | A bridge an app registers itself at start-up, `audit-<app>`, copying its events to Cataudit |

## Rule prefixes (orch.md)

Every rule has an ID `<PREFIX>-<n>`; the prefix names the area.

| Prefix | Area | Section |
|---|---|---|
| **ARCH** | Architecture | §2 |
| **CFG** | Configuration contract | §2.1 |
| **READ** | Readability principles, every language | §3.1 |
| **JAVA** | Java conventions | §3.2 |
| **HTML** | HTML conventions | §3.3 |
| **JS** | JavaScript conventions | §3.4 |
| **CSS** | CSS conventions | §3.5 |
| **DOC** | Documentation | §3.6 |
| **CODE** | Java and Spring code structure | §4.1 |
| **PUL** | Pulsar usage | §4.2 |
| **MONGO** | MongoDB usage | §4.3 |
| **EVT** | Events | §4.4 |
| **API** | REST contracts and errors | §5 |
| **UI** | Catapulsar's frontend | §6 |
| **PERF** | Performance | §7 |
| **RES** | Resilience | §8 |
| **TEST** | Testing | §9 |
| **OBS** | Observability | §10 |
| **SEC** | Security | §11 |
| **OPS** | Build and deploy | §12 |
| **GIT** | Workflow and definition of done | §13 |
| **G** | A known gap: code that does not yet follow a rule | §14 |

The strength words come from RFC 2119: **MUST** / **MUST NOT** are mandatory, **SHOULD** is the default unless
there is a written reason, **MAY** is optional.

## Technical acronyms

| Acronym | Stands for | Here |
|---|---|---|
| **ADR** | Architecture Decision Record | `apps/docs/adr/NNNN-title.md` (DOC-4) |
| **API** | Application Programming Interface | Each app's REST API under `/api/v1` |
| **BFF** | Backend For Frontend | Catapulsar's API, which forwards to the other apps for its UI (ARCH-2) |
| **BOM** | Bill Of Materials | The Spring Boot parent that manages library versions |
| **CA** | Certificate Authority | Our own CA, issued by cert-manager (decision 97) |
| **CI** | Continuous Integration | The GitHub Actions workflows |
| **CORS** | Cross-Origin Resource Sharing | Closed by default (API-7; `CATAPONTE_CORS_ALLOWED_ORIGINS`) |
| **CPU** | Central Processing Unit | meloes has 4; requests and limits in each Deployment |
| **CRD** | Custom Resource Definition | Kubernetes types added by operators (cert-manager, Prometheus, MongoDB) |
| **CRI-O** | Container Runtime Interface, OCI implementation | MicroShift's container runtime |
| **CRUD** | Create, Read, Update, Delete | The bridges' API in Cataponte |
| **CSRF** / **XSRF** | Cross-Site Request Forgery | Catapulsar's token in the `XSRF-TOKEN` cookie (SEC-2) |
| **DLQ** | Dead-Letter Queue | The topic a message goes to after too many failed deliveries (decision 68) |
| **DNS** | Domain Name System | The records that point `*.catacumbas69.com` at meloes |
| **DOM** | Document Object Model | The page Alpine.js updates |
| **DTO** | Data Transfer Object | The records the REST APIs read and write |
| **ECDSA** | Elliptic Curve Digital Signature Algorithm | The key type of our CA (P-256) |
| **ECS** | Elastic Common Schema | The JSON layout of the apps' logs (OBS-3) |
| **GitOps** | Git as the source of truth for what runs | Argo CD on meloes, Flux on K3s |
| **GPL** | GNU General Public License | Sonarr's licence, so also Catasonarr's (`apps/catasonarr/LICENSE`, GPL-3.0) |
| **HTTP(S)** | HyperText Transfer Protocol (Secure) | Every Route is HTTPS (SEC-8) |
| **ID** | Identifier | Rule IDs, trace IDs, bridge IDs |
| **IT** | Integration Test | Test classes named `*IT`, run by failsafe with Testcontainers |
| **JDK** / **JRE** / **JVM** | Java Development Kit / Runtime Environment / Virtual Machine | Temurin 21 |
| **JSON** | JavaScript Object Notation | Events, API bodies, logs |
| **LTS** | Long-Term Support | Java 21 |
| **LVMS** | Logical Volume Manager Storage | MicroShift's storage (TopoLVM, `topolvm-provisioner`) |
| **MDC** | Mapped Diagnostic Context | Per-thread log fields: `bridgeId`, `trace_id` (OBS-4, OBS-11) |
| **NFS** | Network File System | The NAS share with the media and the downloads |
| **NUC** | Next Unit of Computing | The small Intel PC that is meloes |
| **OIDC** | OpenID Connect | The login protocol Dex speaks (decision 98) |
| **OTel** | OpenTelemetry | The Java agent in every app (OBS-11) |
| **OTLP** | OpenTelemetry Protocol | How the agent sends traces to Alloy (ports 4317 and 4318) |
| **PKCE** | Proof Key for Code Exchange | Used by Grafana's login through Dex |
| **PSI** | Pressure Stall Information | meloes' CPU, memory and I/O pressure, on the host dashboard |
| **PVC** | Persistent Volume Claim | A pod's disk: MongoDB, Pulsar, Prometheus, Loki, Tempo, Grafana, registry |
| **RBAC** | Role-Based Access Control | Kubernetes permissions; Argo CD's `argocd-rbac-cm` |
| **REST** | Representational State Transfer | The apps' HTTP APIs |
| **RHEL** | Red Hat Enterprise Linux | meloes' operating system |
| **RPM** | RPM Package Manager | How MicroShift is installed on meloes |
| **RSS** | Really Simple Syndication | The indexers' feeds of new releases, read on every RSS sync |
| **SCC** | Security Context Constraints | OpenShift's pod security; granted by RoleBindings in git |
| **SHA** | Secure Hash Algorithm (here: a commit's hash) | The image tag `<version>-<short sha>` (OPS-2) |
| **SSO** | Single Sign-On | One Dex session for Catapulsar, Grafana and Argo CD |
| **TLS** | Transport Layer Security | Ended at the router with the wildcard certificate (`termination: edge`) |
| **TMDB** / **TVDB** | The Movie Database / TheTVDB | Where Catasonarr reads series, seasons and episodes (decision 109) |
| **Torznab** | Torrent indexer API in the Newznab style | How Prowlarr's indexers answer searches |
| **TTL** | Time To Live | MongoDB TTL indexes; claims and status documents that expire |
| **UI** | User Interface | Catapulsar's web page; the Grafana, Argo CD and Pulsar UIs |
| **URI** / **URL** | Uniform Resource Identifier / Locator | MongoDB's connection string; the apps' editable URLs |
| **UUID** | Universally Unique Identifier | The Dex user's ID, and other generated identifiers |
| **WCAG** | Web Content Accessibility Guidelines | The UI's accessibility target: 2.2 level AA (§3.3) |
| **YAML** | YAML Ain't Markup Language | Every manifest in `microshift/` |
