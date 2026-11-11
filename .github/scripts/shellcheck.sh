#!/usr/bin/env bash
# Runs shellcheck on every *.sh tracked by git (orch.md decision 88). Used by the `scripts` workflow.
#
#   bash .github/scripts/shellcheck.sh
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."   # the repository root
shellcheck --version
git ls-files -z '*.sh' | xargs -0 --no-run-if-empty shellcheck
