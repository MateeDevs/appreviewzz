package cz.matee.appreviewzz.core.model

import java.security.MessageDigest

/** SHA-256 v hexu. Používá se na otisky obsahu (dedup recenzí, deduplikace odpovědí). */
fun sha256Hex(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
