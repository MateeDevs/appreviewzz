package cz.matee.appreviewzz.channels.slack

import cz.matee.appreviewzz.core.message.MessageCatalog
import cz.matee.appreviewzz.core.message.MessageKey
import cz.matee.appreviewzz.core.message.PlatformRatings
import cz.matee.appreviewzz.core.message.RatingsDigest
import cz.matee.appreviewzz.core.message.ReviewNotification
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.port.ReplyRendering
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Block Kit skládaný z [ReviewNotification]. Zprávy vypadají jako dnes v n8n, ale se třemi
 * rozdíly, které jdou za App Directory a za spolehlivostí:
 *
 * - **Žádná custom emoji.** Dnešní šablona používá `:empty_star:`, což je emoji nahrané do
 *   workspace klienta — v cizím workspace se ukáže jako holý text. Hvězdičky kreslíme
 *   unicodem, takže zpráva vypadá stejně všude.
 * - **Stabilní `block_id` a `action_id`.** n8n si při čtení odpovědi bralo „první blok, který
 *   má hodnotu"; tady se vstup pojmenuje a čte se podle jména.
 * - **Datum jako Slack `<!date^…>`**, takže ho každý vidí ve své zóně. Fallback text zůstává
 *   v zóně appky.
 */
internal object SlackBlocks {
    const val REPLY_BLOCK_ID = "appreviewzz_reply"
    const val REPLY_ACTION_ID = "reply_text"
    const val SUBMIT_ACTION_ID = "submit_reply"

    /** Zpráva s recenzí: obsah + vstup s návrhem + tlačítko. */
    fun review(notification: ReviewNotification): JsonArray =
        JsonArray(content(notification) + input(notification) + submitButton(notification))

    /**
     * Táž zpráva po odeslání odpovědi: vstup i tlačítko jsou pryč, místo nich je vidět, co se
     * odeslalo a kdo to napsal. Skládá se ze stejného obsahu, takže se původní zpráva nemusí
     * číst zpátky ze Slacku — a aplikace tím pádem nepotřebuje scope na historii kanálu.
     */
    fun replied(rendering: ReplyRendering): JsonArray {
        val notification = rendering.notification
        val catalog = notification.catalog
        val author = rendering.authorDisplayName?.takeIf { it.isNotBlank() }
        val stamp =
            listOfNotNull(
                ":white_check_mark: ${escape(catalog[MessageKey.REVIEW_PROCESSED])}",
                author?.let { escape(it) },
                slackDate(rendering.repliedAt.epochSeconds, notification.formattedDate()),
            ).joinToString(" · ")

        return JsonArray(
            content(notification) +
                listOf(
                    section("*${escape(catalog[MessageKey.YOU_REPLIED])}:*"),
                    plainSection(rendering.replyText.take(SECTION_TEXT_LIMIT).ifBlank { "—" }),
                    context(stamp),
                ),
        )
    }

    /** Ověřovací zpráva po `channel test`: krátká, bez formuláře, ať se nepřehlédne s recenzí. */
    fun connectivityCheck(
        catalog: MessageCatalog,
        appName: String,
    ): JsonArray =
        JsonArray(
            listOf(
                section(":white_check_mark: *${escape(catalog[MessageKey.CONNECTION_OK_TITLE])}*"),
                context(escape(catalog.format(MessageKey.CONNECTION_OK_DETAIL, "app" to appName))),
            ),
        )

    /** Hlášení do vlákna, když store odpověď odmítl. */
    fun failure(
        notification: ReviewNotification,
        error: String,
    ): JsonArray {
        val catalog = notification.catalog
        val detail = escape(error.ifBlank { "?" }.take(ERROR_LIMIT))
        return JsonArray(
            listOf(
                section(":warning: *${escape(catalog[MessageKey.REPLY_FAILED_TITLE])}*"),
                section("*${escape(catalog[MessageKey.ERROR_LABEL])}:* ```$detail```"),
            ),
        )
    }

    /**
     * Denní přehled hodnocení. Šablona je pro obě platformy jedna a stejná jako v Teams —
     * v n8n se rozešly čtyři varianty (Slack Matee, Slack Improvio, Teams orchestrátor,
     * Teams jedno-platformový), každá s jinými šipkami a jiným počtem desetinných míst.
     */
    fun ratingsDigest(digest: RatingsDigest): JsonArray {
        val catalog = digest.catalog
        return JsonArray(
            buildList {
                add(
                    buildJsonObject {
                        put("type", "header")
                        putJsonObject("text") {
                            put("type", "plain_text")
                            put("text", "📊 ${catalog[MessageKey.RATINGS_SUMMARY_TITLE]} · ${digest.appName}".take(HEADER_LIMIT))
                            put("emoji", true)
                        }
                    },
                )
                digest.platforms.forEachIndexed { index, part ->
                    if (index > 0) add(buildJsonObject { put("type", "divider") })
                    add(section(platformSection(digest, part)))
                }
                add(context(escape("${catalog[MessageKey.DATE_LABEL]}: ${digest.formattedDate(digest.date)}")))
            },
        )
    }

    private fun platformSection(
        digest: RatingsDigest,
        part: PlatformRatings,
    ): String {
        val catalog = digest.catalog
        val name = if (part.platform == cz.matee.appreviewzz.core.model.Platform.ANDROID) "Android" else "iOS"
        val average = part.average?.let { RatingsDigest.formatRating(it) } ?: "—"
        val change = part.delta
        val delta =
            when {
                part.isFirstRun || change == null -> ""
                else ->
                    "${catalog[MessageKey.DELTA_LABEL]}: " +
                        "${RatingsDigest.trend(change)} ${RatingsDigest.formatDelta(change)}"
            }

        return buildList {
            add("${RatingsDigest.platformEmoji(part.platform)} *$name*")
            add("⭐ *${escape(catalog[MessageKey.TOTAL_LABEL])}*: `$average`   ${escape(delta)}")
            add(RatingsDigest.bar(part.average))
            part.totalCount?.let { add("📈 ${escape(catalog[MessageKey.RATINGS_TOTAL_COUNT_LABEL])}: `$it`") }
            add(newRatingsLine(digest, part))
            // Datum platformy, ne dnešek: Play export bývá o den dva pozadu a tohle je jediné
            // místo, kde je to vidět. Dnešní zpráva to zamlčuje.
            if (part.asOf != digest.date) {
                add("_${escape(catalog[MessageKey.DATE_LABEL])}: ${escape(digest.formattedDate(part.asOf))}_")
            }
        }.joinToString("\n")
    }

    private fun newRatingsLine(
        digest: RatingsDigest,
        part: PlatformRatings,
    ): String {
        val catalog = digest.catalog
        val total = part.newTotal
        if (total == null || part.isFirstRun) return "_${escape(catalog[MessageKey.RATINGS_FIRST_RUN])}_"
        if (total == 0L) return ":new: ${escape(catalog[MessageKey.RATINGS_NO_NEW])}"

        val breakdown =
            part.newRatings
                .takeIf { it.isNotEmpty() }
                ?.let { counts -> (1..MAX_STARS).joinToString(" · ") { stars -> "$stars★: ${counts[stars] ?: 0}" } }
        return listOfNotNull(":new: *${escape(catalog[MessageKey.NEW_RATINGS_TODAY_LABEL])}*: $total", breakdown)
            .joinToString(" · ")
    }

    /** Společná část zprávy: kdo, kolik hvězd, co napsal a k jaké verzi appky. */
    private fun content(notification: ReviewNotification): List<JsonObject> {
        val catalog = notification.catalog
        val header =
            "${platformEmoji(notification.platform)} ${notification.authorName} ${stars(notification.starRating)}"
        return buildList {
            if (notification.isUpdate) {
                add(context(":pencil2: *${escape(catalog[MessageKey.REVIEW_UPDATED])}*"))
            }
            add(
                buildJsonObject {
                    put("type", "header")
                    putJsonObject("text") {
                        put("type", "plain_text")
                        put("text", header.take(HEADER_LIMIT))
                        put("emoji", true)
                    }
                },
            )
            add(section(escape(notification.text).take(SECTION_TEXT_LIMIT)))
            notification.previousReply?.let { previous ->
                val quoted = escape(previous).take(QUOTE_LIMIT).lineSequence().joinToString("\n") { "> $it" }
                add(section("*${escape(catalog[MessageKey.YOU_ALREADY_REPLIED])}*\n$quoted"))
            }
            add(context(footer(notification)))
        }
    }

    private fun input(notification: ReviewNotification): JsonObject =
        buildJsonObject {
            put("type", "input")
            put("block_id", REPLY_BLOCK_ID)
            putJsonObject("element") {
                put("type", "plain_text_input")
                put("action_id", REPLY_ACTION_ID)
                put("multiline", true)
                put("max_length", inputMaxLength(notification))
                notification.suggestedReply?.takeIf { it.isNotBlank() }?.let {
                    put("initial_value", it.take(inputMaxLength(notification)))
                }
            }
            putJsonObject("label") {
                put("type", "plain_text")
                put("text", inputLabel(notification))
                put("emoji", true)
            }
        }

    private fun submitButton(notification: ReviewNotification): JsonObject =
        buildJsonObject {
            put("type", "actions")
            putJsonArray("elements") {
                add(
                    buildJsonObject {
                        put("type", "button")
                        put("action_id", SUBMIT_ACTION_ID)
                        put("style", "primary")
                        put("value", notification.review.id.toString())
                        putJsonObject("text") {
                            put("type", "plain_text")
                            put("text", notification.catalog[MessageKey.SEND])
                            put("emoji", true)
                        }
                    },
                )
            }
        }

    private fun section(markdown: String): JsonObject =
        buildJsonObject {
            put("type", "section")
            putJsonObject("text") {
                put("type", "mrkdwn")
                put("text", markdown)
            }
        }

    /** Text odpovědi je uživatelský vstup — do zprávy patří tak, jak byl napsaný, bez markupu. */
    private fun plainSection(text: String): JsonObject =
        buildJsonObject {
            put("type", "section")
            putJsonObject("text") {
                put("type", "plain_text")
                put("text", text)
                put("emoji", false)
            }
        }

    private fun context(markdown: String): JsonObject =
        buildJsonObject {
            put("type", "context")
            putJsonArray("elements") {
                add(
                    buildJsonObject {
                        put("type", "mrkdwn")
                        put("text", markdown)
                    },
                )
            }
        }

    private fun footer(notification: ReviewNotification): String {
        val catalog = notification.catalog
        val appName = notification.appName.ifBlank { catalog[MessageKey.APP_FALLBACK] }
        val date = slackDate(notification.review.submittedAt.epochSeconds, notification.formattedDate())
        val version = notification.appVersion?.let { "${catalog[MessageKey.VERSION_LABEL]} ${escape(it)}" }
        return listOfNotNull(":iphone: *${escape(appName)}*", date, version).joinToString(" · ")
    }

    /**
     * Datum ve Slack formátu — každý člen týmu ho uvidí ve své zóně. Fallback (část za `|`)
     * je datum v zóně appky pro klienty, kteří dynamické datum neumí vykreslit.
     */
    private fun slackDate(
        epochSeconds: Long,
        fallback: String,
    ): String = "<!date^$epochSeconds^{date_short} {time}|${escape(fallback)}>"

    private fun platformEmoji(platform: Platform): String =
        when (platform) {
            Platform.ANDROID -> "🤖"
            Platform.IOS -> "🍎"
        }

    private fun stars(rating: Int): String {
        val full = rating.coerceIn(0, MAX_STARS)
        return "★".repeat(full) + "☆".repeat(MAX_STARS - full)
    }

    /**
     * Slack vstup pobere 3 000 znaků, App Store odpověď 5 970. Kdo potřebuje delší odpověď,
     * napíše ji v consoli — návrh delší než vstup by Slack odmítl jako neplatné bloky.
     */
    private fun inputMaxLength(notification: ReviewNotification): Int = notification.replyCharLimit.coerceIn(1, INPUT_MAX_LENGTH)

    private fun inputLabel(notification: ReviewNotification): String {
        val catalog = notification.catalog
        val label = catalog.format(MessageKey.SUGGESTED_REPLY_LABEL, "limit" to inputMaxLength(notification))
        val warning = notification.alreadyRepliedWarning?.let { "⚠️ $it\n\n" }.orEmpty()
        return (warning + "✍️ " + label).take(LABEL_LIMIT)
    }

    /** Slack mrkdwn: tyhle tři znaky se musí escapovat, jinak se z textu recenze stane markup. */
    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private const val MAX_STARS = 5
    private const val HEADER_LIMIT = 150
    private const val SECTION_TEXT_LIMIT = 3_000
    private const val QUOTE_LIMIT = 1_000
    private const val LABEL_LIMIT = 2_000
    private const val INPUT_MAX_LENGTH = 3_000
    private const val ERROR_LIMIT = 500
}
