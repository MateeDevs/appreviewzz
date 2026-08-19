package cz.matee.appreviewzz.persistence

import cz.matee.appreviewzz.core.port.Transactor
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

/**
 * Exposed transakce. Vnořené volání se připojí ke stávající transakci, takže repozitáře
 * mohou svoje zápisy obalovat samy a use-case je přesto zvládne spojit do jedné jednotky práce.
 */
class ExposedTransactor(
    private val database: ExposedDatabase,
) : Transactor {
    override fun <T> transaction(block: () -> T): T = transaction(database) { block() }
}
