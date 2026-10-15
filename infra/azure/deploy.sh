#!/usr/bin/env bash
# Usage: DB_PASSWORD=... RABBIT_PASSWORD=... ./infra/azure/deploy.sh [resource-group] [location]
set -euo pipefail
RG="${1:-notification-worker-rg}"
LOC="${2:-centralindia}"
: "${DB_PASSWORD:?set DB_PASSWORD}" "${RABBIT_PASSWORD:?set RABBIT_PASSWORD}"
HERE="$(cd "$(dirname "$0")" && pwd)"
TAG="$(git rev-parse --short HEAD 2>/dev/null || date +%s)"

az group create -n "$RG" -l "$LOC" >/dev/null
az provider register -n Microsoft.App --wait; az provider register -n Microsoft.OperationalInsights --wait

# 1) Infra with placeholder image (creates ACR, Postgres, RabbitMQ, apps)
ACR=$(az deployment group create -g "$RG" -f "$HERE/main.bicep" \
  -p dbAdminPassword="$DB_PASSWORD" rabbitPassword="$RABBIT_PASSWORD" \
  --query properties.outputs.acrName.value -o tsv)

# 2) Build image in ACR (no local Docker needed)
az acr build -r "$ACR" -t "notification-worker:$TAG" "$HERE/../.."

# 3) Re-deploy pointing apps at the real image
az deployment group create -g "$RG" -f "$HERE/main.bicep" \
  -p dbAdminPassword="$DB_PASSWORD" rabbitPassword="$RABBIT_PASSWORD" \
     image="$ACR.azurecr.io/notification-worker:$TAG" \
  --query properties.outputs.apiUrl.value -o tsv
