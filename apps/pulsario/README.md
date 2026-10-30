# Pulsário

A lightweight, dark-themed admin UI for [Apache Pulsar](https://pulsar.apache.org/) built with Spring Boot and Alpine.js. Pulsário lets you explore your Pulsar cluster, manage topics and subscriptions, produce messages, and peek at backlog — all from a single-page interface with no build step.

---

## Features

| Area | What you can do |
|---|---|
| **Cluster tree** | Browse tenants → namespaces → topics in a collapsible sidebar |
| **Stats** | View per-topic statistics: message rate, throughput, storage size, producer/consumer counts |
| **Subscriptions** | List, create, and delete subscriptions on any topic |
| **Produce** | Send messages to a topic with an optional message key |
| **Peek Backlog** | Non-destructively inspect the first N messages in a subscription's backlog, then Skip individual messages or Skip All |
| **Health check** | Verify connectivity to the Pulsar cluster from the browser via the Spring backend (CORS-safe) |

---

## Architecture

```
Browser (Alpine.js SPA)
        │  REST API calls
        ▼
Spring Boot (port 8081)
  ├── /api/tenants
  ├── /api/namespaces/{tenant}
  ├── /api/topics/{tenant}/{ns}
  ├── /api/topics/{tenant}/{ns}/{topic}/stats
  ├── /api/topics/{tenant}/{ns}/{topic}/subscriptions
  ├── /api/topics/{tenant}/{ns}/{topic}/produce
  ├── /api/topics/{tenant}/{ns}/{topic}/peek/{sub}?count=N
  └── /api/topics/{tenant}/{ns}/{topic}/subscriptions/{sub}/skip/{n}
        │  Pulsar Admin HTTP API
        ▼
Apache Pulsar cluster (192.168.86.35, NodePort 31080)
```

The browser never contacts Pulsar directly — all admin calls are proxied through Spring Boot, which avoids CORS issues and keeps the Pulsar endpoint hidden from the client.

---

## Technology Stack

| Layer | Technology |
|---|---|
| **Backend** | Java 21 · Spring Boot 3.3.4 · Spring Web (RestTemplate) |
| **Frontend** | Alpine.js 3.14.8 (CDN, no build step) |
| **Styling** | Vanilla CSS with CSS custom properties (dark theme) |
| **Pulsar client** | Pulsar Admin REST API (HTTP, not the Java client library) |
| **Packaging** | Maven · Spring Boot fat JAR |

---

## Project Structure

```
pulsario/
├── pom.xml
└── src/main/
    ├── java/com/catacumbas69/pulsario/
    │   ├── PulsarioApplication.java          # Spring Boot entry point
    │   ├── config/
    │   │   └── PulsarConfig.java             # RestTemplate bean, base URL config
    │   ├── controller/
    │   │   └── PulsarController.java         # REST endpoints exposed to the UI
    │   └── service/
    │       └── PulsarAdminService.java       # Calls to Pulsar Admin API
    └── resources/
        ├── application.properties            # pulsar.admin.url, server.port
        └── static/
            ├── index.html                    # Single-page app shell + Alpine.js component
            ├── pulsario.js                   # app() function — all reactive state & API logic
            ├── pulsario.css                  # Full dark-theme stylesheet
            ├── pulsario-icon.svg             # Brand icon (SVG)
            └── favicon.png                   # 32×32 PNG favicon for browser tab
```

---

## Configuration

`src/main/resources/application.properties`:

```properties
server.port=8081
pulsar.admin.url=http://192.168.86.35:31080
```

The Pulsar URL can also be overridden at runtime from the Settings panel in the UI (stored in `localStorage`). The **Settings → Health Check** button calls `/api/tenants` through the backend to verify the connection without CORS errors.

---

## Running Locally

### Prerequisites

- Java 21+
- Maven 3.8+
- A running Apache Pulsar cluster (standalone or distributed)

### Build & Run

```bash
cd apps/pulsario
mvn package -DskipTests
java -jar target/pulsario-*.jar
```

Then open [http://localhost:8081](http://localhost:8081).

### Development

```bash
mvn spring-boot:run
```

> **Note:** Static resources (`index.html`, `pulsario.js`, `pulsario.css`) are embedded in the JAR at compile time. If you change a static file while running from a compiled JAR, rebuild and restart to pick up the changes. With `spring-boot:run` (dev mode), changes to static files under `src/main/resources/static/` are served live without a restart.

---

## UI Overview

### Sidebar — Cluster Tree

The left panel shows the tenant → namespace → topic hierarchy. Click any level to select it. Topics can be deleted inline. New topics are created from the **Stats** tab.

### Main Panel — Tabs

After selecting a topic, four tabs appear:

**Stats** — Key topic metrics (messages in, messages out, rate, size, producer/consumer counts) displayed as stat cards plus a per-subscription backlog breakdown table.

**Subscriptions** — Lists all subscriptions with a Create / Delete inline form.

**Produce** — Free-text message producer with an optional key field. Shows the returned `messageId` on success.

**Peek Backlog** — Pick a subscription from the styled listbox, choose how many messages to fetch (default 10, max 100), then click **Peek**. Messages appear in a table with payload, key, publish time, and producer name. Each row has **View** (formatted JSON modal), **Edit & Re-produce**, and **Skip** buttons. A **Skip All** button at the top skips every fetched message in one call.

### Connection Status

The top-right chip shows the Pulsar host and a coloured dot (green = OK, red = error, amber = checking). Click it to open the Settings drawer where you can change the cluster URL and run a health check.

---

## API Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/tenants` | List all tenants |
| `GET` | `/api/namespaces/{tenant}` | List namespaces for a tenant |
| `GET` | `/api/topics/{tenant}/{ns}` | List topics in a namespace |
| `GET` | `/api/topics/{tenant}/{ns}/{topic}/stats` | Topic statistics |
| `GET` | `/api/topics/{tenant}/{ns}/{topic}/subscriptions` | List subscriptions |
| `POST` | `/api/topics/{tenant}/{ns}/{topic}/subscriptions/{sub}` | Create subscription |
| `DELETE` | `/api/topics/{tenant}/{ns}/{topic}/subscriptions/{sub}` | Delete subscription |
| `POST` | `/api/topics/{tenant}/{ns}/{topic}/produce` | Produce a message `{payload, key}` |
| `GET` | `/api/topics/{tenant}/{ns}/{topic}/peek/{sub}?count=N` | Peek at backlog messages |
| `POST` | `/api/topics/{tenant}/{ns}/{topic}/subscriptions/{sub}/skip/{n}` | Skip N messages |
| `POST` | `/api/topics/{tenant}/{ns}/{topic}` | Create topic |
| `DELETE` | `/api/topics/{tenant}/{ns}/{topic}` | Delete topic |

---

## Deployment

The app is packaged as a single fat JAR and can run anywhere Java is available. In the reference environment it runs on a NUC host alongside an Apache Pulsar standalone cluster on MicroShift, with Pulsar exposed on NodePort `31080` (HTTP admin) and `31650` (binary protocol).

---

## License

MIT
