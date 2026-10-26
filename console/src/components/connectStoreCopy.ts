/** Verified against official documentation on 2026-10-26; sources in add-app-wizard/research.md. */
export const googlePlayCopy = {
  consoleUrl: 'https://play.google.com/console/users-and-permissions',
  scope: {
    heading: 'Co chcete z Google Play získat?',
    lead: 'Rozsah určuje oprávnění, která účtu udělíte v Play Console.',
    reviews: {
      title: 'Jen recenze',
      lead: 'Komentované recenze a možnost odpovídat z appreviewzz.',
      detail:
        'Přístup pouze k vybrané aplikaci. API poskytuje recenze vytvořené nebo změněné za poslední týden.',
    },
    ratings: {
      title: 'Recenze i hodnocení',
      lead: 'Navíc oficiální hodnocení a dostupná historie z exportů.',
      detail:
        'Vyžaduje globální čtení informací o aplikacích a hromadných reportů. Odpovídání lze omezit na tuto aplikaci.',
    },
  },
  invite: {
    heading: 'Pozvěte účet do Google Play Console',
    lead: 'Spravovaný účet zajistíme my. Do Play Console jej pozve vlastník nebo správce s oprávněním Admin na úrovni účtu. Přístup do Google Cloudu nepotřebujete.',
    steps: {
      reviews: [
        'Otevřete Users and permissions v Play Console. Zkontrolujte, že jste ve správném vývojářském účtu, a zvolte Invite new users.',
        'Do pole Email address vložte e-mail účtu uvedený výše.',
        'Na záložce App permissions zvolte Add app, vyberte svou aplikaci a potvrďte Apply.',
        'Pro tuto aplikaci povolte View app information (read-only) a Reply to reviews. Ostatní oprávnění ponechte vypnutá.',
        'Odešlete pozvání tlačítkem Invite user. Poté se vraťte sem a ověřte přístup.',
      ],
      ratings: [
        'Otevřete Users and permissions v Play Console, zkontrolujte vývojářský účet a zvolte Invite new users.',
        'Do pole Email address vložte e-mail účtu uvedený výše.',
        'Na záložce Account permissions povolte View app information and download bulk reports (read-only). Toto čtecí právo musí platit pro celý účet, aby byly dostupné exporty.',
        'Na záložce App permissions přidejte svou aplikaci a povolte pro ni Reply to reviews. Zápisové právo tak omezíte na tuto aplikaci.',
        'Potvrďte Invite user. Vraťte se sem a ověřte přístup; adresu exportů doplníte v samostatném kroku.',
      ],
    },
    note: 'Reply to reviews dovoluje čtení recenzí přes API i zveřejnění odpovědi. Připojení tedy není pouze čtecí. Tato kontrola žádnou odpověď neodesílá.',
  },
  reporting: {
    heading: 'Připojte exporty hodnocení',
    lead: 'Cloud Storage URI je adresa soukromé složky s exporty Play Console. Díky ní můžeme číst oficiální hodnocení a dostupnou historii; samotné API recenzí je neposkytuje.',
    steps: [
      'V Play Console otevřete Download reports → Statistics. U přehledu hodnocení zvolte Copy Cloud Storage URI. Stejný vývojářský bucket najdete i v Download reports → Reviews.',
      'Vložte celou adresu níže. Přijímáme i URI s cestou k reportu; použijeme jeho bucket.',
      'V Users and permissions otevřete pozvaný účet. V Account permissions musí mít View app information and download bulk reports (read-only) pro celý účet. Přístup k těmto exportům se řídí Play Console; další roli v Google Cloud IAM nepřidávejte.',
    ],
    fieldLabel: 'Cloud Storage URI',
    fieldHint:
      'Například gs://pubsite_prod_rev_01234567890123456789. Nejde o veřejnou adresu aplikace.',
  },
} as const
export const appStoreCopy = {
  integrationsUrl: 'https://appstoreconnect.apple.com/access/integrations/api',
  create: {
    heading: 'Připravte týmový klíč v App Store Connect',
    lead: 'Potřebujete Team Key s rolí Customer Support. Vytvoří jej Account Holder nebo Admin. Tato role člověka je odlišná od oprávnění samotného klíče.',
    steps: [
      'Otevřete Users and Access → Integrations → App Store Connect API → Team Keys. Zkontrolujte, že jste ve správném týmu.',
      'Pokud tým API ještě nepoužívá, požádejte Account Holder o Request Access a přijetí podmínek. Poté zvolte Generate API Key nebo tlačítko +.',
      'Do Name zadejte „Appreviewzz — recenze“. V Access zvolte Customer Support a potvrďte Generate.',
      'U vytvořeného klíče zvolte Download API Key a stažení potvrďte. Soubor .p8 lze stáhnout pouze jednou. Bezpečně si jej uložte před odchodem ze stránky.',
      'Poznamenejte si Key ID v řádku klíče a Issuer ID v horní části Team Keys. V dalším kroku je vložíte spolu se souborem.',
    ],
    warnings: [
      'Nevidíte Team Keys nebo Customer Support? Zkontrolujte tým a typ klíče. Požádejte Account Holder nebo Admin o vytvoření týmového klíče s touto rolí.',
      'Team Key má přístup ke všem aplikacím v týmu podle své role; při jeho tvorbě jej nelze omezit na jednu aplikaci. Zde vyberete, které aplikace bude appreviewzz sledovat.',
    ],
    keyName: 'Appreviewzz — recenze',
  },
  upload: {
    heading: 'Nahrajte týmový API klíč',
    drop: 'Vyberte soubor .p8 nebo jej přetáhněte sem',
    keyIdLabel: 'Key ID',
    keyIdHint: 'Deset písmen A–Z nebo číslic. Najdete jej v řádku klíče v Team Keys.',
    issuerLabel: 'Issuer ID',
    issuerHint:
      'Zkopírujte Issuer ID z horní části Users and Access → Integrations → App Store Connect API → Team Keys. Příklad formátu: 69a6de70-0000-47e3-e053-5b8c7c11a4d1.',
    issuerInvalid: 'Issuer ID musí mít formát UUID: 8–4–4–4–12 znaků.',
    notAKey:
      'Soubor neobsahuje platný PEM obal privátního klíče .p8. Vyberte původní soubor stažený z App Store Connect.',
    looksLikeJson:
      'Toto je JSON, například klíč Google Play. Pro App Store Connect vyberte soubor .p8.',
  },
} as const
export function keyIdFromFileName(fileName: string): string | null {
  return /^AuthKey_([A-Z0-9]{10})\.p8$/i.exec(fileName)?.[1]?.toUpperCase() ?? null
}
export function isIssuerId(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.trim())
}
