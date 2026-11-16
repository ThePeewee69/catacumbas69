# HTTPS on meloes: our own CA and the router's wildcard

Every Route on meloes is HTTPS (orch.md SEC-8, decision 97). cert-manager keeps our own CA and issues from it one
wildcard certificate, which the MicroShift router serves for every Route; trust-manager gives the CA to the pods
that call an HTTPS address.

```
cert-manager (Application cert-manager)
  ClusterIssuer selfsigned ──▶ Certificate catacumbas69-ca (10 years) ──▶ Secret cert-manager/catacumbas69-ca
  ClusterIssuer catacumbas69-ca ──▶ Certificate router-certs-custom (90 days, *.catacumbas69.com,
                                    *.microshift.catacumbas69.com) ──▶ Secret openshift-ingress/router-certs-custom
MicroShift router  ◀── /etc/microshift/config.d/10-ingress.yaml: ingress.certificateSecret: router-certs-custom
trust-manager (Application trust-manager)
  Bundle catacumbas69-ca ──▶ ConfigMap catacumbas69-ca (ca.crt, truststore.p12) in catacumbas69-apps, monitoring
```

| File | Content |
|---|---|
| `ca.yaml` | The CA: a self-signed root (ECDSA P-256, 10 years, key never rotated) and the ClusterIssuer that signs with it |
| `router-certificate.yaml` | The wildcard for the router; renewed 30 days before its 90 days end, and the router reloads it on its own |
| `ca-bundle.yaml` | trust-manager's Bundle: the public CAs plus ours, as PEM (`ca.crt`, Grafana) and PKCS#12 (`truststore.p12`, password `changeit`, Catapulsar) |
| `ca.sh` | Runs on the Mac: `cert` saves the CA's certificate to install it, `backup` / `restore` keep the CA with its key in `~/.catacumbas69/ca-backup.yaml` |

The ArgoCD Applications are `microshift/argocd/cert-manager.yaml`, `trust-manager.yaml` and `tls.yaml`, all with
automated sync: a push reaches the cluster on its own. On a fresh cluster `tls` and `trust-manager` fail a few
times until cert-manager's CRDs and webhook are up; their retry takes care of it.

Each Route has only:

```yaml
  tls:
    termination: edge
    insecureEdgeTerminationPolicy: Redirect   # the registry has Allow: Podman and CRI-O still use HTTP
```

A wildcard covers one level: a new name like `a.b.catacumbas69.com` needs a new entry in `router-certificate.yaml`.

## One-time setup

1. **After the push**, wait until the certificates are ready (on meloes):
   ```
   oc -n cert-manager get certificate catacumbas69-ca
   oc -n openshift-ingress get certificate router-certs-custom
   ```
   Both must show `READY True`.
2. **Tell MicroShift to serve the wildcard** (on meloes, once). Until then the router serves MicroShift's own
   certificate and the browser warns:
   ```
   printf 'ingress:\n  certificateSecret: router-certs-custom\n' | sudo tee /etc/microshift/config.d/10-ingress.yaml
   sudo systemctl restart microshift
   ```
   The pods keep running; the router restarts with the new certificate. Check it from the Mac:
   ```
   openssl s_client -connect catapulsar.catacumbas69.com:443 -servername catapulsar.catacumbas69.com </dev/null 2>/dev/null | openssl x509 -noout -issuer -subject
   ```
   The issuer is `CN=catacumbas69 CA`.
3. **Install the CA on the Mac** (needs the ssh key, [`microshift/apps/dex/README.md`](../dex/README.md)):
   ```
   bash microshift/apps/tls/ca.sh cert
   sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain ~/Downloads/catacumbas69-ca.crt
   ```
   Safari and Chrome use the macOS keychain; restart the browser. (Or double-click the file, add it to the
   System keychain, open it and set *When using this certificate* to **Always Trust**.)
4. **Install the CA on the iPhone**: AirDrop `~/Downloads/catacumbas69-ca.crt` to it, then *Settings → Profile
   Downloaded → Install*, and turn it on in *Settings → General → About → Certificate Trust Settings*.
5. **Back up the CA** to the Mac (certificate and private key, readable only by you; not in git):
   ```
   bash microshift/apps/tls/ca.sh backup
   ```

## After reinstalling MicroShift

Put the CA back before (or right after) the Application `tls` syncs, so the Mac and the iPhone keep trusting it:
`bash microshift/apps/tls/ca.sh restore`. cert-manager keeps a CA that matches `ca.yaml`, and issues a new wildcard
from it. Then repeat step 2.

## Troubleshooting

| Symptom | Look at |
|---|---|
| The browser warns about the certificate | Step 2 done? `oc -n openshift-ingress get secret router-certs-custom`; the CA installed and trusted (steps 3–4)? |
| A certificate stays `READY False` | `oc -n openshift-ingress describe certificate router-certs-custom`, then `oc -n cert-manager logs deploy/cert-manager` |
| ConfigMap `catacumbas69-ca` missing in a namespace | `oc get bundle catacumbas69-ca -o yaml` (status), `oc -n cert-manager logs deploy/trust-manager`; add the namespace to `ca-bundle.yaml` |
| Catapulsar cannot reach Dex (`PKIX path building failed`) | The ConfigMap above, and `JAVA_TOOL_OPTIONS` in its Deployment |
