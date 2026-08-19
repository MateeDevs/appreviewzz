package cz.matee.appreviewzz.connectors.appstore

import kotlinx.serialization.Serializable

/**
 * App Store Connect API mluví JSON:API — data, relationships, included.
 * Odpovědi vývojáře přicházejí v `included`, pokud se o ně požádá `include=response`.
 */
@Serializable
internal data class CustomerReviewsResponse(
    val data: List<CustomerReviewDto> = emptyList(),
    val included: List<IncludedResourceDto> = emptyList(),
    val links: PagedLinksDto? = null,
)

@Serializable
internal data class PagedLinksDto(
    val next: String? = null,
)

@Serializable
internal data class CustomerReviewDto(
    val id: String,
    val attributes: CustomerReviewAttributesDto? = null,
    val relationships: CustomerReviewRelationshipsDto? = null,
)

@Serializable
internal data class CustomerReviewAttributesDto(
    val rating: Int = 0,
    val title: String? = null,
    val body: String? = null,
    val reviewerNickname: String? = null,
    val createdDate: String? = null,
    val territory: String? = null,
)

@Serializable
internal data class CustomerReviewRelationshipsDto(
    val response: ReviewResponseRelationshipDto? = null,
)

@Serializable
internal data class ReviewResponseRelationshipDto(
    val data: ResourceIdentifierDto? = null,
)

@Serializable
internal data class ResourceIdentifierDto(
    val type: String,
    val id: String,
)

@Serializable
internal data class IncludedResourceDto(
    val type: String,
    val id: String,
    val attributes: ReviewResponseAttributesDto? = null,
)

@Serializable
internal data class ReviewResponseAttributesDto(
    val responseBody: String? = null,
    val lastModifiedDate: String? = null,
    val state: String? = null,
)

@Serializable
internal data class AppStoreVersionsResponse(
    val data: List<AppStoreVersionDto> = emptyList(),
    val links: PagedLinksDto? = null,
)

@Serializable
internal data class AppStoreVersionDto(
    val id: String,
    val attributes: AppStoreVersionAttributesDto? = null,
)

@Serializable
internal data class AppStoreVersionAttributesDto(
    val versionString: String? = null,
    val appStoreState: String? = null,
    val platform: String? = null,
    val createdDate: String? = null,
)

@Serializable
internal data class CreateResponseRequest(
    val data: CreateResponseData,
) {
    companion object {
        fun of(
            reviewId: String,
            body: String,
        ): CreateResponseRequest =
            CreateResponseRequest(
                CreateResponseData(
                    attributes = CreateResponseAttributes(responseBody = body),
                    relationships =
                        CreateResponseRelationships(
                            review = CreateResponseReview(ResourceIdentifierDto("customerReviews", reviewId)),
                        ),
                ),
            )
    }
}

@Serializable
internal data class CreateResponseData(
    val type: String = "customerReviewResponses",
    val attributes: CreateResponseAttributes,
    val relationships: CreateResponseRelationships,
)

@Serializable
internal data class CreateResponseAttributes(
    val responseBody: String,
)

@Serializable
internal data class CreateResponseRelationships(
    val review: CreateResponseReview,
)

@Serializable
internal data class CreateResponseReview(
    val data: ResourceIdentifierDto,
)

@Serializable
internal data class CreateResponseResult(
    val data: CreatedResponseDto? = null,
)

@Serializable
internal data class CreatedResponseDto(
    val id: String,
    val attributes: ReviewResponseAttributesDto? = null,
)

@Serializable
internal data class AscErrorResponse(
    val errors: List<AscErrorDto> = emptyList(),
)

@Serializable
internal data class AscErrorDto(
    val status: String = "",
    val code: String = "",
    val title: String = "",
    val detail: String = "",
)
