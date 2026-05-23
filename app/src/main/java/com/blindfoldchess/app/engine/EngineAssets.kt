package com.blindfoldchess.app.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Extracts Stockfish NNUE network files from APK assets into internal storage so the native
 * engine can mmap/read them by absolute path. Idempotent: re-running with an already-extracted
 * file of the right size is a no-op.
 *
 * The filenames are pinned to the sf_18 tag's `EvalFileDefaultNameBig` /
 * `EvalFileDefaultNameSmall` constants (see stockfish/src/evaluate.h). If we bump Stockfish
 * the constants here need to bump too.
 */
class EngineAssets(private val context: Context) {

    data class NnueFiles(val big: File, val small: File)

    private val nnueDir: File by lazy { File(context.filesDir, "nnue").apply { mkdirs() } }

    /**
     * Returns the on-disk paths of both NNUE files, extracting them from assets if missing.
     * Returns null if either file isn't bundled (run scripts/fetch_nnue.sh) or extraction fails.
     */
    fun ensureExtracted(): NnueFiles? {
        val big = extract(BIG_NNUE) ?: return null
        val small = extract(SMALL_NNUE) ?: return null
        return NnueFiles(big = big, small = small)
    }

    private fun extract(name: String): File? {
        val target = File(nnueDir, name)
        val expectedSize = sizeInAssets(name) ?: run {
            Log.w(TAG, "Asset $name not bundled — run scripts/fetch_nnue.sh and rebuild")
            return null
        }
        if (target.exists() && target.length() == expectedSize) {
            return target
        }
        return try {
            context.assets.open(name).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "Extracted $name (${target.length()} bytes) -> ${target.absolutePath}")
            target
        } catch (e: IOException) {
            Log.w(TAG, "Failed to extract $name", e)
            target.delete()
            null
        }
    }

    private fun sizeInAssets(name: String): Long? {
        return try {
            context.assets.openFd(name).use { it.length }
        } catch (_: IOException) {
            // openFd fails for compressed assets; fall back to streaming length.
            return try {
                context.assets.open(name).use { input ->
                    var total = 0L
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                    }
                    total
                }
            } catch (_: IOException) {
                null
            }
        }
    }

    companion object {
        private const val TAG = "EngineAssets"
        const val BIG_NNUE = "nn-c288c895ea92.nnue"
        const val SMALL_NNUE = "nn-37f18f62d772.nnue"
    }
}
