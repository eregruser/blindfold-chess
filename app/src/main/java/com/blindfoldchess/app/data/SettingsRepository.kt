package com.blindfoldchess.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * User-tunable preferences (Stockfish skill, engine think time, TTS notation + verbosity).
 * Backed by DataStore Preferences. One instance per process, owned by
 * [com.blindfoldchess.app.BlindfoldChessApp].
 */
class SettingsRepository(context: Context, scope: CoroutineScope) {

    enum class Notation { LetterByLetter, Nato }

    /**
     * Initial fog state when opening the Board view. After entry, the user toggles individual
     * squares with taps; this only controls the default.
     */
    enum class FogMode {
        /** All 64 squares start fogged — true blindfold practice. */
        FogAll,
        /** Opponent's piece squares fogged; user's pieces and empty squares revealed. */
        FogOpponent,
        /** Everything visible. */
        RevealAll,
    }

    data class Settings(
        val skillLevel: Int = DEFAULT_SKILL,
        val moveTimeMs: Long = DEFAULT_MOVE_TIME_MS,
        val notation: Notation = Notation.LetterByLetter,
        val verbose: Boolean = false,
        val fogMode: FogMode = FogMode.FogAll,
    )

    private val store: DataStore<Preferences> = context.applicationContext.dataStore

    /**
     * Eagerly-started StateFlow so callers can read `.value` synchronously without suspending.
     * Initial value is the defaults; the first DataStore emission replaces it within ms.
     */
    val settings: StateFlow<Settings> = store.data
        .map { prefs ->
            Settings(
                skillLevel = prefs[KEY_SKILL] ?: DEFAULT_SKILL,
                moveTimeMs = prefs[KEY_MOVE_TIME] ?: DEFAULT_MOVE_TIME_MS,
                notation = prefs[KEY_NOTATION]?.let { runCatching { Notation.valueOf(it) }.getOrNull() }
                    ?: Notation.LetterByLetter,
                verbose = prefs[KEY_VERBOSE] ?: false,
                fogMode = prefs[KEY_FOG_MODE]?.let { runCatching { FogMode.valueOf(it) }.getOrNull() }
                    ?: FogMode.FogAll,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, Settings())

    suspend fun setSkillLevel(level: Int) {
        val clamped = level.coerceIn(0, 20)
        store.edit { it[KEY_SKILL] = clamped }
    }

    suspend fun setMoveTimeMs(ms: Long) {
        val clamped = ms.coerceIn(50L, 10_000L)
        store.edit { it[KEY_MOVE_TIME] = clamped }
    }

    suspend fun setNotation(notation: Notation) {
        store.edit { it[KEY_NOTATION] = notation.name }
    }

    suspend fun setVerbose(verbose: Boolean) {
        store.edit { it[KEY_VERBOSE] = verbose }
    }

    suspend fun setFogMode(mode: FogMode) {
        store.edit { it[KEY_FOG_MODE] = mode.name }
    }

    companion object {
        const val DEFAULT_SKILL = 5
        const val DEFAULT_MOVE_TIME_MS = 500L

        private val KEY_SKILL = intPreferencesKey("skill_level")
        private val KEY_MOVE_TIME = longPreferencesKey("move_time_ms")
        private val KEY_NOTATION = stringPreferencesKey("tts_notation")
        private val KEY_VERBOSE = booleanPreferencesKey("tts_verbose")
        private val KEY_FOG_MODE = stringPreferencesKey("fog_mode")
    }
}

/** Top-level extension required by [preferencesDataStore]. */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
