package cz.matee.appreviewzz.core.port

import cz.matee.appreviewzz.core.model.AppId
import cz.matee.appreviewzz.core.model.OrganizationId
import cz.matee.appreviewzz.core.model.Platform
import cz.matee.appreviewzz.core.model.UserId
import kotlin.time.Instant

/** Pouze poloha průchodu; skutečné nastavení zůstává u aplikace a jejích klíčů. */
data class OnboardingProgress(
    val appId: AppId? = null,
    val step: String = "app",
    val preferencesConfirmed: Boolean = false,
    val completed: Boolean = false,
)

data class OnboardingSync(
    val platform: Platform,
    val status: String,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val fetched: Int? = null,
    val message: String? = null,
)

interface OnboardingRepository {
    fun find(
        orgId: OrganizationId,
        userId: UserId,
    ): OnboardingProgress?

    fun save(
        orgId: OrganizationId,
        userId: UserId,
        progress: OnboardingProgress,
    )

    fun syncs(
        orgId: OrganizationId,
        appId: AppId,
    ): List<OnboardingSync>

    /** Atomický lease chrání před opakovaným spuštěním z jiné záložky. */
    fun startSync(
        orgId: OrganizationId,
        appId: AppId,
        platforms: Set<Platform>,
        now: Instant,
    ): Boolean

    fun finishSync(
        orgId: OrganizationId,
        appId: AppId,
        sync: OnboardingSync,
    )
}
