#!/usr/bin/env bash
set -euo pipefail
APP_DIR="${VPS_APP_DIR:-/docker/saffron-backend}"
cd "$APP_DIR"

REGISTRY_HOST="${REGISTRY_HOST:-ghcr.io}"
if [[ -z "${REGISTRY_PASSWORD:-}" || -z "${REGISTRY_USER:-}" ]]; then
  echo "ERROR: REGISTRY_USER and REGISTRY_PASSWORD required to pull from GHCR." >&2
  exit 1
fi
echo "$REGISTRY_PASSWORD" | docker login -u "$REGISTRY_USER" --password-stdin "$REGISTRY_HOST"

cat > .env <<EOF
BACKEND_IMAGE=${BACKEND_IMAGE:?BACKEND_IMAGE required}
POSTGRES_USER=${POSTGRES_USER:-saffron}
POSTGRES_PASSWORD=${POSTGRES_PASSWORD:?POSTGRES_PASSWORD required}
POSTGRES_DB=${POSTGRES_DB:-cashflow}
JWT_SECRET=${JWT_SECRET:?JWT_SECRET required}
APP_BOOTSTRAP_EMPTY_DATABASE=${APP_BOOTSTRAP_EMPTY_DATABASE:-false}
APP_SEED_ADMIN_PASSWORD=${APP_SEED_ADMIN_PASSWORD:-}
EOF
chmod 600 .env

docker network inspect saffron_net >/dev/null 2>&1 || docker network create saffron_net

docker compose -f docker-compose.prod.yml --env-file .env pull
docker compose -f docker-compose.prod.yml --env-file .env up -d --remove-orphans

echo "Backend: $(docker ps --filter name=saffron-backend --format '{{.Names}} {{.Status}}')"
