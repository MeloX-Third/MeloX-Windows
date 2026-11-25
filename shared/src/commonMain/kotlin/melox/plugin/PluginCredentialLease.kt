package melox.plugin

import melox.music.model.SourceId

/** Host-only authorization; implementations own platform storage and durable revocation checks. */
internal interface PluginCredentialLease {
    val source: SourceId
    val token: String
    val policy: PluginAuthentication
    val expiresAt: Long?
    fun isCurrent(): Boolean
}
