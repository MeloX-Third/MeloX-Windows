package melox.plugin

import kotlin.test.*

class PluginOAuthBrowserLoginTest {
    @Test fun callbackRequiresUniqueStateAndExactlyOneOutcome(): Unit {
        val state = "a".repeat(43)
        assertTrue(PluginOAuthBrowserLogin.validQuery("state=$state&code=code", state))
        assertTrue(PluginOAuthBrowserLogin.validQuery("state=$state&error=access_denied", state))
        for (query in listOf("state=wrong&code=code", "state=$state&code=", "state=$state", "state=$state&code=code&error=error",
            "state=$state&state=$state&code=code", "state=$state&%73tate=$state&code=code", "state=$state&code=%0A",
            "state=$state&code=%", "state=$state&code=" + "x".repeat(8192))) {
            assertFalse(PluginOAuthBrowserLogin.validQuery(query, state), query.take(100))
        }
    }
}
