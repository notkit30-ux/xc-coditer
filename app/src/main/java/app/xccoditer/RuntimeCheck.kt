package app.xccoditer

import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Live checks: a runtime is only shown as verified after a real execution test passes on this device. */
object RuntimeCheck {
    suspend fun collect(ctx: Context): List<RuntimeInfo> {
        val out = ArrayList<RuntimeInfo>()

        val sqlite = withContext(Dispatchers.IO) {
            runCatching {
                val db = SQLiteDatabase.create(null)
                try {
                    val v = DatabaseUtils.stringForQuery(db, "select sqlite_version()", null)
                    val r = DatabaseUtils.longForQuery(db, "select 6*7", null)
                    if (r == 42L) v else null
                } finally { db.close() }
            }.getOrNull()
        }
        out += if (sqlite != null)
            RuntimeInfo("SQL / SQLite", RuntimeStatus.VERIFIED, sqlite, true, "0 MB (part of Android)", "Test passed: ran a query on this device. Databases are in-memory per run.")
        else RuntimeInfo("SQL / SQLite", RuntimeStatus.UNSUPPORTED, "-", false, "-", "The SQLite self-test failed on this device.")

        val shell = withContext(Dispatchers.IO) {
            runCatching {
                val p = ProcessBuilder("/system/bin/sh", "-c", "echo xc-ok").redirectErrorStream(true).start()
                p.outputStream.close()
                val text = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                text == "xc-ok"
            }.getOrDefault(false)
        }
        out += if (shell)
            RuntimeInfo("Android shell", RuntimeStatus.LIMITED, "Android ${Build.VERSION.RELEASE}", true, "0 MB (part of Android)", "Test passed. Limited: not a full Linux, runs as this app's user, no interactive input.")
        else RuntimeInfo("Android shell", RuntimeStatus.UNSUPPORTED, "-", false, "-", "The shell self-test failed.")

        val webVersion = runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull()
        val js = withContext(Dispatchers.Main) { jsSelfTest(ctx) }
        out += if (js)
            RuntimeInfo("HTML / CSS / JavaScript", RuntimeStatus.LIMITED, "System WebView ${webVersion ?: "?"}", true, "0 MB (system WebView)", "Test passed: ran JavaScript here. Limited: browser JS, not Node.js; network blocked in Play.")
        else RuntimeInfo("HTML / CSS / JavaScript", RuntimeStatus.UNSUPPORTED, webVersion ?: "-", false, "-", "The WebView JavaScript self-test failed or timed out.")

        val pyVersion = withContext(Dispatchers.IO) { PythonRunner.selfTest(ctx) }
        out += if (pyVersion != null)
            RuntimeInfo("Python", RuntimeStatus.LIMITED, "CPython $pyVersion", true, "~10-15 MB per CPU type (inside the APK)",
                "Test passed: ran code on this device. Limited: standard library only, no pip or native packages (numpy etc.), no keyboard input, Stop acts at the next instruction.")
        else RuntimeInfo("Python", RuntimeStatus.UNSUPPORTED, "-", false, "-", "The Python self-test failed. This build may not include your device's CPU type.")

        val termux = TermuxBridge.selfTest(ctx)
        out += when {
            !TermuxBridge.isInstalled(ctx) -> RuntimeInfo("Termux (Java, Kotlin, C, C++, Go, Rust, PHP, Ruby)", RuntimeStatus.NEEDS_CONFIG, "-", true, "Termux is a separate app",
                "Termux is not installed. Get it from F-Droid. Compilers are installed inside Termux with pkg.")
            !TermuxBridge.hasPermission(ctx) -> RuntimeInfo("Termux (Java, Kotlin, C, C++, Go, Rust, PHP, Ruby)", RuntimeStatus.NEEDS_CONFIG, "-", true, "Termux is a separate app",
                "Termux is installed. Run a .c/.java/.go/... file once and allow the permission, after enabling allow-external-apps in Termux.")
            termux == true -> RuntimeInfo("Termux (Java, Kotlin, C, C++, Go, Rust, PHP, Ruby)", RuntimeStatus.VERIFIED, "bridge works", true, "Termux is a separate app",
                "Test passed: a command ran in Termux and returned. Each language still needs its package installed in Termux (pkg install ...).")
            else -> RuntimeInfo("Termux (Java, Kotlin, C, C++, Go, Rust, PHP, Ruby)", RuntimeStatus.NEEDS_CONFIG, "-", true, "Termux is a separate app",
                "Termux did not answer. Enable allow-external-apps=true in ~/.termux/termux.properties and restart Termux.")
        }

        val luaOut = ArrayList<String>()
        val luaOk = withContext(Dispatchers.Default) {
            runCatching { LuaRunner.run("print(6*7)", "@selftest", { luaOut += it }, { false }) == 0 && luaOut == listOf("42") }.getOrDefault(false)
        }
        out += if (luaOk)
            RuntimeInfo("Lua", RuntimeStatus.LIMITED, "Lua 5.2 (LuaJ)", true, "~0.5 MB", "Test passed: ran code on this device. Limited: sandboxed with no io, os or require; no keyboard input.")
        else RuntimeInfo("Lua", RuntimeStatus.UNSUPPORTED, "-", false, "-", "The Lua self-test failed.")

        val md = Markdown.toHtml("# a").contains("<h1>a</h1>")
        out += RuntimeInfo("Markdown", if (md) RuntimeStatus.VERIFIED else RuntimeStatus.UNSUPPORTED, "built-in", true, "0 MB", "Test passed: converted a sample heading.")

        out += RuntimeCatalog.notInstalled()
        return out
    }

    private suspend fun jsSelfTest(ctx: Context): Boolean = withTimeoutOrNull(6000L) {
        suspendCancellableCoroutine<Boolean> { cont ->
            val wv = WebView(ctx.applicationContext)
            var destroyed = false
            fun done(r: Boolean) {
                if (cont.isActive) cont.resume(r)
                if (!destroyed) { destroyed = true; wv.destroy() }
            }
            wv.settings.javaScriptEnabled = true
            wv.settings.blockNetworkLoads = true
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript("String(6*7)") { r -> done(r == "\"42\"") } ?: done(false)
                }
            }
            cont.invokeOnCancellation { if (!destroyed) { destroyed = true; wv.destroy() } }
            wv.loadDataWithBaseURL(null, "<html><body>t</body></html>", "text/html", "utf-8", null)
        }
    } ?: false
}
