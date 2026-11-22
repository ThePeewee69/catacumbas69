# catacumbas69 apps on MicroShift

The five catacumbas69 apps (Cataponte, Cataudit, Catametricas, Catavigia, Catapulsar) run in MicroShift on
**meloes**, in the namespace `catacumbas69-apps`, synced by ArgoCD. Rules and decisions:
[`apps/orch.md`](../../../apps/orch.md) OPS-1..6, SEC-2, SEC-8 and decisions 81–87, 96–100, 104.

```
necromancer (Mac)                                meloes (MicroShift)
apps/build/push.sh <app> ── podman push ───────▶ registry (ns registry, Route registry.catacumbas69.com, HTTP)
  writes newTag into <app>/kustomization.yaml               │ CRI-O pulls registry.catacumbas69.com/<app>:<tag>
git commit + push ──▶ GitHub ──▶ ArgoCD (manual Sync) ──▶ ns catacumbas69-apps
                                                              ├── Deployment, Service, Route per app
                                                              └── Secret catacumbas69-apps-mongodb (copied)
```

## Layout

| Folder | Content |
|---|---|
| `microshift/apps/registry/` | The image registry: `registry:2.8.3`, 20 Gi PVC (`topolvm-provisioner`), Route `registry.catacumbas69.com`, HTTPS and HTTP without authentication (home network only) |
| `microshift/apps/catacumbas69-apps/<app>/` | Per app: ConfigMap, Deployment, Service, Route and a `kustomization.yaml` holding the image tag |
| `microshift/apps/mongodb/mongodb-cluster.yaml` | The MongoDB ReplicaSet, with the user `catacumbas69-apps` (read and write on `catacumbas69` only) and the admin `peewee69` |
| `microshift/apps/mongodb/mongo-express.yaml` | The one web UI of MongoDB, `https://mongo-express.catacumbas69.com`: connects as `peewee69` with the operator's `peewee69-connection`, basic-auth from `mongo-express-auth` (orch.md decision 101) |
| `microshift/apps/mongodb/mongo-admin.sh` | Runs on the Mac over ssh: `peewee69` sets the admin's password, `express` the mongo-express login (asked twice, not shown, never in git) |
| `microshift/argocd/` | Applied by the root Application `microshift`: a new or changed file there reaches the cluster with a push, and deleting a file deletes its Application (not what it deployed). One ArgoCD Application per app and one for the registry and the monitoring, with no automated sync: Sync by hand in the UI. Also `pulsar`, `mongodb` (the operator's chart), `mongodb-cluster`, `cert-manager`, `trust-manager`, `tls`, `dex` and `argocd-config` (orch.md decisions 97–99), and `loki`, `tempo`, `alloy` and `observability` (decision 104), which sync **on their own** (prune, selfHeal): change those folders in git, then Refresh; an `oc apply` by hand is undone |
| `microshift/bootstrap/microshift.yaml` | The root Application (app of apps): it reads `microshift/argocd/` with automated sync, prune and selfHeal. Applied once by hand; it does not manage itself |
| `apps/build/push.sh` | Builds an image with Podman (linux/amd64), pushes it and writes its tag into the app's `kustomization.yaml` |
| `apps/build/mongo-secret.sh` | Creates the apps' MongoDB password and copies their connection string into `catacumbas69-apps` |

| App | Replicas | Port | Route |
|---|---|---|---|
| Cataponte | 2 | 8082 | `https://cataponte.catacumbas69.com` |
| Cataudit | 2 | 8084 | `https://cataudit.catacumbas69.com` |
| Catametricas | 2 | 8083 | `https://catametricas.catacumbas69.com` |
| Catavigia | 2 | 8085 | `https://catavigia.catacumbas69.com` |
| Catasonarr | 1 | 8086 | `https://catasonarr.catacumbas69.com` (page: login through Dex; `/api/v3`: Sonarr's API key) |
| Catapulsar | 1 | 8081 | `https://catapulsar.catacumbas69.com` (login through Dex) |

Catasonarr is the only one that mounts the NAS (`/data` and `/downloads` over NFS, as user 1000; section 8).

Every container asks for 25m CPU and 448 Mi and may use up to 1 CPU and 896 Mi (meloes has 4 CPUs, most of
them already requested by MongoDB and Pulsar; 64 Mi and 128 Mi of that are for the OpenTelemetry agent, orch.md
decision 104). Spring's banner is printed at start-up (`SPRING_MAIN_BANNER_MODE=console`: Spring turns it off when the log is
JSON; orch.md decision 108). The JVM options are in each Deployment's `JAVA_TOOL_OPTIONS`
(heap up to 50 % of the limit, exit on `OutOfMemoryError`; orch.md decision 89): change them there and Sync, no
new image needed. The log starts with `Picked up JAVA_TOOL_OPTIONS: …`, which is the JVM confirming them. Probes are on Actuator
(startup and liveness on `/actuator/health/liveness`, readiness on `/actuator/health/readiness`), each with a 3 s
timeout; an app has up to 5 minutes to start, because after meloes reboots every pod starts at once on its 4 CPUs
(orch.md decision 92). The apps reach
each other through their Services (`http://<app>:<port>`), Pulsar through `pulsar.pulsar.svc.cluster.local`, and
MongoDB through the URI in the Secret `catacumbas69-apps-mongodb` (key `uri`), which is never in git.

**Names, never IPs** (orch.md decision 87): the registry and the Routes are reached as `*.catacumbas69.com`, and
Pulsar's external listener (used from IntelliJ) is advertised as `meloes:31650`. When the network changes
(192.168.86.x → 192.168.69.x), only the DNS records change. Every Route is HTTPS with the router's wildcard
certificate from our own CA, and `http://` is redirected (orch.md decision 97,
[`microshift/apps/tls/README.md`](../tls/README.md)); the registry also accepts plain HTTP.

Metrics and dashboards: Prometheus and Grafana on meloes collect every replica's `/actuator/prometheus`; see
[`microshift/apps/monitoring/README.md`](../monitoring/README.md) (orch.md decision 90).

Logs and traces (orch.md decision 104, [`microshift/apps/observability/README.md`](../observability/README.md)):
Alloy ships every pod's log to Loki. Each Deployment has an init container, `otel-agent`, that copies the
**OpenTelemetry Java agent** (image `ghcr.io/open-telemetry/opentelemetry-operator/autoinstrumentation-java:2`,
the newest 2.x) into an `emptyDir`; `JAVA_TOOL_OPTIONS` starts it with `-javaagent:/otel/javaagent.jar`. It traces
every HTTP request, MongoDB command and Pulsar message (the trace follows a message from one app to the next),
sends the traces to Alloy (`OTEL_EXPORTER_OTLP_ENDPOINT`), which forwards them to Tempo, and puts `trace_id` and
`span_id` in each log line. The `OTEL_*` variables are in the Deployment; the agent exports no metrics and no logs
(Prometheus and stdout keep those), and only the instrumentations the apps use are on
(`OTEL_INSTRUMENTATION_<NAME>_ENABLED`, orch.md decision 105): with all of them, nine JVMs starting together took
longer than the startup probe allows. Tracing a new library means turning its instrumentation on in every
Deployment. To turn it off for one app, remove `-javaagent:…` from `JAVA_TOOL_OPTIONS`
and Sync. Pods need `ghcr.io` reachable to start, since the image is pulled again on every start.

> **Login** (orch.md SEC-2): Catapulsar asks for it, through Dex at `https://auth.catacumbas69.com`
> ([`microshift/apps/dex/README.md`](../dex/README.md)); Catapulsar's Deployment needs the Secret
> `catapulsar-oidc` and the ConfigMap `catacumbas69-ca`. The other four apps have no login: anyone who reaches
> their Route can use them, and the user they record comes in `X-Catacumbas69-User` as sent. Keep the Routes
> inside the home network.

## One-time setup

Where each thing runs:

- **On meloes** (fish shell): every `oc` command and `apps/build/mongo-secret.sh`, from the root of a read-only
  clone of this repository, `~/GIT/github/catacumbas69` (`git pull` before each step, to get what was pushed from
  the Mac). `oc` uses `~/.kube/config` from `/var/lib/microshift/resources/kubeadmin/kubeconfig`. Run the scripts
  with `bash apps/build/<script>.sh`. The commands below work in fish and in bash (no `<<EOF`, no `$(…)`).
- **On the Mac** (necromancer, Intel, macOS 13, zsh): `apps/build/push.sh` (Podman), commits and pushes. If the
  scripts are not executable, `chmod 755 apps/build/*.sh` (and `git add --chmod=+x` to keep it in git).
- **In the ArgoCD UI**: every Sync, by hand. After a push, press **Refresh** first: ArgoCD may still have the
  previous commit and would deploy it.
- **A change to a file in `microshift/argocd/`** (an Application itself: its chart values, its sync policy) reaches
  the cluster through the root Application **`microshift`**, not through the Application the file describes.
  Refreshing that Application only re-reads its own sources, with the values it already has. Refresh
  **`microshift`** first, then the Application:
  ```
  oc -n argocd annotate applications.argoproj.io microshift argocd.argoproj.io/refresh=hard --overwrite
  oc -n argocd annotate applications.argoproj.io <app> argocd.argoproj.io/refresh=hard --overwrite
  ```
  To check what the Application received: `oc -n argocd get applications.argoproj.io <app> -o yaml`. A value a
  Helm chart has by default stays, even when the file stops setting it: set it explicitly (the `dex` chart's
  `readOnlyRootFilesystem`, `microshift/argocd/dex.yaml`).

To make the clone on meloes the first time:

```
mkdir -p ~/GIT/github
cd ~/GIT/github
git clone https://github.com/ThePeewee69/catacumbas69.git
```

### 1. Names (DNS)

`meloes` and `*.catacumbas69.com` must resolve to meloes **from the Mac and from meloes itself**: CRI-O on
meloes pulls from `registry.catacumbas69.com`, which goes through meloes' own router. `meloes` has no domain, so
it resolves only through the search domain the network gives each machine (DHCP) or a hosts entry. Check on the
Mac with `dscacheutil -q host -a name meloes` and on meloes with
`getent hosts registry.catacumbas69.com meloes`.

Pulsar advertises its external listener as `meloes:31650` (`microshift/apps/pulsar/broker-init-script.sh`).
Its ConfigMap keeps the same name, so after syncing `pulsar` in ArgoCD restart the broker once:
`oc -n pulsar delete pod -l app=pulsar` (not `rollout restart`, which ArgoCD undoes: orch.md decision 110).

### 2. Let CRI-O pull from the registry over HTTP (on meloes)

```
printf '%s\n' '[[registry]]' 'location = "registry.catacumbas69.com"' 'insecure = true' | sudo tee /etc/containers/registries.conf.d/900-catacumbas69.conf
sudo systemctl restart crio
```

To check it: `sudo crictl pull registry.catacumbas69.com/<app>:<tag>` pulls exactly as the kubelet does, with the
full error if it fails.

If pods stay in `ErrImagePull` afterwards with an HTTPS error, restart MicroShift too
(`sudo systemctl restart microshift`).

### 3. Deploy the registry

```
oc apply -f microshift/argocd/registry.yaml
```

Sync `registry` in the ArgoCD UI, then check from the Mac: `curl http://registry.catacumbas69.com/v2/` answers
`{}`.

### 4. Install Podman (on the Mac)

The Mac is an **Intel** Mac on macOS 13, so:

- **Not Homebrew**: on macOS 13 it has no bottles and the `podman` formula (6.x) needs Apple silicon.
- **Not Podman 6**: it dropped Intel Macs. The **5.8** series is the last one for them, with security and critical
  fixes until June 2027.
- **Podman Desktop** (universal `.dmg`, 1.29 or later) installs Podman 5 on an Intel Mac: open it, accept
  *Install Podman*, then *Create Podman machine*. The machine here is called **`Catacumbas69`** (applehv).

Check in a new terminal:

```
podman --version                                     # 5.8.x
podman machine ls                                    # Catacumbas69, Currently running
podman run --rm docker.io/library/alpine uname -m   # x86_64
curl http://registry.catacumbas69.com/v2/            # {}
```

meloes is `linux/amd64` too, so everything builds natively, without Rosetta. Commands that act on the machine
need its name, e.g. `podman machine ssh Catacumbas69 -- top -b -n 1`. The first `push.sh` takes several minutes:
Maven downloads every dependency into a cache and, with `-q`, prints nothing meanwhile (`java` at high CPU in
that `top` means it is working). The next builds reuse the cache.

### 5. MongoDB user for the apps

**First** create the password, **then** Sync `mongodb`: if the operator sees the new user before its password
Secret exists, it goes `Failed` (`user password secret … not found`).

```
git pull
bash apps/build/mongo-secret.sh password     # random password in Secret mongodb/catacumbas69-apps-password, never shown
```

It must print `Created Secret mongodb/catacumbas69-apps-password…`. Then Sync `mongodb` in ArgoCD and wait until
the operator has created the user:

```
oc -n mongodb get mdbc -w                                # Pending, then Running (Ctrl-C to leave)
oc -n mongodb get secret catacumbas69-apps-connection    # created by the operator
```

If it stays `Failed` although the password Secret exists, the operator is waiting longer between retries; make it
reconcile now with `oc -n mongodb delete pod -l name=mongodb-kubernetes-operator` (decision 110). The reason is in
`oc -n mongodb get mdbc catacumbas69 -o jsonpath='{.status.message}{"\n"}'`. A `Failed` here does not stop
MongoDB: the members keep serving. Then:

```
bash apps/build/mongo-secret.sh copy         # Secret catacumbas69-apps/catacumbas69-apps-mongodb (key uri)
oc -n catacumbas69-apps get secret catacumbas69-apps-mongodb
```

The operator cannot keep that Secret in another namespace (it is garbage-collected), so it is copied. Run `copy`
again only if the password changes; then restart the apps (`oc -n catacumbas69-apps rollout restart deploy`).

### 6. First images and ArgoCD Applications

With everything under `apps/` committed:

```
apps/build/push.sh all                  # on the Mac, in start order; writes each newTag
git status --short                      # only the five kustomization.yaml
git add microshift/apps/catacumbas69-apps && git commit -m "chore(microshift): deploy <sha>" && git push
```

**The tags must be pushed before any Sync**: until then the manifests say `not-built-yet`, and a Sync deploys pods
that can never pull (`manifest unknown`). On meloes:

```
git pull
grep newTag microshift/apps/catacumbas69-apps/*/kustomization.yaml    # no not-built-yet left
oc apply -f microshift/bootstrap/microshift.yaml     # once: the root Application, which creates the others
oc -n catacumbas69-apps get pods -w
```

The root Application `microshift` creates every Application in `microshift/argocd/` on its own; the apps' own
Applications stay manual, so nothing is deployed until you Sync them.

In the ArgoCD UI, **Refresh** then **Sync**, one app at a time in the **start order**: Cataponte → Cataudit →
Catametricas → Catavigia → Catapulsar, each one once the previous is Healthy. The nine pods end `Running`, `1/1`.

**CPU on meloes is tight.** It has 4 CPUs, and MongoDB (3 members × 600m), its operator (250m) and Pulsar (400m)
request a good part of them (trimmed a little in orch.md decision 96), so each app container requests only **25m**
(decision 85). A pod that stays
`Pending` with `Insufficient cpu` means the requests are full again; see them with
`oc describe node meloes | grep -A 12 "Allocated resources"`.

### 7. Reset the URLs in Catapulsar

Catapulsar's settings topic may still hold the `localhost` URLs saved from IntelliJ runs, and they win over the
ConfigMap. Open `https://catapulsar.catacumbas69.com` → Settings → Connections and choose **use default** for
Cataponte, Catametricas, Cataudit and Catavigia (Catavigia also reads the first three from there).

### 8. Catasonarr

Catasonarr (orch.md decisions 109 and 112, [`apps/catasonarr/README.md`](../../../apps/catasonarr/README.md)) needs,
before its first Sync:

1. **DNS**: `catasonarr.catacumbas69.com` pointing at meloes, like the other Routes.
2. **Its Dex client** (after the push that brings it in `microshift/apps/dex/config.yaml.tmpl`), on the Mac:
   ```
   bash microshift/apps/dex/dex-secret.sh clients
   ```
   It generates only the new client's secret, writes `catacumbas69-apps/catasonarr-oidc` and restarts Dex.
3. **Sonarr's API key**, so the clients only change Sonarr's address (Sonarr → Settings → General → API Key), on
   the Mac:
   ```
   bash microshift/apps/catacumbas69-apps/catasonarr/catasonarr-secret.sh api-key
   ```
   It asks for the key twice, without showing it, and writes `catacumbas69-apps/catasonarr-api-key`.
4. **The NAS for meloes** (decision 112): Catasonarr mounts `192.168.86.50:/volume1/Media` on `/data` and
   `192.168.86.50:/volumeUSB4/usbshare` on `/downloads`, as Sonarr does in K3s. On meloes, NFS must be installed:
   ```
   rpm -q nfs-utils
   ```
   (if it is missing: `sudo dnf install nfs-utils`). On the Synology, in **Control Panel → Shared Folder**, edit **Media** and the USB
   share, **NFS Permissions → Create**: meloes' IP (`192.168.86.35`), *Read/Write*, the same *Squash* and *Security*
   as the K3s nodes already have. Without them the pod stays in `ContainerCreating` (`mount.nfs: access denied`).
5. **TMDB** (optional: without it, TVmaze's pictures and English summaries). Make the token at
   <https://www.themoviedb.org>: an account, then **Settings → API → Create → Developer**, fill in the form (personal
   use), and copy the **API Read Access Token** (the long one, `eyJ…`, not the short API Key). On the Mac:
   ```
   bash microshift/apps/catacumbas69-apps/catasonarr/catasonarr-secret.sh tmdb
   ```
   It asks for it twice, without showing it, and writes `catacumbas69-apps/catasonarr-tmdb`.
6. **The image**: `apps/build/push.sh catasonarr`, commit the new tag, push, and **Sync** `catasonarr` in ArgoCD.
7. **Quality from Sonarr** (after the Sync): copy `sonarr.db` from Sonarr's `/config` (`/volume1/k3s/media/Sonarr`
   on the NAS) to the Mac, then:
   ```
   bash microshift/apps/catacumbas69-apps/catasonarr/catasonarr-import-sonarr.sh ~/Downloads/sonarr.db
   ```
   It reads the copy only, asks for the API key without showing it, and replaces Catasonarr's quality definitions,
   custom formats, profiles and folder naming with Sonarr's. Then add the root folders in Catasonarr's
   **Settings** (the same as Sonarr's, e.g. `/data/...`).

Without the API key or the Dex Secret the pod stays in `CreateContainerConfigError`; the TMDB Secret is optional.

## Every release (after changing code)

Everything happens on the Mac, except the Sync in ArgoCD. Nothing is needed on meloes.

1. **Change the code** and check it, as always:
   ```
   cd apps && mvn clean install
   ```
2. **Commit and push the code.** `push.sh` refuses to build while `apps/` has uncommitted changes, because the
   image tag is the commit's sha (OPS-2):
   ```
   git add … && git commit -m "feat(cataponte): …" && git push
   ```
   The push starts the `apps` workflow on GitHub (the repository's **Actions** tab), which runs `mvn verify`
   again; a change to the manifests also runs `manifests`, and a script `scripts`. If one fails, fix it before
   going on.
3. **Build and push the images** of the apps that changed:
   ```
   apps/build/push.sh cataponte      # one app
   apps/build/push.sh all            # after a change in catacommons, which goes into every app
   ```
4. **Commit and push the tags** that `push.sh` wrote into the `kustomization.yaml` files. This is the step that
   is easy to forget: without it ArgoCD does not see the new tag and keeps running the previous image.
   ```
   git add microshift/apps/catacumbas69-apps
   git commit -m "chore(microshift): deploy <sha>"
   git push
   ```
5. **In ArgoCD**, for each app that changed: **Refresh**, then **Sync**. Several apps follow the start order
   (Cataponte → Cataudit → Catametricas → Catavigia → Catapulsar).

The tag is `<version>-<short sha>`. `FORCE=1 apps/build/push.sh <app>` builds with uncommitted changes and a
`-dirty-<date-time>` tag, for a quick test only. To check on meloes: `git pull` and
`grep newTag microshift/apps/catacumbas69-apps/*/kustomization.yaml`.

## Troubleshooting

| Symptom | Look at |
|---|---|
| `ErrImagePull` with tag `not-built-yet`, or an old tag | The tags were not pushed, or ArgoCD was not refreshed before the Sync: step 6 |
| `ErrImagePull` / `ImagePullBackOff` with the right tag | Steps 1–2 on meloes; the tag is in the registry (`curl http://registry.catacumbas69.com/v2/<app>/tags/list`); `sudo crictl pull …` shows the full error |
| `Pending`, `Insufficient cpu` | The node's requests are full: step 6, *CPU on meloes is tight* |
| `mdbc` `Failed`, `user password secret … not found` | Step 5: `password` first, then restart the operator |
| `CreateContainerConfigError` | The Secret `catacumbas69-apps-mongodb` is missing: step 5 (`copy`) |
| Pod not Ready | `oc -n catacumbas69-apps logs deploy/<app>`; `/actuator/health/readiness` lists what is down (Pulsar, MongoDB) |
| Restarts with `Error`, exit 143, a few minutes after meloes boots | The startup probe gave up (5 minutes): see `oc -n catacumbas69-apps get pods -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.containerStatuses[0].lastState.terminated.exitCode}{"\n"}{end}'`; they recover on their own, raise `failureThreshold` of the `startupProbe` if it keeps happening |
| Catapulsar panels say an app does not answer | Step 7 |
| A change to `microshift/argocd/<app>.yaml` does not reach the cluster, although the Application is `Synced` at the new commit | The root Application `microshift` has not applied the file yet: Refresh `microshift`, then `<app>` (*Where each thing runs*) |
| `podman machine ssh` says `podman-machine-default … does not exist` | Name the machine: `podman machine ssh Catacumbas69` |
| `podman push` fails with a timeout | The Route waits 5 min (`haproxy.router.openshift.io/timeout`); raise it in `registry-route.yaml` |
| Registry full | Delete old tags through the registry API, then `registry garbage-collect /etc/docker/registry/config.yml` in its pod |
