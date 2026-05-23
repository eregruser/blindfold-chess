package com.blindfoldchess.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.blindfoldchess.app.BlindfoldChessApp
import com.blindfoldchess.app.data.GameEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(gameId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BlindfoldChessApp
    var game by remember { mutableStateOf<GameEntity?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(gameId) {
        game = withContext(Dispatchers.IO) { app.gameRepository.findById(gameId) }
        loaded = true
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
            )
        },
    ) { padding ->
        when {
            !loaded -> CenteredSpinner(padding)
            game == null -> CenteredMessage(padding, "Game $gameId not found.")
            else -> DetailBody(game!!, padding)
        }
    }
}

@Composable
private fun DetailBody(game: GameEntity, padding: androidx.compose.foundation.layout.PaddingValues) {
    val moves = game.movesUci.split(' ').filter { it.isNotBlank() }
    val fullMoves = (moves.size + 1) / 2

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

        HorizontalDivider()

        Text("Moves", style = MaterialTheme.typography.labelLarge)
        if (moves.isEmpty()) {
            Text("(no moves)", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(moves.chunked(2)) { idx, pair ->
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
