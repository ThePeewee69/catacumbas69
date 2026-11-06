# Catapulsar

A dark-themed web console for [Apache Pulsar](https://pulsar.apache.org/) built with Spring Boot and
Alpine.js. Browse tenants, namespaces and topics; manage subscriptions; produce, peek and skip messages;
and manage **Cataponte bridges** (topic fan-out) — all from one page with no frontend build step.

Engineering rules: [`../orch.md`](../orch.md). Architecture decisions: [`../docs/adr`](../docs/adr).

## Features

| Area | What you can do |
|---|---|
| **Cluster tree** | Browse tenants → namespaces → topics; create and delete topics |
| **Overviews** | Home = cluster (tenants); click a tenant = its namespaces; click a namespace = its topics. Backlog, rates, storage, counts, traffic in the last 1 h / 24 h / 7 d / 30 d, all-time traffic, chart and sparklines (published, delivered or backlog). Data from Catametricas' API (ADR 0007, 0013); figures are those of the last collection, so `collect-tasks` notes that its backlog may include the task being collected. **⟳ Collect now** has Catametricas collect the view's scope at once (each namespace at most every 5 s, audited). **● Live** (namespace views only) does it every 5 s, only while the browser tab is visible, and turns itself off after 10 minutes or when you leave the namespace; a red banner shows it is on, what it covers and the time left |
| **Overview** | Message rates, throughput, backlog, producers, subscriptions, storage |
| **Subscriptions** | List, create and delete subscriptions. Pulsar's own subscriptions (`__compaction`, any name starting with `__`) get a *system* tag and can only be read and peeked |
| **Produce** | Send a message with an optional key |
| **Sidebar** | Tenants and namespaces stay open as you open them (remembered per browser); click a name to select and open it, click the selected one again to close it; **Collapse all** keeps only the path to the selection. Auto-refresh reloads the selected node |
| **Bridge problems** | A bridge card shows *destination unavailable* (the bridge resends its held messages until accepted) or *stuck on <message>* (an ordered bridge whose destination refuses a message), with **Skip this message** on `Failover` bridges (confirmed first; audited by Cataponte); bridges with a DLQ show a *DLQ* tag |
| **Name fields** | New topic, new subscription, bridge name and subscription, and area name turn a character the name cannot have (a space included) into `-` as you type or paste, and skip a first character the name cannot start with; area patterns drop spaces |
| **Subscription details** | Backlog per subscription in Overview, with **Clear backlog** (skips every message). A subscription a bridge uses shows a *bridge* tag with the bridge's name and destinations (click to open it in Bridges); the consumer count opens the list of who is connected: consumer name (ours are `app@instance`), address, connected since, client version, rate, unacked, *active* on `Failover`. The Backlog list shows the same bridge and consumers |
| **Backlog** | Subscriptions with backlog statistics (messages, size as computed by Pulsar on request, consumers, type, rate out, unacked) refreshed automatically; one click peeks up to 100 messages without consuming them (audited); view, edit & re-produce, skip one or all |
| **Bridges** | Create, edit, enable/disable and delete Cataponte bridges (subscription `Shared`, `Failover` or `Key_Shared`); running state over every Cataponte replica ("1/3 replicas active"), errors, loop blocks and all-time message counts per topic. Tabs **System** (bridges the apps register), **Bridges** (no area) and one per **area** (ADR 0014), with counts; opens on the last tab used (per browser), System the first time. **+ Area** creates an area with glob patterns (`home/media/*`, `home/**`) and a preview of the topics they match; in an area the topic pickers show only those topics and the bridge must fit. Bridges left outside after the patterns changed are tagged *outside filter*. Deleting an area with bridges asks whether to delete them or move them to Bridges or another area. Deleting a bridge, or changing its source or subscription, warns first that Cataponte will delete its subscription about 30 s later and how many messages are still in its backlog (or that it is kept because another bridge uses it); a new bridge's subscription defaults to `cataponte-<name>` and must be unique for its source topic. Every area action is audited by Cataponte (Cataudit) |
| **Settings** | ⚙️ Settings, in tabs: **Connections** (Pulsar label, Cataponte, Cataudit and Catametricas URLs, each with a check button) **Refresh & collection** (auto-refresh interval and Catametricas collection interval) and **Dead letters** (deliveries before a message goes to its DLQ, 2–100, default 10, for every app: they subscribe again when it changes). Changed at runtime: saved in MongoDB, audited, published on the settings topic. Only the body scrolls; title, tabs and buttons stay visible |
| **Breadcrumb** | Cluster › tenant › namespace › topic, each part clickable |
| **Auto-refresh** | Top bar "Auto N s": on/off per browser; ▾ sets the interval for everyone (2–300 s, default 10 s, saved in MongoDB, audited). Refreshes the sidebar, Overview, Subscriptions and Bridges; never repeats a backlog peek |
| **Consumers** | ⚙ Consumers panel (ADR 0015), tab **Consumers**: every consumer of Cataudit (`audit`) and Catametricas (`collect-tasks`), per replica: state (*running*, *paused (auto)* with why, *paused by hand* with who, *gone* when not reported for 30 s), since when, the last MongoDB and Pulsar check, and **Pause** (confirmed), **Resume** and **Check** per replica or for all of them. Read from each app's `consumers` topic, so it works while MongoDB is down; commands go on its `consumer-commands` topic and apply within seconds. A pause by hand is never resumed automatically |
| **Dead letters** | Tab **Dead letters** of the Consumers panel: every DLQ (`<topic>-<subscription>-DLQ`) of the apps' consumers and of the bridges, with its backlog on `dlq` (or *not created yet*), and a count on the tab. Clicking one peeks up to 50 messages (audited): id, time, original topic, reason, key, payload. Per message **Send back** (to the original topic, same key and event time, without the DLQ properties) or **Discard**; **Send all back** and **Discard all**. Each is confirmed and audited (`dead-letter.sent-back`, `dead-letter.discarded`); a message without an original topic can only be discarded |
| **Audit** | 🛡 Audit panel: the audit trail of every app, from Cataudit (ADR 0009). Filters by app, entity, action, subject prefix, actor and period. Each row expands into before/after and details; clicking a subject filters by it; refreshed automatically |

## Architecture

```
Browser (Alpine.js)
    │  same-origin REST  /api/v1/**
    ▼
Catapulsar  :8081  (Spring Boot)
    ├── /api/v1/tenants, /namespaces, /topics/**  ──► Pulsar admin API (HTTP)  +  Pulsar client (produce)
    ├── /api/v1/bridges/**  (BFF, ADR 0001)        ──► Cataponte (URL from settings, default CATAPONTE_URL)
    ├── /api/v1/areas/**    (BFF, ADR 0014)        ──► Cataponte
    ├── /api/v1/settings/**                        ──► MongoDB catapulsar_settings + settings topic (ADR 0011)
    ├── /api/v1/overview/**  (read-only, ADR 0013) ──► Catametricas API (URL from settings, default CATAMETRICAS_URL)
    ├── /api/v1/audit/**  (read-only, ADR 0009)     ──► Cataudit (URL from settings, default CATAUDIT_URL)
    ├── /api/v1/consumers/**  (ADR 0015)            ──► Pulsar catacumbas69/<app>/consumers (TableView) and consumer-commands
    ├── /api/v1/dead-letters/**  (ADR 0015)         ──► Pulsar: consumer on the DLQ's `dlq` subscription, producer to the original topic
    └── /actuator/**, /swagger-ui.html

Events (ADR 0008, catacommons) ──► Pulsar catacumbas69/catapulsar/events    (every audited action)
Settings (ADR 0011)            ──► Pulsar catacumbas69/catapulsar/settings  (compacted, key = setting key)
```

- **View of one topic = current state from Pulsar** (orch.md OBS-10): the Overview, Subscriptions and Backlog tabs
  read the topic's statistics, subscriptions and messages from Pulsar's admin API on every refresh, never from
  Catametricas, because that view is for acting on the topic. Overviews of the cluster, a tenant or a namespace
  come from Catametricas (last collection, or **Collect now** / **Live**).
- The browser never talks to Pulsar, Cataponte, Cataudit or Catametricas directly: no CORS, and they stay
  internal.
- **Overviews**: for each view Catapulsar asks Catametricas for the topics of the scope (`/api/v1/topics`), the
  series of the period grouped by row (`/api/v1/series`) and the last collection (`/api/v1/collector`), then
  builds the rows, the summary and the chart buckets. Catapulsar reads no Catametricas collection.
- Producers are cached per topic and closed after 5 minutes idle (no producer per request).
- Pulsar, Cataponte, Cataudit, Catametricas and MongoDB outages only affect the features that need them: the
  page keeps working and shows a clear error (Problem Details) for the failing call.
- **Audit = events** (orch.md §4.4): every audited action is published as a CloudEvent on
  `catacumbas69/catapulsar/events` (`topic.created`, `message.produced`, `subscription.backlog-cleared`,
  `setting.changed`, …), and every bridge change forwarded to Cataponte as `bridge.*-requested` with
  Cataponte's HTTP status. Nothing is written to MongoDB for audit any more. At start-up Catapulsar creates
  the namespace `catacumbas69/catapulsar`, its topics and policies, and registers its `audit-catapulsar` system
  bridge at the Cataponte URL in use. That bridge carries the events to Cataudit. Publishing is asynchronous
  and retried; it never fails a request.
- **Settings topic**: each setting is published on `catacumbas69/catapulsar/settings` when it changes and
  once at start-up (a tombstone when it is at its default), for the other services to read from phase 3.

## Configuration

All settings have in-cluster defaults in `application.properties`; override them with environment variables.

| Variable | In the cluster | Local (IntelliJ on necromancer) |
|---|---|---|
| `PULSAR_SERVICE_URL` | `http://pulsar.pulsar.svc.cluster.local:8080` | `http://192.168.86.35:31080` |
| `PULSAR_BROKER_URL` | `pulsar://pulsar.pulsar.svc.cluster.local:6650` | `pulsar://192.168.86.35:31650` |
| `PULSAR_LISTENER_NAME` | *(empty)* | `external` |
| `CATAPONTE_URL` | `http://cataponte:8082` | `http://localhost:8082` |
| `CATAUDIT_URL` | `http://cataudit:8084` | `http://localhost:8084` |
| `CATAMETRICAS_URL` | `http://catametricas:8083` | `http://localhost:8083` |
| `CATAPULSAR_REFRESH_INTERVAL_SECONDS` | `10` | `10` |
| `CATAMETRICAS_COLLECT_INTERVAL_SECONDS` | `60` (same as Catametricas) | `60` |
| `SPRING_DATA_MONGODB_URI` | in-cluster ReplicaSet URI (Secret) | NodePort `32017` + `authSource=admin&directConnection=true` |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | `ecs` (JSON logs for Loki) | *(unset)* |

Timeouts, producer cache size and the peek limit are under `catapulsar.*` in `application.properties`.

`CATAPONTE_URL` is only the default: the Cataponte URL can be changed in ⚙️ Settings → Connections. The new
URL is stored in `catapulsar_settings`, published on the settings topic (every replica follows it within
seconds, ADR 0011) and recorded in the audit log (`setting.changed`). "Use default" deletes it and goes back to
`CATAPONTE_URL`. All settings work this way.

`CATAUDIT_URL` works the same way: it is the default of the Cataudit URL, editable in ⚙️ Settings → Connections or
from the link in the Audit panel header, with a "Check Cataudit" button. So does `CATAMETRICAS_URL`, the
default of the Catametricas URL, with a "Check Catametricas" button; when the overviews cannot reach
Catametricas the panel offers a link to it.

## Run locally (IntelliJ on necromancer)

Run `CatapulsarApplication` (port **8081**). Paste this line into the run configuration's *Environment
variables* and replace `<user>` and `<password>`; `.idea/` stays out of Git (CFG-2):

```
PULSAR_SERVICE_URL=http://192.168.86.35:31080;PULSAR_BROKER_URL=pulsar://192.168.86.35:31650;PULSAR_LISTENER_NAME=external;SPRING_DATA_MONGODB_URI=mongodb://<user>:<password>@192.168.86.35:32017/catacumbas69?authSource=admin&directConnection=true;CATAPONTE_URL=http://localhost:8082;CATAUDIT_URL=http://localhost:8084;CATAMETRICAS_URL=http://localhost:8083
```

Start it **last**: Cataponte → Cataudit → Catametricas → Catapulsar. Catapulsar calls the other three, so its
health and panels are fine from the start instead of answering `503` until they are up. The reasons for the
whole order are in [orch.md §2.1](../orch.md#21-configuration-contract). Open <http://localhost:8081>.

## Build, test, run

The app is a module of the Maven reactor in `apps/` (with the shared library `catacommons`). From `apps/`:

```bash
mvn clean install                       # everything: catacommons + every app, tests + Checkstyle
mvn -pl catapulsar -am verify                # this app and catacommons only
docker build -f catapulsar/Dockerfile -t catapulsar .   # image (BuildKit), context = apps/
```

Run `CatapulsarApplication` from IntelliJ (or `mvn -pl catapulsar spring-boot:run` after an install).

Unit tests need no Docker, MongoDB, Pulsar or Cataponte. Open <http://localhost:8081>.

Static files are versioned (`catapulsar.js?v=N`, `catapulsar.css?v=N`); bump `N` in `index.html` whenever you
change them (UI-2). The server sends `Cache-Control: no-cache`.

## API

Full contract: <http://localhost:8081/swagger-ui.html>. Errors are RFC 9457 Problem Details.

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/tenants` | List tenants |
| `GET` | `/api/v1/namespaces/{tenant}` | List namespaces |
| `GET` | `/api/v1/topics/{tenant}/{ns}` | List topics |
| `POST` / `DELETE` | `/api/v1/topics/{tenant}/{ns}/{topic}` | Create (`201`) / delete (`204`) a topic |
| `GET` | `/api/v1/topics/{tenant}/{ns}/{topic}/stats` | Topic statistics |
| `GET` | `/api/v1/topics/{tenant}/{ns}/{topic}/subscriptions` | List subscriptions |
| `POST` / `DELETE` | `/api/v1/topics/{tenant}/{ns}/{topic}/subscriptions/{sub}` | Create (`201`) / delete (`204`) |
| `POST` | `/api/v1/topics/{tenant}/{ns}/{topic}/produce` | Produce `{payload, key?, properties?}` |
| `GET` | `/api/v1/topics/{tenant}/{ns}/{topic}/peek/{sub}?count=N` | Peek 1–100 backlog messages |
| `POST` | `/api/v1/topics/{tenant}/{ns}/{topic}/subscriptions/{sub}/skip/{n}` | Skip `n` messages (`204`) |
| `POST` | `/api/v1/topics/{tenant}/{ns}/{topic}/subscriptions/{sub}/clear-backlog` | Skip the whole backlog (`204`) |
| `*` | `/api/v1/bridges/**` | Bridges and their traffic counts, forwarded to Cataponte (see Cataponte's API) |
| `GET` / `POST` / `PUT` / `DELETE` | `/api/v1/areas`, `/api/v1/areas/{id}?bridges=delete\|move&to=` | Bridge areas, forwarded to Cataponte; each change is also published as `area.<action>-requested` |
| `GET` / `PUT` / `DELETE` | `/api/v1/settings/cataponte` | Cataponte URL in use / change `{url}` / back to default |
| `GET` / `PUT` / `DELETE` | `/api/v1/settings/cataudit` | Cataudit URL in use / change `{url}` / back to default |
| `GET` / `PUT` / `DELETE` | `/api/v1/settings/catametricas` | Catametricas URL in use / change `{url}` / back to default |
| `GET` / `PUT` / `DELETE` | `/api/v1/settings/refresh-interval` | UI auto-refresh interval / change `{seconds}` (2–300) / back to default |
| `GET` / `PUT` / `DELETE` | `/api/v1/settings/collect-interval` | Catametricas collection interval / change `{seconds}` (15–600) / back to default |
| `GET` / `PUT` / `DELETE` | `/api/v1/settings/dead-letters` | Deliveries before the DLQ (`dlq.max-deliveries`) / change `{value}` (2–100) / back to default (10) |
| `GET` | `/api/v1/consumers` | Every consumer of Cataudit and Catametricas per replica, with `stale` (not reported for 30 s) |
| `POST` | `/api/v1/consumers/commands` | `{app, instance, consumer, action}` (`pause`, `resume`, `check`; `instance`/`consumer` `*` for all) → published on `catacumbas69/<app>/consumer-commands` (`202`; `400` unknown app or action; `503` Pulsar down) |
| `POST` | `/api/v1/dead-letters/send-back` | `{topic, messageId}`: send one message back to its `REAL_TOPIC` and remove it from the DLQ; returns `{to}` (`404` not found, `409` no original topic) |
| `POST` | `/api/v1/dead-letters/discard` | `{topic, messageId}`: remove one message (`204`) |
| `POST` | `/api/v1/dead-letters/send-back-all` | `{topic}`: send every message back; returns `{sent, left}` (`left` = without an original topic, kept) |
| `POST` | `/api/v1/dead-letters/discard-all` | `{topic}`: clear the `dlq` backlog (`204`) |
| `GET` | `/api/v1/overview?period=` | Cluster overview, one row per tenant (`period` = `1h`, `24h`, `7d`, `30d`; `503` if Catametricas is down) |
| `GET` | `/api/v1/overview/{tenant}?period=` | Tenant overview, one row per namespace |
| `GET` | `/api/v1/overview/{tenant}/{namespace}?period=` | Namespace overview, one row per topic (partitions summed) |
| `POST` | `/api/v1/overview/collect?tenant=&namespace=` | Forced collection of the scope by Catametricas (`POST /api/v1/collect`, browser address as `X-Forwarded-For`); `503` if Catametricas is down |
| `GET` | `/api/v1/audit?app=&entity=&action=&subject=&actor=&from=&to=&page=0&size=50` | Audit trail from Cataudit, newest first, with `total` (`503` if Cataudit is down) |
| `GET` | `/api/v1/audit/facets` | Apps, entities and actions in use (filter lists) |
| `GET` | `/api/v1/audit/{id}` | One audit entry |

| Status | Meaning |
|---|---|
| `400` | Invalid name, count or body — `errors` lists each field. Names start with a letter, a digit or `_`. Also returned when creating, deleting, skipping or clearing a Pulsar system subscription (`__*`) |
| `404` / `409` | Pulsar says the resource is missing / already exists |
| `409` *Partitioned topic exists* | Creating a topic whose name is taken by a partitioned topic that has no partitions yet, so it is not listed; the detail gives the `pulsar-admin` commands to delete it or create its partitions |
| `502` | Pulsar failed or is unreachable |
| `503` | Cataponte, Cataudit or Catametricas is unreachable (only its panel), or MongoDB is down while saving a setting |

## Operate

| Endpoint | Use |
|---|---|
| `/actuator/health/liveness` | liveness probe |
| `/actuator/health/readiness` | readiness probe (application state only — dependencies degrade per feature) |
| `/actuator/health` | details: `pulsar`, `cataponte`, `cataudit`, `catametricas`, `mongo` components |
| `/actuator/prometheus` | HTTP, JVM and Pulsar client metrics |

Container image: `Dockerfile` (multi-stage, layered jar, non-root), built from `apps/` (see above).
