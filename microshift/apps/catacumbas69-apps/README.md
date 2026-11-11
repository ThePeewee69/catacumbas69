# catacumbas69 apps on MicroShift

The five catacumbas69 apps (Cataponte, Cataudit, Catametricas, Catavigia, Catapulsar) run in MicroShift on
**meloes**, in the namespace `catacumbas69-apps`, synced by ArgoCD. Rules and decisions:
[`apps/orch.md`](../../../apps/orch.md) OPS-1..6 and decisions 81–87.

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
| `microshift/apps/registry/` | The image registry: `registry:2.8.3`, 20 Gi PVC (`topolvm-provisioner`), Route `registry.catacumbas69.com`, HTTP without authentication (home network only) |
| `microshift/apps/catacumbas69-apps/<app>/` | Per app: ConfigMap, Deployment, Service, Route and a `kustomization.yaml` holding the image tag |
| `microshift/apps/mongodb/mongodb-cluster.yaml` | The MongoDB ReplicaSet, with the user `catacumbas69-apps` (read and write on `catacumbas69` only) |
| `microshift/argocd/` | One ArgoCD Application per app and one for the registry. No automated sync: Sync by hand in the UI |
| `apps/build/push.sh` | Builds an image with Podman (linux/amd64), pushes it and writes its tag into the app's `kustomization.yaml` |
| `apps/build/mongo-secret.sh` | Creates the apps' MongoDB password and copies their connection string into `catacumbas69-apps` |

| App | Replicas | Port | Route |
|---|---|---|---|
| Cataponte | 2 | 8082 | `http://cataponte.catacumbas69.com` |
| Cataudit | 2 | 8084 | `http://cataudit.catacumbas69.com` |
| Catametricas | 2 | 8083 | `http://catametricas.catacumbas69.com` |
| Catavigia | 2 | 8085 | `http://catavigia.catacumbas69.com` |
| Catapulsar | 1 | 8081 | `http://catapulsar.catacumbas69.com` |

Every container asks for 25m CPU and 384 Mi and may use up to 1 CPU and 768 Mi (meloes has 4 CPUs, most of
them already requested by MongoDB and Pulsar). The JVM options are in each Deployment's `JAVA_TOOL_OPTIONS`
(heap up to 50 % of the limit, exit on `OutOfMemoryError`; orch.md decision 89): change them there and Sync, no
new image needed. The log starts with `Picked up JAVA_TOOL_OPTIONS: …`, which is the JVM confirming them. Probes are on Actuator
(startup and liveness on `/actuator/health/liveness`, readiness on `/actuator/health/readiness`). The apps reach
each other through their Services (`http://<app>:<port>`), Pulsar through `pulsar.pulsar.svc.cluster.local`, and
MongoDB through the URI in the Secret `catacumbas69-apps-mongodb` (key `uri`), which is never in git.

**Names, never IPs** (orch.md decision 87): the registry and the Routes are reached as `*.catacumbas69.com`, and
Pulsar's external listener (used from IntelliJ) is advertised as `meloes:31650`. When the network changes
(192.168.86.x → 192.168.69.x), only the DNS records change. Like the other Routes, they are plain HTTP.

Metrics and dashboards: Prometheus and Grafana on meloes collect every replica's `/actuator/prometheus`; see
[`microshift/apps/monitoring/README.md`](../monitoring/README.md) (orch.md decision 90).

> **No authentication yet** (orch.md SEC-2): anyone who reaches a Route can use that app. Keep the Routes inside
> the home network.

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
`oc -n pulsar rollout restart deploy/pulsar`.

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
reconcile now with `oc -n mongodb rollout restart deploy/mongodb-kubernetes-operator`. The reason is in
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
oc apply -f microshift/argocd/cataponte.yaml -f microshift/argocd/cataudit.yaml -f microshift/argocd/catametricas.yaml -f microshift/argocd/catavigia.yaml -f microshift/argocd/catapulsar.yaml
oc -n catacumbas69-apps get pods -w
```

In the ArgoCD UI, **Refresh** then **Sync**, one app at a time in the **start order**: Cataponte → Cataudit →
Catametricas → Catavigia → Catapulsar, each one once the previous is Healthy. The nine pods end `Running`, `1/1`.

**CPU on meloes is tight.** It has 4 CPUs, and MongoDB (3 members × 750m), its operator (500m) and Pulsar (500m)
already request most of them, so each app container requests only **25m** (orch.md decision 85). A pod that stays
`Pending` with `Insufficient cpu` means the requests are full again; see them with
`oc describe node meloes | grep -A 12 "Allocated resources"`.

### 7. Reset the URLs in Catapulsar

Catapulsar's settings topic may still hold the `localhost` URLs saved from IntelliJ runs, and they win over the
ConfigMap. Open `http://catapulsar.catacumbas69.com` → Settings → Connections and choose **use default** for
Cataponte, Catametricas, Cataudit and Catavigia (Catavigia also reads the first three from there).

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
| Catapulsar panels say an app does not answer | Step 7 |
| `podman machine ssh` says `podman-machine-default … does not exist` | Name the machine: `podman machine ssh Catacumbas69` |
| `podman push` fails with a timeout | The Route waits 5 min (`haproxy.router.openshift.io/timeout`); raise it in `registry-route.yaml` |
| Registry full | Delete old tags through the registry API, then `registry garbage-collect /etc/docker/registry/config.yml` in its pod |
