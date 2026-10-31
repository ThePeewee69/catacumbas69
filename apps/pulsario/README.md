# Pulsário

A dark-themed web console for [Apache Pulsar](https://pulsar.apache.org/) built with Spring Boot and
Alpine.js. Browse tenants, namespaces and topics; manage subscriptions; produce, peek and skip messages;
and manage **Passário bridges** (topic fan-out) — all from one page with no frontend build step.

Engineering rules: [`../orch.md`](../orch.md). Architecture decisions: [`../docs/adr`](../docs/adr).

## Features

| Area | What you can do |
|---|---|
| **Cluster tree** | Browse tenants → namespaces → topics; create and delete topics |
| **Overview** | Message rates, throughput, backlog, producers, subscriptions, storage |
| **Subscriptions** | List, create and delete subscriptions |
| **Produce** | Send a message with an optional key |
| **Subscription details** | Backlog per subscription in Overview, with **Clear backlog** (skips every message) |
| **Backlog** | Peek up to 100 messages without consuming them; view, edit & re-produce, skip one or all |
| **Bridges** | Create, edit, enable/disable and delete Passário bridges; running state, errors, loop blocks and all-time message counts per topic |
| **Settings** | ⚙️ Connection Settings: change the Passário URL at runtime (saved in MongoDB, audited) |
| **Auto-refresh** | Top bar "Auto N s": on/off per browser; ▾ sets the interval for everyone (2–300 s, default 10 s, saved in MongoDB, audited). Refreshes the sidebar, Overview, Subscriptions and Bridges; never repeats a backlog peek |
| **Audit** | Every configuration change is recorded in MongoDB (`audit_logs`); bridge changes by Passário (ADR 0006) |

## Architecture

```
Browser (Alpine.js)
    │  same-origin REST  /api/v1/**
    ▼
Pulsário  :8081  (Spring Boot)
    ├── /api/v1/tenants, /namespaces, /topics/**  ──► Pulsar admin API (HTTP)  +  Pulsar client (produce)
    ├── /api/v1/bridges/**  (BFF, ADR 0001)        ──► Passário (URL from settings, default PASSARIO_URL)
    ├── /api/v1/settings/passario                  ──► MongoDB catacumbas69.pulsario_settings
    ├── /api/v1/audit                              ──► MongoDB catacumbas69.audit_logs
    └── /actuator/**, /swagger-ui.html
```

- The browser never talks to Pulsar or Passário directly: no CORS, and Passário stays internal.
- Producers are cached per topic and closed after 5 minutes idle (no producer per request).
- Pulsar, Passário and MongoDB outages only affect the features that need them: the page keeps working
  and shows a clear error (Problem Details) for the failing call.

## Configuration

All settings have in-cluster defaults in `application.properties`; override them with environment variables.

| Variable | In the cluster | Local (IntelliJ on necromancer) |
|---|---|---|
| `PULSAR_SERVICE_URL` | `http://pulsar.pulsar.svc.cluster.local:8080` | `http://192.168.86.35:31080` |
| `PULSAR_BROKER_URL` | `pulsar://pulsar.pulsar.svc.cluster.local:6650` | `pulsar://192.168.86.35:31650` |
| `PULSAR_LISTENER_NAME` | *(empty)* | `external` |
| `PASSARIO_URL` | `http://passario:8082` | `http://localhost:8082` |
| `PULSARIO_REFRESH_INTERVAL_SECONDS` | `10` | `10` |
| `SPRING_DATA_MONGODB_URI` | in-cluster ReplicaSet URI (Secret) | NodePort `32017` + `authSource=admin&directConnection=true` |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | `ecs` (JSON logs for Loki) | *(unset)* |

Timeouts, producer cache size and the peek limit are under `pulsario.*` in `application.properties`.

`PASSARIO_URL` is only the default: the Passário URL can be changed in ⚙️ Connection Settings. The new
URL applies immediately for every user, is stored in `pulsario_settings`, loaded again on start-up and
recorded in the audit log (`CHANGE_SETTING`). "Use default" deletes it and goes back to `PASSARIO_URL`.

## Build, test, run

```bash
mvn verify                       # unit + slice tests, then Checkstyle
mvn spring-boot:run              # or run PulsarioApplication from IntelliJ
```

Unit tests need no Docker, MongoDB, Pulsar or Passário. Open <http://localhost:8081>.

Static files are versioned (`pulsario.js?v=N`, `pulsario.css?v=N`); bump `N` in `index.html` whenever you
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
| `*` | `/api/v1/bridges/**` | Bridges and their traffic counts, forwarded to Passário (see Passário's API) |
| `GET` / `PUT` / `DELETE` | `/api/v1/settings/passario` | Passário URL in use / change `{url}` / back to default |
| `GET` / `PUT` / `DELETE` | `/api/v1/settings/refresh-interval` | UI auto-refresh interval / change `{seconds}` (2–300) / back to default |
| `GET` | `/api/v1/audit?page=0&size=50` | Audit log, newest first |

| Status | Meaning |
|---|---|
| `400` | Invalid name, count or body — `errors` lists each field |
| `404` / `409` | Pulsar says the resource is missing / already exists |
| `502` | Pulsar failed or is unreachable |
| `503` | Passário is unreachable (Bridges panel only), or MongoDB is down while saving a setting |

## Operate

| Endpoint | Use |
|---|---|
| `/actuator/health/liveness` | liveness probe |
| `/actuator/health/readiness` | readiness probe (application state only — dependencies degrade per feature) |
| `/actuator/health` | details: `pulsar`, `passario`, `mongo` components |
| `/actuator/prometheus` | HTTP, JVM and Pulsar client metrics |

Container image: `Dockerfile` (multi-stage, layered jar, non-root).
