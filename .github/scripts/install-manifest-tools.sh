#!/usr/bin/env bash
# Downloads kustomize and kubeconform (Linux amd64) and checks their checksums (orch.md decision 88).
# Used by the `manifests` workflow; also works on meloes.
#
#   bash .github/scripts/install-manifest-tools.sh [dir]     default: $RUNNER_TEMP/bin, or /tmp/manifest-tools
#
# In GitHub Actions the folder is added to the PATH of the next steps.
set -euo pipefail

KUSTOMIZE_VERSION="5.8.3"
KUSTOMIZE_SHA256="cb9e31198d3f63b44848bd0afc4c8efc56e6cfef9c93a4a39a211940e6decd61"
KUBECONFORM_VERSION="0.8.0"
KUBECONFORM_SHA256="9bc2bffbf71f261128533edaf912153948b7ff238f9a531ae6d34466ec287883"

dir="${1:-${RUNNER_TEMP:-/tmp/manifest-tools}/bin}"
mkdir -p "$dir"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fetch() {  # url sha256 file
  curl -sSfLo "$work/$3" "$1"
  echo "$2  $work/$3" | sha256sum -c -
}

fetch "https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize%2Fv${KUSTOMIZE_VERSION}/kustomize_v${KUSTOMIZE_VERSION}_linux_amd64.tar.gz" \
  "$KUSTOMIZE_SHA256" kustomize.tgz
fetch "https://github.com/yannh/kubeconform/releases/download/v${KUBECONFORM_VERSION}/kubeconform-linux-amd64.tar.gz" \
  "$KUBECONFORM_SHA256" kubeconform.tgz
tar -xzf "$work/kustomize.tgz" -C "$dir" kustomize
tar -xzf "$work/kubeconform.tgz" -C "$dir" kubeconform

if [[ -n "${GITHUB_PATH:-}" ]]; then
  echo "$dir" >> "$GITHUB_PATH"
fi
echo "kustomize and kubeconform in $dir"
