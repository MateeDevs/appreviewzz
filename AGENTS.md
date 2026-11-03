# Práce v repozitáři

- Aktivní vývoj probíhá přímo ve větvi `epic/v2`. Na přání uživatele z 3. 11. 2026 platí tento postup i pro další sessions, dokud jej uživatel nezmění.
- Pro běžnou práci nezakládej feature větve ani PR mezi feature větví a epicem. Změny commituj přímo nad aktuálním `epic/v2`.
- Commit do `epic/v2` ber jako součást vydání na staging. Push do `origin/epic/v2` automaticky spouští build a nasazení stagingu podle `.github/workflows/deploy.yml`; další ruční deploy není potřeba. Po pushi ověř výsledek pipeline a úspěšné nasazení. Samotný lokální commit nasazení ještě nespouští.
- Před prací ověř aktuální větev, pracovní strom a stav vzdáleného epicu. Zachovej rozpracované změny; nepoužívej force push.
- Dodržuj kontroly a české Conventional Commits podle `CONTRIBUTING.md`.
