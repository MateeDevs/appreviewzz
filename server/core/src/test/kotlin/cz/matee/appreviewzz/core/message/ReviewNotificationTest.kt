package cz.matee.appreviewzz.core.message

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.MessageLocale
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.Review
import cz.matee.appreviewzz.core.model.ReviewId
import cz.matee.appreviewzz.core.model.ReviewState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val SUBMITTED = Instant.parse("2026-08-19T12:30:00Z")

private fun review(
    platform: Platform = Platform.IOS,
    author: String? = "Jana N.",
    title: String? = "Nejde přihlášení",
    body: String? = "Po updatu   se\nnedostanu dál.",
    developerResponse: String? = null,
): Review =
    Review(
        id = ReviewId(Uuid.random()),
        orgId = OrganizationId(Uuid.random()),
        appId = AppId(Uuid.random()),
        platform = platform,
        storeReviewId = "store-1",
        authorName = author,
        starRating = 2,
        title = title,
        body = body,
        locale = "cs",
        territory = "CZE",
        appVersion = "3.2.1",
        device = null,
        submittedAt = SUBMITTED,
        storeUpdatedAt = null,
        contentHash = "hash",
        developerResponseBody = developerResponse,
        developerResponseAt = null,
        state = ReviewState.NEW,
        firstSeenAt = SUBMITTED,
        lastSeenAt = SUBMITTED,
    )

private fun notification(
    review: Review = review(),
    locale: MessageLocale = MessageLocale.CS,
    timezone: String = "Europe/Prague",
): ReviewNotification =
    ReviewNotification(
        review = review,
        appName = "IsleGrow",
        timezone = timezone,
        locale = locale,
        suggestedReply = null,
    )

class ReviewNotificationTest :
    FunSpec({
        test("spojí titulek s textem a sjednotí bílé znaky") {
            notification().text shouldBe "Nejde přihlášení: Po updatu se nedostanu dál."
        }

        test("recenze bez textu i titulku dostane lokalizovanou náhradu") {
            notification(review(title = null, body = "  ")).text shouldBe "Chybí text recenze."
            notification(review(title = null, body = null), MessageLocale.EN).text shouldBe "No review text."
        }

        test("prázdný autor propadne na lokalizovaný fallback") {
            notification(review(author = " ")).authorName shouldBe "Uživatel"
            notification(review(author = null), MessageLocale.EN).authorName shouldBe "User"
        }

        test("varování o dřívější odpovědi se liší podle platformy") {
            notification(review()).alreadyRepliedWarning shouldBe null
            notification(review(platform = Platform.ANDROID, developerResponse = "Díky!"))
                .alreadyRepliedWarning shouldContain "přidána do konverzace"
            notification(review(platform = Platform.IOS, developerResponse = "Díky!"))
                .alreadyRepliedWarning shouldContain "přepíše tu předchozí"
        }

        test("limit odpovědi je limit storu") {
            notification(review(platform = Platform.ANDROID)).replyCharLimit shouldBe 350
            notification(review(platform = Platform.IOS)).replyCharLimit shouldBe 5_970
        }

        test("datum je v zóně appky a jazyce kanálu") {
            // 12:30 UTC = 14:30 v Praze; stejná recenze v Los Angeles je pořád stejný okamžik.
            notification().formattedDate() shouldContain "14:30"
            notification(timezone = "America/Los_Angeles").formattedDate() shouldContain "5:30"
        }

        test("nesmyslná zóna appky zprávu nepoloží") {
            notification(timezone = "Marsu/Olympus").formattedDate() shouldContain "12:30"
        }
    })

class MessageCatalogTest :
    FunSpec({
        test("dosadí placeholder") {
            MessageCatalog
                .of(MessageLocale.CS)
                .format(MessageKey.SUGGESTED_REPLY_LABEL, "limit" to 350) shouldBe "Návrh odpovědi (max 350 znaků)"
            MessageCatalog
                .of(MessageLocale.EN)
                .format(MessageKey.SUGGESTED_REPLY_LABEL, "limit" to 350) shouldBe "Suggested reply (max 350 characters)"
        }

        test("nedosazený placeholder je chyba, ne text ve Slacku") {
            shouldThrow<IllegalArgumentException> {
                MessageCatalog.of(MessageLocale.CS).format(MessageKey.APP_HAS_NEW_REVIEW)
            }
        }

        test("každý klíč má obě jazykové varianty neprázdné") {
            MessageKey.entries.forEach { key ->
                MessageLocale.entries.forEach { locale ->
                    withClue("$key/$locale") { MessageCatalog.of(locale)[key].isNotBlank() shouldBe true }
                }
            }
        }
    })
