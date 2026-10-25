package app.xccoditer

import java.io.File

enum class RuntimeStatus(val label: String) {
    VERIFIED("Installed and verified"),
    LIMITED("Installed with limitations"),
    DOWNLOADABLE("Available to download"),
    NEEDS_CONFIG("Requires configuration"),
    REMOTE("Remote execution only"),
    UNSUPPORTED("Unsupported on this device"),
    NOT_BUILT("Not built yet"),
}

data class RuntimeInfo(val name: String, val status: RuntimeStatus, val version: String, val offline: Boolean, val size: String, val note: String)

object RuntimeCatalog {
    /** Entries that need no live check. None may claim VERIFIED: only a passed execution test can. */
    fun notInstalled(): List<RuntimeInfo> = listOf(
        RuntimeInfo("Compilers inside this app", RuntimeStatus.UNSUPPORTED, "-", false, "-", "Android ships no JDK, clang, Go or Rust and none are bundled. Java, Kotlin, C, C++, Go, Rust, PHP and Ruby run through Termux instead (see the Termux entry)."),
    )

}

object ShellUtil {
    /** Resolves the target of `cd`. Returns null when it is not an existing directory. */
    fun resolveCd(cwd: File, arg: String, home: File): File? {
        val a = arg.trim()
        val target = when {
            a.isEmpty() || a == "~" -> home
            a.startsWith("~/") -> File(home, a.substring(2))
            a.startsWith("/") -> File(a)
            else -> File(cwd, a)
        }
        return runCatching { target.canonicalFile }.getOrNull()?.takeIf { it.isDirectory }
    }
}
