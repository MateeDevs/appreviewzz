package cz.matee.appreviewzz.backup

/**
 * Adresa databáze pro nástroje příkazové řádky. `pg_dump` ani `pg_restore` neumí JDBC URL,
 * takže se z něj musí vytáhnout host, port a jméno databáze.
 *
 * Heslo jde do procesu **proměnnou prostředí `PGPASSWORD`**, nikdy jako argument — argumenty
 * vidí každý, kdo na hostiteli spustí `ps`.
 */
data class PostgresTarget(
    val host: String,
    val port: Int,
    val database: String,
    val user: String,
    val password: String,
) {
    /** Táž databáze, jiné jméno — obnova se vždycky lije vedle, ne přes běžící provoz. */
    fun withDatabase(name: String): PostgresTarget = copy(database = name)

    fun jdbcUrl(): String = "$JDBC_PREFIX//$host:$port/$database"

    override fun toString(): String = "$host:$port/$database"

    companion object {
        private const val JDBC_PREFIX = "jdbc:postgresql:"
        private const val DEFAULT_PORT = 5432

        /** Rozebere `jdbc:postgresql://host[:port]/databáze[?parametry]`. */
        fun fromJdbcUrl(
            jdbcUrl: String,
            user: String,
            password: String,
        ): PostgresTarget {
            require(jdbcUrl.startsWith("$JDBC_PREFIX//")) {
                "Zálohy umí jen JDBC URL ve tvaru $JDBC_PREFIX//host:port/databáze, dostal jsem '$jdbcUrl'"
            }
            val withoutPrefix = jdbcUrl.removePrefix("$JDBC_PREFIX//").substringBefore('?')
            val authority = withoutPrefix.substringBefore('/')
            val database = withoutPrefix.substringAfter('/', "")
            require(authority.isNotBlank() && database.isNotBlank()) {
                "Z JDBC URL '$jdbcUrl' nejde vyčíst host a jméno databáze"
            }

            val host = authority.substringBefore(':')
            val port =
                authority.substringAfter(':', "").let { raw ->
                    if (raw.isBlank()) {
                        DEFAULT_PORT
                    } else {
                        raw.toIntOrNull() ?: throw IllegalArgumentException("Port '$raw' v JDBC URL není číslo")
                    }
                }
            return PostgresTarget(host = host, port = port, database = database, user = user, password = password)
        }
    }
}
