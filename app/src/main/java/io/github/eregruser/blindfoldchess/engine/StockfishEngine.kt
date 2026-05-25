package io.github.eregruser.blindfoldchess.engine

import android.util.Log
import io.github.eregruser.blindfoldchess.chess.Board
import io.github.eregruser.blindfoldchess.chess.Fen
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * High-level UCI driver on top of [StockfishJni].
 *
 * Each command method follows the same pattern: subscribe to the engine's output flow, run
 * `send(...)` from the [onSubscription] callback (so we're guaranteed to be listening before
 * the command lands), then await the specific response line. This avoids races vs. plain
 * "send, then collect" — there's no window where a fast response could land before we
 * subscribed.
 */
class StockfishEngine(private val jni: StockfishJni = StockfishJni()) {

    val rawOutput: SharedFlow<String> get() = jni.output

    /** Starts the native engine and runs the `uci` handshake. */
    suspend fun start(handshakeTimeout: Duration = 5.seconds) {
        check(jni.start()) { "StockfishJni.start() returned false" }
        withTimeout(handshakeTimeout) {
            jni.output
                .onSubscription { jni.send("uci") }
                .first { it.trim() == "uciok" }
        }
        Log.i(TAG, "UCI handshake complete")
    }

    suspend fun isReady(timeout: Duration = 30.seconds) {
        withTimeout(timeout) {
            jni.output
                .onSubscription { jni.send("isready") }
                .first { it.trim() == "readyok" }
        }
    }

    suspend fun setOption(name: String, value: String) {
        jni.send("setoption name $name value $value")
    }

    suspend fun newGame() {
        jni.send("ucinewgame")
        isReady()
    }

    /**
     * Sets the engine's current position.
     * @param startFen null = standard starting position; otherwise a FEN string.
     * @param moves    list of UCI moves (e.g. `["e2e4", "e7e5"]`) applied from [startFen].
     */
    suspend fun setPosition(startFen: String? = null, moves: List<String> = emptyList()) {
        val cmd = buildString {
            append("position ")
            if (startFen == null) append("startpos") else append("fen ").append(startFen)
            if (moves.isNotEmpty()) {
                append(" moves")
                for (m in moves) {
                    append(' ')
                    append(m)
                }
            }
        }
        jni.send(cmd)
    }

    /**
     * Searches for [movetimeMs] ms and returns the best move in UCI long algebraic form
     * (e.g. `"e2e4"`, `"e7e8q"` for promotion). The engine also emits `info` lines during
     * the search which callers can observe via [rawOutput] if they want.
     */
    suspend fun goMoveTime(movetimeMs: Long, timeout: Duration = 30.seconds): String {
        val line = withTimeout(timeout) {
            jni.output
                .onSubscription { jni.send("go movetime $movetimeMs") }
                .first { it.startsWith("bestmove ") }
        }
        return line.removePrefix("bestmove ").trim().substringBefore(' ')
    }

    /**
     * Sends `go perft <depth>` and returns the list of legal moves (or perft subtree counts'
     * source moves) parsed from the engine's response. At depth = 1 this is exactly the set
     * of legal moves from the current position.
     *
     * Engine must be idle (no active search). Caller is responsible for serializing.
     */
    suspend fun perft(depth: Int = 1, timeout: Duration = 5.seconds): List<String> {
        val moves = mutableListOf<String>()
        withTimeout(timeout) {
            jni.output
                .onSubscription { jni.send("go perft $depth") }
                .takeWhile { !it.startsWith("Nodes searched:") }
                .toList()
                .forEach { line ->
                    val colon = line.indexOf(':')
                    if (colon in 4..5) {
                        val token = line.substring(0, colon).trim()
                        if (UCI_MOVE.matches(token)) moves.add(token)
                    }
                }
        }
        return moves
    }

    /**
     * Sends `d` (display) and parses the resulting `Fen: ...` line into a [Board]. Engine must
     * be idle.
     */
    suspend fun currentBoard(timeout: Duration = 5.seconds): Board {
        val fenLine = withTimeout(timeout) {
            jni.output
                .onSubscription { jni.send("d") }
                .first { it.startsWith("Fen:") }
        }
        val fen = fenLine.removePrefix("Fen:").trim()
        return Fen.parse(fen)
    }

    fun stop() = jni.stop()

    private companion object {
        const val TAG = "StockfishEngine"
        val UCI_MOVE = Regex("^[a-h][1-8][a-h][1-8][qrbn]?$")
    }
}
