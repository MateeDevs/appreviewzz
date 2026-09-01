/**
 * Texty dialogů pro napojení storů — všechny na jednom místě.
 *
 * Není to lokalizace (console je česky), je to **údržba**: tyhle věty popisují cizí konzole,
 * které se mění bez ohlášení, a upravit je po zjištění z reálného účtu musí jít bez čtení
 * komponenty. Proto sem patří i názvy položek v Play Console a v App Store Connect přesně
 * tak, jak se v nich zobrazují — klient je hledá očima.
 */

export const googlePlayCopy = {
  title: 'Připojit Google Play',
  consoleUrl: 'https://play.google.com/console/',

  app: {
    heading: 'Která aplikace?',
    hint: 'Vlož odkaz na stránku appky v Play Storu; package name si vytáhneme sami.',
  },

  invite: {
    heading: 'Pozvěte náš účet do Play Console',
    lead:
      'Účet jsme právě vyrobili — nic v Google Cloudu zakládat nemusíš. Zbývá ho pozvat ' +
      'do Play Console, ať vidí na recenze.',
    emailLabel: 'E-mail účtu k pozvání',
    steps: [
      'V Play Console otevři Users and permissions → Invite new user.',
      'Vlož e-mail výše a přepni se na záložku App permissions.',
      'Vyber svou aplikaci a zaškrtni View app information (read-only) a Reply to reviews.',
      'Potvrď tlačítkem Invite user.',
    ],
    note:
      'Víc práv nepotřebujeme a nechceme: číst recenze a odpovídat na ně. Účet pozvánku ' +
      'nepotvrzuje, takže se po odeslání nic dalšího nečeká.',
    openConsole: 'Otevřít Play Console',
  },

  check: {
    heading: 'Kontrola přístupu',
    lead: 'Až pozvánku odešleš, zkusíme se do storu přihlásit.',
    checking: 'Zkoušíme přístup…',
    ok: 'Hotovo — recenze začnou chodit při nejbližším stahování.',
    // Google práva propaguje se zpožděním; bez téhle věty to vypadá jako naše chyba.
    background:
      'Pozvánka zatím neprošla. Hlídáme to na pozadí (obvykle minuty, výjimečně až den) — ' +
      'dialog můžeš zavřít, stav se v seznamu aplikací přepne sám.',
    retry: 'Zkontrolovat přístup',
  },

  reporting: {
    heading: 'Historie a oficiální hodnocení',
    lead:
      'Nepovinné. Bez tohohle bereme hodnocení z veřejného výpisu ve storu; s ním čteme ' +
      'oficiální čísla z Play Console včetně rozpadu po hvězdách.',
    steps: [
      'Play Console → Download reports → Reviews.',
      'Nahoře je Copy Cloud Storage URI — zkopíruj ho.',
    ],
    fieldLabel: 'Cloud Storage URI',
    fieldHint: 'Začíná gs://pubsite_prod_rev_…',
    note:
      'Vyžaduje, aby náš účet měl View app information jako account-level (Global) oprávnění ' +
      'a u bucketu roli Storage Object Viewer.',
    invalid: 'Čekáme adresu, která začíná gs://.',
  },
} as const

export const appStoreCopy = {
  title: 'Připojit App Store',
  integrationsUrl: 'https://appstoreconnect.apple.com/access/integrations/api',

  create: {
    heading: 'Vytvořte klíč v App Store Connect',
    lead: 'Apple klíč vydat programově neumí — tenhle krok za tebe udělat nemůžeme.',
    steps: [
      'Otevři Users and Access → Integrations → App Store Connect API → Team Keys.',
      'Klikni na Generate API Key.',
      'Název dej „Appreviewzz — recenze", ať víš, k čemu klíč patří.',
      // O2: role se doladí po testu z reálného dropdownu (viz plán onboardingu).
      'Access nastav na Customer Support; pokud v nabídce není, zvol Admin.',
      'Stáhni si .p8 soubor — Apple ho nabídne jen jednou.',
      'Nahoře na stránce zkopíruj Issuer ID.',
    ],
    warnings: [
      'Stránku Integrations vidí jen Admin a Account Holder. Když ji nevidíš, potřebuješ kolegu.',
      'Když tým App Store Connect API ještě nikdy nepoužil, musí Account Holder nejdřív potvrdit Request Access.',
    ],
    keyName: 'Appreviewzz — recenze',
    open: 'Otevřít App Store Connect',
  },

  upload: {
    heading: 'Nahrajte klíč',
    drop: 'Přetáhni sem soubor AuthKey_XXXXXXXXXX.p8, nebo ho vyber',
    keyIdLabel: 'Key ID',
    keyIdHint: 'Předvyplníme z názvu souboru; deset znaků.',
    issuerLabel: 'Issuer ID',
    issuerHint: 'Nahoře na stránce Integrations, ve tvaru 69a6de70-….',
    issuerInvalid: 'Issuer ID vypadá jinak — čekáme UUID (osm-čtyři-čtyři-čtyři-dvanáct znaků).',
    notAKey: 'Tohle nevypadá jako .p8 klíč od Applu — chybí v něm řádek BEGIN PRIVATE KEY.',
    looksLikeJson: 'Tohle je service account z Google Play, ne klíč z App Store Connect.',
    submit: 'Nahrát klíč',
  },

  pick: {
    heading: 'Vyberte aplikace',
    lead: 'Tohle jsou aplikace, na které klíč dosáhne. Vyber ty, jejichž recenze chceš sledovat.',
    empty: 'Klíč nevidí žádnou aplikaci. Nejspíš patří jinému týmu.',
    moderation: 'Odpovědi na recenze schvaluje Apple, zveřejnění trvá 24–48 hodin.',
    submit: 'Sledovat vybrané',
    existing: 'už sledujeme',
  },
} as const

/** Key ID je v názvu staženého souboru — opisovat ho ručně je zbytečný krok navíc. */
export function keyIdFromFileName(fileName: string): string | null {
  return /^AuthKey_([A-Z0-9]{10})\.p8$/i.exec(fileName)?.[1] ?? null
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function isIssuerId(value: string): boolean {
  return UUID.test(value.trim())
}
