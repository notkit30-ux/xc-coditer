package app.xccoditer

import android.content.Context
import java.io.File

data class DebugFrame(val function: String, val line: Int)
data class DebugVar(val name: String, val type: String, val value: String)
data class DebugPause(val reason: String, val line: Int, val stack: List<DebugFrame>, val variables: List<DebugVar>, val final: Boolean)

/** Implemented in Kotlin, called from xcdebug.py. */
interface DebugSink {
    fun write(kind: String, text: String)
    fun paused(json: String)
    fun finished(code: Int)
}

object DebugParse {
    fun pause(json: String): DebugPause? {
        val m = runCatching { MiniJson.parse(json) as? Map<*, *> }.getOrNull() ?: return null
        val stack = (m["stack"] as? List<*>).orEmpty().mapNotNull {
            val f = it as? Map<*, *> ?: return@mapNotNull null
            DebugFrame(f["function"].toString(), (f["line"] as? Double)?.toInt() ?: 0)
        }
        return DebugPause(m["reason"]?.toString().orEmpty(), (m["line"] as? Double)?.toInt() ?: 0, stack, variables(m["variables"]), m["final"] == true)
    }

    fun variables(v: Any?): List<DebugVar> = (v as? List<*>).orEmpty().mapNotNull {
        val x = it as? Map<*, *> ?: return@mapNotNull null
        DebugVar(x["name"].toString(), x["type"].toString(), x["value"].toString())
    }

    fun variablesJson(json: String): List<DebugVar> = variables(runCatching { MiniJson.parse(json) }.getOrNull())

    /** Parses "3, 10 12" into line numbers; returns null if anything is not a positive number. */
    fun lines(text: String): Set<Int>? {
        val parts = text.split(',', ' ', ';').filter { it.isNotBlank() }
        val out = parts.map { it.trim().toIntOrNull()?.takeIf { n -> n in 1..1_000_000 } ?: return null }
        return out.toSet()
    }
}

/** Debugger for Python files running in the embedded interpreter (standard-library bdb). Other languages: not supported. */
object PythonDebugger {
    private fun mod(ctx: Context) = PythonRunner.py(ctx).getModule("xcdebug")

    /** Blocking: runs until the program ends. Call from a background thread. Returns the exit code. */
    fun run(ctx: Context, file: File, breakpoints: Set<Int>, stopOnEntry: Boolean, sink: DebugSink): Int =
        mod(ctx).callAttr("run", file.path, file.parentFile!!.path, breakpoints.toTypedArray(), stopOnEntry, sink).toInt()

    fun command(ctx: Context, cmd: String) { runCatching { mod(ctx).callAttr("command", cmd) } }
    fun setBreakpoints(ctx: Context, lines: Set<Int>) { runCatching { mod(ctx).callAttr("set_breakpoints", lines.toTypedArray()) } }
    fun variables(ctx: Context, frame: Int): List<DebugVar> =
        runCatching { DebugParse.variablesJson(mod(ctx).callAttr("variables", frame).toString()) }.getOrDefault(emptyList())
    fun evaluate(ctx: Context, expr: String, frame: Int): String =
        runCatching { mod(ctx).callAttr("evaluate", expr, frame).toString() }.getOrElse { "error: ${it.message}" }
}
