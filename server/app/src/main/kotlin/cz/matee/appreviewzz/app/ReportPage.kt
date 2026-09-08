package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.ReportSnapshot
import cz.matee.appreviewzz.core.model.ReportWeek
import kotlin.math.roundToInt

/**
 * Veřejná stránka měsíčního reportu (C1).
 *
 * Server-renderované HTML **bez jediného skriptu a bez externího zdroje**: stránka se
 * posílá klientovi, který u nás nemá účet, otevře ji v čemkoli a nejčastěji ji vytiskne
 * do PDF. Tisk řeší `@media print`, ne knihovna na serveru — PDF generované na serveru
 * by znamenalo další závislost kvůli funkci, kterou prohlížeč umí sám.
 *
 * Grafy jsou inline SVG ze stejného důvodu jako v konzoli: pár obdélníků nestojí za
 * knihovnu a bez skriptu se jinak graf nakreslit nedá.
 */
object ReportPage {
    fun render(snapshot: ReportSnapshot): String {
        val t = Texts.of(snapshot.locale)
        return buildString {
            append("<!doctype html>\n")
            append("<html lang=\"${if (snapshot.locale == MessageLocale.CS) "cs" else "en"}\">\n")
            append("<head>\n")
            append("<meta charset=\"utf-8\">\n")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            // Report je soukromý dokument klienta, i když ho odemyká jen token v odkaze.
            append("<meta name=\"robots\" content=\"noindex, nofollow\">\n")
            append("<title>${escape(t.title)} · ${escape(snapshot.appName)}</title>\n")
            append("<style>$CSS</style>\n")
            append("</head>\n<body>\n")

            append("<header>")
            append("<div class=\"org\">${escape(snapshot.organizationName)}</div>")
            append("<h1>${escape(snapshot.appName)}</h1>")
            append("<div class=\"period\">${escape(t.title)} · ${escape(snapshot.periodStart)} – ${escape(snapshot.periodEnd)}</div>")
            append("</header>\n")

            append(summary(snapshot, t))
            append(moodSection(snapshot, t))
            append(topicsSection(snapshot, t))
            append(versionsSection(snapshot, t))
            append(alertsSection(snapshot, t))
            append(territoriesSection(snapshot, t))
            append(repliesSection(snapshot, t))

            append("<footer>")
            append("<p>${escape(t.source)}")
            snapshot.dataSince?.let { append(" ${escape(t.dataSince)} ${escape(it)}.") }
            append("</p>")
            append("</footer>\n")
            append("</body>\n</html>\n")
        }
    }

    private fun summary(
        snapshot: ReportSnapshot,
        t: Texts,
    ): String =
        buildString {
            append("<section class=\"metrics\">")
            metric("${percent(snapshot.positiveShare)} %", t.positive)
            metric(snapshot.reviews.toString(), t.reviews)
            metric(snapshot.avgStars?.let { format(it) } ?: "—", t.avgStars)
            val delta = snapshot.previousNegativeShare?.let { snapshot.negativeShare - it }
            if (delta != null && percent(kotlin.math.abs(delta)) != 0) {
                // Roste podíl nespokojených = nálada jde dolů; znaménko se čte obráceně.
                val direction = if (delta > 0) t.worse else t.better
                metric("${percent(kotlin.math.abs(delta))} b.", direction)
            }
            append("</section>\n")
        }

    private fun StringBuilder.metric(
        value: String,
        label: String,
    ) {
        append("<div class=\"metric\"><div class=\"value\">${escape(value)}</div>")
        append("<div class=\"label\">${escape(label)}</div></div>")
    }

    private fun moodSection(
        snapshot: ReportSnapshot,
        t: Texts,
    ): String {
        val weeks = snapshot.weekly.filter { it.reviews > 0 }
        if (weeks.size < MIN_CHART_POINTS) return ""
        return "<section><h2>${escape(t.mood)}</h2>${SvgCharts.moodBars(weeks)}</section>\n"
    }

    private fun topicsSection(
        snapshot: ReportSnapshot,
        t: Texts,
    ): String {
        if (snapshot.topics.isEmpty()) return ""
        return buildString {
            append("<section><h2>${escape(t.topics)}</h2><table>")
            append("<thead><tr><th>${escape(t.topic)}</th><th>${escape(t.mentions)}</th>")
            append("<th>${escape(t.shareOfReviews)}</th><th>${escape(t.negative)}</th><th>Ø ★</th></tr></thead><tbody>")
            snapshot.topics.forEach { topic ->
                append("<tr><td>${escape(topic.name)}")
                topic.quote?.let { append("<div class=\"quote\">„${escape(it)}“</div>") }
                append("</td><td>${topic.count}</td>")
                append("<td>${percent(topic.share)} %</td>")
                append("<td>${percent(topic.negativeShare)} %</td>")
                append("<td>${topic.avgStars?.let { format(it) } ?: "—"}</td></tr>")
            }
            append("</tbody></table>")
            if (snapshot.improved.isNotEmpty()) {
                val list = snapshot.improved.joinToString(", ") { "${escape(it.name)} (${it.before}× → ${it.after}×)" }
                append("<p class=\"note\">${escape(t.improved)}: $list</p>")
            }
            append("</section>\n")
        }
    }

    private fun versionsSection(
        snapshot: ReportSnapshot,
        t: Texts,
    ): String {
        if (snapshot.versions.isEmpty()) return ""
        return buildString {
            append("<section><h2>${escape(t.versions)}</h2><table>")
            append("<thead><tr><th>${escape(t.version)}</th><th>${escape(t.reviews)}</th>")
            append("<th>Ø ★</th><th>${escape(t.negative)}</th><th>${escape(t.newTopics)}</th></tr></thead><tbody>")
            snapshot.versions.forEach { version ->
                append("<tr><td>${escape(version.version)} <span class=\"muted\">${escape(version.platform)}</span></td>")
                append("<td>${version.reviews}</td>")
                append("<td>${version.avgStars?.let { format(it) } ?: "—"}</td>")
                append("<td>${percent(version.negativeShare)} %</td>")
                append("<td>${version.newTopics.joinToString(", ") { escape(it) }.ifEmpty { "—" }}</td></tr>")
            }
            append("</tbody></table></section>\n")
        }
    }

    private fun alertsSection(
        snapshot: ReportSnapshot,
        t: Texts,
    ): String {
        if (snapshot.alerts.isEmpty()) return ""
        return buildString {
            append("<section><h2>${escape(t.spikes)}</h2><ul>")
            snapshot.alerts.forEach { alert ->
                val what = if (alert.what == "negative") t.negativeReviews else alert.what
                append("<li>${escape(alert.date)}: ${escape(what)} — ${alert.observed}× ")
                append("(${escape(t.usually)} ${format(alert.expected)})</li>")
            }
            append("</ul></section>\n")
        }
    }

    private fun territoriesSection(
        snapshot: ReportSnapshot,
        t: Texts,
    ): String {
        if (snapshot.territories.isEmpty()) return ""
        return buildString {
            append("<section><h2>${escape(t.markets)}</h2><table>")
            append("<thead><tr><th>${escape(t.country)}</th><th>${escape(t.reviews)}</th>")
            append("<th>${escape(t.negative)}</th></tr></thead><tbody>")
            snapshot.territories.forEach {
                append("<tr><td>${escape(it.territory)}</td><td>${it.reviews}</td>")
                append("<td>${percent(it.negativeShare)} %</td></tr>")
            }
            append("</tbody></table></section>\n")
        }
    }

    private fun repliesSection(
        snapshot: ReportSnapshot,
        t: Texts,
    ): String {
        val replies = snapshot.replies
        val share = if (replies.total > 0) replies.replied.toDouble() / replies.total else 0.0
        return buildString {
            append("<section><h2>${escape(t.replies)}</h2><p>")
            append(escape(t.repliedLine(percent(share), replies.replied, replies.total)))
            replies.medianHours?.let { append(" " + escape(t.medianLine(format(it)))) }
            if (replies.uplifted > 0) append(" " + escape(t.upliftLine(replies.uplifted)))
            append("</p></section>\n")
        }
    }

    private fun percent(share: Double): Int = (share * PERCENT).roundToInt()

    private fun format(value: Double): String = String.format(java.util.Locale.ROOT, "%.2f", value)

    /**
     * Escapování je jediná obrana téhle stránky: názvy témat i citáty pocházejí z recenzí,
     * které píšou cizí lidé, a jdou ven bez jakéhokoli sanitizéru mezi tím.
     */
    internal fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private const val PERCENT = 100
    private const val MIN_CHART_POINTS = 2

    /** Texty stránky. Report se generuje v jazyce aplikace, ne prohlížeče příjemce. */
    private class Texts(
        val title: String,
        val positive: String,
        val reviews: String,
        val avgStars: String,
        val better: String,
        val worse: String,
        val mood: String,
        val topics: String,
        val topic: String,
        val mentions: String,
        val shareOfReviews: String,
        val negative: String,
        val improved: String,
        val versions: String,
        val version: String,
        val newTopics: String,
        val spikes: String,
        val negativeReviews: String,
        val usually: String,
        val markets: String,
        val country: String,
        val replies: String,
        val source: String,
        val dataSince: String,
        private val repliedTemplate: String,
        private val medianTemplate: String,
        private val upliftTemplate: String,
    ) {
        fun repliedLine(
            share: Int,
            replied: Int,
            total: Int,
        ): String = repliedTemplate.replace("{share}", "$share").replace("{replied}", "$replied").replace("{total}", "$total")

        fun medianLine(hours: String): String = medianTemplate.replace("{hours}", hours)

        fun upliftLine(count: Int): String = upliftTemplate.replace("{count}", "$count")

        companion object {
            fun of(locale: MessageLocale): Texts =
                when (locale) {
                    MessageLocale.CS ->
                        Texts(
                            title = "Rozbor recenzí",
                            positive = "spokojených",
                            reviews = "recenzí s textem",
                            avgStars = "průměr hvězd",
                            better = "lepší nálada",
                            worse = "horší nálada",
                            mood = "Nálada v čase",
                            topics = "O čem lidé psali",
                            topic = "Téma",
                            mentions = "Zmínek",
                            shareOfReviews = "Podíl recenzí",
                            negative = "Nespokojených",
                            improved = "Zlepšilo se",
                            versions = "Dopad verzí",
                            version = "Verze",
                            newTopics = "Nová témata",
                            spikes = "Výkyvy",
                            negativeReviews = "záporné recenze",
                            usually = "obvykle",
                            markets = "Trhy",
                            country = "Země",
                            replies = "Odpovídání",
                            source = "Data pocházejí z veřejných recenzí v Google Play a App Store.",
                            dataSince = "Sledujeme je od",
                            repliedTemplate = "Odpovězeno {share} % recenzí ({replied} z {total}).",
                            medianTemplate = "Medián do odpovědi {hours} h.",
                            upliftTemplate = "{count} recenzí po odpovědi přidalo hvězdy.",
                        )

                    MessageLocale.EN ->
                        Texts(
                            title = "Review report",
                            positive = "positive",
                            reviews = "reviews with text",
                            avgStars = "average rating",
                            better = "better mood",
                            worse = "worse mood",
                            mood = "Mood over time",
                            topics = "What people wrote about",
                            topic = "Topic",
                            mentions = "Mentions",
                            shareOfReviews = "Share of reviews",
                            negative = "Negative",
                            improved = "Improved",
                            versions = "Release impact",
                            version = "Version",
                            newTopics = "New topics",
                            spikes = "Spikes",
                            negativeReviews = "negative reviews",
                            usually = "usually",
                            markets = "Markets",
                            country = "Country",
                            replies = "Replies",
                            source = "Data comes from public reviews on Google Play and the App Store.",
                            dataSince = "Tracked since",
                            repliedTemplate = "{share} % of reviews answered ({replied} of {total}).",
                            medianTemplate = "Median time to reply {hours} h.",
                            upliftTemplate = "{count} reviews raised their rating after a reply.",
                        )
                }
        }
    }

    /**
     * Styl je uvnitř stránky, ne v souboru: report se posílá odkazem a musí vypadat stejně
     * i tomu, kdo si ho uloží na disk. Tiskové pravidlo je součást zadání — „Uložit jako PDF"
     * z prohlížeče je tu jediná cesta k PDF.
     */
    private val CSS =
        """
        :root { color-scheme: light; }
        * { box-sizing: border-box; }
        body {
          margin: 0 auto; padding: 2.5rem 1.5rem; max-width: 52rem; background: #fff; color: #14181f;
          font: 15px/1.6 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
        }
        header { border-bottom: 2px solid #14181f; padding-bottom: 1rem; margin-bottom: 2rem; }
        .org { font-size: .85rem; letter-spacing: .06em; text-transform: uppercase; color: #5f6b7a; }
        h1 { font-size: 1.9rem; margin: .2rem 0 .3rem; }
        .period { color: #5f6b7a; }
        h2 { font-size: 1.1rem; margin: 2rem 0 .6rem; }
        section { break-inside: avoid; }
        .metrics { display: flex; gap: 2.5rem; flex-wrap: wrap; margin-bottom: .5rem; }
        .metric .value { font-size: 2rem; font-weight: 600; line-height: 1.1; }
        .metric .label { font-size: .82rem; color: #5f6b7a; }
        table { width: 100%; border-collapse: collapse; margin-top: .4rem; }
        th, td { text-align: left; padding: .5rem .4rem; border-bottom: 1px solid #e3e7ee; vertical-align: top; }
        th:first-child, td:first-child { padding-left: 0; }
        th { font-size: .74rem; text-transform: uppercase; letter-spacing: .04em; color: #5f6b7a; font-weight: 600; }
        .quote { font-size: .85rem; color: #5f6b7a; font-style: italic; margin-top: .15rem; }
        .muted { color: #8a94a3; font-size: .85rem; }
        .note { font-size: .88rem; color: #5f6b7a; }
        ul { padding-left: 1.1rem; }
        li { margin: .2rem 0; }
        svg { width: 100%; height: auto; display: block; }
        footer { margin-top: 2.5rem; padding-top: 1rem; border-top: 1px solid #e3e7ee; font-size: .82rem; color: #8a94a3; }
        @media print {
          body { padding: 0; max-width: none; font-size: 11pt; }
          h2 { margin-top: 1.2rem; }
          @page { margin: 16mm; }
        }
        """.trimIndent()
}

/**
 * Grafy do reportu jako inline SVG. Stejná filozofie jako v konzoli — jenže tady navíc
 * platí, že stránka nesmí spustit ani řádek skriptu, takže jiná možnost ani není.
 */
internal object SvgCharts {
    /** Stohované sloupce po týdnech, normalizované na 100 %: klienta zajímá poměr, ne objem. */
    fun moodBars(weeks: List<ReportWeek>): String {
        val slot = (WIDTH - 2 * MARGIN) / weeks.size
        val barWidth = minOf(slot * BAR_RATIO, MAX_BAR)
        val plot = HEIGHT - MARGIN - BOTTOM
        return buildString {
            append("<svg viewBox=\"0 0 $WIDTH $HEIGHT\" role=\"img\" xmlns=\"http://www.w3.org/2000/svg\">")
            weeks.forEachIndexed { index, week ->
                val centre = MARGIN + slot * index + slot / 2
                val x = centre - barWidth / 2
                val negative = week.negative.toDouble() / week.reviews * plot
                val neutral = week.neutral.toDouble() / week.reviews * plot
                val positive = plot - negative - neutral
                rect(x, MARGIN.toDouble(), barWidth, positive, "#2f9e6f")
                rect(x, MARGIN + positive, barWidth, neutral, "#d2d8e2")
                rect(x, MARGIN + positive + neutral, barWidth, negative, "#cf4a3f")
                append("<text x=\"$centre\" y=\"${HEIGHT - 6}\" text-anchor=\"middle\" ")
                append("font-size=\"10\" fill=\"#8a94a3\">${ReportPage.escape(week.weekStart.substring(5))}</text>")
            }
            append("<line x1=\"$MARGIN\" y1=\"${MARGIN + plot}\" x2=\"${WIDTH - MARGIN}\" y2=\"${MARGIN + plot}\" ")
            append("stroke=\"#e3e7ee\" stroke-width=\"1\"/>")
            append("</svg>")
        }
    }

    private fun StringBuilder.rect(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        fill: String,
    ) {
        if (height <= 0) return
        append("<rect x=\"${round(x)}\" y=\"${round(y)}\" width=\"${round(width)}\" height=\"${round(height)}\" fill=\"$fill\"/>")
    }

    private fun round(value: Double): String = String.format(java.util.Locale.ROOT, "%.1f", value)

    private const val WIDTH = 640
    private const val HEIGHT = 190
    private const val MARGIN = 16
    private const val BOTTOM = 26
    private const val BAR_RATIO = 0.68
    private const val MAX_BAR = 48.0
}
