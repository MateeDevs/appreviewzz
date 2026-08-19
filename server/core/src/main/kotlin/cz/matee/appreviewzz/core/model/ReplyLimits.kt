package cz.matee.appreviewzz.core.model

/**
 * Nejdelší odpověď, kterou store přijme. Číslo potřebuje víc míst než jen konektor —
 * vstup ve Slacku podle něj nastavuje `max_length` a AI podle něj dostává instrukci —
 * a rozejít se nesmí, jinak vznikne návrh, který store odmítne.
 *
 * Apple dokumentuje limit 5 970 znaků (ne kulatých 5 000, jak se traduje), Google Play 350.
 */
val Platform.storeReplyMaxLength: Int
    get() =
        when (this) {
            Platform.ANDROID -> 350
            Platform.IOS -> 5_970
        }
