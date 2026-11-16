# 16. Login through Dex, and HTTPS with our own CA

Date: 2026-11-15 · Status: Accepted (2026-11-15) · Decisions: orch.md 96–100 · Rules: SEC-2, SEC-8, EVT-3

## Context

Catapulsar can delete topics, skip backlogs and change where the server sends its requests (SEC-6), and until now
anyone on the home network could open it: SEC-2 asked for authentication from the start, and the audit's actor
was only the browser's address (ADR 0008). Grafana and ArgoCD on meloes each had a local admin password of their
own. Every Route was plain HTTP, so a password or a session cookie would have crossed the network in clear.

meloes is small: its CPU requests were at 3.9 of 4 CPUs (the pods used about 0.2), and 3.5 GiB of memory were
free. The repository is public, so no personal data or secret can be in git.

## Decision

- **HTTPS everywhere on meloes** with **our own CA**, kept by **cert-manager**: a 10-year root and a 90-day
  wildcard (`*.catacumbas69.com`, `*.microshift.catacumbas69.com`) that the MicroShift router serves as its default
  certificate. Routes only say `termination: edge` and redirect HTTP (the registry also keeps HTTP).
  **trust-manager** gives the CA to the pods that call an HTTPS address. The CA is installed once on the Mac and
  the iPhone, and backed up on the Mac.
- **Dex** at `auth.catacumbas69.com` is the OpenID Connect provider of **Catapulsar, Grafana and ArgoCD**, with
  one session for the three (Auth Sessions, Dex 2.46+) and one local user, `peewee69`, admin everywhere. Its
  configuration (email, password hash, client secrets) is rendered from a template in git into a Secret by a
  script that runs on the Mac.
- **Catapulsar** needs the login (Spring Security, OpenID Connect client), with CSRF tokens on every change, `401`
  on the API without a session, and Sign out that ends Dex's session too. The **actor** of every event is the
  signed-in user, passed to the other apps in `X-Catacumbas69-User`. The other apps keep their Routes without
  login and trust that header (home network only).
- A profile **`no-auth`** turns the login off, for IntelliJ and for the tests.
- **ArgoCD's own configuration** (login, RBAC, its Route) moves into git.
- The **CPU requests** of MongoDB and Pulsar are trimmed a little to make room for the five new pods.

## Alternatives

- **Let's Encrypt** (DNS-01): the names resolve only at home, and it needs the domain's DNS API.
- **One certificate per Route** (cert-manager's openshift-routes): four more controllers on meloes, keys inside
  the Route objects, and `ignoreDifferences` in every Application.
- **Keycloak or Authentik**: complete, with an admin UI, but each needs a database and about 1 GB more memory.
- **Authelia or Pocket ID**: light, but they work only over HTTPS (Pocket ID only with passkeys).
- **An OAuth proxy in front of Catapulsar**: no change in the app, but the actor would come from a header the app
  cannot check, and the other apps would still not know the user.
- **Staying on HTTP**: Dex's SSO works there too, but the password and the cookies would cross the network in
  clear.

## Consequences

- The certificate renews on its own; the CA lasts 10 years. If MicroShift is reinstalled, the CA must be restored
  from the backup before the devices stop trusting it (`microshift/apps/tls/ca.sh restore`).
- One thing is set by hand on meloes and not in git: `/etc/microshift/config.d/10-ingress.yaml`.
- Changing Dex's configuration means running `dex-secret.sh config`, not just a push.
- The other apps' Routes stay open: anyone at home can still call them directly, and name any user in the header.
- ArgoCD does not check Dex's certificate (it only takes a CA as text in its own configuration).
- Catapulsar's session is in memory: a restart sends the page through Dex again, which lets the user straight
  back in while Dex's session lasts (30 days).
