package app.xccoditer

import java.io.File
import java.util.Base64

/** Builds the shell script that is sent to Termux: recreates the project folder, then runs one command. Pure logic. */
object TermuxScript {
    const val MAX_FILES = 60
    const val MAX_BYTES = 300_000
    private val skipDirs = setOf(".git", "node_modules", "build", ".gradle", "__pycache__")

    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun safeName(s: String) = s.filter { it.isLetterOrDigit() || it in "._-" }.ifBlank { "project" }

    private fun okPath(p: String) = p.isNotBlank() && !p.startsWith("/") && p.split('/').none { it.isEmpty() || it == ".." || it == "." }

    fun build(folderName: String, files: List<Pair<String, ByteArray>>, runCommand: String): String {
        val sb = StringBuilder()
        sb.append("set -e\nD=\"\$HOME/xc-run/${safeName(folderName)}\"\nrm -rf \"\$D\"\nmkdir -p \"\$D\"\ncd \"\$D\"\n")
        for ((path, data) in files) {
            require(okPath(path)) { "Unsafe path: $path" }
            val dir = path.substringBeforeLast('/', "")
            if (dir.isNotEmpty()) sb.append("mkdir -p ${q(dir)}\n")
            sb.append("printf %s ${q(Base64.getEncoder().encodeToString(data))} | base64 -d > ${q(path)}\n")
        }
        sb.append("set +e\n").append(runCommand).append('\n')
        return sb.toString()
    }

    /** Command to build/run [relPath] in Termux, or null if the language has no Termux route. */
    fun commandFor(relPath: String): String? {
        val name = relPath.substringAfterLast('/')
        val base = name.substringBeforeLast('.')
        val f = q(relPath)
        return when (name.substringAfterLast('.', "").lowercase()) {
            "c" -> "clang $f -o xc_out && ./xc_out"
            "cpp", "cc", "cxx" -> "clang++ $f -o xc_out && ./xc_out"
            "java" -> "javac \$(find . -name '*.java') && java -cp . ${q(base)}"
            "kt" -> "kotlinc $f -include-runtime -d xc_out.jar && java -jar xc_out.jar"
            "go" -> "go run $f"
            "rs" -> "rustc $f -o xc_out && ./xc_out"
            "php" -> "php $f"
            "rb" -> "ruby $f"
            "sh", "bash" -> "bash $f"
            else -> null
        }
    }

    private val notFound = Regex("([A-Za-z0-9_.+-]+): (?:command )?not found")
    private val pkgFor = mapOf(
        "clang" to "clang", "clang++" to "clang", "javac" to "openjdk-17", "java" to "openjdk-17", "kotlinc" to "kotlin",
        "go" to "golang", "rustc" to "rust", "php" to "php", "ruby" to "ruby",
    )

    fun hint(stderr: String): String? {
        val tool = notFound.find(stderr)?.groupValues?.get(1) ?: return null
        val pkg = pkgFor[tool] ?: return "\"$tool\" is not installed in Termux."
        return "\"$tool\" is not installed in Termux. In Termux run:  pkg install $pkg"
    }

    /** Files of [dir] (relative paths) or null when over the limits. Skips build/VCS folders and binary files. */
    fun collect(dir: File): List<Pair<String, ByteArray>>? {
        val out = ArrayList<Pair<String, ByteArray>>()
        var total = 0
        val files = dir.walkTopDown().onEnter { it == dir || it.name !in skipDirs }.filter { it.isFile && !it.name.endsWith(".xctmp") }
        for (f in files) {
            if (out.size >= MAX_FILES) return null
            val data = f.readBytes()
            if (data.any { it == 0.toByte() }) continue
            total += data.size
            if (total > MAX_BYTES) return null
            out += f.relativeTo(dir).invariantSeparatorsPath to data
        }
        return out
    }

    const val SETUP = "Termux setup (one time):\n" +
        "1. Install Termux from F-Droid (the Play Store version is outdated).\n" +
        "2. In Termux run:  mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties\n" +
        "   then close and reopen Termux.\n" +
        "3. Allow the permission when XC CODITER asks.\n" +
        "4. Install the tools you need, e.g.  pkg install clang openjdk-17 golang rust kotlin php ruby"
}
