package app.xccoditer

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WorkspaceTest {
    private lateinit var ws: Workspace

    @Before
    fun setUp() {
        ws = Workspace(Files.createTempDirectory("xcws").toFile())
    }

    @Test
    fun createWriteReadRoundTrip() {
        val f = ws.createFile(ws.root, "hello.py")
        ws.write(f, "print('hi')\n")
        assertEquals("print('hi')\n", ws.read(f))
    }

    @Test
    fun renameAndDelete() {
        val f = ws.createFile(ws.root, "a.txt")
        val g = ws.rename(f, "b.txt")
        assertFalse(f.exists())
        assertTrue(g.exists())
        ws.delete(g)
        assertFalse(g.exists())
    }

    @Test(expected = IOException::class)
    fun duplicateNameRejected() {
        ws.createFile(ws.root, "a.txt")
        ws.createFile(ws.root, "a.txt")
    }

    @Test
    fun pathTraversalRejected() {
        for (bad in listOf("..", "../x", "a/b", "", "  ")) {
            assertThrows(IllegalArgumentException::class.java) { ws.child(ws.root, bad) }
        }
        assertFalse(ws.contains(File(ws.root, "../outside")))
    }

    @Test
    fun cannotDeleteRoot() {
        assertThrows(IllegalArgumentException::class.java) { ws.delete(ws.root) }
    }

    @Test
    fun listSortsFoldersFirst() {
        ws.createFile(ws.root, "b.txt")
        ws.createFolder(ws.root, "zdir")
        ws.createFile(ws.root, "A.txt")
        assertEquals(listOf("zdir", "A.txt", "b.txt"), ws.list(ws.root).map { it.name })
    }
}
