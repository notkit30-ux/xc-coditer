package app.xccoditer

import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.EmptyCommitException
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator

data class GitIdentity(val name: String, val email: String)
data class GitAuth(val user: String, val token: String)

/** Thin wrapper over JGit. Blocking: call from a background thread. Tokens are never logged or shown. */
object GitOps {
    private const val MAX_DIFF_CHARS = 100_000

    fun isRepo(dir: File) = File(dir, ".git").exists()

    private fun creds(a: GitAuth?) = a?.let { UsernamePasswordCredentialsProvider(it.user.ifBlank { "token" }, it.token) }
    private fun open(dir: File): Git = Git.open(dir)

    fun init(dir: File) {
        dir.mkdirs()
        Git.init().setDirectory(dir).setInitialBranch("main").call().close()
    }

    fun clone(url: String, dir: File, auth: GitAuth?, allowFile: Boolean = false): String {
        val u = url.trim()
        require(u.startsWith("https://") || (allowFile && u.startsWith("file://"))) { "Only https:// repository URLs are supported" }
        Git.cloneRepository().setURI(u).setDirectory(dir).setCredentialsProvider(creds(auth)).call().use {
            // Cloning an empty repository leaves JGit on "master"; GitHub's default is "main".
            if (it.repository.resolve("HEAD") == null) it.repository.updateRef(Constants.HEAD).link("refs/heads/main")
            return "Cloned into ${dir.name} (branch ${it.repository.branch})"
        }
    }

    fun status(dir: File): String = open(dir).use { git ->
        val st = git.status().call()
        val rows = ArrayList<String>()
        st.added.sorted().forEach { rows += "A   $it" }
        st.changed.sorted().forEach { rows += "M   $it" }
        st.removed.sorted().forEach { rows += "D   $it" }
        st.modified.sorted().forEach { rows += " M  $it" }
        st.missing.sorted().forEach { rows += " D  $it" }
        st.untracked.sorted().forEach { rows += "??  $it" }
        st.conflicting.sorted().forEach { rows += "U   $it  (merge conflict)" }
        "On branch ${git.repository.branch}\n" + if (rows.isEmpty()) "Nothing to commit, working tree clean" else rows.joinToString("\n")
    }

    /** Stages everything (including deletions) and commits. */
    fun commitAll(dir: File, message: String, id: GitIdentity): String {
        require(message.isNotBlank()) { "Write a commit message" }
        require(id.name.isNotBlank() && id.email.isNotBlank()) { "Set your Git name and email first (Git settings)" }
        open(dir).use { git ->
            git.add().addFilepattern(".").call()
            git.add().setUpdate(true).addFilepattern(".").call()
            try {
                val c = git.commit().setMessage(message.trim()).setAuthor(id.name, id.email).setCommitter(id.name, id.email).setAllowEmpty(false).call()
                return "Committed ${c.name.take(7)}: ${c.shortMessage}"
            } catch (e: EmptyCommitException) {
                throw IllegalStateException("Nothing to commit")
            }
        }
    }

    fun log(dir: File, max: Int = 20): String = open(dir).use { git ->
        try {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            git.log().setMaxCount(max).call().joinToString("\n") {
                "${it.name.take(7)} ${it.shortMessage}  (${it.authorIdent.name}, ${fmt.format(Date(it.commitTime * 1000L))})"
            }.ifEmpty { "No commits yet" }
        } catch (e: NoHeadException) {
            "No commits yet"
        }
    }

    fun branches(dir: File): String = open(dir).use { git ->
        val cur = git.repository.branch
        git.branchList().call().map { Repository.shortenRefName(it.name) }.sorted()
            .joinToString("\n") { (if (it == cur) "* " else "  ") + it }.ifEmpty { "No branches yet (make a commit first)" }
    }

    fun checkout(dir: File, name: String): String {
        open(dir).use { it.checkout().setName(name.trim()).call() }
        return "Switched to ${name.trim()}"
    }

    fun createBranch(dir: File, name: String): String {
        val n = name.trim()
        require(Repository.isValidRefName("refs/heads/$n")) { "Invalid branch name" }
        open(dir).use { it.checkout().setCreateBranch(true).setName(n).call() }
        return "Created and switched to $n"
    }

    fun setRemote(dir: File, url: String): String {
        val u = url.trim()
        require(u.startsWith("https://")) { "Only https:// repository URLs are supported" }
        open(dir).use { git ->
            val cfg = git.repository.config
            cfg.setString("remote", "origin", "url", u)
            cfg.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
            cfg.save()
        }
        return "Remote origin set to $u"
    }

    private fun remoteUrl(git: Git): String? = git.repository.config.getString("remote", "origin", "url")

    private fun needAuth(git: Git, auth: GitAuth?) {
        if (remoteUrl(git)?.startsWith("https://") == true) require(auth != null && auth.token.isNotBlank()) { "Add your access token in Git settings first" }
    }

    fun push(dir: File, auth: GitAuth?): String = open(dir).use { git ->
        needAuth(git, auth)
        val res = git.push().setCredentialsProvider(creds(auth)).call()
        val lines = res.flatMap { it.remoteUpdates }.map { "${Repository.shortenRefName(it.remoteName)}: ${it.status}" }
        val bad = res.flatMap { it.remoteUpdates }.any { it.status !in setOf(RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE) }
        (if (bad) "Push problem:\n" else "Push finished:\n") + lines.joinToString("\n")
    }

    fun pull(dir: File, auth: GitAuth?): String = open(dir).use { git ->
        needAuth(git, auth)
        val r = git.pull().setCredentialsProvider(creds(auth)).call()
        if (r.isSuccessful) "Pull finished: ${r.mergeResult?.mergeStatus ?: "up to date"}"
        else "Pull has problems: ${r.mergeResult?.mergeStatus}\nConflicts: ${r.mergeResult?.conflicts?.keys?.joinToString() ?: "none"}\nOpen the files, fix the <<<<<<< markers, then commit."
    }

    /** Changes in tracked files since the last commit (untracked files are listed by status, not diffed). */
    fun diff(dir: File): String = open(dir).use { git ->
        val repo = git.repository
        val out = ByteArrayOutputStream()
        repo.newObjectReader().use { reader ->
            val head = repo.resolve("HEAD^{tree}")
            val old = if (head == null) EmptyTreeIterator() else CanonicalTreeParser().also { it.reset(reader, head) }
            DiffFormatter(out).use { f ->
                f.setRepository(repo)
                f.format(old, FileTreeIterator(repo))
            }
        }
        val s = out.toString("UTF-8")
        if (s.isBlank()) "No changes in tracked files" else if (s.length > MAX_DIFF_CHARS) s.take(MAX_DIFF_CHARS) + "\n[diff truncated]" else s
    }

    fun friendly(e: Throwable): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            e is IllegalArgumentException || e is IllegalStateException -> m
            e is TransportException && (m.contains("not authorized", true) || m.contains("401") || m.contains("403") || m.contains("Authentication", true)) ->
                "Authentication failed. Check your username and that your token has the \"repo\" permission."
            generateSequence(e) { it.cause }.any { it is java.net.UnknownHostException || it is java.net.ConnectException } -> "No internet connection or the server could not be reached."
            e is TransportException -> "Network or server error: $m"
            else -> m
        }
    }
}
