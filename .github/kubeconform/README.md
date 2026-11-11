# kubeconform schemas

JSON schemas that kubeconform has nowhere else, used by `.github/scripts/validate-manifests.sh` in the
`manifests` workflow (orch.md decision 88). Kubernetes' own kinds come from kubeconform's default
location, and the CRDs of ArgoCD and the MongoDB operator from the datreeio CRDs-catalog (pinned to one commit in
the workflow). The layout is the one kubeconform looks up: `<group>/<kind lowercased>_<version>.json`.

| Schema | Source |
|---|---|
| `route.openshift.io/route_v1.json` | `route/v1/zz_generated.crd-manifests/routes.crd.yaml` of [openshift/api](https://github.com/openshift/api), commit `f6e8a0876ff972521e8bf3bf687079e6511ac001` (2026-11-09) |

## What they check

Field names, types, enums, required fields and the int-or-string ports. Unknown fields are rejected, as with
`kubectl --validate=strict`. Defaults and the CEL rules (`x-kubernetes-validations`) are not applied; the API
server still checks those at Sync. See the docstring of `crd-to-schema.py` for the details.

## Run by hand

On meloes, or any Linux amd64 machine with git, curl and bash:

```
bash .github/scripts/install-manifest-tools.sh /tmp/manifest-tools
PATH=/tmp/manifest-tools:$PATH bash .github/scripts/validate-manifests.sh
```

## Regenerate (for example after a MicroShift upgrade)

```
git clone --depth 1 --filter=blob:none --sparse https://github.com/openshift/api.git /tmp/openshift-api
git -C /tmp/openshift-api sparse-checkout set route/v1
python3 .github/kubeconform/crd-to-schema.py \
  /tmp/openshift-api/route/v1/zz_generated.crd-manifests/routes.crd.yaml v1 \
  > .github/kubeconform/route.openshift.io/route_v1.json
```

`crd-to-schema.py` needs PyYAML (`pip install pyyaml`). The same script works for any other CRD: give it the CRD
file and the version, and save the output as `<group>/<kind>_<version>.json`. Update the commit in the table.
