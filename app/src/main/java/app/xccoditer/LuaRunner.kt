package app.xccoditer

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.CoroutineLib
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.PackageLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JseMathLib
import org.luaj.vm2.LoadState

/**
 * Lua 5.2 (LuaJ, pure Java) in a sandbox: no io, os, require, dofile, loadfile, package or debug library,
 * so scripts cannot touch files or the network. Stop works through an instruction-count hook.
 */
object LuaRunner {
    const val EXIT_OK = 0
    const val EXIT_ERROR = 1
    const val EXIT_STOPPED = 130

    /** Blocking. Returns an exit code. [onLine] gets each output line; errors are reported as a final "lua: ..." line. */
    fun run(source: String, chunkName: String, onLine: (String) -> Unit, shouldStop: () -> Boolean): Int {
        val bytes = ByteArrayOutputStream()
        val stream = object : OutputStream() {
            override fun write(b: Int) {
                if (b == '\n'.code) { onLine(bytes.toString("UTF-8").trimEnd('\r')); bytes.reset() } else bytes.write(b)
            }
        }
        val out = PrintStream(stream, true, "UTF-8")
        var stopped = false
        try {
            val g = Globals()
            g.load(BaseLib()); g.load(PackageLib()); g.load(Bit32Lib()); g.load(TableLib())
            g.load(StringLib()); g.load(CoroutineLib()); g.load(JseMathLib()); g.load(DebugLib())
            LoadState.install(g); LuaC.install(g)
            g.STDOUT = out
            g.STDERR = out
            val hook = object : VarArgFunction() {
                override fun invoke(args: Varargs): Varargs {
                    if (shouldStop()) { stopped = true; throw LuaError("stopped") }
                    return LuaValue.NONE
                }
            }
            g.get("debug").get("sethook").invoke(LuaValue.varargsOf(arrayOf<LuaValue>(hook, LuaValue.valueOf(""), LuaValue.valueOf(1000))))
            for (name in listOf("debug", "package", "require", "dofile", "loadfile")) g.set(name, LuaValue.NIL)
            val chunk = g.load(source, chunkName)
            chunk.call()
            return EXIT_OK
        } catch (e: LuaError) {
            out.flush()
            if (bytes.size() > 0) { onLine(bytes.toString("UTF-8")); bytes.reset() }
            if (stopped) { onLine("[stopped]"); return EXIT_STOPPED }
            onLine("lua: " + (e.message ?: "error"))
            return EXIT_ERROR
        } catch (e: Throwable) {
            onLine("lua: internal error: " + (e.message ?: e.javaClass.simpleName))
            return EXIT_ERROR
        } finally {
            out.flush()
            if (bytes.size() > 0) { onLine(bytes.toString("UTF-8")); bytes.reset() }
        }
    }
}
