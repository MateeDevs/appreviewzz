#!/usr/bin/env bash
# Spustí nasazení v Coolify přes deploy webhook.
#
# Bez nastaveného webhooku končí úspěchem a jen to řekne — image je publikovaný v GHCR
# a nasadit se dá ručně. Dokud secrets nejsou v repozitáři, nemá smysl kvůli tomu shazovat
# celou pipeline.
#
# Použití: COOLIFY_TOKEN=… trigger-deploy.sh <webhook-url> <popis prostředí>
set -euo pipefail

url="${1:-}"
label="${2:-prostředí}"

if [ -z "$url" ]; then
  echo "Webhook pro $label není nastavený — nasazení přeskočeno, image je v GHCR."
  exit 0
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
