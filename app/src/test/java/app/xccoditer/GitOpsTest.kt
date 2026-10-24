package app.xccoditer

import java.io.File
import java.nio.file.Files
import org.eclipse.jgit.api.Git
import org.junit.Assert.*
import org.junit.Test

class GitOpsTest {
    private val id = GitIdentity("Tester", "t@example.com")
    private fun tmp(): File = Files.createTempDirectory("xcg").toFile()

    private fun repoWithCommit(): File {
        val d = tmp()
        GitOps.init(d)
        File(d, "a.txt").writeText("one\n")
        GitOps.commitAll(d, "first", id)
        return d
    }

    @Test fun statusShowsUntrackedThenClean() {
        val d = tmp(); GitOps.init(d)
        File(d, "a.txt").writeText("x")
        assertTrue(GitOps.status(d).contains("??  a.txt"))
        GitOps.commitAll(d, "add a", id)
        assertTrue(GitOps.status(d).contains("working tree clean"))
        assertTrue(GitOps.log(d).contains("add a"))
    }

    @Test fun commitValidation() {
        val d = repoWithCommit()
        assertThrows(IllegalArgumentException::class.java) { GitOps.commitAll(d, "  ", id) }
        assertThrows(IllegalArgumentException::class.java) { GitOps.commitAll(d, "m", GitIdentity("", "")) }
        val e = assertThrows(IllegalStateException::class.java) { GitOps.commitAll(d, "again", id) }
        assertEquals("Nothing to commit", e.message)
    }

    @Test fun deletionsAreCommitted() {
        val d = repoWithCommit()
        File(d, "a.txt").delete()
        GitOps.commitAll(d, "remove", id)
        assertTrue(GitOps.status(d).contains("working tree clean"))
    }

    @Test fun diffShowsChange() {
        val d = repoWithCommit()
        File(d, "a.txt").writeText("one\ntwo\n")
        val diff = GitOps.diff(d)
        assertTrue(diff, diff.contains("+two"))
        GitOps.commitAll(d, "two", id)
        assertEquals("No changes in tracked files", GitOps.diff(d))
    }

    @Test fun branches() {
        val d = repoWithCommit()
        GitOps.createBranch(d, "feature")
        assertTrue(GitOps.branches(d).contains("* feature"))
        GitOps.checkout(d, "main")
        assertTrue(GitOps.branches(d).contains("* main"))
        assertThrows(IllegalArgumentException::class.java) { GitOps.createBranch(d, "bad name") }
    }

    @Test fun cloneRejectsNonHttps() {
        assertThrows(IllegalArgumentException::class.java) { GitOps.clone("git@github.com:a/b.git", tmp(), null) }
        assertThrows(IllegalArgumentException::class.java) { GitOps.clone("file:///etc", tmp(), null) }
    }

    @Test fun cloneCommitPushPullRoundTrip() {
        val originDir = tmp()
        Git.init().setBare(true).setDirectory(originDir).setInitialBranch("main").call().close()
        val url = originDir.toURI().toString().replace("file:/", "file:///").replace("file:////", "file:///")
        val a = File(tmp(), "a")
        GitOps.clone(url, a, null, allowFile = true)
        File(a, "hello.txt").writeText("hi\n")
        GitOps.commitAll(a, "hello", id)
        assertTrue(GitOps.push(a, null).startsWith("Push finished"))
        val b = File(tmp(), "b")
        GitOps.clone(url, b, null, allowFile = true)
        assertEquals("hi\n", File(b, "hello.txt").readText())
        File(a, "more.txt").writeText("m")
        GitOps.commitAll(a, "more", id)
        GitOps.push(a, null)
        GitOps.pull(b, null)
        assertTrue(File(b, "more.txt").exists())
    }

    @Test fun httpsRemoteNeedsToken() {
        val d = repoWithCommit()
        Git.open(d).use { g ->
            g.repository.config.setString("remote", "origin", "url", "https://github.com/x/y.git")
            g.repository.config.save()
        }
        val e = assertThrows(IllegalArgumentException::class.java) { GitOps.push(d, null) }
        assertTrue(e.message!!.contains("token"))
    }

    @Test fun friendlyMessagesNeverLeakTokens() {
        assertEquals("Write a commit message", GitOps.friendly(IllegalArgumentException("Write a commit message")))
        assertFalse(GitOps.friendly(java.net.UnknownHostException("github.com")).contains("github"))
    }
}
