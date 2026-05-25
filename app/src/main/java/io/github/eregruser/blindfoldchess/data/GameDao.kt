package io.github.eregruser.blindfoldchess.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {

    @Insert
    suspend fun insert(game: GameEntity): Long

    @Query("UPDATE games SET movesUci = :moves WHERE id = :id")
    suspend fun updateMoves(id: Long, moves: String)

    @Query("UPDATE games SET completedAt = :completedAt, result = :result WHERE id = :id")
    suspend fun markComplete(id: Long, completedAt: Long, result: String)

    /** Most recent in-progress game (null while no game has been started). */
    @Query("SELECT * FROM games WHERE completedAt IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun findActive(): GameEntity?

    @Query("SELECT * FROM games WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): GameEntity?

    /** All completed games (most recently completed first). */
    @Query("SELECT * FROM games WHERE completedAt IS NOT NULL ORDER BY completedAt DESC")
    fun observeCompleted(): Flow<List<GameEntity>>

    @Query("DELETE FROM games WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Clear the completedAt + result columns so the game becomes the active in-progress
     *  row again. Used by resume-from-history for Abandoned games. */
    @Query("UPDATE games SET completedAt = NULL, result = 'InProgress' WHERE id = :id")
    suspend fun markUnfinished(id: Long)
}
