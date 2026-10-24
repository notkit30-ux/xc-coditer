package app.xccoditer

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

fun interface OutputSink {
    fun write(kind: String, text: String)
}

/**
 * Embedded CPython (Chaquopy). Runs inside this app's process with the app's permissions.
 * Limits: bundled standard library only (no runtime pip, no native packages such as numpy),
 * no keyboard input, and Stop works at the next Python instruction.
 */
object PythonRunner {
    private fun py(ctx: Context): Python {
        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx.applicationContext))
        return Python.getInstance()
    }

    /** Version string if a real execution test passes on this device, else null. */
    fun selfTest(ctx: Context): String? = try {
        py(ctx).getModule("xcrun").callAttr("selftest")?.toString()
    } catch (e: Throwable) { // includes UnsatisfiedLinkError on unsupported CPUs
        null
    }

    /** Blocking. Calls [onLine] for each complete output line. Returns the exit code. */
    fun run(ctx: Context, file: File, args: List<String>, onLine: (String) -> Unit): Int {
        val buf = StringBuilder()
        val sink = OutputSink { _, s ->
            buf.append(s)
            while (true) {
                val i = buf.indexOf("\n")
                if (i < 0) break
                onLine(buf.substring(0, i))
                buf.delete(0, i + 1)
            }
        }
        val code = py(ctx).getModule("xcrun")
            .callAttr("run", file.path, file.parentFile!!.path, args.toTypedArray(), sink).toInt()
        if (buf.isNotEmpty()) onLine(buf.toString())
        return code
    }

    fun stop(ctx: Context) {
        runCatching { py(ctx).getModule("xcrun").callAttr("stop") }
    }
}
