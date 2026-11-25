package melox.plugin

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.*

class PluginPublicDnsTest {
    @Test fun privateReservedAndTransitionAddressesCannotBecomeSocketTargets() {
        val blocked = listOf("0.0.0.0", "10.0.0.1", "127.0.0.1", "169.254.169.254", "172.16.0.1", "192.168.0.1",
            "100.64.0.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255",
            "::1", "::", "fc00::1", "fe80::1", "2001:db8::1", "2002:7f00:1::", "2001:20::1", "3fff::1")
        blocked.forEach { assertFalse(PluginPublicAddresses.accepts(InetAddress.getByName(it)), it) }
        listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111", "2001:4860:4860::8888").forEach {
            assertTrue(PluginPublicAddresses.accepts(InetAddress.getByName(it)), it)
        }
    }

    @Test fun validatesEveryLookupAndRejectsMixedAnswersBeforeReturningAny() {
        val public = InetAddress.getByName("8.8.8.8")
        var answers = listOf(public)
        val dns = PluginPublicDns(setOf("api.example.com")) { answers }
        assertEquals(answers, dns.lookup("api.example.com"))
        for (bad in listOf("127.0.0.1", "10.1.2.3", "169.254.169.254", "::1", "fd00::1", "fe80::1", "2001:db8::1")) {
            answers = listOf(public, InetAddress.getByName(bad))
            assertFailsWith<UnknownHostException> { dns.lookup("api.example.com") }
        }
        answers = listOf(InetAddress.getByName("2606:4700:4700::1111"))
        assertEquals(answers, dns.lookup("api.example.com"))
    }

    @Test fun undeclaredHostsNeverReachResolverAndEmptyResultsFail() {
        var calls = 0
        val dns = PluginPublicDns(setOf("api.example.com")) { calls++; emptyList() }
        assertFailsWith<IllegalArgumentException> { dns.lookup("other.example.com") }
        assertEquals(0, calls)
        assertFailsWith<UnknownHostException> { dns.lookup("api.example.com") }
    }
}
