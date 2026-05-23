package com.blindfoldchess.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "games")
data class GameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Epoch millis at game creation. */
    val createdAt: Long,
    /** Epoch millis at game completion. null while the game is still in-progress. */
    val completedAt: Long? = null,
    /** Stored as [GameResult].name. Defaults to InProgress on insert. */
    val result: String = GameResult.InProgress.name,
    /** Stockfish "Skill Level" UCI option value at game start. */
    val skillLevel: Int,
    /** "White" or "Black" — which side the human played. v1 hard-codes White. */
    val userColor: String = "White",
    /** UCI moves in play order, space-separated. Empty string for a freshly-started game. */
    val movesUci: String = "",
)

enum class GameResult {
    /** Active game; completedAt is null while this is set. */
    InProgress,

    /** User checkmated the engine. */
    UserWin,

    /** Engine checkmated the user, or a non-resign termination that wasn't a win. */
    UserLoss,

    /** Stalemate / threefold / fifty-move (we don't yet distinguish — Phase 6+ refinement). */
    Draw,

    /** User explicitly said "resign" (or tapped a resign button). */
    UserResigned,

    /** User started a new game / stopped the service without finishing the existing one. */
    Abandoned,
    ;

    companion object {
        fun fromName(name: String): GameResult =
            entries.firstOrNull { it.name == name } ?: InProgress
    }
}
