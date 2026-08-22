## Co se mění a proč

<!-- Proč je důležitější než co: diff řekne, co se změnilo, ale ne, proti čemu to stojí. -->

## Jak je to ověřené

<!-- Který test to pokrývá. Když to test pokrýt nejde, napiš, jak jsi to zkusil ručně. -->

## Kontrolní seznam

- [ ] `./gradlew build` prochází (build zahrnuje testy i ktlint)
- [ ] U změny v konzoli i `npm run lint` a `npm run build`
- [ ] Migrace databáze je **dopředu kompatibilní** — starý kontejner musí přežít nasazení nové migrace
- [ ] Nic nového neteče do logu: tajemství chodí jako `SecretPayload`
- [ ] Nová cesta v API má `orgId` v každém dotazu (multi-tenancy) a sedí pod `requireSession`
- [ ] Rozhodnutí, které se těžko vrací, má ADR v `docs/adr/`
