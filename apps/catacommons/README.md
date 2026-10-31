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
- `SettingValue`: `{"value": "<text>"}`, the value of the compacted settings topic (ADR 0011).
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
| `catacommons.provisioning.events-retention-time` / `events-retention-size` | `7d` / `1GB` (namespace) |
| `catacommons.provisioning.events-backlog-quota` | `1GB`, `producer_exception` (namespace) |
| `catacommons.provisioning.compaction-threshold` | `1MB` (compacted topics, plus infinite retention) |

Metric: `catacommons_messages_total{topic, outcome=sent|retried|failed}`.

## Build

The library is a module of the reactor in `apps/`. It is never published to a repository:

```bash
cd apps
mvn clean install            # catacommons first, then every app
mvn -pl catacommons verify   # the library alone: unit tests + Checkstyle
```
