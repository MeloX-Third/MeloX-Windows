package melox.plugin

import java.security.MessageDigest
import java.util.Base64
import org.json.JSONObject

/** Platform-independent public-client PKCE and token parsing. Tokens never use data-class toString. */
internal object PluginOAuthContract {
    class Tokens(val accessToken: String, val refreshToken: String?, val expiresAt: Long?)
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun parseTokens(value: JSONObject, now: Long = System.currentTimeMillis()): Tokens {
        fun token(name: String): String {
            require(value.opt(name) is String)
            return value.getString(name).also { text -> require(text.length in 1..4096 && text.all { it.code in 33..126 }) }
        }
        require(value.opt("token_type") is String && value.getString("token_type").equals("Bearer", true)) { "unsupported token type" }
        val expiry = if (value.has("expires_in")) {
            require(value.opt("expires_in") is Number)
            val seconds = value.getDouble("expires_in")
            require(seconds.isFinite() && seconds >= 1 && seconds <= 315_360_000 && seconds == kotlin.math.floor(seconds))
            Math.addExact(now, seconds.toLong() * 1000)
        } else null
        return Tokens(token("access_token"), if (value.has("refresh_token")) token("refresh_token") else null, expiry)
    }
}
