package cz.matee.appreviewzz.app

import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.ReportReplies
import cz.matee.appreviewzz.core.model.ReportSnapshot
import cz.matee.appreviewzz.core.model.ReportTerritory
import cz.matee.appreviewzz.core.model.ReportTopic
import cz.matee.appreviewzz.core.model.ReportVersion
import cz.matee.appreviewzz.core.model.ReportWeek
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

class ReportPageTest :
    StringSpec({
        "report má čitelnou alternativu grafu, českou a anglickou verzi, tisk a bezpečný obsah bez JS" {
            val snapshot =
                ReportSnapshot(
                    appName = "Atlas <script>alert(1)</script>",
                    organizationName = "Studio & tým",
                    periodStart = "2026-09-01",
                    periodEnd = "2026-09-30",
                    reviews = 24,
                    avgStars = 4.2,
                    positiveShare = .75,
                    neutralShare = .125,
                    negativeShare = .125,
                    previousNegativeShare = .2,
                    byPlatform = mapOf("IOS" to 24),
                    weekly =
                        listOf(
                            ReportWeek("2026-09-01", 9, 1, 2, 12, 4.1),
                            ReportWeek("2026-09-08", 9, 2, 1, 12, 4.3),
                        ),
                    replies = ReportReplies(24, 18, 4.5, 2),
                    dataSince = "2026-01-01",
                )
            val html = ReportPage.render(snapshot)
            html shouldNotContain "<script>"
            html shouldContain "&lt;script&gt;"
            html shouldContain "Studio &amp; tým"
            html shouldContain "@media print"
            html shouldContain "size: A4"
            html shouldContain "Týden od"
            html shouldContain "2026-09-08</td><td>12"
            html shouldContain "0–100 %"
            ReportPage.render(snapshot.copy(locale = MessageLocale.EN)) shouldContain "Week of"
            // Opt-in artefakt pro browser kontrolu skutečného serverového rendereru.
            System.getenv("AAA_REPORT_ARTIFACT")?.let {
                File(it).writeText(
                    ReportPage.render(
                        snapshot.copy(
                            appName = "Atlas",
                            topics =
                                listOf(
                                    ReportTopic("Přihlášení", 6, .25, .8, 2.1, "GROWING", "Po aktualizaci se nemohu přihlásit."),
                                    ReportTopic("Rychlost", 4, .167, .5, 3.1, "FALLING"),
                                ),
                            territories = listOf(ReportTerritory("CZ", 18, .11), ReportTerritory("SK", 6, .167)),
                            versions = listOf(ReportVersion("4.8.0", "IOS", 12, 4.3, .1, listOf("Přihlášení"))),
                        ),
                    ),
                )
            }
        }
    })
