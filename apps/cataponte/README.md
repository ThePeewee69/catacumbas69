# Cataponte

Cataponte runs **Pulsar topic bridges**: each bridge consumes one source topic and copies every message to
one or more destination topics. Bridges are stored in the MongoDB collection `cataponte_bridges` (database
`catacumbas69`) and managed through a REST API — normally from the Bridges panel in Catapulsar. Any number of
replicas can run (ADR 0012).

Engineering rules: [`../orch.md`](../orch.md). Architecture decisions: [`../docs/adr`](../docs/adr).

## How it works

- `BridgeService` validates and stores definitions; it never touches running workers.
- `BridgeManager` reconciles every 5 s: starts enabled bridges, stops disabled/deleted ones, restarts
  changed ones (detected by the document `version`). Start failures back off from 1 s to 60 s.
- `BridgeWorker` relays one bridge on a virtual thread with pipelined async sends, bounded by
  `maxInFlight` (default 1,000). A message is acknowledged only when **every** destination accepted it.
- **Subscription types**: `Failover` (default for ordered bridges), `Key_Shared` or `Shared` (default for
  unordered ones). `Exclusive` is refused (`400`): only one consumer could join it, and every replica runs every
  bridge.
- **Ordered** bridges (default unless the subscription is `Shared`) replay unacknowledged messages in order
  after a failure; **unordered** bridges negatively acknowledge single messages. Delivery is
  at-least-once in both modes — consumers of the destinations must tolerate duplicates.
- **Loops are refused** (ADR 0005): a create, update or enable that would close a cycle between enabled
  bridges (A→B→A, A→B→C→A, partitions count as their topic) returns `400` naming the loop. Bridges on a
  cycle written straight to MongoDB are not started (state `BLOCKED`).
- **Traffic counts**: messages and bytes per bridge, topic and minute are counted in memory and written to
  MongoDB every 5 s with `$inc` bulk upserts — `cataponte_traffic_minutes` (history) and
  `cataponte_traffic_totals` (all time), both kept forever. Each replica adds what it relayed itself. `SOURCE` counts messages delivered to every
  destination; `DESTINATION` counts each accepted send. If MongoDB is down the counts wait in memory.
- **Audit = events** (ADR 0008/0009, orch.md §4.4): every bridge change is published as
  `com.catacumbas69.cataponte.bridge.<created|updated|deleted|enabled|disabled>.v1` on
  `catacumbas69/cataponte/events`, keyed by bridge id. Each event carries the definition before and after
  (flattened) and the actor: the client address from Catapulsar's `X-Forwarded-For`, the app name for system
  bridges, or `cataponte` for its own `audit-cataponte`. Cataudit owns the trail. The namespace and topic are created at start-up through the Pulsar admin API
  (`PULSAR_SERVICE_URL`).
- **System bridges** (ADR 0009): each app registers its `audit-<app>` bridge
  (`<app>/events` → `cataudit/audit`) at start-up with `PUT /api/v1/bridges/system/{name}`. Cataponte
  registers its own `audit-cataponte` in-process. The call is idempotent: nothing changes when the definition
  already matches, and an enabled state a user changed is kept. System bridges have `system: true` (a
  badge in the UI) and are otherwise normal bridges.
- **Replicas** (ADR 0012, orch.md ARCH-5): every replica runs every enabled bridge and Pulsar spreads the
  messages. On a `Failover` subscription one replica receives and the others stand by (the worker follows the
  broker's consumer events); on `Shared` and `Key_Shared` every replica receives a share. Each replica writes
  its workers' status to `cataponte_worker_status` every 5 s, so `GET /api/v1/bridges/status` answers for all
  of them: most advanced state, summed counters, `replicas` and `activeReplicas`. Two writes on different
  replicas at the same instant could still close a loop; the reconciler then blocks those bridges.
- **Collections**: `cataponte_bridges`, `cataponte_traffic_minutes`, `cataponte_traffic_totals` and
  `cataponte_worker_status`. Their indexes are created shortly after start-up (retried while MongoDB is down).

## Run locally (IntelliJ on necromancer)

Run `CataponteApplication` with these environment variables:

| Variable | Value |
|---|---|
| `PULSAR_BROKER_URL` | `pulsar://192.168.86.35:31650` |
| `PULSAR_LISTENER_NAME` | `external` |
| `PULSAR_SERVICE_URL` | `http://192.168.86.35:31080` (admin API, namespace creation) |
| `SPRING_DATA_MONGODB_URI` | `mongodb://<user>:<password>@192.168.86.35:32017/catacumbas69?authSource=admin&directConnection=true` |
| `CATAPONTE_STATUS_WRITEINTERVAL` | *(optional)* how often each replica writes its workers' status, `PT5S` |
| `CATAPONTE_STATUS_STALEAFTER` | *(optional)* reports of other replicas older than this are ignored, `PT30S` |
| `CATAPONTE_CORS_ALLOWED_ORIGINS` | *(optional)* empty = CORS closed; the browser goes through Catapulsar |

IntelliJ *Environment variables* (replace `<user>` and `<password>`; `.idea/` stays out of Git, CFG-2):

```
PULSAR_SERVICE_URL=http://192.168.86.35:31080;PULSAR_BROKER_URL=pulsar://192.168.86.35:31650;PULSAR_LISTENER_NAME=external;SPRING_DATA_MONGODB_URI=mongodb://<user>:<password>@192.168.86.35:32017/catacumbas69?authSource=admin&directConnection=true
```

Port **8082**. Start it **first**: Cataponte → Cataudit → Catametricas → Catapulsar. Every other app registers its
`audit-<app>` bridge here at start-up and retries for minutes if Cataponte is not up yet (reasons in
[orch.md §2.1](../orch.md#21-configuration-contract)).

**Second replica**: copy the run configuration, add `;SERVER_PORT=8092` and allow multiple instances (*Modify
options*). The Bridges panel then shows "2/2 replicas active" on a `Shared` bridge and "1/2" on a `Failover` one;
stop one and within 30 s (`stale-after`) it shows "1/1", the other taking over a `Failover` bridge.

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
| `PUT` | `/api/v1/bridges/system/{name}` | Create or update a system bridge by name (apps at start-up; header `X-Catacumbas69-App` = actor) |
| `GET` | `/api/v1/bridges/status` | Runtime state per bridge over every replica: `RUNNING`, `STARTING`, `FAILED`, `BLOCKED`; counters summed; `replicas`, `activeReplicas` |
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
| `/actuator/health` | details, including the `bridges` component (running / failed counts in this replica) |
| `/actuator/prometheus` | `cataponte_messages_forwarded_total`, `cataponte_forward_errors_total`, `cataponte_messages_in_flight`, `cataponte_bridges_running` — tagged by `bridge` |
