#!/usr/bin/env bash
# Usage:
#   ./run.sh            CreatePermission loop: counts the requests coturn received
#   ./run.sh deadlock   Agent.free() deadlock: counts frees that hang (FREE_MODE=remove-stream-first for the workaround)
set -euo pipefail
cd "$(dirname "$0")"
scenario="${1:-loop}"

docker compose build repro deadlock
docker compose up -d coturn
sleep 2

case "$scenario" in
  loop)
    docker compose run --rm repro
    total=$(docker compose logs coturn | grep -c "CREATE_PERMISSION" || true)
    refused=$(docker compose logs coturn | grep "CREATE_PERMISSION" | grep -c "403" || true)
    echo
    echo "coturn received ${total} CreatePermission requests (${refused} refused with 403 Forbidden IP)"
    docker compose logs coturn | grep "CREATE_PERMISSION" | head -3 || true
    ;;
  deadlock)
    docker compose run --rm deadlock 2>&1 | grep -E "^Freeing|^iteration|^RESULT"
    ;;
  *)
    echo "Unknown scenario: ${scenario} (expected: loop, deadlock)" >&2
    exit 1
    ;;
esac

docker compose down -v >/dev/null 2>&1
