package app.xccoditer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class TermuxResult(val stdout: String, val stderr: String, val exitCode: Int, val error: String?)

/**
 * Sends a command to the Termux app (RUN_COMMAND intent) and receives stdout/stderr/exit code back.
 * Needs: Termux installed, "allow-external-apps=true" in its properties, and the RUN_COMMAND permission granted to this app.
 * The code runs inside Termux with Termux's own permissions, not this app's.
 */
object TermuxBridge {
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private const val EXTRA_ID = "xc_exec_id"
    @Volatile var listener: ((Int, TermuxResult) -> Unit)? = null
    private var nextId = 100

    fun isInstalled(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo("com.termux", 0); true }.getOrDefault(false)
    fun hasPermission(ctx: Context) = ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Returns the id that the result will carry. Throws SecurityException when Termux or Android refuses. */
    fun run(ctx: Context, script: String): Int {
        val id = synchronized(this) { nextId++ }
        val back = PendingIntent.getBroadcast(
            ctx, id, Intent(ctx, TermuxResultReceiver::class.java).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val i = Intent("com.termux.RUN_COMMAND").setClassName("com.termux", "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", back)
        ctx.startForegroundService(i)
        return id
    }

    fun deliver(intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val b = intent.getBundleExtra("result")
        val err = b?.getInt("err", -1) ?: -1
        val res = if (b == null) TermuxResult("", "", -1, "Termux sent no result")
        else TermuxResult(
            b.getString("stdout") ?: "", b.getString("stderr") ?: "", b.getInt("exitCode", -1),
            if (err != -1 && err != 0) (b.getString("errmsg") ?: "Termux error $err") else null,
        )
        listener?.invoke(id, res)
    }

    /** Real verification: runs `echo` through Termux and waits for the answer. Null = could not reach Termux. */
    suspend fun selfTest(ctx: Context): Boolean? {
        if (!isInstalled(ctx) || !hasPermission(ctx)) return null
        return withTimeoutOrNull(8000L) {
            suspendCancellableCoroutine<Boolean?> { cont ->
                val previous = listener
                val id = try { run(ctx, "echo xc-termux-ok") } catch (e: Exception) { cont.resume(null); return@suspendCancellableCoroutine }
                listener = { rid, res ->
                    if (rid == id) {
                        listener = previous
                        if (cont.isActive) cont.resume(res.stdout.trim() == "xc-termux-ok")
                    } else previous?.invoke(rid, res)
                }
                cont.invokeOnCancellation { if (listener != null) listener = previous }
            }
        }
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = TermuxBridge.deliver(intent)
}
