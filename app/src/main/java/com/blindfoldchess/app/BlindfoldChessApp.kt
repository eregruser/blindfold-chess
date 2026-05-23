package com.blindfoldchess.app

import android.app.Application
import androidx.room.Room
import com.blindfoldchess.app.data.AppDatabase
import com.blindfoldchess.app.data.GameRepository

/**
 * Process-wide singletons: the Room database and its repository. Held here so both the
 * service ([com.blindfoldchess.app.service.ChessGameService]) and the UI layer can pull
 * them from `applicationContext as BlindfoldChessApp`.
 */
class BlindfoldChessApp : Application() {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, DB_NAME).build()
    }

    val gameRepository: GameRepository by lazy { GameRepository(database.gameDao()) }

    private companion object {
        const val DB_NAME = "blindfold-chess.db"
    }
}
