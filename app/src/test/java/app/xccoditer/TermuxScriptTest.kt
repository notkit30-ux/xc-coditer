package app.xccoditer

import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class TermuxScriptTest {
    @Test fun scriptRecreatesFilesAndRuns() {
        val s = TermuxScript.build("my proj!", listOf("main.c" to "int main(){}".toByteArray(), "src/a.h" to "x".toByteArray()), "clang 'main.c' -o xc_out && ./xc_out")
        assertTrue(s.contains("xc-run/myproj\""))
        assertTrue(s.contains("printf %s '" + Base64.getEncoder().encodeToString("int main(){}".toByteArray()) + "' | base64 -d > 'main.c'"))
        assertTrue(s.contains("mkdir -p 'src'"))
        assertTrue(s.trimEnd().endsWith("./xc_out"))
    }
    @Test fun quotingBlocksInjection() {
        assertEquals("'a'\\''b'", TermuxScript.q("a'b"))
        val cmd = TermuxScript.commandFor("it's; rm -rf ~.c")!!
        assertTrue(cmd, cmd.contains("'it'\\''s; rm -rf ~.c'"))
        assertThrows(IllegalArgumentException::class.java) { TermuxScript.build("p", listOf("../evil" to ByteArray(1)), "true") }
        assertThrows(IllegalArgumentException::class.java) { TermuxScript.build("p", listOf("/etc/x" to ByteArray(1)), "true") }
    }
    @Test fun commandsPerLanguage() {
        assertEquals("clang 'a.c' -o xc_out && ./xc_out", TermuxScript.commandFor("a.c"))
        assertTrue(TermuxScript.commandFor("d/Main.java")!!.endsWith("java -cp . 'Main'"))
        assertEquals("go run 'x.go'", TermuxScript.commandFor("x.go"))
        assertEquals("php 'i.php'", TermuxScript.commandFor("i.php"))
        assertNull(TermuxScript.commandFor("notes.txt"))
    }
    @Test fun installHints() {
        assertTrue(TermuxScript.hint("bash: clang: command not found")!!.contains("pkg install clang"))
        assertTrue(TermuxScript.hint("bash: javac: command not found")!!.contains("openjdk-17"))
        assertNull(TermuxScript.hint("error: expected ';'"))
    }
    @Test fun collectRespectsLimitsAndSkips() {
        val d = Files.createTempDirectory("xct").toFile()
        File(d, "a.c").writeText("x"); File(d, "sub").mkdirs(); File(d, "sub/b.c").writeText("y")
        File(d, ".git").mkdirs(); File(d, ".git/x").writeText("z"); File(d, "bin").writeBytes(byteArrayOf(1, 0, 2))
        assertEquals(listOf("a.c", "sub/b.c"), TermuxScript.collect(d)!!.map { it.first }.sorted())
        File(d, "big.txt").writeBytes(ByteArray(TermuxScript.MAX_BYTES + 1) { 65 })
        assertNull(TermuxScript.collect(d))
    }
}
