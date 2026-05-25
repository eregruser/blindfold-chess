package io.github.eregruser.blindfoldchess.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat as MediaNotificationCompat
import io.github.eregruser.blindfoldchess.BlindfoldChessApp
import io.github.eregruser.blindfoldchess.MainActivity
import io.github.eregruser.blindfoldchess.R
import io.github.eregruser.blindfoldchess.engine.GameController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChessGameService : Service() {

    data class State(
        val gameActive: Boolean = false,
        val testModeActive: Boolean = false,
        /**
         * True iff we currently hold AUDIOFOCUS_GAIN and are running the silent A2DP
         * keepalive. When false (i.e. another media app took focus) the game state is
         * still alive but headset taps no longer reach us — they go to the music app.
         * The user can keep playing via on-screen Speak / tap-to-move, and bring this
         * back true by returning to the app (MainActivity.onResume re-requests focus).
         */
        val headsetRoutingActive: Boolean = false,
    ) {
        val sessionActive: Boolean get() = gameActive || testModeActive
    }

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var tts: TtsManager
    private lateinit var sessionAudio: SessionAudio
    private lateinit var earcons: Earcons
    private lateinit var gameController: GameController

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var foregroundStarted = false
    private var currentForegroundType: Int = 0

    override fun onCreate() {
        super.onCreate()
        _state.value = State()
        _gameState.value = GameController.State()

        tts = TtsManager(applicationContext)
        earcons = Earcons()
        // gameController must be initialized before sessionAudio's callbacks fire — both
        // closures capture it as a lateinit reference and dispatch to it at runtime.
        val app = applicationContext as BlindfoldChessApp
        gameController = GameController(
            applicationContext,
            tts,
            earcons,
            app.gameRepository,
            app.settingsRepository,
        )
        sessionAudio = SessionAudio(
            applicationContext,
            onPermanentLoss = {
                // Another media app (YouTube, Spotify, podcast, …) has taken focus. We
                // lose headset routing because AVRCP follows whichever app is currently
                // streaming over A2DP, but the game state itself is fine to retain — the
                // user can still play via on-screen Speak + tap-to-move. Focus comes
                // back when they foreground us (MainActivity.onResume → ACTION_TRY_REACQUIRE_FOCUS)
                // or when the music app voluntarily releases focus.
                Log.w(TAG, "Audio focus permanently lost; headset routing paused (game retained)")
                updateState { copy(headsetRoutingActive = false) }
            },
            onFocusRegained = {
                Log.i(TAG, "Audio focus regained; headset routing restored")
                updateState { copy(headsetRoutingActive = true) }
                gameController.onFocusRegained()
            },
        )

        serviceScope.launch {
            gameController.state.collect { _gameState.value = it }
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
        // Decide foreground type up-front; the type-with-microphone path needs RECORD_AUDIO
        // granted at startForeground time on Android 14+.
        val willHaveGame = when (intent?.action) {
            ACTION_START_GAME, ACTION_RESUME_GAME -> true
            ACTION_STOP_GAME -> false
            else -> _state.value.gameActive
        }
        startForegroundIfNeeded(includeMic = willHaveGame)

        when (intent?.action) {
            ACTION_START_GAME -> {
                updateState { copy(gameActive = true) }
                serviceScope.launch { gameController.startGame() }
            }
            ACTION_RESUME_GAME -> {
                val gameId = intent.getLongExtra(EXTRA_GAME_ID, -1L)
                if (gameId < 0) {
                    Log.w(TAG, "ACTION_RESUME_GAME missing $EXTRA_GAME_ID extra")
                } else {
                    updateState { copy(gameActive = true) }
                    serviceScope.launch { gameController.resumeGame(gameId) }
                }
            }
            ACTION_STOP_GAME -> {
                gameController.stopGame()
                updateState { copy(gameActive = false) }
            }
            ACTION_SUBMIT_MOVE -> {
                val uci = intent.getStringExtra(EXTRA_MOVE_UCI)
                if (uci.isNullOrBlank()) {
                    Log.w(TAG, "ACTION_SUBMIT_MOVE missing $EXTRA_MOVE_UCI extra")
                } else {
                    serviceScope.launch { gameController.submitTextMove(uci) }
                }
            }
            ACTION_TAKE_BACK -> {
                serviceScope.launch { gameController.takeBack() }
            }
            ACTION_OPEN_LISTEN_WINDOW -> gameController.openListenWindow()
            ACTION_CANCEL_LISTEN_WINDOW -> gameController.cancelListenWindow()
            ACTION_ENABLE_TEST_MODE -> updateState { copy(testModeActive = true) }
            ACTION_DISABLE_TEST_MODE -> updateState { copy(testModeActive = false) }
            ACTION_TRY_REACQUIRE_FOCUS -> {
                // Triggered by MainActivity.onResume after the user returns to the app.
                // Only meaningful while a session is active but headset routing is paused.
                val st = _state.value
                if (st.sessionActive && !st.headsetRoutingActive) {
                    val granted = sessionAudio.tryReacquire()
                    if (granted) {
                        Log.i(TAG, "tryReacquireFocus: granted")
                        updateState { copy(headsetRoutingActive = true) }
                        gameController.onFocusRegained()
                    } else {
                        Log.d(TAG, "tryReacquireFocus: not granted (another app likely still holds focus)")
                    }
                }
            }
            else -> Log.w(TAG, "Unknown start action: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        gameController.release()
        sessionAudio.release()
        mediaSession.isActive = false
        mediaSession.release()
        tts.shutdown()
        earcons.release()
        _state.value = State()
        _gameState.value = GameController.State()
        foregroundStarted = false
        currentForegroundType = 0
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateState(transform: State.() -> State) {
        val previous = _state.value
        val next = transform(previous)
        if (next == previous) return
        _state.value = next
        // The MediaSession only routes headset events to us while we hold focus —
        // gating its active flag on both keeps it from sitting in a weird half-state
        // where the system thinks we own headset routing but we actually don't.
        mediaSession.isActive = next.sessionActive && next.headsetRoutingActive
        if (next.sessionActive && !previous.sessionActive) {
            val granted = sessionAudio.acquire()
            // Reflect the synchronous acquire result. The async listener will further
            // update this on subsequent GAIN/LOSS events. updateState is re-entrant via
            // the equality short-circuit: the next call only changes headsetRoutingActive,
            // so the "started session" branch above won't fire again.
            updateState { copy(headsetRoutingActive = granted) }
        } else if (!next.sessionActive && previous.sessionActive) {
            sessionAudio.release()
        }
        if (!next.sessionActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Calls [startForeground] with the appropriate type combo. Idempotent vs. equivalent
     * subsequent calls. On a type change (e.g. test mode → game), re-invokes startForeground
     * to upgrade the declared type so the system permits microphone capture from the service.
     */
    private fun startForegroundIfNeeded(includeMic: Boolean) {
        val newType = computeForegroundType(includeMic)
        if (foregroundStarted && newType == currentForegroundType) return
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, newType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        foregroundStarted = true
        currentForegroundType = newType
    }

    private fun computeForegroundType(includeMic: Boolean): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (includeMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasMicPermission()) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return type
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

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
                description = "Active while a game or headphone test is running"
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

            if (!_state.value.gameActive) return true
            if (keyEvent.action != KeyEvent.ACTION_DOWN) return true
            if (keyEvent.repeatCount != 0) return true

            when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK -> gameController.openListenWindow()

                KeyEvent.KEYCODE_MEDIA_NEXT -> gameController.repeatLastEngineMove()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> gameController.cancelListenWindow()
            }
            return true
        }
    }

    companion object {
        private const val TAG = "ChessGameService"
        private const val CHANNEL_ID = "chess_game_session"
        private const val NOTIFICATION_ID = 1

        private const val ACTION_START_GAME = "io.github.eregruser.blindfoldchess.action.START_GAME"
        private const val ACTION_RESUME_GAME = "io.github.eregruser.blindfoldchess.action.RESUME_GAME"
        private const val ACTION_STOP_GAME = "io.github.eregruser.blindfoldchess.action.STOP_GAME"
        private const val ACTION_SUBMIT_MOVE = "io.github.eregruser.blindfoldchess.action.SUBMIT_MOVE"
        private const val ACTION_TAKE_BACK = "io.github.eregruser.blindfoldchess.action.TAKE_BACK"
        private const val ACTION_OPEN_LISTEN_WINDOW = "io.github.eregruser.blindfoldchess.action.OPEN_LISTEN_WINDOW"
        private const val ACTION_CANCEL_LISTEN_WINDOW = "io.github.eregruser.blindfoldchess.action.CANCEL_LISTEN_WINDOW"
        private const val ACTION_ENABLE_TEST_MODE = "io.github.eregruser.blindfoldchess.action.ENABLE_TEST_MODE"
        private const val ACTION_DISABLE_TEST_MODE = "io.github.eregruser.blindfoldchess.action.DISABLE_TEST_MODE"
        private const val ACTION_TRY_REACQUIRE_FOCUS = "io.github.eregruser.blindfoldchess.action.TRY_REACQUIRE_FOCUS"
        private const val EXTRA_GAME_ID = "gameId"
        private const val EXTRA_MOVE_UCI = "moveUci"

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        private val _gameState = MutableStateFlow(GameController.State())
        val gameState: StateFlow<GameController.State> = _gameState.asStateFlow()

        fun startGame(context: Context) = dispatch(context, ACTION_START_GAME)
        fun stopGame(context: Context) = dispatch(context, ACTION_STOP_GAME)
        fun enableTestMode(context: Context) = dispatch(context, ACTION_ENABLE_TEST_MODE)
        fun disableTestMode(context: Context) = dispatch(context, ACTION_DISABLE_TEST_MODE)

        fun resumeGame(context: Context, gameId: Long) {
            val intent = Intent(context, ChessGameService::class.java)
                .setAction(ACTION_RESUME_GAME)
                .putExtra(EXTRA_GAME_ID, gameId)
            context.startForegroundService(intent)
        }

        /** Submits a UCI move from a non-voice path (board long-press, debug UI). */
        fun submitMove(context: Context, uci: String) {
            val intent = Intent(context, ChessGameService::class.java)
                .setAction(ACTION_SUBMIT_MOVE)
                .putExtra(EXTRA_MOVE_UCI, uci)
            context.startForegroundService(intent)
        }

        /** Drops the last user+engine move pair. Mirrors the "take back" voice command. */
        fun takeBack(context: Context) = dispatch(context, ACTION_TAKE_BACK)

        /** Opens an ASR listen window from a non-headset path (on-screen Speak button). */
        fun openListenWindow(context: Context) = dispatch(context, ACTION_OPEN_LISTEN_WINDOW)

        /** Cancels a listen window from the on-screen Cancel button. */
        fun cancelListenWindow(context: Context) = dispatch(context, ACTION_CANCEL_LISTEN_WINDOW)

        /**
         * Try to re-acquire audio focus after a permanent loss. Called by
         * MainActivity.onResume — if the user is returning to the app after playing
         * something elsewhere, this gives the music app a chance to lose focus to us
         * again so headset routing comes back. No-op when there's no active session or
         * we already hold focus, so it's safe to call unconditionally on every resume.
         */
        fun tryReacquireFocus(context: Context) {
            val st = _state.value
            if (!st.sessionActive || st.headsetRoutingActive) return
            dispatch(context, ACTION_TRY_REACQUIRE_FOCUS)
        }

        private fun dispatch(context: Context, action: String) {
            val intent = Intent(context, ChessGameService::class.java).setAction(action)
            context.startForegroundService(intent)
        }
    }
}
