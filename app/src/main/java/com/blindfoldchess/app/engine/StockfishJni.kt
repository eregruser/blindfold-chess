package com.blindfoldchess.app.engine

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Process-wide Stockfish engine wrapper.
 *
 * One instance per app process (the underlying bridge uses static fds). [start] spawns the
 * native UCI loop on a worker thread and a Kotlin reader coroutine that drains stdout into
 * [output]. Callers send UCI commands via [send] and observe responses on [output].
 *
 * [stop] sends `quit`, joins the engine thread, and cancels the reader. Safe to call multiple
 * times.
 */
class StockfishJni {

    private val _output = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val output: SharedFlow<String> = _output.asSharedFlow()

    private var readerScope: CoroutineScope? = null

    @Synchronized
    fun start(): Boolean {
        if (readerScope != null) return true
        if (!nativeStart()) {
            Log.w(TAG, "nativeStart returned false")
            return false
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        readerScope = scope
        scope.launch {
            while (true) {
                val line = try {
                    nativeReadLine()
                } catch (t: Throwable) {
                    Log.w(TAG, "nativeReadLine threw", t)
                    null
                } ?: break
                _output.emit(line)
            }
            Log.i(TAG, "Reader coroutine exiting (engine stdout EOF)")
        }
        return true
    }

    suspend fun send(command: String) = withContext(Dispatchers.IO) {
        nativeWrite(command)
    }

    @Synchronized
    fun stop() {
        val scope = readerScope ?: return
        // Native stop closes the engine's stdin pipe, which causes the engine thread to exit
        // and our reader coroutine to see EOF and finish on its own.
        runBlocking { withContext(Dispatchers.IO) { nativeStop() } }
        scope.coroutineContext[Job]?.cancel()
        readerScope = null
    }

    companion object {
        private const val TAG = "StockfishJni"

        init {
            System.loadLibrary("stockfish_bridge")
        }

        @JvmStatic external fun nativeStart(): Boolean
        @JvmStatic external fun nativeWrite(command: String)
        @JvmStatic external fun nativeReadLine(): String?
        @JvmStatic external fun nativeStop()
    }
}
