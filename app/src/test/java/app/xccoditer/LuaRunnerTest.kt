package app.xccoditer

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class LuaRunnerTest {
    private fun run(src: String, stop: () -> Boolean = { false }): Pair<Int, List<String>> {
        val lines = ArrayList<String>()
        val code = LuaRunner.run(src, "@t.lua", { lines += it }, stop)
        return code to lines
    }

    @Test fun printsAndComputes() {
        val (c, l) = run("print('hi', 1+2)\nprint(('abc'):upper(), math.floor(2.7))")
        assertEquals(0, c)
        assertEquals(listOf("hi\t3", "ABC\t2"), l)
    }

    @Test fun runtimeErrorReported() {
        val (c, l) = run("local x = nil\nprint(x.y)")
        assertEquals(1, c)
        assertTrue(l.toString(), l.last().startsWith("lua: ") && l.last().contains(":2") && l.last().contains("nil"))
    }

    @Test fun syntaxErrorReported() {
        val (c, l) = run("x = = 1")
        assertEquals(1, c)
        assertTrue(l.last().startsWith("lua:"))
    }

    @Test fun sandboxHasNoFilesOrOs() {
        val (c, l) = run("print(os, io, require, dofile, loadfile, package, debug, luajava)")
        assertEquals(0, c)
        assertEquals("nil\tnil\tnil\tnil\tnil\tnil\tnil\tnil", l.single())
    }

    @Test(timeout = 20000) fun stopInterruptsInfiniteLoop() {
        val stop = AtomicBoolean(false)
        Thread { Thread.sleep(300); stop.set(true) }.start()
        val (c, l) = run("local i = 0\nwhile true do i = i + 1 end", { stop.get() })
        assertEquals(130, c)
        assertEquals("[stopped]", l.last())
    }

    @Test fun outputWithoutTrailingNewlineIsFlushed() {
        val (_, l) = run("io = nil; print('a'); print('b')")
        assertEquals(listOf("a", "b"), l)
    }
}
