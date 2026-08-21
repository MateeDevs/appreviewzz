package cz.matee.appreviewzz.core.port

import cz.matee.appreviewzz.core.message.RatingsDigest
import cz.matee.appreviewzz.core.message.ReviewNotification
import cz.matee.appreviewzz.core.model.ChannelType
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.SecretPayload
import kotlin.time.Instant

/**
 * Kam se zpráva doručuje a čím se k tomu autorizujeme. Credential se rozbaluje z vaultu
 * až těsně před odesláním, stejně jako u store konektorů.
 */
data class ChannelTarget(
    /** Slack channel ID (`C0123…`), resp. Teams conversation ID. */
    val conversationId: String,
    val credential: SecretPayload,
)

/**
 * Odkaz na doručenou zprávu. Podle něj se zpráva později upravuje („✅ odpovězeno") a podle
 * něj se příchozí interakce páruje zpět na recenzi.
 */
data class PostedMessage(
    val conversationId: String,
    /** Slack `ts`, resp. Teams activity ID. */
    val messageId: String,
)

/** Obsah ověřovací zprávy. Jazyk se bere z kanálu, ne z organizace — stejně jako u recenzí. */
data class ConnectivityNotice(
    val appName: String,
    val locale: MessageLocale,
)

/** Co se dopisuje do zprávy poté, co odpověď odešla do storu. */
data class ReplyRendering(
    val notification: ReviewNotification,
    val replyText: String,
    /** Kdo odpověď odeslal — ve Slacku jméno z workspace, v consoli jméno uživatele. */
    val authorDisplayName: String?,
    val repliedAt: Instant,
)

/** Druh selhání kanálu; řídí retry stejně jako [StoreErrorKind] u storů. */
enum class ChannelErrorKind {
    /** Token je neplatný nebo mu chybí scope — chce to člověka, ne retry. */
    AUTH,

    /** Kanál neexistuje, bot v něm není, zpráva už zmizela. */
    NOT_FOUND,

    /** Zprávu odmítl kvůli obsahu (moc dlouhý blok, špatný formát) — retry nepomůže. */
    INVALID_REQUEST,

    RATE_LIMITED,

    TRANSIENT,
}

class ChannelException(
    val kind: ChannelErrorKind,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    val isRetryable: Boolean get() = kind == ChannelErrorKind.RATE_LIMITED || kind == ChannelErrorKind.TRANSIENT
}

/**
 * Kanál, do kterého se doručují recenze (plán §5.5). Slack a Teams se liší jen tímhle
 * rozhraním — doručovací use-case ani scheduler o nich nevědí nic konkrétního.
 */
interface NotificationChannel {
    val type: ChannelType

    /** Pošle recenzi s předvyplněným návrhem odpovědi a tlačítkem k odeslání. */
    suspend fun postReview(
        target: ChannelTarget,
        notification: ReviewNotification,
    ): PostedMessage

    /**
     * Přepíše doručenou zprávu do stavu „odpovězeno": vstup i tlačítko zmizí a místo nich je
     * vidět odeslaná odpověď. Tím se z kanálu stane historie, ne seznam nedodělků.
     */
    suspend fun markReplied(
        target: ChannelTarget,
        message: PostedMessage,
        rendering: ReplyRendering,
    )

    /**
     * Pošle do kanálu zprávu „propojení funguje". Slouží k ověření hned po nastavení: token,
     * scopes i členství bota se jinak poznají až tím, že první recenze nedorazí — a to je ta
     * nejhorší chvíle, kdy to zjišťovat.
     */
    suspend fun postConnectivityCheck(
        target: ChannelTarget,
        notice: ConnectivityNotice,
    ): PostedMessage

    /**
     * Denní přehled hodnocení (F4). Je to jediná zpráva, kterou kanál posílá sám od sebe —
     * proto nemá formulář ani tlačítko a nic se k ní později nedopisuje.
     */
    suspend fun postRatingsDigest(
        target: ChannelTarget,
        digest: RatingsDigest,
    ): PostedMessage

    /**
     * Nahlásí selhání publikace **do vlákna pod zprávou**. Původní zpráva zůstává i s formulářem,
     * aby to šlo hned zkusit znovu.
     */
    suspend fun reportFailure(
        target: ChannelTarget,
        message: PostedMessage,
        notification: ReviewNotification,
        error: String,
    )
}
