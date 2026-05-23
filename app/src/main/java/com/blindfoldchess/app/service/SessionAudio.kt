package com.blindfoldchess.app.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Owns the audio resources needed for headset media buttons to actually reach us on Bluetooth.
 *
 * On wired headsets, an active MediaSession is enough — key events flow through the input layer
 * into MediaSession routing. On Bluetooth, AVRCP runs its own routing and delivers media keys
 * directly to whichever app is currently streaming over A2DP. Setting MediaSession active does
 * not override that.
 *
 * To win AVRCP routing we therefore have to look like a media app:
 *   1. Request AUDIOFOCUS_GAIN so other media apps lose focus and pause.
 *   2. Continuously stream silent PCM with USAGE_MEDIA/CONTENT_TYPE_MUSIC so the BT stack
 *      treats us as the active media source.
 *
 * The writer thread blocks on [AudioTrack.write] (no busy-loop), so CPU/battery cost is minimal.
 */
class SessionAudio(
    context: Context,
    private val onPermanentLoss: () -> Unit = {},
) {

    private val audioManager: AudioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change -> handleFocusChange(change) }
            .build()

    private var audioTrack: AudioTrack? = null
    private var writerThread: Thread? = null

    @Volatile
    private var streaming = false
    private var acquired = false

    @Synchronized
    fun acquire(): Boolean {
        if (acquired) return true
        acquired = true
        val result = audioManager.requestAudioFocus(focusRequest)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "AUDIOFOCUS_GAIN request not granted (result=$result)")
        }
        startSilentStream()
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Synchronized
    fun release() {
        if (!acquired) return
        acquired = false
        stopSilentStream()
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    @Synchronized
    private fun handleFocusChange(change: Int) {
        if (!acquired) return
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "Transient focus loss (change=$change); pausing keepalive")
                stopSilentStream()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "Focus regained; resuming keepalive")
                startSilentStream()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.w(TAG, "Permanent focus loss; signalling session end")
                stopSilentStream()
                acquired = false
                // Don't abandon focus here — the system already revoked it.
                onPermanentLoss()
            }
            else -> Log.d(TAG, "AudioFocus change=$change (no specific handling)")
        }
    }

    private fun startSilentStream() {
        if (streaming) return
        val sampleRate = 44_100
        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferBytes = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBufferBytes <= 0) {
            Log.w(TAG, "AudioTrack.getMinBufferSize returned $minBufferBytes; aborting keepalive")
            return
        }
        // ~100 ms of audio, but no smaller than the device's minimum.
        val bufferBytes = maxOf(minBufferBytes, sampleRate * 2 / 10)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(encoding)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioTrack build failed", t)
            return
        }
        try {
            track.play()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioTrack.play failed", t)
            track.release()
            return
        }
        audioTrack = track
        streaming = true
        val silence = ShortArray(bufferBytes / 2)
        writerThread = Thread({
            try {
                while (streaming) {
                    val written = track.write(silence, 0, silence.size)
                    if (written < 0) {
                        Log.w(TAG, "AudioTrack.write error=$written; stopping keepalive")
                        break
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Silent stream writer crashed", t)
            }
        }, "ChessSessionSilentTrack").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopSilentStream() {
        streaming = false
        val track = audioTrack
        audioTrack = null
        try { track?.stop() } catch (_: Throwable) {}
        try { writerThread?.join(500) } catch (_: InterruptedException) {}
        writerThread = null
        try { track?.release() } catch (_: Throwable) {}
    }

    private companion object {
        const val TAG = "SessionAudio"
    }
}
