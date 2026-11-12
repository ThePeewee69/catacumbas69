# Cataudit

Cataudit owns the **audit trail** of the catacumbas69 apps (ADR 0009). Every app publishes its audited actions
as CloudEvents on its own topic `catacumbas69/<app>/events`. A system bridge in Cataponte, `audit-<app>`, which
each app registers at start-up, copies them to `catacumbas69/cataudit/audit`. Cataudit stores each one once
and serves them to Catapulsar's Audit panel.

Engineering rules: [`../orch.md`](../orch.md) §4.4. Decisions: [ADR 0009](../docs/adr/0009-cataudit.md),
[ADR 0008](../docs/adr/0008-event-driven-integration.md), [ADR 0012](../docs/adr/0012-horizontal-scaling-and-idempotency.md).

## How it works

```
Catapulsar ─┐                       ┌─ audit-catapulsar ─┐
Cataponte  ─┼─▶ <app>/events ──▶ Cataponte ─ audit-cataponte ─┼─▶ cataudit/audit ──▶ Cataudit ──▶ cataudit_events
Catametricas┘                       └─ audit-catametricas┘        (Shared)          (_id = event id)
```

- **Consumer**: subscription `cataudit`, type `Shared`, so every replica takes a share; each replica's consumer
  is named `cataudit@<instance>` in Pulsar's statistics. A message is acknowledged once stored, or when an event
  with the same id is already stored (duplicates are harmless). Subscribing runs in the background and is retried,
  so Cataudit starts while Pulsar is down.
- **Poison messages** (ADR 0015): a message that fails to be stored for its own reasons is negatively acknowledged
  and delivered again after 10 s; after `dlq.max-deliveries` deliveries (shared setting, default 10, about
  2 minutes) Pulsar moves it to `catacumbas69/cataudit/audit-cataudit-DLQ`, whose subscription `dlq` keeps it. A
  message that is not a CloudEvent goes there straight away (property `DLQ_REASON`). Changing the setting makes the
  consumer subscribe again.
- **MongoDB down** pauses the consumer (the message is delivered again later, counted once) instead of retrying
  every message; it resumes by itself when MongoDB answers a ping (checked every 5 s).
- **Control** (orch.md decision 67): the consumer can be paused, resumed and its connections checked by hand from
  Catapulsar's Consumers panel, through `catacumbas69/cataudit/consumer-commands`; its state is published on
  `catacumbas69/cataudit/consumers`. A manual pause is resumed only by hand. Each change is audited as
  `consumer.paused|resumed|checked`.
- **Storage**: `cataudit_events` holds the CloudEvent fields plus `app`, `entity` and `action` taken from the
  type `com.catacumbas69.<app>.<entity>.<action>.v1`, and `receivedAt`. `before`, `after` and `details` are
  stored as lists of `{key, value}` pairs, because their keys contain dots (`source.topic`) and MongoDB map
  keys cannot (MONGO-7); the API returns them as maps. Events are kept **forever**. Indexes
  cover `time` and `app`, `subject`, `actor` and `entity+action`, each with `time`.
- **Pulsar**: at start-up catacommons creates the namespace `catacumbas69/cataudit` and the topic `audit`, with
  the CloudEvent schema. Cataudit registers its own `audit-cataudit` bridge (`cataudit/events` → `cataudit/audit`)
  in Cataponte for its consumer events; they are published only when the consumer changes state, so there is no
  loop.
- **Several replicas are safe**: the `Shared` subscription and the id-keyed inserts make that so.

## API

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/audit?app=&entity=&action=&subject=&actor=&from=&to=&page=0&size=50` | Search, newest first. `subject` matches a prefix, `from`/`to` are ISO-8601, `size` is capped at 500. Returns `{items, page, size, total}` |
| `GET` | `/api/v1/audit/{id}` | One entry (`404` if unknown) |
| `GET` | `/api/v1/audit/facets` | Apps, entities and actions in use |

Errors are RFC 9457 Problem Details (`503` when MongoDB is down). Full contract:
`http://localhost:8084/swagger-ui.html`. The browser reaches this API only through Catapulsar's
`/api/v1/audit/**`.

## Run locally (IntelliJ on necromancer)

| Variable | Value |
|---|---|
| `PULSAR_SERVICE_URL` | `http://192.168.86.35:31080` |
| `PULSAR_BROKER_URL` | `pulsar://192.168.86.35:31650` |
| `PULSAR_LISTENER_NAME` | `external` |
| `SPRING_DATA_MONGODB_URI` | `mongodb://<user>:<password>@192.168.86.35:32017/catacumbas69?authSource=admin&directConnection=true` |
| `CATAPONTE_URL` | `http://localhost:8082` (registers `audit-cataudit`) |

IntelliJ *Environment variables* (replace `<user>` and `<password>`; `.idea/` stays out of Git, CFG-2):

```
PULSAR_SERVICE_URL=http://192.168.86.35:31080;PULSAR_BROKER_URL=pulsar://192.168.86.35:31650;PULSAR_LISTENER_NAME=external;SPRING_DATA_MONGODB_URI=mongodb://<user>:<password>@192.168.86.35:32017/catacumbas69?authSource=admin&directConnection=true;CATAPONTE_URL=http://localhost:8082
```

Start it **second**, after Cataponte: Cataponte → Cataudit → Catametricas → Catapulsar. Every audit bridge writes
to `cataudit/audit`; the first time, Cataudit's subscription starts at the latest message, so starting it early
loses nothing and the audit view works from the first action (reasons in
[orch.md §2.1](../orch.md#21-configuration-contract)).

Run `CatauditApplication` (port **8084**). In Catapulsar set `CATAUDIT_URL=http://localhost:8084`, or change the
Cataudit URL in ⚙️ Settings → Connections. Settings are
under `cataudit.*` in `application.properties`.

## Build and test

The app is a module of the Maven reactor in `apps/` (with the shared library `catacommons`). From `apps/`:

```bash
mvn clean install                         # everything: catacommons + every app, tests + Checkstyle
mvn -pl cataudit -am verify               # this app and catacommons only
docker build -f cataudit/Dockerfile -t cataudit .   # image (BuildKit), context = apps/
build/push.sh cataudit   # image for MicroShift (Podman, linux/amd64), pushed, tag written in its manifest
```

Deploying to MicroShift (registry, ArgoCD, MongoDB Secret): [`microshift/apps/catacumbas69-apps/README.md`](../../microshift/apps/catacumbas69-apps/README.md).

Unit tests need no Docker, MongoDB or Pulsar. The integration tests (`*IT`, run by failsafe in `mvn verify`)
start a real Pulsar and MongoDB with Testcontainers: `AuditTrailIT` produces to `catacumbas69/cataudit/audit` as
the `audit-<app>` bridges do and checks that an event is stored and served by `GET /api/v1/audit/{id}`, that the
same event delivered twice is stored once, and that a message that is not a CloudEvent goes to
`audit-cataudit-DLQ`. Locally they need Docker or Podman: see
[catacommons' README](../catacommons/README.md#integration-tests-catacumbascontainers).

## Operate

| Endpoint | Use |
|---|---|
| `/actuator/health/liveness`, `/actuator/health/readiness` | probes; readiness does not depend on Pulsar, so the API stays up |
| `/actuator/health` | `auditConsumer` component: up while the audit topic is consumed (with `state`: `RUNNING`, `PAUSED_AUTO` and why, `PAUSED_MANUAL`), last error otherwise |
| `/actuator/prometheus` | `cataudit_events_total{outcome=stored\|duplicate\|invalid\|retry\|error}`, `catacommons_messages_total` |

Container image: `Dockerfile` (multi-stage, layered jar, non-root, port 8084), built from `apps/`.
