#!/usr/bin/env bash
# Checks every manifest under microshift/ before ArgoCD syncs it (orch.md decision 88). A folder with a
# kustomization.yaml is checked as kustomize builds it; every other YAML file as it is. kubeconform -strict checks
# each resource against Kubernetes, the CRDs-catalog (ArgoCD, MongoDB operator) and .github/kubeconform/ (Route).
# Used by the `manifests` workflow; needs kustomize and kubeconform on the PATH (install-manifest-tools.sh).
#
#   bash .github/scripts/validate-manifests.sh
set -euo pipefail

KUBERNETES_VERSION="1.35.0"   # MicroShift 4.22 on meloes
# CRD schemas, pinned to one commit of the catalog
CRDS_CATALOG="https://raw.githubusercontent.com/datreeio/CRDs-catalog/49729e49e8f062933e3716a26540d41b0cd0856c"

cd "$(dirname "${BASH_SOURCE[0]}")/../.."   # the repository root

check() {
  kubeconform -strict -summary -kubernetes-version "$KUBERNETES_VERSION" \
    -schema-location default \
    -schema-location '.github/kubeconform/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' \
    -schema-location "$CRDS_CATALOG"'/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' \
    "$@"
}

group() {  # Collapsible sections in the GitHub log; plain titles elsewhere
  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then echo "::group::$1"; else echo "── $1"; fi
}

endgroup() {
  if [[ -n "${GITHUB_ACTIONS:-}" ]]; then echo "::endgroup::"; fi
}

failed=0
while IFS= read -r dir; do
  group "$dir"
  kustomize build "$dir" | check - || failed=1
  endgroup
done < <(find microshift -name kustomization.yaml -exec dirname {} \; | sort)

plain=()
while IFS= read -r file; do
  [[ -f "$(dirname "$file")/kustomization.yaml" ]] || plain+=("$file")
done < <(find microshift -name '*.yaml' -not -name kustomization.yaml | sort)
if (( ${#plain[@]} )); then
  group "files without kustomization"
  check "${plain[@]}" || failed=1
  endgroup
fi

exit "$failed"
