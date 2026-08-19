package cz.matee.appreviewzz.core.model

/** Store, ze kterého recenze pochází. */
enum class Platform {
    ANDROID,
    IOS,
}

enum class OrgRole {
    /** Zakládá a maže organizaci, spravuje členy. */
    OWNER,

    /** Credentials, aplikace, kanály. */
    ADMIN,

    /** Vidí recenze a odpovídá na ně. */
    MEMBER,
}

enum class CredentialType {
    GP_SERVICE_ACCOUNT,
    ASC_API_KEY,
    SLACK_INSTALL,
    TEAMS_BOT_REF,
}

/** K čemu je credential u konkrétní appky přiřazený — jeden klíč může umět víc věcí. */
enum class CredentialPurpose {
    REVIEWS,
    REPLIES,
    RATINGS,
}

enum class ValidationStatus {
    UNKNOWN,
    VALID,
    INVALID,
}

enum class ChannelType {
    SLACK,
    TEAMS,
}

/** Jazyk zpráv do kanálu; katalog textů přebíráme z dnešního n8n řešení. */
enum class MessageLocale {
    CS,
    EN,
    ;

    val code: String get() = name.lowercase()

    companion object {
        fun ofCode(code: String): MessageLocale =
            entries.firstOrNull { it.code == code.lowercase() }
                ?: error("Unsupported locale '$code'")
    }
}

enum class ReviewState {
    /** Načtená, zatím neodeslaná do kanálu. */
    NEW,

    /** Doručená alespoň do jednoho kanálu. */
    NOTIFIED,

    /** Odpověď publikovaná ve storu. */
    REPLIED,

    /**
     * Autor recenzi po doručení přepsal. Notifikovatelný stav — z trojky se mohla stát pětka
     * a odpovědět se dá znovu. Do NEW se recenze nikdy nevrací, aby šlo odlišit první doručení
     * od aktualizace.
     */
    UPDATED,

    /** Člověk ji odložil. */
    IGNORED,

    /** Starší než `notify_from` appky — uložená kvůli historii, ale bez notifikace. */
    SUPPRESSED,
}

enum class MessageStatus {
    PENDING,
    SENT,
    FAILED,
}

enum class ReplySource {
    SLACK,
    TEAMS,
    CONSOLE,
}

enum class ReplyStatus {
    PENDING,
    PUBLISHED,
    FAILED,
}

enum class RatingSource {
    ASC_LISTING,
    ITUNES_LOOKUP,
    GP_CSV,
    GP_SCRAPE,
}

enum class ActorType {
    /** Přihlášený uživatel console. */
    USER,

    /** Člověk ze Slacku/Teams, kterého známe jen podle chat identity. */
    CHAT,

    /** Scheduler, ingest, konektory. */
    SYSTEM,
}

/** Co se v recenzi změnilo mezi dvěma pozorováními — podklad pro text notifikace o update. */
enum class ReviewChange {
    RATING,
    TEXT,
    APP_VERSION,
    DEVELOPER_RESPONSE,
}

/** Výsledek jednoho běhu zálohy. Záznam vzniká až po doběhnutí, běžící záloha řádek nemá. */
enum class BackupStatus {
    SUCCEEDED,
    FAILED,
}
