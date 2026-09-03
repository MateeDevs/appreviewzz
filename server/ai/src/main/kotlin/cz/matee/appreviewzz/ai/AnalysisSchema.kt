package cz.matee.appreviewzz.ai

import cz.matee.appreviewzz.core.model.OverallSentiment
import cz.matee.appreviewzz.core.model.ReviewType
import cz.matee.appreviewzz.core.model.Topic
import cz.matee.appreviewzz.core.model.TopicSentiment
import cz.matee.appreviewzz.core.model.Urgency
import cz.matee.appreviewzz.core.port.CustomTopic
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JSON schema pro structured output (F8).
 *
 * Schema, ne prosba v promptu: klíč tématu je `enum`, takže model nemá jak vrátit téma,
 * které neexistuje, a parsování nemusí hádat. Enum se skládá **per aplikace** — vlastní
 * témata klienta jsou v něm jako `custom:<uuid>`.
 *
 * Schválně mělké a krátké: dokumentace Gemini varuje, že velká nebo hluboce zanořená schémata
 * API odmítá, a tohle je schema, které se posílá u každé dávky.
 */
object AnalysisSchema {
    /** Kolik témat smí model přiřadit jedné recenzi. Víc než čtyři už je seznam, ne výklad. */
    const val MAX_TOPICS = 4

    fun of(customTopics: List<CustomTopic>): JsonObject {
        val topicKeys = Topic.entries.map { it.key } + customTopics.map { it.key }
        return obj(
            "type" to JsonPrimitive("object"),
            "properties" to
                obj(
                    "items" to
                        obj(
                            "type" to JsonPrimitive("array"),
                            "items" to item(topicKeys),
                        ),
                ),
            "required" to strings(listOf("items")),
        )
    }

    private fun item(topicKeys: List<String>): JsonObject =
        obj(
            "type" to JsonPrimitive("object"),
            "properties" to
                obj(
                    "id" to obj("type" to JsonPrimitive("string")),
                    "sentiment" to enumOf(OverallSentiment.entries.map { it.name }),
                    "type" to enumOf(ReviewType.entries.map { it.name }),
                    "urgency" to enumOf(Urgency.entries.map { it.name }),
                    "language" to obj("type" to JsonPrimitive("string")),
                    "translation" to obj("type" to JsonPrimitive("string")),
                    "topics" to
                        obj(
                            "type" to JsonPrimitive("array"),
                            "minItems" to JsonPrimitive(1),
                            "maxItems" to JsonPrimitive(MAX_TOPICS),
                            "items" to
                                obj(
                                    "type" to JsonPrimitive("object"),
                                    "properties" to
                                        obj(
                                            "key" to enumOf(topicKeys),
                                            "sentiment" to enumOf(TopicSentiment.entries.map { it.name }),
                                            "quote" to obj("type" to JsonPrimitive("string")),
                                        ),
                                    "required" to strings(listOf("key", "sentiment")),
                                ),
                        ),
                ),
            "required" to strings(listOf("id", "sentiment", "type", "urgency", "topics")),
        )

    private fun enumOf(values: List<String>): JsonObject = obj("type" to JsonPrimitive("string"), "enum" to strings(values))

    private fun strings(values: List<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

    private fun obj(vararg entries: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject = JsonObject(entries.toMap())
}
