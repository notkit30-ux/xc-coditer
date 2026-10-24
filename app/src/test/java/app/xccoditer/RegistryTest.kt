package app.xccoditer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class RegistryTest {
    private val manifest = OfficialExtensions.all[1]
    private val bytes = manifest.toByteArray()
    private val entry = RegistryEntry("xc.python-snippets", "Python snippets", "1.0.0", "XC CODITER", "d", listOf("python"), "extensions/x.json", Registry.sha256Hex(bytes))
    private fun store() = ExtensionStore(Files.createTempDirectory("xcr").toFile())

    @Test fun installsWhenHashMatches() {
        val st = store()
        Registry.install(entry, "https://example.com/r/index.json", st) { url -> assertEquals("https://example.com/r/extensions/x.json", url); bytes }
        assertEquals(1, st.list().size)
    }
    @Test fun tamperedFileIsRejected() {
        val st = store()
        val e = assertThrows(IllegalArgumentException::class.java) { Registry.install(entry, "https://example.com/i.json", st) { (manifest + " ").toByteArray() } }
        assertTrue(e.message!!.contains("Integrity"))
        assertTrue(st.list().isEmpty())
    }
    @Test fun listingMismatchIsRejected() {
        val st = store()
        assertThrows(IllegalArgumentException::class.java) { Registry.install(entry.copy(id = "xc.other"), "https://example.com/i.json", st) { bytes } }
    }
    @Test fun onlyHttps() {
        assertThrows(IllegalArgumentException::class.java) { Registry.requireHttps("http://example.com/i.json") }
        assertThrows(IllegalArgumentException::class.java) { Registry.resolve("https://example.com/i.json", "http://evil.com/x.json") }
        assertThrows(IllegalArgumentException::class.java) { Registry.resolve("https://example.com/i.json", "file:///etc/passwd") }
    }
    @Test fun parsesAndSearchesIndex() {
        val idx = """{"registry":1,"extensions":[{"id":"a.b","name":"Alpha","version":"1.0.0","publisher":"p","description":"x","languages":["python"],"url":"a.json","sha256":"${"a".repeat(64)}"}]}"""
        val list = Registry.parseIndex(idx)
        assertEquals(1, Registry.search(list, "pyth").size)
        assertEquals(0, Registry.search(list, "zzz").size)
        assertThrows(IllegalArgumentException::class.java) { Registry.parseIndex(idx.replace("a".repeat(64), "short")) }
        assertThrows(IllegalArgumentException::class.java) { Registry.parseIndex("""{"registry":2,"extensions":[]}""") }
    }
    @Test fun shippedRegistryFilesAreConsistent() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "registry/index.json").exists()) dir = dir.parentFile
        if (dir == null) return // registry folder not reachable from the test working directory
        val entries = Registry.parseIndex(File(dir, "registry/index.json").readText())
        assertTrue(entries.isNotEmpty())
        for (e in entries) {
            val data = File(dir, "registry/" + e.url).readBytes()
            assertEquals(e.id, e.sha256, Registry.sha256Hex(data))
            val x = ExtensionManifest.parse(String(data))
            assertEquals(e.id, x.id); assertEquals(e.version, x.version)
        }
    }
}
