package app.xccoditer

import org.junit.Assert.*
import org.junit.Test

class DebugParseTest {
    @Test fun parsesPause() {
        val p = DebugParse.pause("""{"reason":"breakpoint","line":4,"stack":[{"function":"f","line":4},{"function":"<module>","line":9}],"variables":[{"name":"a","type":"int","value":"1"}],"final":true}""")!!
        assertEquals(4, p.line)
        assertEquals(listOf("f", "<module>"), p.stack.map { it.function })
        assertEquals(DebugVar("a", "int", "1"), p.variables.single())
        assertTrue(p.final)
    }
    @Test fun badJsonIsNull() { assertNull(DebugParse.pause("nope")) }
    @Test fun variablesJson() { assertEquals(1, DebugParse.variablesJson("""[{"name":"x","type":"str","value":"'a'"}]""").size) }
    @Test fun lineParsing() {
        assertEquals(setOf(3, 10, 12), DebugParse.lines("3, 10 12"))
        assertEquals(emptySet<Int>(), DebugParse.lines(""))
        assertNull(DebugParse.lines("3,x"))
        assertNull(DebugParse.lines("0"))
    }
}
