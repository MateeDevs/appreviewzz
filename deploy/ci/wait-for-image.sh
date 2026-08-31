#!/usr/bin/env bash
# Počká, až bude v GHCR image pro daný commit.
#
# Do produkce se pushuje kdykoli — klidně vteřinu po pushi do vývojové větve. Image ale
# vzniká až buildem, který běží minuty. Bez tohohle čekání by promote na ten rozdíl selhal
# a musel se ručně pouštět znovu, což je práce pro stroj, ne pro člověka.
#
# Nečeká ale slepě, jinak by se z pojistky stalo zdržení: když build téhož commitu selže
# nebo když pro ten commit žádný build neexistuje, skončí hned a s vysvětlením.
#
# Použití: GH_TOKEN=… wait-for-image.sh <image> <sha> <větev, na které se staví>
set -euo pipefail

image="${1:?chybí image}"
sha="${2:?chybí sha}"
branch="${3:?chybí větev}"

deadline=$(( SECONDS + ${WAIT_TIMEOUT_MINUTES:-25} * 60 ))
# Commit, který na vývojové větvi nikdy nebyl, se nikdy stavět nezačne. Bez tolerance na
# rozjezd by ale spadl i legitimní případ, kdy se pushlo do obou větví během jedné vteřiny
# a GitHub run ještě nestihl založit.
grace=$(( SECONDS + 180 ))
# Doběhnutý build bez image znamená, že se něco pokazilo jinde než v buildu samotném —
# čekat na to plný timeout nemá smysl.
after_success=0

while :; do
  if docker buildx imagetools inspect "$image:$sha" >/dev/null 2>&1; then
    echo "Image $image:$sha je v GHCR."
    exit 0
  fi

  run=$(gh run list --workflow=deploy.yml --branch="$branch" --limit 30 \
    --json headSha,status,conclusion,url \
    --jq "[.[] | select(.headSha == \"$sha\")] | first // empty")

  if [ -n "$run" ]; then
    status=$(jq -r '.status' <<<"$run")
    conclusion=$(jq -r '.conclusion // ""' <<<"$run")
    url=$(jq -r '.url' <<<"$run")

    if [ "$status" = "completed" ]; then
      if [ "$conclusion" != "success" ]; then
        echo "::error::Build commitu ${sha:0:7} na větvi $branch skončil jako $conclusion. Do produkce nejde revize, která se nepostavila. $url"
        exit 1
      fi
      after_success=$(( after_success + 1 ))
      if [ "$after_success" -ge 3 ]; then
        echo "::error::Build commitu ${sha:0:7} doběhl úspěšně, ale $image:$sha v GHCR není. $url"
        exit 1
      fi
    fi
    echo "Build $status, image zatím ne — čekám. $url"
  elif [ "$SECONDS" -gt "$grace" ]; then
    echo "::error::Pro commit ${sha:0:7} neexistuje build na větvi $branch. Do produkce patří jen revize, které prošly stagingem."
    exit 1
  else
    echo "Build ještě nezaložený — čekám."
  fi

  if [ "$SECONDS" -gt "$deadline" ]; then
    echo "::error::Image $image:$sha se neobjevil ani po ${WAIT_TIMEOUT_MINUTES:-25} minutách."
    exit 1
  fi
  sleep 20
done
