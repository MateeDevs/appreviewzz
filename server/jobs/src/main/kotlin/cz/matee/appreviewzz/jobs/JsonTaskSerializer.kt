package cz.matee.appreviewzz.jobs

import com.github.kagkarlsson.scheduler.serializer.Serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Payload úloh se ukládá jako JSON, ne javovskou serializací (výchozí chování db-scheduleru).
 *
 * Důvod je provozní: javovský blob v `task_data` nejde přečíst při ladění a rozbije se při
 * přejmenování třídy. JSON přežije refactoring i nasazení nové verze nad frontou, která
 * ještě obsahuje staré úlohy, a v `psql` je vidět, čeho se úloha týká.
 */
object JsonTaskSerializer : Serializer {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    override fun serialize(data: Any?): ByteArray =
        if (data == null) {
            // Tasky bez dat (Void) — knihovna null payload používá běžně.
            ByteArray(0)
        } else {
            json.encodeToString(serializer(data.javaClass), data).toByteArray(Charsets.UTF_8)
        }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> deserialize(
        clazz: Class<T>,
        serializedData: ByteArray,
    ): T =
        if (serializedData.isEmpty()) {
            null as T
        } else {
            json.decodeFromString(serializer(clazz), serializedData.toString(Charsets.UTF_8)) as T
        }
}
