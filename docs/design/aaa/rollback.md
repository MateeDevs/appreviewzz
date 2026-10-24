# Staging a návrat

Cíl: https://console.staging.appreviewzz.com. Staging větev je `epic/v2`, podle znovu přečteného
`.github/workflows/deploy.yml`. `production` ani tag `prod` se nemění.

Předchozí staging revize: `59ac0175be621b9f636581d8a74dae1760c1fa13`.
Image `ghcr.io/mateedevs/appreviewzz:59ac0175be621b9f636581d8a74dae1760c1fa13` byl 24. 10. 2026
ověřen přes `docker buildx imagetools inspect`, obě architektury dostupné. Digest manifestu:
`sha256:9311d745df56ac49c4dbdac20199fa3dec4a36ec640e5bd66c1a0dcd1edce29b`.
[Předchozí úspěšný deploy](https://github.com/MateeDevs/appreviewzz/actions/runs/37684736265).

Při kritické regresi revertovat integrační redesign commit(y) na `epic/v2`, standardně pushnout,
sledovat nový CI/deploy a ověřit `/health/live` proti SHA revertu. Žádný force push. Žádné DB
migrace tento redesign nepřidává, takže návrat je kompatibilní. Pokud je potřeba okamžitý návrat
image v Coolify, použít uvedený neměnný tag/digest pro staging resource a následně srovnat větev
standardním revertem. Produkční resource ani `prod` nepřesměrovávat.

Nasazení a CI jsou nezávislé: redesign se integruje až po lokálních kontrolách a zeleném PR CI.
Samotný zelený deploy neprokazuje přihlášený smoke; ten je v validation.md oddělený.

Integrační commit redesignu: `6886d7df50e219ee6b638ec1b3b0a40c58957a18` (merge PR #3).
Konkrétní návrat na větvi od aktuálního origin/epic/v2: `git revert -m 1 6886d7df50e219ee6b638ec1b3b0a40c58957a18`,
kontroly, PR do epic/v2 a standardní merge/deploy. Vytvoří nový SHA; health/live se musí shodovat
s tímto novým SHA, nikoli s původním před redesignem. Nouzový image tag/digest je uveden výše.
