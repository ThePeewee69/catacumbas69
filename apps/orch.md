# orch.md — Engineering rules for `catacumbas69/apps`

This file is the single source of truth for **how Pulsário and Passário are built, changed and run**.
It is written for two readers:

- **Humans** — read it top to bottom once; use the checklists when reviewing.
- **Coding agents** — treat every rule tagged `[MUST]` as a hard constraint. Before changing code, read the
  rules for the area you touch. When a rule and existing code disagree, follow the rule and fix the code in
  the same change (or list it under [Known gaps](#14-known-gaps) if out of scope). Never weaken a rule to fit
  the code.

Rule strength follows RFC 2119: **MUST** / **MUST NOT** are mandatory, **SHOULD** is the default unless
there is a written reason, **MAY** is optional. Each rule has an ID (e.g. `ARCH-3`) so reviews and commits
can cite it.

This file lives at `apps/orch.md`. `apps/pulsario/orch.md` and `apps/passario/orch.md` are symlinks to it —
edit only the original.

---

## 1. System overview

```
                    ┌──────────────────────────────┐
  Browser ─────────▶│  Pulsário  (:8081)           │──── Admin REST ───▶ Pulsar broker
  (Alpine.js UI)    │  UI + BFF API + audit        │                     (HTTP :8080 / NodePort 31080)
                    └──────┬───────────────┬───────┘
                           │ REST /api/v1  │ audit
                           ▼               ▼
                    ┌──────────────┐   ┌──────────────────────────┐
                    │ Passário     │──▶│ MongoDB  catacumbas69    │
                    │ (:8082)      │   │ (ReplicaSet, 3 members)  │
                    │ bridge engine│   └──────────────────────────┘
                    └──────┬───────┘
                           │ binary protocol (consume → produce, fan-out)
                           ▼
                     Pulsar broker (:6650 / NodePort 31650)
```

| Component | Responsibility | Owns (data) | Talks to |
|---|---|---|---|
| **Pulsário** | Web UI for Pulsar (tenants, namespaces, topics, subscriptions, backlog, produce); UI for bridges; audit of user actions | Mongo collection for audit entries | Pulsar Admin API, Pulsar binary (produce/peek), Passário REST, MongoDB |
| **Passário** | Runs bridges: consumes a source topic and fans each message out to 1..n destination topics; CRUD API for bridge config | Mongo collection `bridges` | Pulsar binary, MongoDB |

Runtime: MicroShift on `meloes` (NUC, `192.168.86.35`), deployed with ArgoCD. Local development runs
from IntelliJ on the Mac (`necromancer`) against the cluster through NodePorts.

---

## 2. Architecture rules

- **ARCH-1 [MUST]** Each service owns its MongoDB collections. A service MUST NOT read or write another
  service's collection. Cross-service data goes through the owner's REST API.
- **ARCH-2 [MUST]** The browser talks only to Pulsário. Pulsário exposes the bridge API to its UI as a
  **backend-for-frontend (BFF)** proxy (`/api/v1/bridges/**` → Passário). Passário is not exposed outside
  the cluster except for local development.
  *Why:* one origin (no CORS), Passário stays internal, and every bridge change can be audited in one place.
- **ARCH-3 [MUST]** Passário is the only component that consumes or produces on behalf of a bridge.
  Pulsário MUST NOT implement relay logic.
- **ARCH-4 [MUST]** Bridge configuration in MongoDB is the desired state; Passário reconciles running
  workers to it on a fixed interval (`bridge.reconcile.interval`). API calls change the document only;
  they MUST NOT start or stop workers directly. This keeps one code path for start/stop/restart.
- **ARCH-5 [MUST]** Passário runs with **exactly one replica** until leader election or bridge sharding
  exists. With 2+ replicas every replica runs every bridge: `Exclusive`/`Failover` subscriptions fail and
  `Shared` ones split traffic unpredictably. Encode this in the Deployment (`replicas: 1`,
  `strategy: Recreate`).
- **ARCH-6 [MUST]** Bridge delivery is **at-least-once**: the source message is acknowledged only after
  every destination accepted it; any failure → negative ack → redelivery. Consumers of destination topics
  MUST tolerate duplicates. Do not add "exactly-once" claims anywhere without Pulsar transactions.
- **ARCH-7 [SHOULD]** Keep services stateless apart from MongoDB and Pulsar. In-memory state (e.g. Passário's
  worker map and counters) is a cache of runtime facts, never configuration.
- **ARCH-8 [MUST]** Configuration follows 12-factor: every environment-specific value is an env var with a
  cluster-internal default in `application.properties`. No profile-specific property files with hosts or
  credentials.

### 2.1 Configuration contract

| Env var | Service | Default (in-cluster) | Local (necromancer) |
|---|---|---|---|
| `PULSAR_SERVICE_URL` | Pulsário | `http://pulsar.pulsar.svc.cluster.local:8080` | `http://192.168.86.35:31080` |
| `PULSAR_BROKER_URL` | both | `pulsar://pulsar.pulsar.svc.cluster.local:6650` | `pulsar://192.168.86.35:31650` |
| `PULSAR_LISTENER_NAME` | both | *(empty)* | `external` |
| `SPRING_DATA_MONGODB_URI` | both | in-cluster ReplicaSet URI from Secret | NodePort `32017` + `authSource=admin&directConnection=true` |
| `PASSARIO_URL` | Pulsário | `http://passario.<ns>.svc.cluster.local:8082` | `http://localhost:8082` |

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
  boundaries (e.g. a `BridgeClient` interface in Pulsário for calls to Passário), no circular
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
  goes in `pulsario.css` classes. No inline `<script>` blocks.
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
  BFF for bridges (ARCH-2), single-replica Passário (ARCH-5), at-least-once fan-out (ARCH-6),
  config-as-desired-state reconciliation (ARCH-4).
- **DOC-5 [SHOULD]** Diagrams as text (Mermaid or ASCII) in Markdown, so they are versioned with the code.

### 3.7 Tooling

| Language | Formatter | Linter | Runs in |
|---|---|---|---|
| Java | IntelliJ default style + `.editorconfig` | Checkstyle | `mvn verify` |
| HTML | Prettier | html-validate | `npm run lint` in `apps/pulsario` |
| JS | Prettier | ESLint | `npm run lint` in `apps/pulsario` |
| CSS | Prettier | Stylelint | `npm run lint` in `apps/pulsario` |

The frontend lint tools are dev-only (`apps/pulsario/package.json`, `devDependencies`); they never ship
in the jar. `mvn verify` in Pulsário runs `npm run lint` through `frontend-maven-plugin` so one command
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

- **PUL-1 [MUST]** One `PulsarClient` bean per service (`destroyMethod = "close"`), configured from
  `PULSAR_BROKER_URL` + optional `PULSAR_LISTENER_NAME`. Never create clients per request.
- **PUL-2 [MUST]** Both services use the **same Pulsar client version**, from the broker's LTS line.
- **PUL-3 [MUST]** Topics are always stored and passed as fully-qualified names
  (`persistent://tenant/namespace/topic`).
- **PUL-4 [SHOULD]** Bridge subscriptions are named `passario-<bridge-name>` unless the user sets one;
  never reuse an application's own subscription.
- **PUL-5 [MUST]** Producers/consumers are closed when a worker stops, including when `start()` fails
  halfway.

### 4.3 MongoDB

- **MONGO-1 [MUST]** Timestamps come from Spring Data auditing (`@CreatedDate`, `@LastModifiedDate`,
  `@EnableMongoAuditing`), not set by hand in controllers.
- **MONGO-2 [MUST]** Schema changes are backward compatible: read old and new shapes, write only the new
  one (example: `destination` → `destinations[]` in `bridges`). Remove the old shape only after a
  migration has rewritten all documents.
- **MONGO-3 [SHOULD]** Declare indexes in code (`@Indexed`, `@CompoundIndex`) with
  `spring.data.mongodb.auto-index-creation=true`; `bridges.name` is unique.
- **MONGO-4 [MUST]** Local connections to the ReplicaSet use `directConnection=true` against the
  NodePort; in-cluster connections use the replica-set URI.

---

## 5. REST contracts and errors

- **API-1 [MUST]** All endpoints live under `/api/v1/…`. Breaking changes → `/api/v2`, never silent.
- **API-2 [MUST]** Resource-oriented URLs, plural nouns: `GET/POST /api/v1/bridges`,
  `GET/PUT/DELETE /api/v1/bridges/{id}`. State changes that are not CRUD are sub-resources with POST:
  `POST /api/v1/bridges/{id}/enable`.
- **API-3 [MUST]** Status codes: `200` read/update, `201` + `Location` on create, `204` on delete,
  `400` validation, `404` missing, `409` conflict (e.g. duplicate name), `502` upstream (Pulsar/Passário)
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

## 6. Frontend (Pulsário static UI)

Stack: plain HTML + Alpine.js 3 (from cdnjs, pinned version), one `index.html`, `pulsario.js`, `pulsario.css`.

- **UI-1 [MUST]** Every `<template x-if>` / `<template x-for>` contains **exactly one element root**.
  Text-only content (`<template x-if="…">📤</template>`) breaks Alpine — wrap it in a `<span>`.
- **UI-2 [MUST]** Static assets are cache-busted: links carry `?v=<N>`, and `N` is bumped in the same
  change that edits `pulsario.js` or `pulsario.css`. The server sends `Cache-Control: no-cache`.
- **UI-3 [MUST]** All HTTP calls go through one helper (`api()`), which turns Problem Details into toast
  messages. No `fetch` scattered in templates.
- **UI-4 [MUST]** The UI language is **English** — labels, placeholders, empty states, toasts.
- **UI-5 [MUST]** Destructive actions (delete topic/subscription/bridge, skip messages) ask for
  confirmation and name the target.
- **UI-6 [SHOULD]** Group state and methods by feature (`bridges.*`, `backlog.*`) in `pulsario.js`;
  keep expressions in HTML to simple reads and single calls.
- **UI-7 [MUST]** Pickers and lists have bounded height (scroll inside), consistent with `.sub-picker`
  styles; no unbounded growth of the page.
- **UI-8 [MUST]** Views are mutually exclusive: opening a topic closes the Bridges panel and vice versa.
- **UI-9 [SHOULD]** Colours, radii and fonts only from the CSS variables in `:root`.

---

## 7. Performance

Targets are measured on `meloes` (NUC, MicroShift) with the broker on the same node.

- **PERF-1 [MUST]** Each Passário bridge sustains **≥ 1,000 msg/s** (1 KiB payloads, up to 3 destinations)
  with p99 forward latency **< 100 ms**. Pulsário read endpoints answer p95 **< 300 ms**.
- **PERF-2 [MUST]** No blocking round-trip per message in Passário. Sends are asynchronous
  (`sendAsync`) and pipelined; the number of unacknowledged messages per bridge is bounded by
  `maxInFlight` (default 1,000) with a semaphore, so memory stays flat under back-pressure.
- **PERF-3 [MUST]** Producers use batching (`batchingMaxPublishDelay` 1 ms, `batchingMaxMessages` 1,000)
  and `blockIfQueueFull=true`; consumers use `receiverQueueSize` 1,000 and grouped acks
  (`acknowledgmentGroupTime` 100 ms). Values are configuration, not literals.
- **PERF-4 [MUST]** Ordering is a per-bridge choice (`ordered`, default `true`):
  - **ordered** — pipelined sends through one producer per destination keep source order on success.
    On any failure the worker pauses, waits for in-flight sends, and calls
    `redeliverUnacknowledgedMessages()` so the stream is replayed **in order** (duplicates possible,
    reordering not). Requires an `Exclusive`, `Failover` or `Key_Shared` subscription — `Shared` is
    rejected by validation.
  - **unordered** — failures are negative-acked individually and redelivered later; any subscription type
    is allowed. Highest throughput.
- **PERF-5 [MUST]** Pulsário never loads unbounded data into memory: peek counts are capped (≤ 100),
  lists are paginated (API-8), and the topic list for the bridge form is fetched with bounded
  concurrency.
- **PERF-6 [SHOULD]** Reuse clients: one `PulsarClient`, one `PulsarAdmin`, one HTTP client per
  downstream; never create them per request.
- **PERF-7 [SHOULD]** Hot paths avoid per-message allocations that can be hoisted (e.g. build the
  property map once per message, not once per destination), and avoid logging per message at INFO.

---

## 8. Resilience

The services must keep working — or degrade visibly and recover on their own — when MongoDB, Pulsar or the
other service is slow or down.

- **RES-1 [MUST]** Both services **start** with MongoDB or Pulsar unavailable. Readiness reports the
  outage (OBS-2); nothing crashes or exits.
- **RES-2 [MUST]** Every remote call has explicit timeouts: Pulsar client (`connectionTimeout` 10 s,
  `operationTimeout` 30 s, producer `sendTimeout` 30 s), Pulsar admin (connect 5 s, read 30 s),
  Pulsário → Passário HTTP (connect 2 s, read 10 s), MongoDB (`serverSelectionTimeoutMS` 5000).
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
- **RES-9 [MUST]** Concurrent edits are detected: `bridges` documents carry `@Version`; a stale update
  returns `409 Conflict`.
- **RES-10 [MUST]** Write endpoints are idempotent where HTTP says so (`PUT`, `DELETE`,
  `POST …/enable|disable`). Deleting a missing bridge returns `404`, never `500`.
- **RES-11 [MUST]** Audit writes are best-effort and never fail the user's action: an audit failure is
  logged at WARN and the operation result is returned.
- **RES-12 [MUST]** The UI degrades per feature: if Passário is down, the Bridges panel shows the error
  and the rest of Pulsário keeps working; every request shows a clear toast on failure.

---

## 9. Testing

Pyramid: many unit tests, focused slice tests, few end-to-end tests.

- **TEST-1 [MUST]** Unit tests (JUnit 5, AssertJ, Mockito) for all service logic, with no network, no
  Docker and no Spring context — they run in seconds. Mandatory targets:
  - Passário: `BridgeManager` reconcile decisions (start / stop / restart on `updatedAt` change / keep
    running when Mongo fails / back-off after start failure); `BridgeWorker` fan-out to every
    destination, ack only after all destinations succeed, ordered-mode replay vs unordered nack,
    `maxInFlight` bound; bridge validation (destinations, source ≠ destination, ordered ⇒ not `Shared`);
    legacy `destination` → `destinations` normalisation.
  - Pulsário: audit is best-effort (RES-11), BFF error mapping (Passário down → `502`), input
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
- **OBS-4 [MUST]** Passário puts `bridgeId` and `bridgeName` in the MDC inside worker threads.
- **OBS-5 [MUST]** Passário exposes Micrometer metrics per bridge, tagged `bridge`:
  `passario_messages_forwarded_total`, `passario_forward_errors_total`, `passario_bridges_running`.
  The `/api/v1/bridges/status` counters read from the same meters.
- **OBS-6 [SHOULD]** Pulsário records an audit entry for every mutating action, including bridge
  create/update/delete/enable/disable passed through the BFF.

---

## 11. Security

- **SEC-1 [MUST]** Secrets (Mongo user/password, any tokens) come from Kubernetes Secrets mounted as env
  vars. Locally they live only in the IntelliJ run configuration, which is git-ignored.
- **SEC-2 [MUST]** Passário's Service is `ClusterIP` (ARCH-2). Pulsário is exposed through a Route behind
  authentication (e.g. an OAuth proxy) — the UI can delete topics and skip backlog.
- **SEC-3 [MUST]** Mongo users have least privilege: `readWrite` on `catacumbas69` only.
- **SEC-4 [MUST]** Validate and bound all user input (topic names, counts, payload sizes); never build
  Pulsar admin paths from unvalidated strings.
- **SEC-5 [MUST]** Containers run as non-root with a read-only root filesystem where possible
  (MicroShift `restricted-v2` SCC compatible: no fixed UID).

---

## 12. Build and deploy

- **OPS-1 [MUST]** Multi-stage Dockerfile per service: Maven build stage → JRE 21 runtime stage, layered
  jar (`java -Djarmode=layertools`), non-root, `EXPOSE` the service port, JVM tuned with
  `-XX:MaxRAMPercentage=75`.
- **OPS-2 [MUST]** Image tags are immutable (`<version>-<git-sha>`); `latest` is never deployed.
- **OPS-3 [MUST]** Kubernetes manifests live in the GitOps repo and are synced by ArgoCD. Each Deployment
  sets resource requests/limits, liveness/readiness probes on Actuator, and config via ConfigMap + Secret.
- **OPS-4 [MUST]** Passário Deployment: `replicas: 1`, `strategy: Recreate` (ARCH-5).
- **OPS-5 [SHOULD]** Graceful shutdown (`server.shutdown=graceful`) so workers stop and ack in-flight
  messages before the pod dies; `terminationGracePeriodSeconds` ≥ 30.

---

## 13. Workflow and definition of done

- **GIT-1 [MUST]** Conventional commits: `feat(passario): …`, `fix(pulsario): …`, `docs: …`. One logical
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
| G-1 | Pulsário | Spring Boot 3.3.4 is out of OSS support | §4 baseline |
| G-2 | Pulsário | Endpoints are under `/api/…`, not `/api/v1/…` | API-1 |
| G-3 | Pulsário | No OpenAPI (springdoc) | API-6 |
| G-4 | Pulsário | No Problem Details / central exception handler; ad-hoc `{error}` bodies | API-4 |
| G-5 | Pulsário | No Jakarta Validation on request types | API-5 |
| G-6 | both | No Testcontainers integration tests; Pulsário has only a placeholder test | TEST-1..3 |
| G-7 | Pulsário | Plain-text logs; no Prometheus metrics | OBS-3, OBS-5 |
| G-9 | Pulsário | UI calls Passário directly from the browser (CORS allowed from `PASSARIO_CORS_ALLOWED_ORIGINS`) instead of a BFF proxy; bridge actions not audited | ARCH-2, API-7, OBS-6 |
| G-11 | Pulsário | No UI smoke test | TEST-4 |
| G-16 | Passário | No Kubernetes manifests yet (Dockerfile exists) | OPS-2..4 |
| G-17 | Pulsário | No Checkstyle, ESLint, Stylelint, html-validate or Prettier | JAVA-7, HTML-5, JS-6, CSS-6 |
| G-18 | Pulsário | Missing Javadoc, `package-info.java`, JSDoc and README update | DOC-1, DOC-2, DOC-3, JS-4 |
| G-20 | Pulsário | Clickable `<div>`s in the sidebar tree and pickers; many inline `style=""`; modals without `role="dialog"`; decorative SVGs without `aria-hidden` | HTML-1, HTML-2, HTML-4 |
| G-21 | both | No CI pipeline running `mvn verify` and the frontend linters on every push | TEST-5 |
| G-22 | Pulsário | `Dockerfile` copies `target/pulsar-client-*.jar`, but the artifact is `pulsario-*.jar` | OPS-1 |
| G-24 | Pulsário | Remote calls rely on library default timeouts; no MongoDB server-selection timeout | RES-2 |

Closed for Passário (2026-10-30, `mvn verify` green, 46 tests, 0 Checkstyle violations): Boot 3.5, Pulsar
client 4.0.3, `@ConfigurationProperties`, auditing, indexes, `/api/v1`, DTOs, validation, Problem Details,
OpenAPI, Actuator probes, Micrometer meters, MDC, async fan-out with ordering modes, start back-off,
`@Version`, explicit timeouts, Javadoc, `package-info.java`, ADRs 0001–0004, README, Dockerfile (G-8, G-10,
G-12, G-13, G-14 partly, G-15, G-19, G-23).

### Remediation plan

Applied in this order; each phase ends with `mvn verify` green and the app running locally.

1. **Foundations** — G-1, G-8, G-13, G-14, G-17, G-24: Boot upgrade, aligned Pulsar client,
   `@ConfigurationProperties`, Mongo auditing and indexes, CORS from config, and the formatter/linter
   tooling — so every later phase is checked automatically.
2. **API contract** — G-2, G-3, G-4, G-5, G-12: `/api/v1`, DTO records, validation, Problem Details,
   springdoc.
3. **BFF** — G-9: Pulsário proxies `/api/v1/bridges/**` to Passário and audits mutations; UI switches to
   same-origin calls; Passário CORS disabled.
4. **Performance & resilience** — G-23: async pipelined fan-out, ordering modes, back-off, optimistic
   locking, graceful shutdown.
5. **Observability** — G-7, G-15: Actuator probes, JSON logs, MDC, Micrometer meters.
6. **Tests** — G-6, G-11: unit, slice, Testcontainers, Playwright smoke.
7. **UI conventions** — G-10, G-20: English strings, semantic and accessible markup, inline styles moved
   to CSS.
8. **Documentation** — G-18, G-19: Javadoc, `package-info.java`, JSDoc, ADRs, README per service.
9. **Deploy** — G-16, G-21, G-22: Passário Dockerfile and manifests for ArgoCD.
