package app.xccoditer

import android.content.Context
import java.io.File

/** Python intelligence through jedi and pyflakes, bundled with the embedded interpreter. Works offline. */
object PythonIntel {
    private fun mod(ctx: Context) = PythonRunner.py(ctx).getModule("xcintel")

    fun availability(ctx: Context): String? = runCatching { mod(ctx).callAttr("available").toString() }.getOrNull()

    fun diagnostics(ctx: Context, code: String, file: File) = IntelParse.diagnostics(mod(ctx).callAttr("diagnostics", code, file.name).toString())
    fun complete(ctx: Context, code: String, line: Int, col: Int, file: File, root: File) =
        IntelParse.completions(mod(ctx).callAttr("complete", code, line, col, file.path, root.path).toString())
    fun goto(ctx: Context, code: String, line: Int, col: Int, file: File, root: File) =
        IntelParse.gotos(mod(ctx).callAttr("goto", code, line, col, file.path, root.path).toString())
    fun hover(ctx: Context, code: String, line: Int, col: Int, file: File, root: File): String =
        mod(ctx).callAttr("hover", code, line, col, file.path, root.path).toString()
    fun references(ctx: Context, code: String, line: Int, col: Int, file: File, root: File) =
        IntelParse.refLines(mod(ctx).callAttr("references", code, line, col, file.path, root.path).toString())
    fun rename(ctx: Context, code: String, line: Int, col: Int, name: String, file: File, root: File): String =
        IntelParse.renamed(mod(ctx).callAttr("rename", code, line, col, name, file.path, root.path).toString())
}
