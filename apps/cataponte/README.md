# Cataponte

Cataponte runs **Pulsar topic bridges**: each bridge consumes one source topic and copies every message to
one or more destination topics. Bridges are stored in the MongoDB collection `bridges` (database
`catacumbas69`) and managed through a REST API — normally from the Bridges panel in Catapulsar.

Engineering rules: [`../orch.md`](../orch.md). Architecture decisions: [`../docs/adr`](../docs/adr).

## How it works

- `BridgeService` validates and stores definitions; it never touches running workers.
- `BridgeManager` reconciles every 5 s: starts enabled bridges, stops disabled/deleted ones, restarts
  changed ones (detected by the document `version`). Start failures back off from 1 s to 60 s.
- `BridgeWorker` relays one bridge on a virtual thread with pipelined async sends, bounded by
  `maxInFlight` (default 1,000). A message is acknowledged only when **every** destination accepted it.
- **Ordered** bridges (default unless the subscription is `Shared`) replay unacknowledged messages in order
  after a failure; **unordered** bridges negatively acknowledge single messages. Delivery is
  at-least-once in both modes — consumers of the destinations must tolerate duplicates.
- **Loops are refused** (ADR 0005): a create, update or enable that would close a cycle between enabled
  bridges (A→B→A, A→B→C→A, partitions count as their topic) returns `400` naming the loop. Bridges on a
  cycle written straight to MongoDB are not started (state `BLOCKED`).
- **Traffic counts**: messages and bytes per bridge, topic and minute are counted in memory and written to
  MongoDB every 5 s with `$inc` bulk upserts — `bridge_traffic_minutes` (history) and
  `bridge_traffic_totals` (all time), both kept forever. `SOURCE` counts messages delivered to every
  destination; `DESTINATION` counts each accepted send. If MongoDB is down the counts wait in memory.
- **Audit**: every bridge change is inserted into `audit_logs` with the definition before and after and
  the client address (`X-Forwarded-For` from Catapulsar), see ADR 0006. Schema migrations are recorded
  too (`MIGRATE_BRIDGE`, user `passario`).
- **Events** (ADR 0008, orch.md §4.4): every audited change is also published as
  `com.catacumbas69.cataponte.bridge.<created|updated|deleted|enabled|disabled|migrated>.v1` on
  `catacumbas69/cataponte/events`, keyed by bridge id, with the definition before and after (flattened).
  The namespace and topic are created at start-up through the Pulsar admin API (`PULSAR_SERVICE_URL`).
- Run **one replica** only (see ADR 0002).

## Run locally (IntelliJ on necromancer)

Run `CataponteApplication` with these environment variables:

| Variable | Value |
|---|---|
| `PULSAR_BROKER_URL` | `pulsar://192.168.86.35:31650` |
| `PULSAR_LISTENER_NAME` | `external` |
| `PULSAR_SERVICE_URL` | `http://192.168.86.35:31080` (admin API, namespace creation) |
| `SPRING_DATA_MONGODB_URI` | `mongodb://<user>:<password>@192.168.86.35:32017/catacumbas69?authSource=admin&directConnection=true` |

In the cluster leave `PULSAR_LISTENER_NAME` empty, use the in-cluster MongoDB URI from a Secret, and set
`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` for JSON logs.

## Build and test

The app is a module of the Maven reactor in `apps/` (with the shared library `catacommons`). From `apps/`:

```bash
mvn clean install                       # everything: catacommons + every app, tests + Checkstyle
mvn -pl cataponte -am verify                # this app and catacommons only
docker build -f cataponte/Dockerfile -t cataponte .   # image (BuildKit), context = apps/
```

Unit tests need no Docker, MongoDB or Pulsar.

## API

| Method | Path | Description |
|---|---|---|
| `GET` / `POST` | `/api/v1/bridges` | List / create (`201` + `Location`) |
| `GET` / `PUT` / `DELETE` | `/api/v1/bridges/{id}` | Get / replace (send `version`) / delete (`204`) |
| `POST` | `/api/v1/bridges/{id}/enable`, `/disable` | Idempotent |
| `GET` | `/api/v1/bridges/status` | Runtime state per bridge: `RUNNING`, `STARTING`, `FAILED`, `BLOCKED` |
| `GET` | `/api/v1/bridges/traffic` | All-time messages/bytes of every bridge, per role and topic |
| `GET` | `/api/v1/bridges/{id}/traffic` | All-time counts of one bridge |
| `GET` | `/api/v1/bridges/{id}/traffic/minutes?from=…&to=…` | Per-minute counts (ISO-8601; default last hour, max 7 days) |

Errors are RFC 9457 Problem Details. Full contract: `http://localhost:8082/swagger-ui.html`.

Example:

```json
{
  "name": "orders-fanout",
  "source": { "topic": "persistent://home/media/orders", "subscription": "cataponte-orders",
              "subscriptionType": "Failover" },
  "destinations": [ { "topic": "persistent://home/media/orders-audit" },
                    { "topic": "persistent://home/media/orders-analytics" } ],
  "ordered": true
}
```

## Operate

| Endpoint | Use |
|---|---|
| `/actuator/health/liveness` | liveness probe |
| `/actuator/health/readiness` | readiness probe (includes MongoDB) |
| `/actuator/health` | details, including the `bridges` component (running / failed counts) |
| `/actuator/prometheus` | `cataponte_messages_forwarded_total`, `cataponte_forward_errors_total`, `cataponte_messages_in_flight`, `cataponte_bridges_running` — tagged by `bridge` |
