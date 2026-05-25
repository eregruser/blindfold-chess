package io.github.eregruser.blindfoldchess.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eregruser.blindfoldchess.BlindfoldChessApp
import io.github.eregruser.blindfoldchess.data.GameEntity
import io.github.eregruser.blindfoldchess.data.GameResult
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameHistoryScreen(onBack: () -> Unit, onOpenDetail: (Long) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BlindfoldChessApp
    val games by app.gameRepository.observeCompleted()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Game history") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (games.isEmpty()) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No completed games yet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
            ) {
                items(games, key = { it.id }) { game ->
                    GameRow(game, onClick = { onOpenDetail(game.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun GameRow(game: GameEntity, onClick: () -> Unit) {
    val moveCount = countMoves(game.movesUci)
    val fullMoves = (moveCount + 1) / 2
    ListItem(
        headlineContent = {
            Text("${resultLabel(game.result)} · $fullMoves move${if (fullMoves == 1) "" else "s"}")
        },
        supportingContent = {
            Text(
                "${formatTimestamp(game.completedAt ?: game.createdAt)} · Skill ${game.skillLevel} · ${game.userColor}",
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

internal fun resultLabel(result: String): String = when (GameResult.fromName(result)) {
    GameResult.InProgress -> "In progress"
    GameResult.UserWin -> "Win"
    GameResult.UserLoss -> "Loss"
    GameResult.Draw -> "Draw"
    GameResult.UserResigned -> "Resigned"
    GameResult.Abandoned -> "Abandoned"
}

internal fun countMoves(movesUci: String): Int =
    movesUci.split(' ').count { it.isNotBlank() }

internal fun formatTimestamp(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))
