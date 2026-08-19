package cz.matee.appreviewzz.connectors.googleplay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Odpovědi androidpublisher API v3. Držíme jen pole, která opravdu čteme —
 * `ignoreUnknownKeys` zbytek přejde, takže rozšíření API nás nerozbije.
 */
@Serializable
internal data class ReviewsListResponse(
    val reviews: List<ReviewDto> = emptyList(),
    val tokenPagination: TokenPagination? = null,
)

@Serializable
internal data class TokenPagination(
    val nextPageToken: String? = null,
)

@Serializable
internal data class ReviewDto(
    val reviewId: String,
    val authorName: String? = null,
    val comments: List<CommentDto> = emptyList(),
)

@Serializable
internal data class CommentDto(
    val userComment: UserCommentDto? = null,
    val developerComment: DeveloperCommentDto? = null,
)

@Serializable
internal data class UserCommentDto(
    val text: String? = null,
    val lastModified: TimestampDto? = null,
    val starRating: Int = 0,
    val reviewerLanguage: String? = null,
    val device: String? = null,
    val appVersionName: String? = null,
    val appVersionCode: Int? = null,
    val deviceMetadata: DeviceMetadataDto? = null,
)

@Serializable
internal data class DeviceMetadataDto(
    val productName: String? = null,
    val manufacturer: String? = null,
)

@Serializable
internal data class DeveloperCommentDto(
    val text: String? = null,
    val lastModified: TimestampDto? = null,
)

/** Google posílá čas jako `{"seconds":"1724060000","nanos":0}` — sekundy jako string. */
@Serializable
internal data class TimestampDto(
    val seconds: String? = null,
    val nanos: Int = 0,
)

@Serializable
internal data class ReplyRequest(
    val replyText: String,
)

@Serializable
internal data class ReplyResponse(
    val result: ReplyResultDto? = null,
)

@Serializable
internal data class ReplyResultDto(
    val replyText: String? = null,
    val lastEdited: TimestampDto? = null,
)

@Serializable
internal data class GoogleErrorResponse(
    val error: GoogleErrorDto? = null,
)

@Serializable
internal data class GoogleErrorDto(
    val code: Int = 0,
    val message: String = "",
    val status: String = "",
    @SerialName("errors") val details: List<GoogleErrorDetailDto> = emptyList(),
)

@Serializable
internal data class GoogleErrorDetailDto(
    val reason: String = "",
    val message: String = "",
)
