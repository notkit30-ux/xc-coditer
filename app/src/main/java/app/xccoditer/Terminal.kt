package app.xccoditer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * One terminal tab. Commands run through Android's own shell (/system/bin/sh) as this app's user:
 * no root, no other apps' data, no apt/gcc/python unless the device provides them. stdin is closed,
 * so interactive programs are not supported.
 */
class TerminalTab(val title: String, start: File) {
    val lines = mutableStateListOf<String>()
    var cwd by mutableStateOf(start)
    var running by mutableStateOf(false)
    val history = ArrayList<String>()
    var historyPos = -1
    @Volatile var process: Process? = null
    @Volatile var stopped = false

    fun stop() {
        stopped = true
        process?.destroyForcibly()
    }
}

const val TERMINAL_HELP = "Built-in: cd, pwd, clear, help. Everything else runs in Android's shell (/system/bin/sh).\n" +
    "This is not a full Linux system: apt, gcc, python and similar are usually missing.\n" +
    "Programs that wait for keyboard input are not supported (stdin is closed). Use Stop to cancel."
