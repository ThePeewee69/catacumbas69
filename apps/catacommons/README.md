# catacommons

Shared library of the catacumbas69 apps: what they need to talk to each other over Pulsar (ADR 0008).
It is not a service. Every app depends on it, and it configures itself through Spring Boot auto-configuration.

Engineering rules: [`../orch.md`](../orch.md) §4.4 Events. Decisions: [`../docs/adr`](../docs/adr) 0008–0012.

## What an app gets

| Bean | Does |
|---|---|
| `EventPublisher` | `publish(entity, verb, subject, EventData)`: builds a CloudEvent 1.0 (`com.catacumbas69.<app>.<entity>.<verb>.v1`, source `/catacumbas69/<app>`) and sends it to `catacumbas69/<app>/events`, key = subject |
| `RetryingSenderFactory` | Senders for the app's other topics, with the same retries (Catapulsar uses it for the settings topic) |
| `PulsarProvisioner` | Once the app is ready, creates the tenant `catacumbas69` (if missing), the namespace `catacumbas69/<app>` with its policies, and every `TopicSpec` bean (the events topic is always one), with its schema |
| `AuditBridgeRegistrar` | Once the app is ready, registers its system bridge `audit-<app>` (`<app>/events` → `cataudit/audit`) in Cataponte through a `SystemBridgeRegistry`. The default registry calls `PUT /api/v1/bridges/system/{name}` at a `CataponteLocation`. Catapulsar provides its own location (the editable URL), Cataponte its own in-process registry (ADR 0009) |
| `ConsumerControl` | With `catacommons.consumers.enabled=true` (ADR 0015): the app registers each consumer as a `ConsumerGate` (`pauseAutomatically`, `resumeAutomatically`, `pauseManually`, `resumeManually`, `attach` on every subscribe). Every 10 s and on each change it publishes a `ConsumerStatus` per consumer on the compacted `catacumbas69/<app>/consumers` (key `<instance>/<consumer>`, tombstone on shutdown), reads `ConsumerCommand`s (`pause`, `resume`, `check`; one instance or `*`) from `catacumbas69/<app>/consumer-commands`, resumes automatic pauses every 5 s once the gate's `ConnectionProbe`s answer, and publishes `consumer.paused|resumed|checked`. `consumerName()` = `<app>@<instance>` |
| `ConnectionProbe` | Beans that check a dependency now. catacommons provides `PulsarAdminProbe` (`pulsar`); apps add theirs (MongoDB, `mongodb`) |
| `DeadLetters`, `DeadLetterWriter` | `DeadLetters.policy(topic, subscription, n)`: Pulsar's DLQ after `n` deliveries, to `<topic>-<subscription>-DLQ` with subscription `dlq`; `maxDeliveries(settings, default)` reads the shared setting `dlq.max-deliveries` (2–100). `DeadLetterWriter.send(...)` puts a message straight in its DLQ, with Pulsar's `REAL_TOPIC` and `ORIGIN_MESSAGE_ID` plus `DLQ_REASON` |
| `AlertSetting` | The shared alert thresholds (ADR 0015, Settings → Alerts): `CHECK_INTERVAL` (`alerts.check-interval`, 30 s, 10–600), `DEBOUNCE` (`alerts.debounce`, 2, 1–10), `BACKLOG_LIMIT` (`alerts.backlog-limit`, 10 000, 1–10⁹), `COLLECTION_STALE_INTERVALS` (`alerts.collection-stale-intervals`, 3, 2–20). `value(settings)` reads the settings topic and falls back to the default for a missing or invalid value. Read by Catavigia, edited in Catapulsar |
| `InstanceId` | This replica: host name plus a random suffix |
| `SharedSettings` | The shared settings: a `TableView` on `catacumbas69/catapulsar/settings` opened at start-up (retried while Pulsar is down). `get(key)` and `listen(key, …)`; empty/`null` = use your default (ADR 0011) |

The app must provide a `PulsarClient`, a `PulsarAdmin` and `catacommons.app=<app>`.

Sending is asynchronous: the caller never waits and never sees a failure. A failed send is retried after
1 s, 2 s and 4 s. After the last retry the message is logged at `ERROR` with its full JSON, so it can be
recovered from the logs. The producer is created on first use and again after a failure, so an app starts and
keeps working while Pulsar is down. Provisioning is retried with back-off (30 s doubling to 5 min) until it
succeeds. "Already exists" counts as success, and policies are set again on every start.

## Model

- `CloudEvent`: `specversion`, `id` (UUID), `source`, `type`, `subject`, `time` (RFC 3339 UTC),
  `datacontenttype`, `data`. Registered in Pulsar as a JSON schema derived from the record.
- `EventData`: `actor`, `before`, `after`, `details`, all text maps. Use `EventData.action(actor, details)`
  or `EventData.change(actor, before, after, details)`, which flatten nested maps (`source.topic`) and join
  lists with commas.
- `SettingValue`: `{"value": "<text>"}`, the value of the compacted settings topic (ADR 0011), read by
  `SharedSettings`.
- `CatacumbasTopics`: tenant, namespace and topic names.

## Configuration (`catacommons.*`)

| Property | Default |
|---|---|
| `catacommons.app` | **required**, e.g. `catapulsar` |
| `catacommons.events.enabled` | `true` (`false` drops events) |
| `catacommons.events.retry-delays` | `1s,2s,4s` |
| `catacommons.events.send-timeout` | `10s` |
| `catacommons.provisioning.enabled` | `true` |
| `catacommons.provisioning.retry-interval` / `max-retry-interval` | `30s` / `5m` |
| `catacommons.provisioning.events-retention-time` / `events-retention-size` | `7d` / `2GB` (namespace) |
| `catacommons.provisioning.events-backlog-quota` | `1GB`, `producer_exception` (namespace); must be smaller than the retention size, or the app refuses to start (Pulsar refuses it with `412`) |
| `catacommons.provisioning.compaction-threshold` | `1MB` (compacted topics, plus infinite retention) |
| `catacommons.audit-bridge.enabled` | `true` |
| `catacommons.audit-bridge.cataponte-url` | `http://cataponte:8082`; set it from `CATAPONTE_URL` |
| `catacommons.audit-bridge.timeout` / `retry-interval` / `max-retry-interval` | `5s` / `10s` / `5m` |
| `catacommons.settings.enabled` / `retry-interval` | `true` (`false` in Cataponte) / `10s` |
| `catacommons.consumers.enabled` | `false` (`true` in Cataudit, Catametricas and Catavigia) |
| `catacommons.consumers.status-interval` / `resume-check-interval` | `10s` / `5s` |
| `catacommons.consumers.max-deliveries` | `10`, unless the shared setting `dlq.max-deliveries` says otherwise |

Metric: `catacommons_messages_total{topic, outcome=sent|retried|failed}`.

## Build

The library is a module of the reactor in `apps/`. It is never published to a repository:

```bash
cd apps
mvn clean install            # catacommons first, then every app
mvn -pl catacommons verify   # the library alone: unit tests + Checkstyle
```
