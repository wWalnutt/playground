#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

if ! command -v tmuxinator >/dev/null 2>&1; then
  if ! command -v brew >/dev/null 2>&1; then
    printf 'Install tmuxinator before running this script.\n' >&2
    exit 1
  fi
  brew install tmuxinator
fi

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  exec tmuxinator help start
fi

exec tmuxinator start -p .tmuxinator_default.yml "$@"
