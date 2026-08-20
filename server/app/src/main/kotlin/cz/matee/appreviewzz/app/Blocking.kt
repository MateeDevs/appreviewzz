package cz.matee.appreviewzz.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Blokující práce mimo vlákna, na kterých Ktor obsluhuje požadavky.
 *
 * U databáze je to hygiena, u přihlášení nutnost: argon2id je schválně pomalý (desítky
 * milisekund a 19 MiB paměti) a pár souběžných loginů by jinak zadrhlo celý server.
 */
suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
