# Práce v repozitáři

- Aktivní vývoj probíhá přímo ve větvi `epic/v2`. Na přání uživatele z 3. 11. 2026 platí tento postup i pro další sessions, dokud jej uživatel nezmění.
- Pro běžnou práci nezakládej feature větve ani PR mezi feature větví a epicem. Po dokončení změn a úspěšných lokálních kontrolách je rovnou commituj a pushuj do `origin/epic/v2`, bez dalšího vyzvání nebo potvrzení uživatele. Hotovou práci nenechávej pouze v lokálním commitu; tento postup platí i pro další sessions, dokud jej uživatel nezmění.
- Commit do `epic/v2` ber jako součást vydání na staging. Push do `origin/epic/v2` automaticky vede k vydání stagingu; další ruční deploy není potřeba. Samotný lokální commit nasazení ještě nespouští.
- Po pushi nesleduj CI kontroly ani na ně nečekej, pokud o to uživatel výslovně nepožádá. Build a nasazení ponech na existující automatizaci s Coolify; neověřuj automaticky pipeline ani dokončení deploye. Tím nejsou dotčené lokální kontroly před commitem podle `CONTRIBUTING.md`.
- Před prací ověř aktuální větev, pracovní strom a stav vzdáleného epicu. Zachovej rozpracované změny; nepoužívej force push.
- Dodržuj kontroly a české Conventional Commits podle `CONTRIBUTING.md`.
