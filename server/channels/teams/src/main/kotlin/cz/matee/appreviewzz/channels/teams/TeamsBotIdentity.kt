package cz.matee.appreviewzz.channels.teams

import cz.matee.appreviewzz.core.model.SecretPayload

/**
 * Náš Azure Bot. Je jeden pro celý deployment — v cloudu náš, v self-hostu ten, kterého si
 * provozovatel založil podle dokumentace. Klienti se od sebe liší tenantem v [TeamsInstall],
 * ne vlastním botem; tím padá dnešní ruční zakládání registrace pro každého klienta.
 */
data class TeamsBotIdentity(
    /** `client_id` app registrace; zároveň `aud` v tokenech, které nám Bot Connector posílá. */
    val appId: String,
    val appPassword: SecretPayload,
    /**
     * Tenant, ve kterém je registrace založená. `null` = multi-tenant bot, který si o token
     * říká přes `botframework.com`. Single-tenant registrace (dnešní stav) tady má svůj tenant.
     */
    val tenantId: String? = null,
) {
    /** Autorita, u které se žádá o token pro Bot Connector. */
    fun tokenAuthority(): String = tenantId?.takeIf { it.isNotBlank() } ?: MULTI_TENANT_AUTHORITY

    companion object {
        /** Multi-tenant boti si o token říkají u téhle „virtuální" autority, ne u svého tenantu. */
        const val MULTI_TENANT_AUTHORITY = "botframework.com"
    }
}
