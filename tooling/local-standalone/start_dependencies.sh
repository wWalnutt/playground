#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
if ! docker info >/dev/null 2>&1; then
  printf 'Docker is not reachable. Start Docker Desktop or Colima first.\n' >&2
  exit 1
fi

if ! command -v docker-compose >/dev/null 2>&1; then
  printf 'docker-compose is required to start this project.\n' >&2
  exit 1
fi

docker-compose -f docker-compose.pgvector.yml --profile embedding up -d --wait
if ! docker-compose -f docker-compose.pgvector.yml exec -T ollama ollama show bge-m3 >/dev/null; then
  printf 'bge-m3 is unavailable. Check Ollama and download it with:\n' >&2
  printf '  docker-compose -f docker-compose.pgvector.yml exec -T ollama ollama pull bge-m3\n' >&2
  exit 1
fi
