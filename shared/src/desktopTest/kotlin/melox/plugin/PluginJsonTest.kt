package melox.plugin

import kotlin.test.*

class PluginJsonTest {
    @Test fun boundsApplyBeforeParsingAndIgnoreBracketsInsideStrings() {
        PluginJson.check("[".repeat(32) + "0" + "]".repeat(32))
        PluginJson.check("{\"text\":\"[{}]\\\"\"}")
        assertFailsWith<IllegalArgumentException> { PluginJson.check("[".repeat(33) + "0" + "]".repeat(33)) }
        assertFailsWith<IllegalArgumentException> { PluginJson.check("\"中文\"", 7) }
        PluginJson.check("\"中文\"", 8)
        listOf("{", "}{", "\"unfinished").forEach { raw ->
            assertFailsWith<IllegalArgumentException> { PluginJson.check(raw) }
        }
    }
}
