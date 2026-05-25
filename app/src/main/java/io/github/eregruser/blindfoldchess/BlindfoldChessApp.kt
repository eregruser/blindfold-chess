package io.github.eregruser.blindfoldchess

import android.app.Application
import androidx.room.Room
import io.github.eregruser.blindfoldchess.data.AppDatabase
import io.github.eregruser.blindfoldchess.data.GameRepository
import io.github.eregruser.blindfoldchess.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide singletons: the Room database, game repository, and settings repository.
 * Held here so both the service ([io.github.eregruser.blindfoldchess.service.ChessGameService]) and the
 * UI layer can pull them from `applicationContext as BlindfoldChessApp`.
 */
class BlindfoldChessApp : Application() {

    /** Long-lived scope for repositories that need one (e.g. DataStore StateFlow). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, DB_NAME).build()
    }

    val gameRepository: GameRepository by lazy { GameRepository(database.gameDao()) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this, appScope) }

    private companion object {
        const val DB_NAME = "blindfold-chess.db"
    }
}
