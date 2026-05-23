package com.blindfoldchess.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import com.blindfoldchess.app.MainActivity
import com.blindfoldchess.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChessGameService : Service() {

    data class State(
        val mockGameActive: Boolean = false,
        val testModeActive: Boolean = false,
    ) {
        val sessionActive: Boolean get() = mockGameActive || testModeActive
    }

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var tts: TtsManager
    private lateinit var sessionAudio: SessionAudio
    private lateinit var earcons: Earcons
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        _state.value = State()
        tts = TtsManager(applicationContext)
        earcons = Earcons()
        sessionAudio = SessionAudio(applicationContext) {
            Log.w(TAG, "SessionAudio reported permanent focus loss; ending session")
            updateState { State() }
        }
        mediaSession = MediaSessionCompat(this, "ChessGameService").apply {
            setCallback(SessionCallback())
            setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                    .setState(PlaybackStateCompat.STATE_PLAYING, 0L, 1.0f)
                    .build()
            )
        }
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundIfNeeded()
        when (intent?.action) {
            ACTION_START_MOCK_GAME -> updateState { copy(mockGameActive = true) }
            ACTION_STOP_MOCK_GAME -> updateState { copy(mockGameActive = false) }
            ACTION_ENABLE_TEST_MODE -> updateState { copy(testModeActive = true) }
            ACTION_DISABLE_TEST_MODE -> updateState { copy(testModeActive = false) }
            else -> Log.w(TAG, "Unknown start action: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sessionAudio.release()
        mediaSession.isActive = false
        mediaSession.release()
        tts.shutdown()
        earcons.release()
        _state.value = State()
        foregroundStarted = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateState(transform: State.() -> State) {
        val previous = _state.value
        val next = transform(previous)
        if (next == previous) return
        _state.value = next
        mediaSession.isActive = next.sessionActive
        if (next.sessionActive && !previous.sessionActive) {
            sessionAudio.acquire()
        } else if (!next.sessionActive && previous.sessionActive) {
            sessionAudio.release()
        }
        if (!next.sessionActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundIfNeeded() {
        if (foregroundStarted) return
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Game session active")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                MediaNotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
            )
            .build()
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Game session",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Active while a mock game or headphone test is running"
                setShowBadge(false)
            }
        )
    }

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
            val keyEvent: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent
            }
            if (keyEvent == null) return false

            Log.d(
                TAG,
                "onMediaButtonEvent action=${keyEvent.action} keyCode=${keyEvent.keyCode} " +
                    "(${KeyEvent.keyCodeToString(keyEvent.keyCode)}) repeat=${keyEvent.repeatCount}",
            )
            KeyEventLog.record(keyEvent)

            if (!_state.value.mockGameActive) return true
            if (keyEvent.action != KeyEvent.ACTION_DOWN) return true
            if (keyEvent.repeatCount != 0) return true

            when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> {
                    earcons.listenStart()
                    tts.speak("listening")
                }

                KeyEvent.KEYCODE_MEDIA_NEXT -> tts.speak("repeat")
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> tts.speak("cancel")
            }
            return true
        }
    }

    companion object {
        private const val TAG = "ChessGameService"
        private const val CHANNEL_ID = "chess_game_session"
        private const val NOTIFICATION_ID = 1

        private const val ACTION_START_MOCK_GAME = "com.blindfoldchess.app.action.START_MOCK_GAME"
        private const val ACTION_STOP_MOCK_GAME = "com.blindfoldchess.app.action.STOP_MOCK_GAME"
        private const val ACTION_ENABLE_TEST_MODE = "com.blindfoldchess.app.action.ENABLE_TEST_MODE"
        private const val ACTION_DISABLE_TEST_MODE = "com.blindfoldchess.app.action.DISABLE_TEST_MODE"

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        fun startMockGame(context: Context) = dispatch(context, ACTION_START_MOCK_GAME)
        fun stopMockGame(context: Context) = dispatch(context, ACTION_STOP_MOCK_GAME)
        fun enableTestMode(context: Context) = dispatch(context, ACTION_ENABLE_TEST_MODE)
        fun disableTestMode(context: Context) = dispatch(context, ACTION_DISABLE_TEST_MODE)

        private fun dispatch(context: Context, action: String) {
            val intent = Intent(context, ChessGameService::class.java).setAction(action)
            context.startForegroundService(intent)
        }
    }
}
