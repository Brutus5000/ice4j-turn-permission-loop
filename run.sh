#!/usr/bin/env bash
# Runs the reproduction and counts the CreatePermission requests coturn received.
set -euo pipefail
cd "$(dirname "$0")"

docker compose build repro
docker compose up -d coturn
sleep 2
docker compose run --rm repro

total=$(docker compose logs coturn | grep -c "CREATE_PERMISSION" || true)
refused=$(docker compose logs coturn | grep "CREATE_PERMISSION" | grep -c "403" || true)
echo
echo "coturn received ${total} CreatePermission requests (${refused} refused with 403 Forbidden IP)"
docker compose logs coturn | grep "CREATE_PERMISSION" | head -3 || true

docker compose down -v >/dev/null 2>&1
