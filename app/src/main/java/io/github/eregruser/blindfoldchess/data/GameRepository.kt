package io.github.eregruser.blindfoldchess.data

import kotlinx.coroutines.flow.Flow

/**
 * Thin coroutine-friendly wrapper over [GameDao]. One instance per process; held by the
 * [io.github.eregruser.blindfoldchess.BlindfoldChessApp] application class and injected into the
 * service-owned [io.github.eregruser.blindfoldchess.engine.GameController].
 */
class GameRepository(private val dao: GameDao) {

    suspend fun startNewGame(skillLevel: Int, userColor: String = "White"): Long {
        return dao.insert(
            GameEntity(
                createdAt = System.currentTimeMillis(),
                skillLevel = skillLevel,
                userColor = userColor,
            )
        )
    }

    suspend fun recordMoves(id: Long, moves: List<String>) {
        dao.updateMoves(id, moves.joinToString(" "))
    }

    suspend fun markComplete(id: Long, result: GameResult) {
        dao.markComplete(id, System.currentTimeMillis(), result.name)
    }

    suspend fun findActive(): GameEntity? = dao.findActive()

    suspend fun findById(id: Long): GameEntity? = dao.findById(id)

    fun observeCompleted(): Flow<List<GameEntity>> = dao.observeCompleted()

    suspend fun delete(id: Long) = dao.deleteById(id)
}
