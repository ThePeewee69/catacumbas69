# orch.md — Engineering rules for `catacumbas69/apps`

This file is the single source of truth for **how Catapulsar, Cataponte, Catametricas and Cataudit are built,
changed and run**.
It is written for two readers:

- **Humans** — read it top to bottom once; use the checklists when reviewing.
- **Coding agents** — treat every rule tagged `[MUST]` as a hard constraint. Before changing code, read the
  rules for the area you touch. When a rule and existing code disagree, follow the rule and fix the code in
  the same change (or list it under [Known gaps](#14-known-gaps) if out of scope). Never weaken a rule to fit
  the code.

Rule strength follows RFC 2119: **MUST** / **MUST NOT** are mandatory, **SHOULD** is the default unless
there is a written reason, **MAY** is optional. Each rule has an ID (e.g. `ARCH-3`) so reviews and commits
can cite it.

This file lives at `apps/orch.md`. `apps/<module>/orch.md` (catapulsar, cataponte, catametricas, cataudit,
catacommons) are symlinks to it — edit only the original.

---

## 1. System overview

```
                    ┌──────────────────────────────┐
  Browser ─────────▶│  Catapulsar  (:8081)         │──── Admin REST ───▶ Pulsar broker
  (Alpine.js UI)    │  UI + BFF API                │                     (HTTP :8080 / NodePort 31080)
                    └──┬──────────┬──────────┬───┬─┘
         REST bridges  │   REST   │          │   │ REST overviews (ADR 0013)
                       │   audit  │          │   └──────────────────────────────────────┐
                       ▼          ▼          ▼ catapulsar_settings                      ▼
              ┌──────────────┐ ┌──────────┐ ┌──────────────────────────┐ ┌──────────────────┐
              │ Cataponte    │ │ Cataudit │ │ MongoDB  catacumbas69    │ │ Catametricas (N) │── Admin REST ──▶ Pulsar
              │ (:8082)      │ │ (:8084)  │ │ (ReplicaSet, 3 members)  │ │ (:8083)          │   (topic stats)
              │ bridge engine│ └────▲─────┘ └──────────────────────────┘ └────────▲─────────┘
              └──────┬───────┘      │ consumes cataudit/audit                       │ shares catametricas/collect-tasks
                     │ binary protocol: user bridges, and audit-<app>: <app>/events → cataudit/audit
                     ▼
               Pulsar broker (:6650 / NodePort 31650)
```

| Component | Responsibility | Owns (data) | Talks to |
|---|---|---|---|
| **Catapulsar** | Web UI for Pulsar (tenants, namespaces, topics, subscriptions, backlog, produce); UI for bridges, for the audit trail (BFF to Cataudit) and for the statistics (overviews from Catametricas' API); runtime settings (Cataponte, Cataudit and Catametricas URLs, intervals), published on the settings topic; events for its actions | `catapulsar_settings` | Pulsar Admin API, Pulsar binary (produce/peek, events), Cataponte REST, Cataudit REST, Catametricas REST, MongoDB |
| **Cataponte** | Runs bridges: consumes a source topic and fans each message out to 1..n destination topics; CRUD API for bridge config; system bridges registered by the apps (`audit-<app>`); per-topic traffic counts; loop blocking; bridge areas limited by glob patterns (ADR 0014); events for bridge and area changes. Runs N replicas, each running every enabled bridge; Pulsar spreads the messages (ADR 0012) | `cataponte_bridges`, `cataponte_areas`, `cataponte_subscription_cleanups`, `cataponte_traffic_minutes`, `cataponte_traffic_totals`, `cataponte_worker_status` | Pulsar binary + Admin API, MongoDB |
| **Cataudit** | Owns the audit trail (ADR 0009): consumes `catacumbas69/cataudit/audit` (fed by the `audit-<app>` bridges), stores every event once, serves a query API | `cataudit_events` | Pulsar binary + Admin API, MongoDB |
| **catacommons** | Shared library (not a service): CloudEvents envelope, event publisher with retries, Pulsar names, start-up provisioning of the tenant/namespace/topics, registration of the app's audit bridge (ADR 0008, 0009) | — | Pulsar binary + Admin API through each app's clients; Cataponte REST |
| **Catavigia** | Watches the platform and keeps the history of every alert (ADR 0015): checks as tasks on Pulsar, one per check and round, shared by its replicas; debounce; open, seen and archived alerts; backlog limits per subscription; events for every alert and limit change; `catavigia_alerts_open` metric. Only watches; the owner of each resource fixes it | `catavigia_alerts`, `catavigia_alerts_archive`, `catavigia_observations`, `catavigia_task_runs`, `catavigia_backlog_limits`; reads its thresholds from the settings topic | Pulsar Admin API + binary (tasks, consumers' status, events, settings), Cataponte, Catametricas and every app's health (REST), MongoDB |
| **Catametricas** | Collects every topic's statistics from Pulsar on an interval and stores traffic (messages/bytes in and out) and backlog per topic: latest snapshot, per minute (30 days), per hour and all-time (ADR 0007). The work is shared by every replica through collection tasks on Pulsar (ADR 0012). The only metrics API: topics, series and collector state (ADR 0013) | `catametricas_topic_snapshots`, `catametricas_topic_minutes`, `catametricas_topic_hours`, `catametricas_topic_totals`, `catametricas_task_runs`; reads its interval from the settings topic | Pulsar Admin API + binary (tasks, events, settings), MongoDB |

**Names.** The apps were renamed on 2026-10-31: Pulsário → **Catapulsar**, Passário → **Cataponte**,
Contário → **Catametricas** (folders, Maven artifacts, packages, classes, properties, environment variables,
metrics, images and docs). Every MongoDB collection now carries its owner's prefix (`catapulsar_*`,
`cataponte_*`, `catametricas_*`, `cataudit_*`). The apps keep **no backward compatibility** (decision 40): the
collections from before (`pulsario_settings`, `contario_*`, `bridges`, `bridge_traffic_*`, `audit_logs`) held only
test data and were dropped by hand; no code reads or migrates them.

**Events and audit (§15 phases 1–2, §4.4).** Every app owns a Pulsar namespace `catacumbas69/<app>`,
created at start-up, and publishes a CloudEvent to `catacumbas69/<app>/events` for every action it audits.
At start-up each app also registers its **system bridge** `audit-<app>` in Cataponte, which copies those
events to `catacumbas69/cataudit/audit`; Cataudit stores them and is the only audit trail. Catapulsar also
publishes the shared settings to the compacted topic `catacumbas69/catapulsar/settings`, read from phase 3.

Runtime: MicroShift on `meloes` (NUC, `192.168.86.35`), deployed with ArgoCD. Local development runs
from IntelliJ on the Mac (`necromancer`) against the cluster through NodePorts.

---

## 2. Architecture rules

- **ARCH-1 [MUST]** Each service owns its MongoDB collections. A service MUST NOT read or write another
  service's collection. Cross-service data goes through the owner's REST API or its events; there are no
  exceptions. Statistics come from Catametricas' API (ADR 0013) and shared settings travel on the settings
  topic (ADR 0011), never through another app's collection.
- **ARCH-2 [MUST]** The browser talks only to Catapulsar. Catapulsar exposes the bridge API to its UI as a
  **backend-for-frontend (BFF)** proxy (`/api/v1/bridges/**` → Cataponte). Cataponte is not exposed outside
  the cluster except for local development.
  *Why:* one origin (no CORS) and Cataponte stays internal. Catapulsar forwards the browser's address as
  `X-Forwarded-For` so Cataponte's audit events name the user. The audit view works the same way: the
  browser calls Catapulsar's `/api/v1/audit/**`, which forwards to Cataudit (ADR 0009).
- **ARCH-3 [MUST]** Cataponte is the only component that consumes or produces on behalf of a bridge.
  Catapulsar MUST NOT implement relay logic.
- **ARCH-4 [MUST]** Bridge configuration in MongoDB is the desired state; Cataponte reconciles running
  workers to it on a fixed interval (`bridge.reconcile.interval`). API calls change the document only;
  they MUST NOT start or stop workers directly. This keeps one code path for start/stop/restart.
- **ARCH-5 [MUST]** Cataponte may run **any number of replicas**; every replica runs every enabled bridge and
  **Pulsar spreads the messages** through the bridge's subscription type (ADR 0012, superseding ADR 0002):
  `Failover` (ordered) — one replica receives, the others stand by and take over; `Key_Shared` — spread by key,
  order kept per key; `Shared` (unordered) — spread round-robin. **`Exclusive` is not allowed**: the API refuses
  it and existing bridges were moved to `Failover`. Each replica adds to the traffic counters only what it relayed
  itself, and writes the status of its workers to `cataponte_worker_status`, so `GET /api/v1/bridges/status`
  answers for all replicas. After a subscription type change, replicas restart one by one; a replica whose type
  differs from the consumers still connected fails to start and retries with back-off until they agree.
- **ARCH-6 [MUST]** Bridge delivery is **at-least-once**: the source message is acknowledged only after
  every destination accepted it; any failure → negative ack → redelivery. Consumers of destination topics
  MUST tolerate duplicates. Do not add "exactly-once" claims anywhere without Pulsar transactions.
- **ARCH-7 [SHOULD]** Keep services stateless apart from MongoDB and Pulsar. In-memory state (e.g. Cataponte's
  worker map and counters) is a cache of runtime facts, never configuration.
- **ARCH-8 [MUST]** Configuration follows 12-factor: every environment-specific value is an env var with a
  cluster-internal default in `application.properties`. No profile-specific property files with hosts or
  credentials.

### 2.1 Configuration contract

| Env var | Service | Default (in-cluster) | Local (necromancer) |
|---|---|---|---|
| `PULSAR_SERVICE_URL` | all (admin API; namespace creation at start-up) | `http://pulsar.pulsar.svc.cluster.local:8080` | `http://192.168.86.35:31080` |
| `PULSAR_BROKER_URL` | all (binary protocol; events) | `pulsar://pulsar.pulsar.svc.cluster.local:6650` | `pulsar://192.168.86.35:31650` |
| `PULSAR_LISTENER_NAME` | all | *(empty)* | `external` |
| `SPRING_DATA_MONGODB_URI` | all | Secret `catacumbas69-apps-mongodb` (key `uri`): the operator's connection string of the user `catacumbas69-apps` (decision 84) | NodePort `32017` + `authSource=admin&directConnection=true` |
| `SPRING_DATA_MONGODB_DATABASE` | all (in the cluster: the operator's URI names `admin`) | `catacumbas69` (ConfigMap) | *(not set: the URI names the database)* |
| `CATAPONTE_URL` | Catapulsar (default of the editable URL), Catavigia (until the setting exists), Catametricas, Cataudit and Catavigia (audit bridge registration) | `http://cataponte.<ns>.svc.cluster.local:8082` | `http://localhost:8082` |
| `CATAUDIT_URL` | Catapulsar (default of the editable URL of the audit view), Catavigia (until the setting exists) | `http://cataudit:8084` | `http://localhost:8084` |
| `CATAMETRICAS_URL` | Catapulsar (default of the editable URL of the overviews), Catavigia (until the setting exists) | `http://catametricas:8083` | `http://localhost:8083` |
| `CATAVIGIA_URL` | Catapulsar (default of the editable URL of the alerts) | `http://catavigia:8085` | `http://localhost:8085` |
| `CATAPULSAR_URL` | Catavigia (`service.down` of Catapulsar, decision 78) | `http://catapulsar:8081` | `http://localhost:8081` |
| `CATAVIGIA_EXPECTED_BROKERS` (optional) | Catavigia (`pulsar.broker.down` below this) | `1` | `1` |
| `CATAPULSAR_REFRESH_INTERVAL_SECONDS` | Catapulsar | `10` | `10` |
| `CATAMETRICAS_COLLECT_INTERVAL_SECONDS` | Catapulsar, Catametricas (same value) | `60` | `60` |
| `CATACOMMONS_*` (optional) | all | see `CatacommonsProperties`: retries `1s,2s,4s`, retention `7d`/`2GB`, backlog quota `1GB` (must be below the retention) | same |
| `CATAPONTE_STATUS_WRITEINTERVAL`, `CATAPONTE_STATUS_STALEAFTER` (optional) | Cataponte (status of each replica, ADR 0012) | `PT5S`, `PT30S` | same |
| `CATAPONTE_CORS_ALLOWED_ORIGINS` (optional) | Cataponte | *(empty: CORS closed)* | *(empty)* |
| `SERVER_PORT` | all (only for a second local instance) | Catapulsar `8081`, Cataponte `8082`, Catametricas `8083`, Cataudit `8084`, Catavigia `8085` | e.g. `8092`, `8093` |

`CATAPONTE_URL`, `CATAUDIT_URL` and `CATAMETRICAS_URL` are defaults; each can be changed at runtime from
Catapulsar's Settings → Connections (`PUT /api/v1/settings/cataponte|cataudit|catametricas`), which stores them in
`catapulsar_settings`, audits the change and publishes it on the settings topic. Deleting the setting goes back
to the env var.

**Running locally.** Each app's README has the line to paste into the IntelliJ run configuration's
*Environment variables* (`KEY=value;KEY=value`, with `<user>` and `<password>` to replace). For a second
instance, copy the run configuration, add `;SERVER_PORT=…` and allow multiple instances.

**Start order (local): Cataponte → Cataudit → Catametricas → Catapulsar**; second instances at any time after.
Every app starts alone and retries what it needs, so no order is required; this one avoids gaps and warnings:

1. **Cataponte first.** Every other app registers its `audit-<app>` bridge there at start-up. Without Cataponte
   the registration is retried after 10 s, doubling up to 5 min, so a late Cataponte delays it by minutes.
   The first time a bridge runs, its subscription starts at the latest message, so events published before
   that never reach Cataudit; afterwards the durable subscription keeps them until the bridge runs again.
2. **Cataudit second.** It consumes `catacumbas69/cataudit/audit`, where every audit bridge writes. Its
   subscription likewise starts at the latest message the first time and keeps what arrives while it is down
   afterwards; started early, the audit view also works from the first action.
3. **Catametricas third.** It registers its own audit bridge in Cataponte, and starting it before Catapulsar
   gives the overviews data sooner (the first interval of each namespace only sets the starting point).
4. **Catapulsar last.** It is the UI and calls the other three; started last, its health and panels are fine
   from the start instead of answering `503` until the others are up. Catametricas uses its default interval
   until it has read the settings topic Catapulsar publishes (decision 27).

In the cluster there is no start order: the same retries cover it, and after the first deployment the durable
subscriptions exist.

- **CFG-1 [MUST]** Bind config with `@ConfigurationProperties` records (validated with `@Validated`), not
  scattered `@Value` fields.
- **CFG-2 [MUST NOT]** commit credentials anywhere — properties files, manifests, README, or IntelliJ run
  configurations. `.idea/` MUST be git-ignored (run configs contain the Mongo password).

---

## 3. Conventions, readability and documentation

Code is written to be **read by a human first**. Every language follows its standard, widely used
conventions, and those conventions are **enforced by tools in the build**, not by memory.

### 3.1 Principles (all languages)

- **READ-1 [MUST]** Names say what a thing is or does, in English, without abbreviations except
  well-known ones (`id`, `url`, `dto`). `bridgeWorker`, not `bw`; `isRunning()`, not `chk()`.
- **READ-2 [MUST]** One level of abstraction per function. A function either orchestrates (calls other
  well-named functions) or does detailed work — not both.
- **READ-3 [MUST]** Early returns over nested `if`s; no more than 3 levels of nesting.
- **READ-4 [MUST]** No magic numbers or strings: name them as constants or configuration
  (`SEND_TIMEOUT_SECONDS`, `bridge.reconcile.interval`).
- **READ-5 [MUST]** No dead code, commented-out code or `TODO` without an owner and a gap ID
  (`// TODO(G-7): export as Micrometer counter`).
- **READ-6 [MUST]** Comments explain **why**, not what. If a comment explains what the code does, rename
  or extract instead.
- **READ-7 [MUST]** Formatting is automatic and identical for everyone: `apps/.editorconfig` (UTF-8, LF,
  final newline, trimmed trailing whitespace; 4 spaces for Java/XML, 2 spaces for HTML/JS/CSS/YAML/JSON;
  max line length 120).
- **READ-8 [MUST]** SOLID at class/module level: single responsibility, depend on abstractions at
  boundaries (e.g. a `BridgeClient` interface in Catapulsar for calls to Cataponte), no circular
  dependencies between packages.

### 3.2 Java

Standard: **Java Language Specification naming conventions**, **Oracle/OpenJDK code conventions** for
layout, and the idioms of *Effective Java* (3rd ed.). Formatting matches IntelliJ's default Java style
(4-space indent, K&R braces, 120 columns).

- **JAVA-1 [MUST]** Naming: `UpperCamelCase` types, `lowerCamelCase` methods/fields/locals,
  `UPPER_SNAKE_CASE` constants (`static final` immutable), lowercase packages with no underscores.
  Interfaces are not prefixed (`BridgeClient`, not `IBridgeClient`); implementations are named by what
  makes them different (`HttpBridgeClient`).
- **JAVA-2 [MUST]** Braces on every `if`/`for`/`while`, even single statements. One statement per line.
  Imports explicit — no wildcard imports (`org.apache.pulsar.client.api.*` must be expanded).
- **JAVA-3 [MUST]** Member order: constants, static fields, instance fields, constructors, public methods,
  package/private methods, nested types. Overloads stay together.
- **JAVA-4 [MUST]** `Optional` only as a return type, never as a field or parameter. Return empty
  collections, never `null`. Use `Objects.requireNonNull` at public boundaries.
- **JAVA-5 [MUST]** Prefer `final` locals/fields where it helps reading; prefer immutable types
  (`record`, `List.copyOf`).
- **JAVA-6 [MUST]** Checked exceptions are wrapped in domain exceptions at layer boundaries
  (`BridgeStartException`), never leaked to controllers as `Exception`.
- **JAVA-7 [MUST]** Enforced with **Checkstyle** (Google checks adapted to 4-space indent and 120 columns,
  config in `apps/build/checkstyle.xml`) bound to `mvn verify`; build fails on violations.

### 3.3 HTML

Standard: **WHATWG HTML Living Standard**, **WCAG 2.2 level AA** for accessibility.

- **HTML-1 [MUST]** Valid, semantic markup: `<header>`, `<nav>`, `<main>`, `<section>`, `<button>` for
  actions, `<a>` only for navigation. No clickable `<div>` without `role="button"`, `tabindex="0"` and
  keyboard handling — prefer a real `<button>`.
- **HTML-2 [MUST]** Every form control has a `<label for>` (or `aria-label`); icon-only buttons have
  `aria-label`; decorative SVGs have `aria-hidden="true"`; modals use `role="dialog"` + `aria-modal`.
- **HTML-3 [MUST]** Lowercase element and attribute names, double-quoted attribute values, 2-space
  indent, one top-level section per feature with a comment banner (`<!-- ══ Bridges ══ -->`).
- **HTML-4 [MUST]** No inline `style=""` except for one-off values computed by Alpine bindings; styling
  goes in `catapulsar.css` classes. No inline `<script>` blocks.
- **HTML-5 [MUST]** Enforced with **html-validate** (`html-validate:recommended` +
  `html-validate:a11y`), configured to allow Alpine attributes (`x-*`, `@*`, `:*`).

### 3.4 JavaScript

Standard: **ECMAScript 2022**, idioms from **MDN** and the **Airbnb JavaScript Style Guide** (naming and
structure), formatted by **Prettier** defaults (2 spaces, single quotes, semicolons, trailing commas).

- **JS-1 [MUST]** `const` by default, `let` when reassigned, never `var`. Strict equality (`===`) only.
- **JS-2 [MUST]** `lowerCamelCase` functions/variables, `UPPER_SNAKE_CASE` module constants,
  verbs for methods (`loadBridges`, `toggleDestination`), `is/has` for booleans.
- **JS-3 [MUST]** `async`/`await` with `try/catch/finally` for every network call; no unhandled promise.
  Loading flags are reset in `finally`.
- **JS-4 [MUST]** Every method in the Alpine component has a **JSDoc** block with a one-line summary,
  `@param` and `@returns` where applicable.
- **JS-5 [MUST]** No DOM manipulation by hand (`document.querySelector`) inside the Alpine component —
  state drives the DOM.
- **JS-6 [MUST]** Enforced with **ESLint** (`eslint:recommended`, `browser` env, `Alpine` global) and
  **Prettier --check**.

### 3.5 CSS

Standard: **W3C CSS** specs, **stylelint-config-standard**, Prettier formatting.

- **CSS-1 [MUST]** Class names in `kebab-case`, component-scoped by prefix (`.bridge-card`,
  `.bridge-card-header`, `.topic-picker`). No IDs for styling except the three layout regions
  (`#sidebar`, `#main`, `#content`).
- **CSS-2 [MUST]** Colours, radii, spacing and fonts come from custom properties in `:root`; no new hex
  colours inline in rules.
- **CSS-3 [MUST]** One rule per line block, properties in a consistent order (layout → box → typography →
  visual → motion). No `!important`.
- **CSS-4 [MUST]** File is organised in commented sections matching the UI (`/* ── Bridges panel ── */`),
  in the same order as `index.html`.
- **CSS-5 [MUST]** Visible keyboard focus (`:focus-visible`) on every interactive element; text contrast
  ≥ 4.5:1 (WCAG AA).
- **CSS-6 [MUST]** Enforced with **Stylelint** (`stylelint-config-standard`).

### 3.6 Documentation

- **DOC-1 [MUST]** Javadoc on every public type and every public method in `api/`, `service/` and
  `config/`: what it does, parameters, return value, exceptions, and threading/transaction notes where
  relevant. Trivial getters/setters and record components need no Javadoc beyond the record's.
- **DOC-2 [MUST]** Each package has a `package-info.java` stating its responsibility in one paragraph.
- **DOC-3 [MUST]** Each service has a `README.md` with: purpose, how to run locally (env vars from §2.1),
  how to test, API link (`/swagger-ui.html`), and operational notes.
- **DOC-4 [MUST]** Architecture decisions are recorded as **ADRs** (Michael Nygard format: Context,
  Decision, Consequences) in `apps/docs/adr/NNNN-title.md`. Existing decisions to record first:
  BFF for bridges (ARCH-2), Cataponte replicas (ARCH-5), at-least-once fan-out (ARCH-6),
  config-as-desired-state reconciliation (ARCH-4).
- **DOC-5 [SHOULD]** Diagrams as text (Mermaid or ASCII) in Markdown, so they are versioned with the code.

### 3.7 Tooling

| Language | Formatter | Linter | Runs in |
|---|---|---|---|
| Java | IntelliJ default style + `.editorconfig` | Checkstyle | `mvn verify` |
| HTML | Prettier | html-validate | `npm run lint` in `apps/catapulsar` |
| JS | Prettier | ESLint | `npm run lint` in `apps/catapulsar` |
| CSS | Prettier | Stylelint | `npm run lint` in `apps/catapulsar` |

The frontend lint tools are dev-only (`apps/catapulsar/package.json`, `devDependencies`); they never ship
in the jar. `mvn verify` in Catapulsar runs `npm run lint` through `frontend-maven-plugin` so one command
checks everything.

---

## 4. Java / Spring code rules

**Baseline:** Java 21, Spring Boot on a **currently supported** line (3.3.x is out of OSS support — upgrade
is a known gap), Maven, one Spring Boot app per service.

### 4.1 Structure

```
com.catacumbas69.<service>
├── <Service>Application.java
├── config/        @Configuration, @ConfigurationProperties, client beans
├── controller/    @RestController + @RestControllerAdvice (Problem Details)
├── dto/           request/response records and the mappers to/from the model
├── service/       business logic, orchestration, workers, domain exceptions
├── model/         Mongo @Document classes and value objects
└── repository/    Spring Data repositories
```

Every package has a `package-info.java` (DOC-2). Dependencies point downwards only:
`controller → dto, service → model, repository`. `service` never imports `controller` or `dto`.

- **CODE-1 [MUST]** Controllers are thin: validate input, call one service method, map to a DTO. No
  repository access from controllers.
- **CODE-2 [MUST]** API request/response types are **records in `dto/`**. Mongo documents MUST NOT be
  returned from or bound to controllers (prevents leaking internal fields and makes schema changes safe).
- **CODE-3 [MUST]** Constructor injection only (`final` fields; Lombok `@RequiredArgsConstructor` is fine).
  No field `@Autowired`.
- **CODE-4 [SHOULD]** Prefer records and immutable value objects. Lombok is allowed for `@Slf4j`,
  `@RequiredArgsConstructor`, `@Getter`/`@Setter` on documents. Avoid `@Data` on classes with identity or
  collections exposed to callers.
- **CODE-5 [MUST]** Logging through SLF4J with parameters (`log.info("x={}", x)`), never string
  concatenation, never `System.out`. Prefix-free messages — the logger name and MDC carry context.
- **CODE-6 [MUST]** No swallowed exceptions. `catch (Exception ignored) {}` is allowed only when closing
  resources during shutdown, with a comment.
- **CODE-7 [MUST]** Long-running loops (Pulsar workers) run on threads the service creates and stops
  explicitly; they MUST respond to interruption and close their Pulsar resources on every exit path.
- **CODE-8 [SHOULD]** Methods ≤ ~40 lines, classes with one reason to change. Extract when a method needs a
  comment to explain its sections.
- **CODE-9 [MUST]** Dependency versions are managed by the Spring Boot BOM or a `<properties>` entry —
  no inline versions in `<dependency>` blocks.

### 4.2 Pulsar

- **PUL-1 [MUST]** One `PulsarClient` and one `PulsarAdmin` bean per service (`destroyMethod = "close"`),
  created by the app's own configuration from `PULSAR_BROKER_URL` + optional `PULSAR_LISTENER_NAME` and
  `PULSAR_SERVICE_URL`; catacommons uses them. Never create clients per request.
- **PUL-2 [MUST]** Every service and catacommons use the **same Pulsar client version**, from the broker's
  LTS line.
- **PUL-3 [MUST]** Topics are always stored and passed as fully-qualified names
  (`persistent://tenant/namespace/topic`).
- **PUL-4 [SHOULD]** Bridge subscriptions are named `cataponte-<bridge-name>` unless the user sets one;
  never reuse an application's own subscription.
- **PUL-5 [MUST]** Producers/consumers are closed when a worker stops, including when `start()` fails
  halfway.

### 4.3 MongoDB

- **MONGO-1 [MUST]** Timestamps come from Spring Data auditing (`@CreatedDate`, `@LastModifiedDate`,
  `@EnableMongoAuditing`), not set by hand in controllers.
- **MONGO-2 [MUST]** Once there is data worth keeping, schema changes are backward compatible: read old and new
  shapes, write only the new one, and remove the old shape only after a migration has rewritten all documents.
  **Not applied today** (decision 40): with test data only, a change replaces the old shape and old data is
  dropped.
- **MONGO-3 [SHOULD]** Declare indexes in code (`@Indexed`, `@CompoundIndex`) with
  `spring.data.mongodb.auto-index-creation=true`; `cataponte_bridges.name` and `cataponte_areas.name` are unique.
- **MONGO-4 [MUST]** Local connections to the ReplicaSet use `directConnection=true` against the
  NodePort; in-cluster connections use the replica-set URI.
- **MONGO-5 [MUST]** Counters are written with `$inc` bulk upserts on a unique key, never read-modify-write:
  `cataponte_traffic_minutes` (`bridgeId`, `role`, `topic`, `minute`) and `cataponte_traffic_totals`
  (`bridgeId`, `role`, `topic`). Both are kept forever (no TTL); queries over minutes are bounded to
  7 days (PERF-5). Catametricas uses the same pattern: `catametricas_topic_minutes` (`topic`, `minute`, TTL
  30 days), `catametricas_topic_hours` (`topic`, `hour`, forever) and `catametricas_topic_totals` (`_id` = topic,
  forever).
- **MONGO-6 [MUST]** Cumulative counters read from Pulsar are stored as deltas: the last raw value is kept
  per topic (`catametricas_topic_snapshots`); a lower value than before is a reset (count the new value); the
  first collection of a namespace only sets the baseline. History of deleted topics is kept (`active=false`).
  The snapshot is replaced only by a newer sample (conditional `findAndReplace`), and only the instance that
  replaced it adds the delta, so a repeated or late collection task never counts twice (ADR 0012).
- **MONGO-7 [MUST]** Maps whose keys come from outside (event `before`/`after`/`details`, flattened as
  `source.topic`) are stored as lists of `{key, value}` pairs, never as map keys: MongoDB map keys cannot
  contain dots and Spring Data refuses them. The API turns them back into maps.

---

### 4.4 Events (ADR 0008)

- **EVT-1 [MUST]** Publish only through catacommons' `EventPublisher`, **after** the change is stored. It is
  asynchronous, retried (`1s, 2s, 4s`) and never fails or delays the caller; an event still not sent is
  logged at `ERROR` with its full JSON. Never publish before the change or make a request fail because
  publishing failed.
- **EVT-2 [MUST]** Event type `com.catacumbas69.<app>.<entity>.<verb>.v1`, verb in the past tense
  (`created`, `backlog-cleared`); `subject` is the thing the event is about and is also the message key, so
  events about one thing stay in order. A breaking change of `data` is a new type (`.v2`) published next to
  the old one until every consumer has moved.
- **EVT-3 [MUST]** `data` is `{actor, before, after, details}` with text values only: build it with
  `EventData.action(...)` or `EventData.change(...)`, which flatten nested values (`source.topic`) and join
  lists with commas. `actor` is the client IP address (ADR 0008) or the app name for changes the app makes
  on its own.
- **EVT-4 [MUST]** The audit trail **is** the events: every audited action publishes an event and nothing
  else (no app writes an audit collection). Each app registers its `audit-<app>` system bridge at start-up
  through catacommons (`AuditBridgeRegistrar`, `PUT /api/v1/bridges/system/{name}` on Cataponte,
  idempotent, retried in the background); Cataudit stores what arrives on `catacumbas69/cataudit/audit`,
  keyed by event id (ADR 0009).
- **EVT-5 [MUST]** An app writes only to topics in its own namespace `catacumbas69/<app>`. Topics are
  declared as `TopicSpec` beans (the events topic is declared by catacommons) and created at start-up by
  `PulsarProvisioner`, never by hand; topics are non-partitioned.
- **EVT-6 [MUST]** Namespace policies are set by the app on every start: retention 7 days / 2 GB, backlog
  quota 1 GB with `producer_exception`. The quota MUST be below the retention size (Pulsar answers `412`
  otherwise); `CatacommonsProperties` refuses to start when it is not. Compacted topics (settings) get topic policies: infinite retention
  and a compaction threshold of 1 MB.

Event catalogue (phase 1):

| Source | Type (`com.catacumbas69.` + …) | Subject | `data` |
|---|---|---|---|
| Catapulsar | `catapulsar.topic.created\|deleted.v1` | topic | actor |
| Catapulsar | `catapulsar.subscription.created\|deleted\|backlog-cleared.v1` | topic | details: `subscription` |
| Catapulsar | `catapulsar.message.produced.v1` | topic | details: `key`, `payload`, `properties.*`… |
| Catapulsar | `catapulsar.message.peeked\|skipped.v1` | topic | details: `subscription`, `count` |
| Catapulsar | `catapulsar.setting.changed.v1` | setting key (below) | before/after: `value` |
| Catapulsar | `catapulsar.bridge.create\|update\|delete\|enable\|disable-requested.v1` | bridge id (create: id from the response, else the requested name) | details: `status` (Cataponte's HTTP status, `503` if unreachable), `request` (body) |
| Cataponte | `cataponte.bridge.created\|updated\|deleted\|enabled\|disabled.v1` | bridge id | before/after: flattened definition; actor `cataponte` for its own system bridge |
| Catametricas | `catametricas.collection.failed\|recovered.v1` | `collector` | details: `stage` (`schedule`, `claim`, `collect`), `error`, `instance`, `task`… (state changes of each instance only) |

System bridges (ADR 0009): `audit-<app>` = `catacumbas69/<app>/events` → `catacumbas69/cataudit/audit`,
subscription `cataponte-audit-<app>` (`Failover`, ordered), flagged `system` and shown with a badge; otherwise
ordinary bridges. Registering again keeps an enabled state a user changed; a deleted one comes back at the
app's next start. Registration events have the app name as actor (`X-Catacumbas69-App`).

Settings topic `catacumbas69/catapulsar/settings` (ADR 0011): key = setting key, value `{"value": "<text>"}`,
empty value (tombstone) = default. Catapulsar publishes on every change and every value (or tombstone) at
start-up, from `catapulsar_settings` (document id = key).

- **EVT-7 [MUST]** Every service reads shared settings only through catacommons' `SharedSettings` (a Pulsar
  `TableView` on the topic, opened at start-up and retried while Pulsar is down): `get(key)` or
  `listen(key, …)`. Until the topic has been read, and for a missing or invalid value, the configured default
  is used; nobody waits for the topic. An app that uses no shared setting sets
  `catacommons.settings.enabled=false` (Cataponte, Cataudit).
- **EVT-8 [MUST]** A change is applied at once by the replica that made it and reaches every other replica
  and service through the topic; settings are never read from MongoDB at request time.

| Key | Used by |
|---|---|
| `catapulsar.cataponte-url` | Catapulsar |
| `catapulsar.cataudit-url` | Catapulsar |
| `catapulsar.catametricas-url` | Catapulsar |
| `catapulsar.refresh-interval-seconds` | Catapulsar |
| `catametricas.collect-interval-seconds` | Catametricas |

## 5. REST contracts and errors

- **API-1 [MUST]** All endpoints live under `/api/v1/…`. Breaking changes → `/api/v2`, never silent.
- **API-2 [MUST]** Resource-oriented URLs, plural nouns: `GET/POST /api/v1/bridges`,
  `GET/PUT/DELETE /api/v1/bridges/{id}`. State changes that are not CRUD are sub-resources with POST:
  `POST /api/v1/bridges/{id}/enable`.
- **API-3 [MUST]** Status codes: `200` read/update, `201` + `Location` on create, `204` on delete,
  `400` validation, `404` missing, `409` conflict (e.g. duplicate name), `502` upstream (Pulsar/Cataponte)
  failure.
- **API-4 [MUST]** Errors are RFC 9457 **Problem Details** (`application/problem+json`) produced by one
  `@RestControllerAdvice` per service (`spring.mvc.problemdetails.enabled=true`). Field errors go in an
  `errors` extension: `[{ "field": "...", "message": "..." }]`. The frontend reads `detail`/`title`.
- **API-5 [MUST]** Input is validated with Jakarta Validation on request records (`@NotBlank`,
  `@Size`, `@Pattern` for topic names). Cross-field rules (destination ≠ source) live in a custom
  validator or the service, never only in the UI.
- **API-6 [MUST]** Each service publishes OpenAPI 3 via springdoc (`/v3/api-docs`, UI at
  `/swagger-ui.html`). The spec is the contract — update annotations with every API change.
- **API-7 [MUST]** CORS is closed by default. Only local development may enable it, for an explicit
  origin list from config — never `allowedOriginPatterns("*")`.
- **API-8 [SHOULD]** List endpoints that can grow (audit log, messages) are paginated
  (`page`, `size`, max 100).

---

## 6. Frontend (Catapulsar static UI)

Stack: plain HTML + Alpine.js 3 (from cdnjs, pinned version), one `index.html`, `catapulsar.js`, `catapulsar.css`.

- **UI-1 [MUST]** Every `<template x-if>` / `<template x-for>` contains **exactly one element root**.
  Text-only content (`<template x-if="…">📤</template>`) breaks Alpine — wrap it in a `<span>`.
- **UI-2 [MUST]** Static assets are cache-busted: links carry `?v=<N>`, and `N` is bumped in the same
  change that edits `catapulsar.js` or `catapulsar.css`. The server sends `Cache-Control: no-cache`.
- **UI-3 [MUST]** All HTTP calls go through one helper (`api()`), which turns Problem Details into toast
  messages. No `fetch` scattered in templates.
- **UI-4 [MUST]** The UI language is **English** — labels, placeholders, empty states, toasts.
- **UI-5 [MUST]** Destructive actions (delete topic/subscription/bridge, skip messages, clear backlog) ask for
  confirmation and name the target.
- **UI-6 [SHOULD]** Group state and methods by feature (`bridges.*`, `backlog.*`) in `catapulsar.js`;
  keep expressions in HTML to simple reads and single calls.
- **UI-7 [MUST]** Pickers and lists have bounded height (scroll inside), consistent with `.sub-picker`
  styles; no unbounded growth of the page.
- **UI-8 [MUST]** Views are mutually exclusive: opening a topic closes the Bridges panel and vice versa.
- **UI-9 [SHOULD]** Colours, radii and fonts only from the CSS variables in `:root`.
- **UI-10 [MUST]** Auto-refresh only repeats reads: sidebar lists, Overview statistics, subscription list,
  bridge list/status/traffic. It never repeats an action with side effects or one that writes an audit
  entry (backlog peek). It refreshes in place (no spinner, no toast), skips while the browser tab is
  hidden, and lists are replaced only when they changed. The on/off switch is per browser; the interval
  is shared (settings topic, 2–300 s, audited).

---

## 7. Performance

Targets are measured on `meloes` (NUC, MicroShift) with the broker on the same node.

- **PERF-1 [MUST]** Each Cataponte bridge sustains **≥ 1,000 msg/s** (1 KiB payloads, up to 3 destinations)
  with p99 forward latency **< 100 ms**. Catapulsar read endpoints answer p95 **< 300 ms**.
- **PERF-2 [MUST]** No blocking round-trip per message in Cataponte. Sends are asynchronous
  (`sendAsync`) and pipelined; the number of unacknowledged messages per bridge is bounded by
  `maxInFlight` (default 1,000) with a semaphore, so memory stays flat under back-pressure.
- **PERF-3 [MUST]** Producers use batching (`batchingMaxPublishDelay` 1 ms, `batchingMaxMessages` 1,000)
  and `blockIfQueueFull=true`; consumers use `receiverQueueSize` 1,000 and grouped acks
  (`acknowledgmentGroupTime` 100 ms). Values are configuration, not literals.
- **PERF-4 [MUST]** Ordering is a per-bridge choice (`ordered`, default `true`):
  - **ordered** — pipelined sends through one producer per destination keep source order on success.
    On any failure the worker pauses, waits for in-flight sends, and calls
    `redeliverUnacknowledgedMessages()` so the stream is replayed **in order** (duplicates possible,
    reordering not). Requires a `Failover` or `Key_Shared` subscription — `Shared` is rejected by validation
    (and `Exclusive` always is, ARCH-5).
  - **unordered** — failures are negative-acked individually and redelivered later; any subscription type
    is allowed. Highest throughput.
- **PERF-5 [MUST]** Catapulsar never loads unbounded data into memory: peek counts are capped (≤ 100),
  lists are paginated (API-8), and the topic list for the bridge form is fetched with bounded
  concurrency.
- **PERF-6 [SHOULD]** Reuse clients: one `PulsarClient`, one `PulsarAdmin`, one HTTP client per
  downstream; never create them per request.
- **PERF-7 [SHOULD]** Hot paths avoid per-message allocations that can be hoisted (e.g. build the
  property map once per message, not once per destination), and avoid logging per message at INFO.
- **PERF-8 [MUST]** Traffic counting costs no I/O per message: workers increment in-memory atomics per
  (bridge, role, topic, minute); `TrafficRecorder` flushes them in two bulk writes every
  `cataponte.traffic.flush-interval` (5 s) and once more on shutdown. `SOURCE` counts a message when every
  destination accepted it; `DESTINATION` counts each accepted send. Redeliveries are counted again
  (at-least-once, ARCH-6).

---

## 8. Resilience

The services must keep working — or degrade visibly and recover on their own — when MongoDB, Pulsar or the
other service is slow or down.

- **RES-1 [MUST]** Both services **start** with MongoDB or Pulsar unavailable. Readiness reports the
  outage (OBS-2); nothing crashes or exits.
- **RES-2 [MUST]** Every remote call has explicit timeouts: Pulsar client (`connectionTimeout` 10 s,
  `operationTimeout` 30 s, producer `sendTimeout` 30 s), Pulsar admin (connect 5 s, read 30 s),
  Catapulsar → Cataponte HTTP (connect 2 s, read 10 s), MongoDB (`serverSelectionTimeoutMS` 5000).
- **RES-3 [MUST]** Reconnects use exponential back-off with a cap (Pulsar client `startingBackoffInterval`
  100 ms, `maxBackoffInterval` 30 s).
- **RES-4 [MUST]** Bridge isolation: each bridge runs on its own worker; an exception in one bridge MUST
  NOT stop, slow or restart another. The reconciler wraps every per-bridge action in its own
  `try/catch`.
- **RES-5 [MUST]** A bridge that fails to start is retried with **exponential back-off** (1 s → 60 s cap),
  not on every reconcile tick. Its status shows `FAILED`, the last error and the next retry time.
- **RES-6 [MUST]** If MongoDB cannot be read, the reconciler **keeps the current workers running** and
  retries on the next tick. Desired state is only acted on when it was read successfully.
- **RES-7 [MUST]** Graceful shutdown: stop receiving, wait for in-flight sends (bounded by the shutdown
  grace period), ack what was delivered, flush and close producers, then close the consumer.
  Un-acked messages are redelivered after restart (at-least-once, ARCH-6).
- **RES-8 [MUST]** Negative acks use a redelivery delay (`negativeAckRedeliveryDelay` 1 s) so a broken
  destination does not cause a hot retry loop.
- **RES-9 [MUST]** Concurrent edits are detected: `cataponte_bridges` documents carry `@Version`; a stale update
  returns `409 Conflict`.
- **RES-10 [MUST]** Write endpoints are idempotent where HTTP says so (`PUT`, `DELETE`,
  `POST …/enable|disable`). Deleting a missing bridge returns `404`, never `500`.
- **RES-11 [MUST]** Audit writes are best-effort and never fail the user's action: an audit failure is
  logged (with the full entry in Cataponte) and the operation result is returned.
- **RES-13 [MUST]** No message loops between bridges (ADR 0005): a create, update or enable that would
  close a cycle among enabled bridges is rejected with `400`; the reconciler never runs a bridge on a
  cycle (state `BLOCKED`), even when the documents were written to MongoDB directly.
- **RES-14 [MUST]** Traffic counts that cannot be written to MongoDB stay in memory and are retried on the
  next flush; a write failure never affects relaying.
- **RES-15 [MUST]** A consumed message is always acknowledged or negatively acknowledged, whatever fails while
  handling it; an unexpected error is logged at `ERROR` with the exception and the message is delivered again.
- **RES-12 [MUST]** The UI degrades per feature: if Cataponte is down, the Bridges panel shows the error
  and the rest of Catapulsar keeps working; every request shows a clear toast on failure.

---

## 9. Testing

Pyramid: many unit tests, focused slice tests, few end-to-end tests.

- **TEST-1 [MUST]** Unit tests (JUnit 5, AssertJ, Mockito) for all service logic, with no network, no
  Docker and no Spring context — they run in seconds. Mandatory targets:
  - Cataponte: `BridgeManager` reconcile decisions (start / stop / restart on `updatedAt` change / keep
    running when Mongo fails / back-off after start failure); `BridgeWorker` fan-out to every
    destination, ack only after all destinations succeed, ordered-mode replay vs unordered nack,
    `maxInFlight` bound; bridge validation (destinations, source ≠ destination, ordered ⇒ not `Shared`,
    no `Exclusive`).
  - Catapulsar: audit is best-effort (RES-11), BFF error mapping (Cataponte down → `502`), input
    validation of topic names and peek counts.
- **TEST-1b [MUST]** Pulsar and Mongo types are mocked at the service boundary (`PulsarClient`,
  `Consumer`, `Producer`, repositories); tests assert behaviour (what was sent, acked, nacked,
  started), not implementation details.
- **TEST-2 [MUST]** Slice tests for every controller (`@WebMvcTest`): status codes, Problem Details shape,
  validation messages.
- **TEST-3 [MUST]** Integration tests with **Testcontainers** for MongoDB and Pulsar
  (`org.testcontainers:mongodb`, `org.testcontainers:pulsar`): at least one test that creates a bridge
  and observes a message arriving on every destination. Containers are wired with
  `@DynamicPropertySource` / `@ServiceConnection`.
- **TEST-4 [SHOULD]** A Playwright smoke test for the UI with the API mocked: load page, open a topic,
  open Bridges, create a bridge, go back to a topic, and fail on any console error.
- **TEST-5 [MUST]** `mvn verify` passes before a change is considered done. Until CI exists (G-21), the
  developer runs it on `necromancer` and attaches the result to the change. Tests MUST NOT depend on the
  NUC or any shared environment.
- **TEST-6 [SHOULD]** Test names describe behaviour: `reconcile_restartsWorker_whenUpdatedAtChanges()`.

---

## 10. Observability

- **OBS-1 [MUST]** Both services include Actuator with `health`, `info`, `prometheus` exposed;
  liveness and readiness groups enabled (`management.endpoint.health.probes.enabled=true`).
- **OBS-2 [MUST]** Readiness includes MongoDB and a Pulsar health indicator (client connected / admin
  reachable). Liveness does **not** include external dependencies.
- **OBS-3 [MUST]** Logs are JSON on stdout in the cluster (structured logging, ECS format) and
  human-readable locally. Alloy ships them to Loki; Kubernetes labels `app`, `namespace` come from Alloy,
  not from the app.
- **OBS-9 [MUST]** A failure the code handles (retried or ignored) is logged once, in one line, by our code.
  Library loggers that repeat it at WARN with a stack trace are lowered to ERROR in `application.properties`
  (Pulsar client: `BinaryProtoLookupService`, `PulsarClientImpl`, admin `BaseResource`).
- **OBS-4 [MUST]** Cataponte puts `bridgeId` and `bridgeName` in the MDC inside worker threads.
- **OBS-5 [MUST]** Cataponte exposes Micrometer metrics per bridge, tagged `bridge`:
  `cataponte_messages_forwarded_total`, `cataponte_forward_errors_total`, `cataponte_bridges_running`.
  The `/api/v1/bridges/status` counters read from the same meters.
- **OBS-6 [MUST]** Every configuration change is audited by its owner as an event (§4.4) and lands in
  Cataudit (ADR 0009), whichever client made it: Catapulsar records topic, subscription, backlog, produce,
  peek and settings changes (settings with `before`/`after`) and every bridge request it forwards; Cataponte
  records every bridge change with the definition `before` and `after`. A new configurable thing is not done
  until its changes are audited.
- **OBS-7 [MUST]** Cataponte stores per-topic message and byte counts per bridge: all-time totals and
  per-minute history, served at `GET /api/v1/bridges/traffic`, `/{id}/traffic` and
  `/{id}/traffic/minutes?from&to`.
- **OBS-10 [MUST]** Everything Catapulsar shows in the view of one topic (Overview, Subscriptions and Backlog tabs)
  is the **current state of that topic**, read directly from Pulsar's admin API on every refresh, never from
  Catametricas: the view is for operating on the topic, so it shows the state the actions apply to. Aggregated and
  historical figures (overviews, series, totals, alerts) come from Catametricas only (OBS-8, ADR 0013). The one
  exception is history: a subscription's row, when expanded, charts its past backlog and deliveries from
  Catametricas (decision 78); the numbers on the row still come from Pulsar.
- **OBS-8 [MUST]** Catametricas stores every topic's traffic and backlog (ADR 0007) and is the only metrics API
  (ADR 0013): `GET /api/v1/topics`, `/api/v1/series?groupBy=&from=&to=&step=` and `/api/v1/collector`.
  Catapulsar shows them as the cluster (tenants), tenant (namespaces) and namespace (topics) overviews at
  `GET /api/v1/overview`, `/{tenant}` and `/{tenant}/{namespace}?period=1h|24h|7d|30d`, with the time of the
  latest sample (`503` when Catametricas is down). Catametricas exposes `catametricas_tasks_total{outcome}` and
  `catametricas_collect_duration` (per namespace), and a `collector` health component (always UP; details
  show whether this instance consumes tasks and its last task).

---

## 11. Security

- **SEC-1 [MUST]** Secrets (Mongo user/password, any tokens) come from Kubernetes Secrets mounted as env
  vars. Locally they live only in the IntelliJ run configuration, which is git-ignored.
- **SEC-2 [MUST]** Cataponte's Service is `ClusterIP` (ARCH-2). Catapulsar is exposed through a Route behind
  authentication (e.g. an OAuth proxy) — the UI can delete topics and skip backlog.
- **SEC-3 [MUST]** Mongo users have least privilege: `readWrite` on `catacumbas69` only.
- **SEC-4 [MUST]** Validate and bound all user input (topic names, counts, payload sizes); never build
  Pulsar admin paths from unvalidated strings. Names start with a letter, a digit or `_` (Pulsar's own names,
  `__compaction`, `__change_events`), then letters, digits, `. _ = : -`.
- **SEC-7 [MUST]** Pulsar's own subscriptions (names starting with `__`) are read-only in Catapulsar: they can be
  listed and peeked, but creating, deleting, skipping or clearing them is refused with `400` and the UI hides
  those actions. Skipping `__compaction` would break topic compaction.
- **SEC-5 [MUST]** Containers run as non-root with a read-only root filesystem where possible
  (MicroShift `restricted-v2` SCC compatible: no fixed UID).

- **SEC-6 [MUST]** Settings that make the server call a URL (the Cataponte URL) accept only absolute
  `http`/`https` URLs without credentials, query or fragment, and every change is audited. Anyone who can
  open Catapulsar can point it at another host, which is one more reason for SEC-2's authentication.
---

## 12. Build and deploy

- **OPS-1 [MUST]** Multi-stage Dockerfile per service: Maven build stage → JRE 21 runtime stage, layered
  jar (`java -Djarmode=tools`), non-root, `EXPOSE` the service port, JVM tuned with
  `-XX:MaxRAMPercentage=75`. The build context is `apps/` (`docker build -f <app>/Dockerfile .`): the build
  stage runs `mvn -pl <app> -am package` so catacommons is compiled with the app, with the Maven repository
  in a BuildKit cache mount; `apps/.dockerignore` sends only the reactor. The build stage runs on the build
  machine's platform (`FROM --platform=$BUILDPLATFORM`) and only the runtime stage is the target platform, so an
  ARM Mac builds `linux/amd64` images for meloes without emulating Maven. Images for the cluster are built with
  Podman by `apps/build/push.sh <app|all>` (decision 81).
- **OPS-6 [MUST]** `apps/pom.xml` is an aggregator only: it lists `catacommons` and every app. Each module
  keeps `spring-boot-starter-parent` as its parent and its own version; apps depend on
  `catacommons` through a `catacommons.version` property. `mvn clean install` at `apps/` builds and checks
  everything.
- **OPS-2 [MUST]** Image tags are immutable (`<version>-<git-sha>`); `latest` is never deployed. Images go to
  the registry in MicroShift (`registry.catacumbas69.com`, decision 82). `apps/build/push.sh` refuses to build with
  uncommitted changes under `apps/` (`FORCE=1` builds a `-dirty-<date-time>` tag, for tests only) and writes the
  pushed tag into the app's `kustomization.yaml` (decision 83).
- **OPS-3 [MUST]** Kubernetes manifests live in this repository under `microshift/` and are synced by ArgoCD
  (one Application per app, Sync by hand). All apps share the namespace `catacumbas69-apps`, one folder per app
  (`microshift/apps/catacumbas69-apps/<app>/`: ConfigMap, Deployment, Service, Route, kustomization). Each
  Deployment sets resource requests/limits, startup/liveness/readiness probes on Actuator, and config via
  ConfigMap + Secret; the MongoDB URI is the only secret and is never in git (decisions 84–87). Runbook:
  `microshift/apps/catacumbas69-apps/README.md`.
- **OPS-4 [MUST]** Cataponte may run several replicas with a rolling update (ARCH-5); during the update old
  and new pods run side by side, which Pulsar handles like any other replicas. Catametricas may run
  several replicas, all collecting: the `Shared` subscription of `collect-tasks` spreads the tasks and the claims
  in `catametricas_task_runs` keep each task single (ADR 0012). Cataudit may run several
  replicas: its `Shared` subscription splits the audit topic between them and inserts are keyed by event id
  (ADR 0012).
- **OPS-5 [SHOULD]** Graceful shutdown (`server.shutdown=graceful`) so workers stop and ack in-flight
  messages before the pod dies; `terminationGracePeriodSeconds` ≥ 30.

---

## 13. Workflow and definition of done

- **GIT-1 [MUST]** Conventional commits: `feat(cataponte): …`, `fix(catapulsar): …`, `docs: …`. One logical
  change per commit; cite rule IDs when a commit exists to satisfy one.
- **GIT-2 [MUST]** No commits with secrets, `target/`, or `.idea/`.

**Definition of done** — a change is done only when:

1. `mvn verify` passes for every touched service, including Checkstyle and the frontend linters
   (TEST-5, JAVA-7, HTML-5, JS-6, CSS-6).
2. New/changed endpoints are in OpenAPI and return Problem Details on error (API-4, API-6).
3. UI changes bumped the asset version and produce no console errors (UI-2, TEST-4).
4. Config changes are reflected in §2.1 and in the manifests.
5. Public Java types/methods and every JS method touched are documented (DOC-1, JS-4); an ADR exists
   for any new architectural decision (DOC-4).
6. This file is updated if a rule changed, and [Known gaps](#14-known-gaps) is updated if one was closed
   or opened.

---

## 14. Known gaps

The code does not yet follow every rule. Agents fixing a gap remove it here in the same change.

| ID | Service | Gap | Rule |
|---|---|---|---|
| G-6 | all | No Testcontainers integration tests (unit and slice tests exist) | TEST-3 |
| G-11 | Catapulsar | No Playwright UI smoke test in the build | TEST-4 |
| G-17 | Catapulsar | No ESLint, Stylelint, html-validate or Prettier for the static UI | HTML-5, JS-6, CSS-6 |
| G-18 | Catapulsar | JSDoc missing on most `catapulsar.js` methods | JS-4 |
| G-20 | Catapulsar | Clickable `<div>`s in the sidebar tree and pickers; many inline `style=""`; modals without `role="dialog"`; decorative SVGs without `aria-hidden` | HTML-1, HTML-2, HTML-4 |
| G-21 | all | No CI pipeline running `mvn verify` and the frontend linters on every push | TEST-5 |

Closed on 2026-10-30:

- **Cataponte** (`mvn verify` green, 46 tests, 0 Checkstyle violations): Boot 3.5, Pulsar client 4.0.3,
  `@ConfigurationProperties`, auditing, indexes, `/api/v1`, DTOs, validation, Problem Details, OpenAPI,
  Actuator probes, Micrometer meters, MDC, async fan-out with ordering modes, start back-off, `@Version`,
  explicit timeouts, Javadoc, `package-info.java`, README, Dockerfile, CORS closed.
- **Catapulsar** (`mvn verify` green, 31 tests, 0 Checkstyle violations): Boot 3.5, `@ConfigurationProperties`, explicit timeouts, `/api/v1`, DTOs, validation with
  `@PulsarName`, Problem Details (Pulsar 4xx kept, 5xx → 502, Cataponte down → 503), OpenAPI, bridge BFF with
  audit, asynchronous best-effort audit, audit indexes, producer cache, skip endpoint (was missing),
  `binary` flag fix, health indicators, Prometheus, Javadoc, `package-info.java`, Checkstyle, README,
  Dockerfile fix, English UI strings.
- **Shared**: ADRs 0001–0004, `.editorconfig`, `build/checkstyle.xml`.
- **Later the same day** (`mvn verify` green on 2026-10-31: Cataponte 77 tests, Catapulsar 50 tests, 0 Checkstyle
  violations): per-topic traffic counts in MongoDB (OBS-7, PERF-8,
  MONGO-5), loop blocking (RES-13, ADR 0005), audit of every configuration change by its owner (OBS-6,
  ADR 0006), runtime-editable Cataponte URL, clear backlog per subscription in Overview.
- **2026-11-09, MicroShift deployment** (G-16 closed; decisions 81–87): manifests for the five apps in
  `microshift/apps/catacumbas69-apps/`, ArgoCD Applications, image registry in the cluster, `apps/build/push.sh`
  (Podman, `linux/amd64`, tag written into the manifest), `apps/build/mongo-secret.sh`, MongoDB user
  `catacumbas69-apps`, Dockerfiles with a native build stage, `apps/.dockerignore` includes Catavigia.
- **2026-11-09, phase 7b of §15, step 3: alerts in Catapulsar** (`mvn clean install` at `apps/` green: catacommons
  39, Catapulsar 136, Cataponte 146, Catametricas 70, Cataudit 19, Catavigia 33 tests, 0 Checkstyle violations):
  Alerts button and badge, Alerts panel (Open and Archived, mark seen, actions to where each alert is fixed),
  Limit column and 📈 history chart on a subscription's row, Settings → Alerts and the Catavigia URL, alerts and
  limits forwarded to Catavigia and audited by both apps (decision 80). **Phase 7 is complete.**
- **2026-11-07/08, phase 7b of §15, steps 1–2: history per subscription and Catavigia** (`mvn clean install` at
  `apps/` green: catacommons 39, Catapulsar 130, Cataponte 146, Catametricas 70, Cataudit 19, Catavigia 33 tests,
  0 Checkstyle violations): Catametricas keeps each subscription's backlog and deliveries in the topic's snapshot,
  minutes and hours, served on `/api/v1/subscriptions` (decision 77); new service **Catavigia** (:8085) with its
  checks as tasks on Pulsar, debounce, open, seen and archived alerts, backlog limits per subscription, events and
  the `catavigia_alerts_open` metric (decisions 75, 76, 79); catacommons `AlertSetting` for the `alerts.*`
  thresholds. Step 3 (Catapulsar) to come.
- **2026-11-06, phase 7a of §15: dead letters and consumer control** (`mvn clean install` at `apps/` green:
  catacommons 37, Catapulsar 130, Cataponte 146, Catametricas 60, Cataudit 19 tests, 0 Checkstyle violations): DLQs in Cataudit,
  Catametricas and unordered `Shared`/`Key_Shared` bridges, `dlq.max-deliveries` shared setting (Settings → Dead
  letters); consumers pause on outages and resume by themselves, and can be paused, resumed and checked by hand from
  Catapulsar's Consumers panel through the `consumers` and `consumer-commands` topics; Catapulsar's dead-letters tab
  (peek, send back, discard, one or all); Cataponte tells refused messages from an unavailable destination and offers
  *Skip this message* on `Failover` bridges (decisions 66–74, ADR 0015).
- **2026-11-04/06, bridge areas, bridge subscriptions and UI** (`mvn clean install` at `apps/` green: catacommons 25,
  Catapulsar 114, Cataponte 136, Catametricas 58, Cataudit 18 tests, 0 Checkstyle violations): bridge areas
  (ADR 0014, decisions 54–59), `409` explaining a partitioned topic with the same name, subscriptions of deleted or
  changed bridges deleted, shared subscriptions refused and consumers named (decisions 60–63), name fields corrected
  as you type (64), sidebar nodes kept open (65).
- **2026-11-04, forced collection** (`mvn clean install` at `apps/` green: catacommons 25, Catapulsar 106,
  Cataponte 91, Catametricas 58, Cataudit 18 tests, 0 Checkstyle violations): `POST /api/v1/collect` in
  Catametricas, **Collect now** and **Live** in Catapulsar's overviews (decisions 50–53), Settings in tabs, topic view
  boundary (OBS-10). Creating a topic whose name is taken by an unlisted partitioned topic (no partitions yet)
  answers `409` *Partitioned topic exists* with the `pulsar-admin` commands to fix it.
- **2026-11-03/04, fixes found running the apps** (`mvn clean install` at `apps/` green: catacommons 25,
  Catapulsar 102, Cataponte 91, Catametricas 51, Cataudit 18 tests, 0 Checkstyle violations): namespace retention
  2 GB with the backlog quota below it (decision 41), Pulsar client loggers lowered (OBS-9), Pulsar system names
  accepted and `__*` subscriptions read-only (SEC-4, SEC-7), backlog size per subscription in Catapulsar, Cataudit
  event values stored as pairs (MONGO-7) and every message acknowledged or negatively acknowledged (RES-15).
- **2026-11-01, phase 5 of §15 and no backward compatibility** (`mvn clean install` at `apps/` green:
  catacommons 20, Catapulsar 98, Cataponte 91, Catametricas 51, Cataudit 14 tests, 0 Checkstyle violations):
  Cataponte runs N replicas; `Exclusive` refused; status merged over replicas (`cataponte_worker_status`,
  `replicas`/`activeReplicas` in the Bridges panel); collections `cataponte_*`. Every migration and legacy-shape
  code removed from all apps (settings, statistics, bridges, audit copy, `destination`, `bridge.migrated`).
- **2026-11-01, phase 4 of §15** (`mvn clean install` at `apps/` green: catacommons 20, Catapulsar 102,
  Cataponte 84, Catametricas 54, Cataudit 18 tests, 0 Checkstyle violations): Catametricas API (`topics`, `series`,
  `collector`, OpenAPI), collection tasks on `catacumbas69/catametricas/collect-tasks` with claims and
  conditional snapshot updates (lease removed, every replica collects), `contario_*` renamed to
  `catametricas_*`; Catapulsar overviews from the API, Catametricas URL editable.
- **2026-11-01, phase 3 of §15** (`mvn clean install` at `apps/` green: catacommons 20, Catapulsar 90,
  Cataponte 84, Catametricas 26, Cataudit 18 tests, 0 Checkstyle violations): `SharedSettings` (TableView) in
  catacommons; Catapulsar and Catametricas read their settings from the topic; `pulsario_settings` migrated to
  `catapulsar_settings` and dropped.
- **2026-11-01, phase 2 of §15** (`mvn clean install` at `apps/` green: catacommons 17, Catapulsar 88,
  Cataponte 84, Catametricas 26, Cataudit 18 tests, 0 Checkstyle violations): **Cataudit** (new service, port
  8084), `audit-<app>` system bridges registered by each app, `PUT /api/v1/bridges/system/{name}` in
  Cataponte, `audit_logs` no longer written and copied into `cataudit_events`, new audit view in Catapulsar,
  Cataudit URL editable like the Cataponte URL.
- **2026-10-31, phase 1 of §15** (`mvn clean install` at `apps/` green: catacommons 11, Catapulsar 83,
  Cataponte 80, Catametricas 26 tests, 0 Checkstyle violations): Maven reactor and `catacommons`,
  Pulsar namespaces created at start-up, events for every audited action (§4.4), settings topic published,
  Dockerfiles built from `apps/`.
- **2026-10-31** (`mvn verify` green): Catapulsar auto-refresh (UI-10), backlog tab with per-subscription
  statistics and peek on click, **Catametricas** (new service, ADR 0007) and the cluster/tenant/namespace
  overviews (OBS-8), collection interval editable in Catapulsar, clickable breadcrumb.

### Remediation plan

1. **UI conventions** — G-17, G-18, G-20: frontend lint tooling, JSDoc, semantic and accessible markup,
   inline styles moved to CSS.
2. **Tests** — G-6, G-11: Testcontainers integration tests (MongoDB, Pulsar), Playwright smoke test.
3. **Delivery** — G-21: a CI pipeline (the MicroShift manifests are done, decisions 81–87).

---

## 15. Target architecture (proposed — ADR 0008–0013)

Status: **phases 1–5 implemented** (2026-10-31 – 2026-11-01); phase **7 implemented** (7a 2026-11-06, 7b 2026-11-09)
(decisions 42–49, 66–80); phase 6 later. Until a phase below is implemented, the
rules in sections 1–14 stay in force. When a phase lands, the rules it changes are updated in the same change
and its ADRs become "Accepted".

```
                         REST (commands, queries)                          Pulsar tenant catacumbas69
 Browser ──▶ Catapulsar ───────────────┬──────────────▶ Cataponte ──────────┐
   (UI)      (N replicas)              └──────────────▶ Catametricas        │ bridges
                 │ publishes                                (N)            │ audit-<app>:
                 ├── catapulsar/events ─────────────────────────────────────┤ <app>/events
                 └── catapulsar/settings (compacted) ──▶ read by every      │   → cataudit/audit
                                                         service at start   ▼
 Cataponte (N) ── cataponte/events            Catametricas (N) ◀── catametricas/collect-tasks (Shared)
 Catametricas ─── catametricas/events                 │ MongoDB (history) [+ Prometheus later]
                                               Cataudit (N) ◀── cataudit/audit (Shared) → cataudit_events
```

Principles:

- **Commands and queries over REST, facts over events** (ADR 0008). Events are CloudEvents 1.0 in JSON with
  a registered schema, one namespace per app, key = subject, published after commit with retries.
- **Each collection has one owner**; other services use its API or its events (ADR 0010).
- **Audit** is a service of its own (Cataudit) fed by each app's events through Cataponte bridges that each
  app registers at start-up (ADR 0009).
- **Settings** are owned by Catapulsar and published on a compacted topic every service reads at start-up
  (ADR 0011).
- **Every service runs N replicas, all working**: Pulsar subscriptions distribute bridge messages, collection
  tasks and audit events; writes are idempotent (deterministic ids, conditional updates, claims) (ADR 0012).
- **Catametricas is the only metrics API**; MongoDB for history, Prometheus for short ranges once it exists
  on MicroShift (ADR 0013). Exception: everything in Catapulsar's **view of one topic** is that topic's current
  state, read directly from Pulsar on every refresh (OBS-10).

### Phases

Each phase ends with `mvn verify` green in every app, the docs updated and the app usable.

| # | Phase | Main changes | Status |
|---|---|---|---|
| 1 | Event foundations | Reactor + catacommons; tenant/namespaces/policies at start-up; CloudEvents publisher with retries in each app; events for every action already audited today (still writing `audit_logs` too); Catapulsar already publishes the settings topic | **Done** 2026-10-31 |
| 2 | Cataudit | New service; each app registers its `audit-<app>` system bridge; `audit_logs` copied into `cataudit_events` and no longer written; Catapulsar's new audit view reads Cataudit | **Done** 2026-10-31 |
| 3 | Settings topic | Catametricas and all Catapulsar replicas read the settings topic (`SharedSettings`, TableView); nobody reads settings from MongoDB; `pulsario_settings` → `catapulsar_settings` (copied, then dropped) | **Done** 2026-11-01 |
| 4 | Catametricas API and tasks | Query API (`topics`, `series`, `collector`); Catapulsar overviews built from it (Catametricas URL editable); collection tasks on Pulsar with claims and conditional snapshot updates; lease removed; `contario_*` → `catametricas_*` (renamed) | **Done** 2026-11-01 |
| 5 | Cataponte replicas | `Exclusive` rejected; N replicas, status merged over replicas (`cataponte_worker_status`); ADR 0002 superseded; collections `cataponte_*` | **Done** 2026-11-01 |
| 6 | Prometheus source (later) | Once Prometheus/OpenMetrics/Loki run on MicroShift: short ranges from PromQL in Catametricas | Later |
| 7 | Alerts and dead letters | Dead-letter topics for our Shared/Key_Shared consumers; new service **Catavigia** with alert history; alerts and actions panel in Catapulsar; Settings dialog in tabs (decisions 42–49 and 66–80; [ADR 0015](docs/adr/0015-dead-letters-and-catavigia.md); 7a: DLQs and consumer control, 7b: Catavigia and alerts) | **Done** 2026-11-09 (7a 2026-11-06) |

### Decisions taken on 2026-10-31

1. Tenant, namespaces, topics and policies are created **by each app at start-up** (idempotent; every app
   needs Pulsar admin access).
2. Envelope, publisher, topic names and settings reader live in a shared library, **`catacommons`**.
3. *(Superseded by 40.)* Existing collections are **renamed to their owner's prefix** with a one-off, idempotent migration per
   owner (ADR 0010).
4. Until an authentication proxy exists, the event `actor` is the **client IP address**.
5. The apps become a **Maven multi-module build**: `apps/pom.xml` aggregates `catacommons` and every app;
   `mvn clean install` at `apps/` builds everything, and each Dockerfile builds from `apps/` with
   `mvn -pl <app> -am`.
6. **Cataudit** listens on port **8084**.

Phase 1 decisions (2026-10-31):

7. `apps/pom.xml` is **aggregator only**; every module keeps `spring-boot-starter-parent` and **its own
   version** (catacommons starts at `0.1.0-SNAPSHOT`).
8. catacommons is a Spring Boot **auto-configuration**; each app creates its own `PulsarClient` and
   `PulsarAdmin` (Cataponte gained the admin client, Catametricas the binary client).
9. Event topics are **non-partitioned**; namespace retention 7 days / 1 GB and backlog quota 1 GB with
   **`producer_exception`**. *(Retention changed to 2 GB by 41.)*
10. The event schema is a Pulsar JSON schema of `CloudEvent`, with **`data` as text maps**
    (`actor`, `before`, `after`, `details`).
11. Publishing is **asynchronous** with 3 retries (1 s, 2 s, 4 s), then `ERROR` with the full event.
12. Bridge changes are published by **both** apps: Catapulsar `bridge.*-requested` (every forwarded request,
    refused ones included) and Cataponte `bridge.*` (the change made).
13. Catametricas publishes only **state changes** of collection: `collection.failed` and
    `collection.recovered`.
14. *(Superseded by 40.)* Collections are renamed to their owner's prefix **in the phase of each owner** (see the table).
15. The settings topic is **created and published already in phase 1**, with the **new keys**; readers come
    in phase 3.
16. Docker builds cache the Maven repository with a **BuildKit cache mount**.

Phase 2 decisions (2026-10-31):

17. **Each app registers its own** `audit-<app>` bridge at start-up (catacommons); Catametricas gets
    `CATAPONTE_URL`, Catapulsar uses its editable Cataponte URL, Cataponte registers in-process.
18. Registration is idempotent through a new **`PUT /api/v1/bridges/system/{name}`** in Cataponte.
19. System bridges get **only a badge**: they can be edited, disabled and deleted like any bridge.
20. *(Superseded by 40.)* `audit_logs` is **copied and kept**: Cataudit copies it at every start (ids `legacy-<ObjectId>`); delete
    it by hand when no longer needed.
21. The audit view is **new, with filters** (app, entity, action, subject prefix, actor, period) and pages.
22. Audit events are kept **forever**.
23. Catapulsar and Cataponte **stop writing `audit_logs` for good** (the code is removed).
24. The **Cataudit URL is editable in Catapulsar**, like the Cataponte URL (Settings dialog, link in the Audit
    panel, check button, audited, published); `CATAUDIT_URL` is its default. Both share `UrlSetting`.

Phase 3 decisions (2026-11-01):

25. **Every** Catapulsar setting is read from the topic (URLs and intervals); `catapulsar_settings` is only
    written, and is the source Catapulsar republishes from at start-up.
26. *(Superseded by 40.)* `pulsario_settings` is **copied and dropped**: ids become the topic keys, a value already in
    `catapulsar_settings` wins, and the old collection is kept (with a warning) if it holds an unknown id.
27. Until the topic has been read, apps **use their configured defaults** (no waiting).
28. Cataponte reads **no** shared setting yet; the reader is available in catacommons when it needs one.

Phase 4 decisions (2026-11-01):

29. Catametricas' API is **generic**: `GET /api/v1/topics` (current state and all-time traffic per topic or
    partition), `GET /api/v1/series` (traffic and backlog per bucket, grouped by tenant, namespace or topic;
    steps below an hour read minutes, whole hours read hours; at most 2000 buckets) and
    `GET /api/v1/collector`. Catapulsar keeps building the overview rows, summary and chart buckets.
30. The **Catametricas URL is editable in Catapulsar**, like the Cataponte and Cataudit URLs
    (`catapulsar.catametricas-url`, default `CATAMETRICAS_URL`, check button, audited, published).
31. *(Superseded by 40.)* `contario_*` collections are **renamed** (`renameCollection`, indexes kept) to `catametricas_*` at start-up;
    `contario_locks` and `contario_status` are dropped. If both names exist, the old one is left with a warning.
32. One collection task **per namespace and interval**, id `<tenant>/<namespace>@<interval start>`; intervals
    are aligned to the epoch so every instance publishes the same ids. Claims live 6 h (TTL).
33. ~~**Every task is run, however late**~~ *(replaced by 43 and 66 in phase 7a: a task that fails N times on its own
    goes to the DLQ; one that fails because MongoDB or Pulsar is down pauses the consumer)*: the conditional snapshot
    update makes a late or repeated task harmless.
    A failed task releases its claim and is delivered again after 10 s, doubling up to 5 min.
34. Overviews answer **`503` with an error in the panel** when Catametricas is down (`catametricas` health
    component, not part of readiness).
35. The first collection of a **namespace** (no snapshot of it yet) only sets the baseline; a namespace that no
    longer exists marks its topics as gone. "Last collection" is the newest snapshot.

Phase 5 decisions (2026-11-01):

36. `Exclusive` is **refused** by the API (`400`); ordered bridges use `Failover` or `Key_Shared`.
37. Bridge status is **merged over replicas through MongoDB**: each replica writes its workers' status to
    `cataponte_worker_status` every 5 s (TTL 5 min, ignored after 30 s); the API returns the most advanced state,
    summed counters, and how many replicas run the bridge and how many receive (`replicas`, `activeReplicas`;
    `Failover` activity comes from the broker's consumer events). If MongoDB is down, a replica answers for itself.
38. Cataponte's collections are `cataponte_bridges`, `cataponte_traffic_minutes`, `cataponte_traffic_totals` and
    `cataponte_worker_status`; the old ones are not migrated (decision 40).
39. The loop check on writes stays **in-process**: two writes on different replicas at the same instant could
    still create a loop, and the reconciler then blocks those bridges (`BLOCKED`, ADR 0005).

No backward compatibility (2026-11-01):

40. The data in MongoDB is test data, so the apps keep **no backward compatibility**. Removed: the copy of
    `pulsario_settings` and its old ids (Catapulsar), the rename of `contario_*` (Catametricas), the copy of
    `audit_logs` and the `legacy` flag (Cataudit), the copy of `bridges`/`bridge_traffic_*`, the move from
    `Exclusive` to `Failover`, missing versions, the single `destination` field and the `bridge.migrated` event
    (Cataponte). The old collections, and the `legacy-*` entries already copied into `cataudit_events`, are dropped
    by hand. MONGO-2 applies again once there is data worth keeping.

Start-up fix (2026-11-01):

41. Pulsar refuses (`412`) a backlog quota equal to or above the namespace retention size, so the 1 GB / 1 GB of
    decision 9 stopped provisioning before the topics were created. Namespace retention is now **2 GB**, the backlog
    quota stays **1 GB**, and `CatacommonsProperties` **refuses to start** when the quota is not below the retention
    (compared in whole megabytes; a retention of zero or less is not checked).

Forced collection and live mode (2026-11-04):

50. Catametricas offers a **forced collection**, `POST /api/v1/collect?tenant&namespace`, synchronous. Each namespace
    of the scope runs through the normal claim and idempotent write as task `forced:<namespace>@<window start>`, so a
    namespace is collected **at most once every 5 s** (`catametricas.collect.forced-window`), shared by every caller
    and replica; later requests in the window count it as coalesced.
51. Each forced collection is **audited by its owner**, Catametricas, as `collection.forced` (actor = browser address
    from `X-Forwarded-For`). Catapulsar forwards the request (`POST /api/v1/overview/collect`) without an event of
    its own.
52. Catapulsar's overviews get **⟳ Collect now** (cluster, tenant and namespace) and **● Live** (namespace only):
    a forced collection every 5 s, **only while the browser tab is visible**, **off after 10 minutes** and when
    the user leaves the namespace.
53. While live mode is on, a **red banner** above the overview shows the namespace, the three limits and the time
    left, with a Stop button.

Bridge areas (2026-11-04, [ADR 0014](docs/adr/0014-bridge-areas.md)):

54. Cataponte owns **areas**: named groups of bridges (`cataponte_areas`), each with 0–20 **glob patterns** over
    topic names written without `persistent://` (`*` within one segment, `**` across segments; no patterns =
    every topic). A bridge has at most one area (`areaId`); without one it is in **Bridges**; system bridges are
    fixed in **System** (`areaId` refused).
55. A bridge in an area must have its **source and every destination** matching the area's patterns, on create, edit
    and move (`400` naming the topics that do not fit). Cataponte enforces it, so it holds for the API too.
56. Changing an area's patterns is **always accepted**: bridges that no longer fit keep running, marked
    **outside filter** (`outsideArea`), and must fit again when edited.
57. Deleting an area that has bridges needs a choice: **delete** them all, or **move** them to Bridges or another
    area (`DELETE /api/v1/areas/{id}?bridges=delete|move&to=`); without it, `409`.
58. **Every area action goes to Cataudit**: `area.created|updated|deleted` on `cataponte/events` through the
    `audit-cataponte` bridge; moves and bulk deletions also publish one `bridge.updated`/`bridge.deleted` per bridge.
59. Catapulsar's Bridges panel has tabs **System | Bridges | <areas> | + Area**, with counts; it opens on the last
    tab used (browser storage), System by default. *Running* counts the open tab; **+ New Bridge** is hidden on
    System and, in an area, creates the bridge there with topic pickers limited to the area's patterns.

Bridge subscriptions and consumers (2026-11-04):

60. Deleting a bridge **deletes its Pulsar subscription**, and so does changing a bridge's source topic or
    subscription name (the old one), unless another bridge still uses it. Cataponte queues the deletion in
    `cataponte_subscription_cleanups` and tries it after 30 s (the bridge's workers on every replica stop first;
    deleting while they are connected would make them subscribe again), without `force`, then every minute until it
    is gone. It is published as `subscription.deleted` (actor = who deleted the bridge, details = bridge and
    backlog), so Cataudit has it. Catapulsar **warns before the last confirmation**, with the subscription and the
    messages left in its backlog (or that it is kept because another bridge uses it).
61. Two bridges may not read the **same topic with the same subscription** (`409`): they would split its messages,
    or on `Failover` only one would receive. The bridge form suggests `cataponte-<bridge name>`.
62. Our consumers have **recognisable names** in Pulsar's statistics: `cataponte@<instance>/<bridge>`,
    `catametricas@<instance>`, `cataudit@<instance>` (instance = host name plus a random suffix).
63. Catapulsar's topic view shows, for each subscription (Subscription Details and the Backlog list), the **bridge**
    that uses it with its destinations (a link to the bridge) and **who is connected**: consumer name, address,
    connected since, client version, rate, unacked, and which one is active on `Failover`. All from Pulsar, except
    the bridge definitions, which come from Cataponte.
64. Name fields in Catapulsar (new topic, new subscription, bridge name and subscription, area name) **correct as you
    type or paste**: a character the name cannot have, a space included, becomes `-`, and a first character it
    cannot start with is not written. Area patterns keep `*`, `/` and `:`, and drop spaces. The APIs still validate.
65. Catapulsar's sidebar **keeps open every tenant and namespace you open**, remembered per browser. Clicking a name
    selects it and opens it; clicking the selected one opens or closes it. **Collapse all** closes everything but the
    path to the selection. Auto-refresh reloads only the selected node; the others reload when selected or after a
    page reload.

Phase 7 decisions (2026-11-04, [ADR 0015](docs/adr/0015-dead-letters-and-catavigia.md) accepted 2026-11-06; detailed in 66–72):

42. A message that keeps failing is **poison**: after **N deliveries** (default **10**, about 2 minutes) Pulsar moves it
    to a **dead-letter topic** and the consumer carries on. N is a shared setting, editable in Catapulsar's Settings.
43. Dead-letter topics for **every Shared/Key_Shared consumer of ours**: Cataudit (`audit`), Catametricas collection
    tasks (this changes decision 33: a task that fails N times goes to the DLQ instead of running however late) and
    Cataponte bridges with `Shared`/`Key_Shared` subscriptions. `Failover` bridges stay as they are (Pulsar has no DLQ
    for them).
44. A new service, **Catavigia**, watches everything periodically and **keeps the history** of each alert (opened,
    closed). Alerts: poison messages (DLQ with messages), bridges `FAILED`/`BLOCKED`, Catametricas collection failing,
    services unavailable, subscription backlog above a limit, Pulsar brokers and bookies available/unavailable,
    MongoDB available/unavailable.
45. Backlog and DLQ numbers come from **Catametricas** (current backlog per topic, at most one collection interval old).
46. Actions are taken by **the owner of each resource** through its existing API (ADR 0010): Catapulsar for DLQ
    messages (peek, send back to the original topic, discard), Cataponte for bridges. Catavigia only watches.
47. Catapulsar gets an **alerts and actions** panel. Its Settings dialog is split into **tabs** (connections, refresh
    and collection, alerts), with the alert limits in the alerts tab. *(Connections and Refresh & collection done on
    2026-11-04, with the auto-refresh interval also editable there; the Alerts tab comes with phase 7.)*
48. Like every app, Catavigia publishes an **event for every action** (`alert.opened`, `alert.closed`, changes of its
    configuration) on `catacumbas69/catavigia/events`, and registers its `audit-catavigia` bridge so Cataudit stores
    them.
49. Catavigia exposes `alerts_open{type,severity}` on `/actuator/prometheus`, for Grafana once Prometheus runs on
    MicroShift. External notifications (Telegram, email) are left for later.

Phase 7, after the review of ADR 0015 (2026-11-06):

66. **Only a message can be poison, not an outage.** Cataudit and Catametricas **pause** their consumer while MongoDB
    or Pulsar's admin API is down and **resume by themselves** when it answers; a Cataponte bridge whose destination
    fails for every message goes `FAILED` instead of negatively acknowledging. Only a failure of that message counts
    towards N deliveries.
67. Our consumers can be **paused, resumed and checked by hand**, per replica or all at once, from Catapulsar's
    **Consumers** panel. A manual pause is never resumed automatically. *Check connections* pings MongoDB and Pulsar's
    admin API now and reports each result. Status goes on the compacted topic `catacumbas69/<app>/consumers` (key =
    instance), commands on `catacumbas69/<app>/consumer-commands` (read by every replica), **through Pulsar, not
    MongoDB**, so they work while MongoDB is down. Each app audits `consumer.paused|resumed|checked`.
68. DLQs are named `<topic>-<subscription>-DLQ`, in the topic's namespace, created with the subscription `dlq`, whose
    backlog is the number of poison messages left to look at. Catapulsar's DLQ view peeks them and can **send back**
    one to its original topic or **discard** one or all, audited.
69. A `Failover` bridge stuck on a poison message offers **Skip this message**: Cataponte acknowledges that message,
    publishes `bridge.message-skipped`, and the bridge carries on.
70. Backlog alerts are **per subscription**: a subscription's own limit, set on its row in the topic view and kept by
    Catavigia, or the **global default** in Settings → Alerts. Every threshold has a global default there.
71. An open alert can be **marked as seen** (who, when; `alert.seen`): it stays open and leaves the badge count.
72. A closed alert **moves to the archive** (`catavigia_alerts_archive`), kept for ever, with its own tab and filters.
73. Cataudit registers its own **`audit-cataudit`** bridge, so its consumer events reach the audit trail (there is no
    loop: they are published on state changes, never per message). A message that is not a CloudEvent goes
    **straight to the DLQ** (property `DLQ_REASON`) instead of being dropped.
74. Cataponte tells a message the destination **refuses** (not valid, too large, schema not compatible: it counts
    towards the DLQ) from a destination that is **unavailable** (timeout, connection lost, quota full, topic
    terminated): the bridge goes `FAILED`, pauses and resends the held messages itself until accepted, so an outage
    never fills the DLQ. Only **unordered** `Shared`/`Key_Shared` bridges have a DLQ. Changing `dlq.max-deliveries`
    restarts them. *Skip this message* checks with Pulsar's admin API that the message is still the first
    unacknowledged one, then skips one entry (partitioned sources not supported yet).

Phase 7b, after the review of its design (2026-11-06, ADR 0015 points 8–13):

75. Catavigia's checks run as **tasks on Pulsar** (`catacumbas69/catavigia/check-tasks`, `Shared` subscription
    `catavigia`): every replica publishes one task per check and round, key `<check>@<round start>`, claimed in
    `catavigia_task_runs`; the debounce counts are in `catavigia_observations`. The consumer has the control of
    decision 67 (Consumers panel), pauses on outages (66) and has a DLQ (68).
76. While its MongoDB is down, each Catavigia replica keeps `mongodb.down` **in memory** (API, badge, metric) and
    publishes it as an event; the other checks stop; when MongoDB answers it is archived with its real duration.
77. Catametricas keeps the **history per subscription inside the topic's documents**: a list in the snapshot and a
    map `subs` in the minute and hour documents (backlog last and maximum, messages and bytes delivered, unacked),
    with the same retention, and serves it on `/api/v1/subscriptions` and `/api/v1/subscriptions/series`.
78. Catapulsar shows a subscription's history when its row is expanded in *Subscription Details* (exception to
    OBS-10 for history only), and keeps **Catavigia's URL** in Settings → Connections; Catavigia reads Catapulsar's
    URL from `CATAPULSAR_URL`.
79. Catavigia's checks, after the review of their design (2026-11-07, ADR 0015 points 14–19): `service.down` reads
    each app's **readiness** (the components of its full health that are not `UP` go in the details);
    `pulsar.broker.down` opens when fewer brokers are active than **expected** (`CATAVIGIA_EXPECTED_BROKERS`), and
    `pulsar.bookie.down` when brokers answer but the **broker health check** fails (Pulsar's admin API lists
    bookies that are down too); `consumer.paused` is **one alert per consumer** (`<app>/<consumer>`) naming the
    paused replicas; `backlog` leaves out the DLQs' `dlq` and Pulsar's `__*`; with Pulsar down no check runs
    (accepted); a check that cannot read its source is **delivered again** like any failed task (up to the DLQ),
    and a task of an old round still runs but changes no alert.
80. Catapulsar's alerts, after the review of their design (2026-11-08, ADR 0015 points 20–27): the **Alerts** button
    counts the open alerts not seen (red if one is critical, orange otherwise, grey `?` when Catavigia does not
    answer) and asks every 30 s while the browser tab is visible, whatever the auto-refresh; the panel's tabs Open
    and Archived are expandable tables like the audit; **Mark seen** per alert and **Mark all seen**; each alert's
    button opens where it is fixed, with the resource selected; the archive filters by type, subject and period.
    Settings → **Alerts** holds the four thresholds (`/api/v1/settings/alerts/{name}`) and the deliveries before the
    DLQ (the Dead letters tab is gone). A subscription's row gets a **Limit** column edited in place and a **📈**
    chart (backlog, maximum, delivered, limit; 24 h by default). Catavigia's URL is in Settings → Connections and
    `catavigia` is a health component. Marking as seen and changing a limit are audited by both apps
    (`*-requested` by Catapulsar).

MicroShift deployment (2026-11-09, closes G-16):

81. **Images** are built with **Podman** on the Mac by `apps/build/push.sh <app|all>`, for `linux/amd64`. The
    Dockerfiles' Maven stage runs on the build machine's own platform (`--platform=$BUILDPLATFORM`); only the
    runtime stage is amd64 (its one `RUN` under Rosetta). Docker still works for local images.
82. The **registry** runs in MicroShift (`registry:2.8.3`, namespace `registry`, 20 Gi PVC) behind the Route
    `registry.catacumbas69.com`, in plain HTTP like the other Routes and without authentication, since it is
    reachable only from the home network (the Route waits up to 5 min for a layer). CRI-O on meloes lists it as an
    insecure registry; Podman pushes with `--tls-verify=false`.
83. **Tags** are `<version>-<short sha>`. `push.sh` refuses to build with uncommitted changes under `apps/`
    (`FORCE=1`: `-dirty-<date-time>`), pushes and **writes the tag** into the app's `kustomization.yaml`
    (`images[].newTag`); the commit of that change is what ArgoCD deploys.
84. The apps use their own **MongoDB user** `catacumbas69-apps` (read and write on `catacumbas69` only), declared
    in `mongodb-cluster.yaml`. Its password is generated by `apps/build/mongo-secret.sh password` and never shown;
    the operator's connection string is **copied** by `mongo-secret.sh copy` into the apps' namespace (Secret
    `catacumbas69-apps-mongodb`, key `uri`), because the operator's `connectionStringSecretNamespace` gets that
    Secret garbage-collected. The URI names `admin`, so the ConfigMaps set `SPRING_DATA_MONGODB_DATABASE`.
85. **Layout**: one namespace `catacumbas69-apps`, one folder per app with ConfigMap, Deployment, Service and
    **Route** (`<app>.catacumbas69.com`, all five). **Two replicas** of Cataponte, Cataudit, Catametricas and
    Catavigia, **one** of Catapulsar; rolling updates with `maxUnavailable: 0`; requests **25m / 384 Mi** (meloes
    has 4 CPUs and its requests were full: 100m did not fit the nine pods), limits **1 CPU / 768 Mi**; startup
    probe up to 3 min; non-root, `seccomp` RuntimeDefault, no capabilities.
86. One **ArgoCD Application** per app and one for the registry, in the namespace `argocd`, **synced by hand** in
    the UI (no automated sync), in the start order Cataponte → Cataudit → Catametricas → Catavigia → Catapulsar.
87. **Names, never IPs**, so that moving the homelab to another network (192.168.86.x → 192.168.69.x) only changes
    DNS: the registry and the Routes are `*.catacumbas69.com`, and Pulsar's external listener is advertised as
    `meloes:31650` (`microshift/apps/pulsar/broker-init-script.sh`). Both names must resolve on the Mac and on
    meloes itself. The local URLs in the docs (IntelliJ) still name the IP for now.
