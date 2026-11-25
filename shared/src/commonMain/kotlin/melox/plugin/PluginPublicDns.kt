package melox.plugin

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

/** Return the exact validated answers to the socket layer; validate every lookup and every answer. */
internal class PluginPublicDns(
    private val domains: Set<String>,
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        require(hostname in domains) { "PERMISSION_DENIED" }
        val addresses = resolve(hostname).toList()
        if (addresses.isEmpty() || addresses.any { !PluginPublicAddresses.accepts(it) }) {
            throw UnknownHostException("PERMISSION_DENIED")
        }
        return addresses
    }
}

/** Android edc829e network policy, shared by plugin API, media and artwork brokers. */
internal object PluginPublicAddresses {
    fun accepts(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 255 }
        if (bytes.size == 16) {
            return bytes[0] in 0x20..0x3f &&
                !(bytes[0] == 0x20 && bytes[1] == 0x01 &&
                    ((bytes[2] == 0x0d && bytes[3] == 0xb8) || bytes[2] < 2)) &&
                !(bytes[0] == 0x20 && bytes[1] == 0x02) && !(bytes[0] == 0x3f && bytes[1] == 0xff)
        }
        if (bytes.size != 4) return false
        val a = bytes[0]; val b = bytes[1]; val c = bytes[2]
        return a !in setOf(0, 10, 127) && a < 224 &&
            !(a == 100 && b in 64..127) && !(a == 169 && b == 254) &&
            !(a == 172 && b in 16..31) && !(a == 192 && (b == 168 || b == 0 || b == 88 && c == 99)) &&
            !(a == 198 && (b in 18..19 || b == 51 && c == 100)) && !(a == 203 && b == 0 && c == 113)
    }
}
