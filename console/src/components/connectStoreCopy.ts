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
  /*
   * Rovnou na stránku s uživateli, ne na rozcestník Play Console. Developer account do
   * adresy doplňovat neumíme (ID neznáme), ale Google si ho na téhle cestě dosadí sám —
   * `/console/users-and-permissions` přesměruje na `/console/developers/users-and-permissions`
   * a přihlašovací obrazovka cestu nese s sebou v `continue`, takže i nepřihlášený klient
   * skončí na správné stránce. Do samotného dialogu Invite new user zalinkovat nejde.
   */
  consoleUrl: 'https://play.google.com/console/users-and-permissions',

  app: {
    heading: 'Která aplikace?',
    hint: 'Vlož odkaz na stránku appky v Play Storu; package name si vytáhneme sami.',
  },

  /*
   * Rozsah se vybírá dřív než pozvánka, protože rozhoduje o právech, která si klient
   * v Play Console naklikne: recenze stačí přiřadit k jedné aplikaci, hodnocení vydá
   * Google jen účtu s právem na celý účet. Doptat se až u bucketu by znamenalo poslat
   * člověka pozvánku přenastavit.
   */
  scope: {
    heading: 'Co budeme sledovat?',
    lead: 'Podle toho se liší práva, o která si v Play Console řekneme.',
    reviews: {
      title: 'Jen recenze',
      lead: 'Nové recenze chodí do kanálu a odpovídat na ně jde od nás.',
      detail: 'Právo stačí k jedné aplikaci.',
    },
    ratings: {
      title: 'Recenze i hodnocení',
      lead: 'Navíc oficiální hvězdičky z Play Console a jejich historie.',
      detail: 'Vyžaduje právo na celý účet a export z Cloud Storage.',
    },
  },

  invite: {
    heading: 'Pozvěte náš účet do Play Console',
    lead:
      'Účet jsme právě vyrobili — nic v Google Cloudu zakládat nemusíš. Zbývá ho pozvat ' +
      'do Play Console, ať vidí na recenze.',
    emailLabel: 'E-mail účtu k pozvání',
    /*
     * Dvě verze návodu, ne jedna s poznámkou „a pokud chceš i hodnocení, tak…". Klient
     * kliká podle kroků a záložka App permissions versus Account permissions je první
     * rozcestí — přehlédnutá výjimka uprostřed odstavce ho stojí druhé kolo v konzoli.
     */
    steps: {
      reviews: [
        'Klikni na Otevřít Users and permissions — Play Console naskočí rovnou na té stránce.',
        'Dej Invite new user, vlož e-mail výše a přepni se na záložku App permissions.',
        'Vyber svou aplikaci a zaškrtni View app information (read-only) a Reply to reviews.',
        'Potvrď tlačítkem Invite user.',
      ],
      ratings: [
        'Klikni na Otevřít Users and permissions — Play Console naskočí rovnou na té stránce.',
        'Dej Invite new user, vlož e-mail výše a zůstaň na záložce Account permissions.',
        'Zaškrtni View app information and download bulk reports (read-only) a Reply to reviews.',
        'Nech to na celý účet, nepřepínej se na App permissions — u jedné aplikace hodnocení nedostaneme.',
        'Potvrď tlačítkem Invite user.',
      ],
    },
    note: {
      reviews:
        'Víc práv nepotřebujeme a nechceme: číst recenze a odpovídat na ně. Účet pozvánku ' +
        'nepotvrzuje, takže se po odeslání nic dalšího nečeká.',
      ratings:
        'Práva zůstávají čtecí — jen platí pro celý účet, protože oficiální hodnocení Google ' +
        'jinak nevydá. Účet pozvánku nepotvrzuje, takže se po odeslání nic dalšího nečeká.',
    },
    openConsole: 'Otevřít Users and permissions',
  },

  check: {
    heading: 'Kontrola pozvánky',
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
      'Poslední krok. Hodnocení přes API nechodí — čtou se z exportu, který Play Console ' +
      'ukládá do Cloud Storage. Bez něj bereme hvězdičky z veřejného výpisu ve storu.',
    steps: [
      'Play Console → Download reports → Reviews.',
      'Nahoře je Copy Cloud Storage URI — zkopíruj ho.',
    ],
    fieldLabel: 'Cloud Storage URI',
    fieldHint: 'Začíná gs://pubsite_prod_rev_…',
    note:
      'U bucketu ještě přidej našemu účtu roli Storage Object Viewer — oprávnění z Play ' +
      'Console na Cloud Storage nedosáhnou.',
    invalid: 'Čekáme adresu, která začíná gs://.',
    checking: 'Zkoušíme, jestli na bucket dosáhneme…',
    /** Uložit adresu, na kterou účet zatím nedosáhne, je legitimní: práva se dají doplnit potom. */
    saveAnyway: 'Uložit i tak',
    retry: 'Zkusit znovu',
    saved: 'Adresu jsme uložili. Hodnocení se rozjedou, jakmile bude export dostupný.',
    later: 'Doplnit to jde i později v detailu aplikace.',
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
