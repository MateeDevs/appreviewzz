#!/usr/bin/env bash
# Spustí nasazení v Coolify přes deploy webhook.
#
# Chybějící webhook je chyba, ne důvod k přeskočení: Coolify má auto-deploy vypnutý,
# takže tenhle skript je jediná cesta, jak se nasazení spustí. Tiché `exit 0` by znamenalo
# zelenou pipeline nad prostředím, které zůstalo na staré verzi.
#
# Použití: COOLIFY_TOKEN=… trigger-deploy.sh <webhook-url> <popis prostředí>
set -euo pipefail

url="${1:-}"
label="${2:-prostředí}"

if [ -z "$url" ]; then
  echo "::error::Deploy webhook pro $label není nastavený (secret COOLIFY_*_WEBHOOK_URL). Image je v GHCR, ale nic se nenasadilo."
  exit 1
fi

response=$(mktemp)
# Deploy endpoint bere POST; na GET vrací 405 s vysvětlením v těle.
status=$(curl --silent --show-error --output "$response" \
  --write-out '%{http_code}' \
  --request POST "$url" \
  --header "Authorization: Bearer ${COOLIFY_TOKEN:-}")

echo "Coolify ($label) odpověděl HTTP $status:"
cat "$response"
echo

case "$status" in
  2*) exit 0 ;;
  *)  echo "::error::Nasazení do $label se nespustilo (HTTP $status)."; exit 1 ;;
esac
