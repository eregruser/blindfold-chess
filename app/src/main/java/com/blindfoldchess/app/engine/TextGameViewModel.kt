package com.blindfoldchess.app.engine

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the engine lifecycle and the user-vs-engine game state for the text-input play
 * screen. Each method funnels through [engineLock] so a user submit can't race with an
 * in-flight engine search.
 */
class TextGameViewModel(application: Application) : AndroidViewModel(application) {

    enum class Status { Idle, Starting, WaitingForUser, Thinking, GameOver, Error }

    data class UiState(
        val status: Status = Status.Idle,
        val skillLevel: Int = DEFAULT_SKILL,
        val moveTimeMs: Long = DEFAULT_MOVE_TIME_MS,
        val moves: List<String> = emptyList(),    // UCI moves in play order
        val lastEngineMove: String? = null,
        val message: String? = null,              // status/error text shown to the user
    )

    private val jni = StockfishJni()
    private val engine = StockfishEngine(jni)
    private val assets = EngineAssets(application)
    private val engineLock = Mutex()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { bootEngine() }
    }

    private suspend fun bootEngine() = engineLock.withLock {
        _uiState.update { it.copy(status = Status.Starting, message = "Loading engine...") }
        try {
            val nnue = withContext(Dispatchers.IO) { assets.ensureExtracted() }
            engine.start()
            if (nnue != null) {
                engine.setOption("EvalFile", nnue.big.absolutePath)
                engine.setOption("EvalFileSmall", nnue.small.absolutePath)
            } else {
                _uiState.update { it.copy(message = "NNUE assets missing — engine will use degraded eval") }
            }
            engine.setOption("Skill Level", _uiState.value.skillLevel.toString())
            engine.newGame()
            _uiState.update {
                it.copy(
                    status = Status.WaitingForUser,
                    moves = emptyList(),
                    lastEngineMove = null,
                    message = null,
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bootEngine failed", t)
            _uiState.update { it.copy(status = Status.Error, message = "Engine init failed: ${t.message}") }
        }
    }

    fun startNewGame() {
        viewModelScope.launch {
            engineLock.withLock {
                _uiState.update { it.copy(status = Status.Starting, message = "Starting new game...") }
                try {
                    engine.setOption("Skill Level", _uiState.value.skillLevel.toString())
                    engine.newGame()
                    _uiState.update {
                        it.copy(
                            status = Status.WaitingForUser,
                            moves = emptyList(),
                            lastEngineMove = null,
                            message = null,
                        )
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "startNewGame failed", t)
                    _uiState.update { it.copy(status = Status.Error, message = "New game failed: ${t.message}") }
                }
            }
        }
    }

    fun setSkillLevel(level: Int) {
        val clamped = level.coerceIn(0, 20)
        _uiState.update { it.copy(skillLevel = clamped) }
        viewModelScope.launch {
            engineLock.withLock {
                try {
                    engine.setOption("Skill Level", clamped.toString())
                } catch (t: Throwable) {
                    Log.w(TAG, "setSkillLevel failed", t)
                }
            }
        }
    }

    fun submitUserMove(rawMove: String) {
        val move = rawMove.trim().lowercase()
        if (move.isEmpty()) return
        if (_uiState.value.status != Status.WaitingForUser) return

        viewModelScope.launch {
            engineLock.withLock {
                val currentMoves = _uiState.value.moves
                val withUser = currentMoves + move
                _uiState.update {
                    it.copy(
                        status = Status.Thinking,
                        moves = withUser,
                        lastEngineMove = null,
                        message = null,
                    )
                }
                try {
                    engine.setPosition(startFen = null, moves = withUser)
                    val reply = engine.goMoveTime(_uiState.value.moveTimeMs)
                    val gameOver = reply == "(none)" || reply == "0000" || reply.isBlank()
                    val newMoves = if (gameOver) withUser else withUser + reply
                    _uiState.update {
                        it.copy(
                            status = if (gameOver) Status.GameOver else Status.WaitingForUser,
                            moves = newMoves,
                            lastEngineMove = if (gameOver) null else reply,
                            message = if (gameOver) "Engine returned no move — game over (checkmate / stalemate / illegal user move)." else null,
                        )
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "submitUserMove failed", t)
                    _uiState.update {
                        it.copy(
                            status = Status.Error,
                            message = "Engine search failed: ${t.message}",
                        )
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            engine.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "engine.stop in onCleared threw", t)
        }
    }

    companion object {
        private const val TAG = "TextGameViewModel"
        const val DEFAULT_SKILL = 5
        const val DEFAULT_MOVE_TIME_MS = 500L
    }
}
