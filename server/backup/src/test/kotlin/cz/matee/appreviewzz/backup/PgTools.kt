package cz.matee.appreviewzz.backup

/**
 * Klientské nástroje Postgresu na stroji, kde běží testy. V CI je instaluje workflow (a když
 * chybí, drill musí spadnout — proto se v CI test nepřeskakuje), lokálně je typicky mít
 * nemusíš: `brew install libpq` nebo `apt install postgresql-client-17`.
 *
 * Verze klienta musí být aspoň taková jako verze serveru — `pg_dump` starší než databáze
 * zálohu odmítne udělat, což je přesně ta chyba, kterou chceme odhalit v testu, ne v provozu.
 */
object PgTools {
    private const val REQUIRED_MAJOR = 17

    val pgDump: String = System.getenv("PG_DUMP_PATH") ?: "pg_dump"
    val pgRestore: String = System.getenv("PG_RESTORE_PATH") ?: "pg_restore"

    val runningInCi: Boolean = !System.getenv("CI").isNullOrBlank()

    val available: Boolean by lazy { majorVersion(pgDump) >= REQUIRED_MAJOR && majorVersion(pgRestore) >= REQUIRED_MAJOR }

    val explanation: String
        get() =
            "Testy zálohování potřebují pg_dump a pg_restore verze $REQUIRED_MAJOR+ " +
                "(macOS: brew install libpq, Debian: postgresql-client-$REQUIRED_MAJOR); " +
                "cestu jde přepsat proměnnými PG_DUMP_PATH a PG_RESTORE_PATH"

    private fun majorVersion(binary: String): Int =
        try {
            val process = ProcessBuilder(binary, "--version").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            Regex("(\\d+)").find(output.substringAfter("PostgreSQL)"))?.value?.toInt() ?: 0
        } catch (error: java.io.IOException) {
            println("$binary není k dispozici: ${error.message}")
            0
        }
}
