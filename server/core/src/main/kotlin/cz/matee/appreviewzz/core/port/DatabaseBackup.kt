package cz.matee.appreviewzz.core.port

import cz.matee.appreviewzz.core.model.BackupRun

/**
 * Záloha databáze do object storage. Port je tady schválně: naplánovaná úloha v `jobs`
 * o `pg_dump` ani o S3 vědět nemusí, stačí jí „udělej zálohu a řekni, jak dopadla".
 *
 * Implementace nesmí házet — selhání zálohy je normální provozní stav, který se zapisuje
 * do historie a teprve odtud se z něj stává alarm.
 */
fun interface DatabaseBackup {
    fun backupNow(): BackupRun
}
