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
        RuntimeInfo("Python", RuntimeStatus.NOT_BUILT, "-", false, "~20-40 MB (planned)", "Planned via an embedded interpreter. Native packages such as numpy will be limited."),
        RuntimeInfo("TypeScript", RuntimeStatus.NOT_BUILT, "-", false, "-", "Needs a bundled transpiler. Editing and highlighting work."),
        RuntimeInfo("PHP / Lua / Ruby", RuntimeStatus.NOT_BUILT, "-", false, "-", "No Android-ready interpreter is bundled yet."),
        RuntimeInfo("Java / Kotlin", RuntimeStatus.NEEDS_CONFIG, "-", false, "-", "No on-device JDK or Kotlin compiler. Use a computer, or Termux with its own packages."),
        RuntimeInfo("C / C++", RuntimeStatus.NEEDS_CONFIG, "-", false, "-", "No on-device compiler bundled. Termux provides clang if you install it."),
        RuntimeInfo("Go / Rust", RuntimeStatus.NEEDS_CONFIG, "-", false, "-", "No on-device toolchain. Use Termux or a computer. Remote build would need your explicit consent per upload."),
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
