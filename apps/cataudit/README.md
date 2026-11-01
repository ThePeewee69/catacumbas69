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

- **Consumer**: subscription `cataudit`, type `Shared`, so every replica takes a share. A message is
  acknowledged once stored. It is also acknowledged when an event with the same id is already stored
  (duplicates are harmless). When MongoDB fails the message is negatively acknowledged and delivered again
  after 10 s. A message that is not a CloudEvent is logged at `ERROR` and dropped. Subscribing runs in the
  background and is retried, so Cataudit starts while Pulsar is down.
- **Storage**: `cataudit_events` holds the CloudEvent fields plus `app`, `entity` and `action` taken from the
  type `com.catacumbas69.<app>.<entity>.<action>.v1`, and `receivedAt`. Events are kept **forever**. Indexes
  cover `time` and `app`, `subject`, `actor` and `entity+action`, each with `time`.
- **Pulsar**: at start-up catacommons creates the namespace `catacumbas69/cataudit` and the topic `audit`, with
  the CloudEvent schema. Cataudit registers no audit bridge of its own.
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

Run `CatauditApplication` (port **8084**). In Catapulsar set `CATAUDIT_URL=http://localhost:8084`, or change the
Cataudit URL in ⚙️ Connection Settings. Settings are
under `cataudit.*` in `application.properties`.

## Build and test

The app is a module of the Maven reactor in `apps/` (with the shared library `catacommons`). From `apps/`:

```bash
mvn clean install                         # everything: catacommons + every app, tests + Checkstyle
mvn -pl cataudit -am verify               # this app and catacommons only
docker build -f cataudit/Dockerfile -t cataudit .   # image (BuildKit), context = apps/
```

Unit tests need no Docker, MongoDB or Pulsar.

## Operate

| Endpoint | Use |
|---|---|
| `/actuator/health/liveness`, `/actuator/health/readiness` | probes; readiness does not depend on Pulsar, so the API stays up |
| `/actuator/health` | `auditConsumer` component: up while the audit topic is consumed, last error otherwise |
| `/actuator/prometheus` | `cataudit_events_total{outcome=stored\|duplicate\|invalid\|retry}`, `catacommons_messages_total` |

Container image: `Dockerfile` (multi-stage, layered jar, non-root, port 8084), built from `apps/`.
