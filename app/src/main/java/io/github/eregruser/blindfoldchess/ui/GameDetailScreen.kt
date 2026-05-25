package io.github.eregruser.blindfoldchess.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.eregruser.blindfoldchess.BlindfoldChessApp
import io.github.eregruser.blindfoldchess.chess.SanConverter
import io.github.eregruser.blindfoldchess.data.GameEntity
import io.github.eregruser.blindfoldchess.data.GameResult
import io.github.eregruser.blindfoldchess.service.ChessGameService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(gameId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BlindfoldChessApp
    val scope = rememberCoroutineScope()
    val serviceState by ChessGameService.state.collectAsStateWithLifecycle()
    var game by remember { mutableStateOf<GameEntity?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(gameId) {
        game = withContext(Dispatchers.IO) { app.gameRepository.findById(gameId) }
        loaded = true
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete game #$gameId?") },
            text = { Text("This removes the game permanently. The move list cannot be recovered.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) { app.gameRepository.delete(gameId) }
                        onBack()
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Game #$gameId") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Delete is only meaningful once the row has actually loaded — guard
                    // against the user tapping during the brief loading window.
                    if (game != null) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete game")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            !loaded -> CenteredSpinner(padding)
            game == null -> CenteredMessage(padding, "Game $gameId not found.")
            else -> DetailBody(
                game = game!!,
                padding = padding,
                gameActive = serviceState.gameActive,
                onResume = {
                    ChessGameService.resumeGame(context, gameId)
                    onBack()
                },
            )
        }
    }
}

@Composable
private fun DetailBody(
    game: GameEntity,
    padding: androidx.compose.foundation.layout.PaddingValues,
    gameActive: Boolean,
    onResume: () -> Unit,
) {
    val moves = game.movesUci.split(' ').filter { it.isNotBlank() }
    val fullMoves = (moves.size + 1) / 2
    // Resume is only meaningful for Abandoned games and only when no other game is
    // currently active in the service. The user can stop their active game from the
    // main screen to unblock the button.
    val isAbandoned = runCatching { GameResult.valueOf(game.result) }
        .getOrDefault(GameResult.InProgress) == GameResult.Abandoned

    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(resultLabel(game.result), style = MaterialTheme.typography.titleMedium)
        Text(
            "Started: ${formatTimestamp(game.createdAt)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (game.completedAt != null) {
            Text(
                "Ended: ${formatTimestamp(game.completedAt)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            "Skill ${game.skillLevel} · Played as ${game.userColor} · $fullMoves full moves",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (isAbandoned) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResume, enabled = !gameActive) { Text("Resume") }
            }
            if (gameActive) {
                Text(
                    "Stop the current game from the main screen to resume this one.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        HorizontalDivider()

        Text("Moves", style = MaterialTheme.typography.labelLarge)
        if (moves.isEmpty()) {
            Text("(no moves)", style = MaterialTheme.typography.bodyMedium)
        } else {
            val sanMoves = remember(moves) { SanConverter.toSan(moves) }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(sanMoves.chunked(2)) { idx, pair ->
                    val n = idx + 1
                    val white = pair[0]
                    val black = pair.getOrNull(1) ?: ""
                    Text(
                        text = "%2d. %-6s   %s".format(n, white, black),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredSpinner(padding: androidx.compose.foundation.layout.PaddingValues) {
    Box(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredMessage(padding: androidx.compose.foundation.layout.PaddingValues, msg: String) {
    Box(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(msg, style = MaterialTheme.typography.bodyMedium)
    }
}
