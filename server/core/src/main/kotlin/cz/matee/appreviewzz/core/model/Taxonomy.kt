package cz.matee.appreviewzz.core.model

/**
 * Uzavřená taxonomie témat recenzí (F8), verze [TAXONOMY_VERSION].
 *
 * Uzavřená schválně: otevřené „objevování témat" dává pokaždé jiný seznam a nedá se z něj
 * počítat trend. Popis je anglicky a jde **doslova do promptu** — u klasifikace s uzavřenou
 * taxonomií je popis uzlu to jediné, co doopravdy zvedá recall. Český popisek je jen pro UI.
 *
 * Sourozenci v jedné skupině se dělí podle dimenze, kterou jedna recenze nemůže obkročit;
 * `group` samotná je jen řazení v konzoli, do promptu nejde.
 */
enum class Topic(
    val key: String,
    val group: TopicGroup,
    val descriptionEn: String,
    val labelCs: String,
    val labelEn: String,
) {
    CRASH(
        "crash",
        TopicGroup.STABILITY,
        "App crashes, closes unexpectedly, or won't open at all.",
        "Pády",
        "Crashes",
    ),
    MALFUNCTION(
        "malfunction",
        TopicGroup.STABILITY,
        "A feature does not work, is broken, freezes, or shows errors (not a crash, not slowness).",
        "Nefunguje / chyby",
        "Broken features",
    ),
    DATA_LOSS(
        "data_loss",
        TopicGroup.STABILITY,
        "Lost progress, history, files, settings or account data.",
        "Ztráta dat",
        "Data loss",
    ),
    SLOW(
        "slow",
        TopicGroup.PERFORMANCE,
        "Slow loading, lag, long waits, poor responsiveness.",
        "Pomalost",
        "Slowness",
    ),
    BATTERY_HEAT(
        "battery_heat",
        TopicGroup.PERFORMANCE,
        "Battery drain, overheating, high data or storage usage.",
        "Baterie / zahřívání",
        "Battery and heat",
    ),
    LOGIN(
        "login",
        TopicGroup.ACCOUNT,
        "Cannot sign in or register, verification codes, password reset, two-factor.",
        "Přihlášení",
        "Sign-in",
    ),
    SYNC(
        "sync",
        TopicGroup.ACCOUNT,
        "Syncing across devices, backup/restore, account data not matching.",
        "Synchronizace",
        "Sync",
    ),
    PRICING(
        "pricing",
        TopicGroup.MONEY,
        "Price, subscription cost, free trial, paywall, value for money, cancellation.",
        "Cena a předplatné",
        "Pricing",
    ),
    PAYMENT(
        "payment",
        TopicGroup.MONEY,
        "Payment failed, charged incorrectly, purchase not delivered, restore purchases.",
        "Platby",
        "Payments",
    ),
    REFUND(
        "refund",
        TopicGroup.MONEY,
        "Asking for or complaining about a refund.",
        "Vrácení peněz",
        "Refunds",
    ),
    ADS(
        "ads",
        TopicGroup.MONEY,
        "Too many ads, intrusive ads, ads not skippable, ads after paying.",
        "Reklamy",
        "Ads",
    ),
    USABILITY(
        "usability",
        TopicGroup.UX,
        "Hard to use, confusing, too many steps, missing guidance, unclear behaviour.",
        "Ovládání",
        "Usability",
    ),
    DESIGN(
        "design",
        TopicGroup.UX,
        "Visual design, layout, dark mode, icons, fonts, animations.",
        "Vzhled",
        "Design",
    ),
    LOCALIZATION(
        "localization",
        TopicGroup.UX,
        "Language support, translation quality, regional formats, unsupported country.",
        "Jazyk a lokalizace",
        "Localization",
    ),
    NOTIFICATIONS(
        "notifications",
        TopicGroup.UX,
        "Push notifications missing, too many, wrong timing, cannot configure.",
        "Notifikace",
        "Notifications",
    ),
    CONTENT(
        "content",
        TopicGroup.CONTENT,
        "Quality, accuracy or amount of content/data the app provides (courses, maps, items, articles).",
        "Obsah",
        "Content",
    ),
    FEATURE_REQUEST(
        "feature_request",
        TopicGroup.CONTENT,
        "Asks for a feature or option that does not exist.",
        "Přání funkce",
        "Feature requests",
    ),
    SUPPORT(
        "support",
        TopicGroup.SERVICE,
        "Customer support experience, response time, unhelpful answers.",
        "Podpora",
        "Support",
    ),
    UPDATE(
        "update",
        TopicGroup.SERVICE,
        "Problems that started after an update, or complaints about a changed/removed behaviour.",
        "Po aktualizaci",
        "After update",
    ),
    COMPATIBILITY(
        "compatibility",
        TopicGroup.SERVICE,
        "Device, OS version, tablet/watch support, install or update problems.",
        "Zařízení a kompatibilita",
        "Compatibility",
    ),
    PRIVACY(
        "privacy",
        TopicGroup.SERVICE,
        "Privacy, permissions, data collection, security concerns, scam suspicion.",
        "Soukromí a bezpečnost",
        "Privacy",
    ),
    PRAISE(
        "praise",
        TopicGroup.GENERAL,
        "General positive feedback without a specific feature (great app, love it, recommend).",
        "Pochvala",
        "Praise",
    ),
    OTHER(
        "other",
        TopicGroup.GENERAL,
        "Nothing above fits. Use alone, never together with another topic.",
        "Ostatní",
        "Other",
    ),
    ;

    fun label(locale: MessageLocale): String =
        when (locale) {
            MessageLocale.CS -> labelCs
            MessageLocale.EN -> labelEn
        }

    companion object {
        /**
         * Verze taxonomie. Mění se při **každé** úpravě seznamu témat nebo jejich popisů —
         * výklady se starou verzí se pak přeanalyzují, protože jinak by se v grafu míchala
         * čísla ze dvou různých pravítek.
         */
        const val TAXONOMY_VERSION = "2026-09-v1"

        /** Prefix klíče vlastního tématu aplikace; zbytek je UUID řádku `app_topic`. */
        const val CUSTOM_PREFIX = "custom:"

        private val byKey = entries.associateBy { it.key }

        fun ofKey(key: String): Topic? = byKey[key]

        fun isCustomKey(key: String): Boolean = key.startsWith(CUSTOM_PREFIX)
    }
}

/** Skupina témat. Slouží jen k řazení a seskupení ve výběru v konzoli, do promptu nejde. */
enum class TopicGroup(
    val labelCs: String,
) {
    STABILITY("Stabilita"),
    PERFORMANCE("Výkon"),
    ACCOUNT("Účet"),
    MONEY("Peníze"),
    UX("Používání"),
    CONTENT("Obsah"),
    SERVICE("Služba"),
    GENERAL("Obecné"),
}
