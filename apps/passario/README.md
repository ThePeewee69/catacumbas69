# Passário

Passário runs **Pulsar topic bridges**: each bridge consumes one source topic and copies every message to
one or more destination topics. Bridges are stored in the MongoDB collection `bridges` (database
`catacumbas69`) and managed through a REST API — normally from the Bridges panel in Pulsário.

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
- Run **one replica** only (see ADR 0002).

## Run locally (IntelliJ on necromancer)

Run `PassarioApplication` with these environment variables:

| Variable | Value |
|---|---|
| `PULSAR_BROKER_URL` | `pulsar://192.168.86.35:31650` |
| `PULSAR_LISTENER_NAME` | `external` |
| `SPRING_DATA_MONGODB_URI` | `mongodb://<user>:<password>@192.168.86.35:32017/catacumbas69?authSource=admin&directConnection=true` |

In the cluster leave `PULSAR_LISTENER_NAME` empty, use the in-cluster MongoDB URI from a Secret, and set
`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` for JSON logs.

## Test

```bash
mvn verify      # unit + slice tests, then Checkstyle
```

Unit tests need no Docker, MongoDB or Pulsar.

## API

`/api/v1/bridges` — list, create (`POST`), get/update/delete (`/{id}`), `POST /{id}/enable|disable`,
`GET /status`. Errors are RFC 9457 Problem Details. Full contract: `http://localhost:8082/swagger-ui.html`.

Example:

```json
{
  "name": "orders-fanout",
  "source": { "topic": "persistent://home/media/orders", "subscription": "passario-orders",
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
| `/actuator/prometheus` | `passario_messages_forwarded_total`, `passario_forward_errors_total`, `passario_messages_in_flight`, `passario_bridges_running` — tagged by `bridge` |
