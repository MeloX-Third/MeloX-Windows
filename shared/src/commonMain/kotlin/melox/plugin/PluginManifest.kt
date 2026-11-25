package melox.plugin

import melox.music.model.SourceId
import org.json.JSONObject
import java.net.IDN
import java.net.URI
import java.util.Locale

data class PluginManifest(
    val sourceId: SourceId, val name: String, val version: String, val apiVersion: Int,
    val entry: String, val capabilities: Set<PluginCapability>, val networkDomains: Set<String>,
    val authentication: PluginAuthentication? = null, val author: String? = null, val homepage: String? = null,
) {
    val isMusicSource: Boolean get() = capabilities.any { it in setOf(PluginCapability.Search,
        PluginCapability.Playback, PluginCapability.Lyrics, PluginCapability.Playlists, PluginCapability.Albums,
        PluginCapability.Artists, PluginCapability.Rankings, PluginCapability.HomeFeed,
        PluginCapability.UserLibrary, PluginCapability.Favorites) }
}

data class PluginAuthentication(val type: String, val header: String = "Authorization", val prefix: String = "Bearer ",
    val domains: Set<String> = emptySet(), val oauth: PluginOAuthConfiguration? = null)
data class PluginOAuthConfiguration(val authorizationEndpoint: String, val tokenEndpoint: String,
    val clientId: String, val scopes: List<String>)

enum class PluginCapability(val wireName: String, val methods: Set<String>) {
    Search("search", setOf("search")), Playback("playback", setOf("resolvePlayback")), Lyrics("lyrics", setOf("lyrics")),
    Playlists("playlists", setOf("playlists", "searchPlaylists", "playlistDetail")),
    Albums("albums", setOf("searchAlbums", "albumDetail")), Artists("artists", setOf("searchArtists", "artistDetail")),
    Rankings("rankings", setOf("rankings", "rankingTracks")), HomeFeed("homeFeed", setOf("homeFeed")),
    UserLibrary("userLibrary", setOf("accountSummary", "userPlaylists", "savedAlbums", "followedArtists")),
    Favorites("favorites", setOf("isFavorite", "setFavorite")), Authentication("authentication", setOf("authenticationStatus")),
    Download("download", setOf("resolveDownload")), Resolver("resolver", setOf("resolveFallback"));
}

/** Manifest v1 from Android edc829e. Parsing never authorizes execution. */
object PluginManifestParser {
    private val pathSegment = Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}")

    fun parse(json: String): PluginManifest {
        PluginJson.check(json, 32 * 1024)
        val value = JSONObject(json)
        fun string(key: String): String { require(value.opt(key) is String); return value.getString(key) }
        val source = requireNotNull(SourceId.parse("plugin:${string("id")}"))
        val name = string("name").also { require(it.trim().isNotEmpty() && it.length <= 120) }.trim()
        val version = string("version").also { require(it.length <= 80 && PluginVersion.valid(it)) }
        require(value.opt("apiVersion") is Number && value.getDouble("apiVersion") == 1.0)
        val entry = string("entry").also {
            require(it.length <= 512 && it.endsWith(".js") && it.split('/').all(pathSegment::matches))
        }
        val rawCapabilities = value.getJSONArray("capabilities")
        require(rawCapabilities.length() <= PluginCapability.entries.size)
        val capabilities = buildSet {
            for (i in 0 until rawCapabilities.length()) {
                require(rawCapabilities.opt(i) is String)
                require(add(requireNotNull(PluginCapability.entries.firstOrNull { it.wireName == rawCapabilities.getString(i) })))
            }
        }
        val rawDomains = value.optJSONArray("networkDomains")
        require(!value.has("networkDomains") || rawDomains != null)
        require(rawDomains == null || rawDomains.length() <= 32)
        val domains = buildSet<String> {
            if (rawDomains != null) for (i in 0 until rawDomains.length()) {
                require(rawDomains.opt(i) is String)
                val domain = rawDomains.getString(i)
                val normalized = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
                require(domain == normalized && domain.length <= 253 && '.' in domain &&
                    domain.split('.').all { it.isNotEmpty() && it.length <= 63 } && domain.any { it in 'a'..'z' })
                require(add(domain))
            }
        }
        val authentication = if (value.has("authentication")) {
            require(PluginCapability.Authentication in capabilities)
            parseAuthentication(value.getJSONObject("authentication"), domains)
        } else null
        require(PluginCapability.Authentication !in capabilities || authentication != null)
        val author = if (value.has("author")) string("author").also { require(it.isNotBlank() && it.length <= 120) } else null
        val homepage = if (value.has("homepage")) string("homepage").also {
            val uri = URI(it)
            require(it.length <= 2048 && uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null)
        } else null
        return PluginManifest(source, name, version, 1, entry, capabilities, domains, authentication, author, homepage)
    }

    private fun parseAuthentication(auth: JSONObject, networkDomains: Set<String>): PluginAuthentication {
        fun string(key: String): String { require(auth.opt(key) is String); return auth.getString(key) }
        val type = string("type")
        if (type == "none") return PluginAuthentication("none")
        require(type == "apiToken" || type == "oauth2Pkce")
        val oauthMode = type == "oauth2Pkce"
        val header = if (oauthMode) "Authorization" else string("header")
        val prefix = if (oauthMode) "Bearer " else string("prefix")
        require(header.matches(Regex("[A-Za-z0-9-]{1,80}")) && header.lowercase(Locale.ROOT) !in setOf(
            "host", "connection", "content-length", "transfer-encoding", "proxy-authorization", "proxy-connection", "upgrade", "cookie"))
        require(prefix.length <= 80 && prefix.all { it.code in 32..126 })
        val requested = auth.getJSONArray("domains")
        require(requested.length() in 1..32)
        val domains = (0 until requested.length()).map { require(requested.opt(it) is String); requested.getString(it) }.toSet()
        require(domains.size == requested.length() && domains.all { it in networkDomains })
        val oauth = if (oauthMode) {
            require(listOf("clientSecret", "client_secret", "redirectUri", "grantType").none(auth::has))
            fun endpoint(key: String): String = string(key).also {
                val uri = URI(it)
                require(it.length <= 2048 && uri.scheme == "https" && uri.port in setOf(-1, 443) &&
                    uri.host in networkDomains && uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null)
            }
            val client = string("clientId").also { require(it.isNotBlank() && it.length <= 512 && it.all { c -> c.code in 33..126 }) }
            val rawScopes = auth.getJSONArray("scopes")
            require(rawScopes.length() <= 32)
            val scopes = (0 until rawScopes.length()).map { i ->
                require(rawScopes.opt(i) is String)
                rawScopes.getString(i).also { scope -> require(scope.length in 1..128 && scope.all { it.code in 33..126 && it != '"' && it != '\\' }) }
            }
            require(scopes.distinct().size == scopes.size)
            PluginOAuthConfiguration(endpoint("authorizationEndpoint"), endpoint("tokenEndpoint"), client, scopes)
        } else null
        return PluginAuthentication(type, header, prefix, domains, oauth)
    }
}
