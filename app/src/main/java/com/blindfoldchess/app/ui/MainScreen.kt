package com.blindfoldchess.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blindfoldchess.app.BlindfoldChessApp
import com.blindfoldchess.app.data.GameEntity
import com.blindfoldchess.app.data.GameResult
import com.blindfoldchess.app.engine.GameController
import com.blindfoldchess.app.service.ChessGameService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BlindfoldChessApp
    val serviceState by ChessGameService.state.collectAsStateWithLifecycle()
    val gameState by ChessGameService.gameState.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }

    // Active unfinished game from a previous session (if any). Re-checked whenever the
    // service's gameActive transitions, so after a Stop/end-of-game the card reappears
    // for the now-completed (or for the abandoned predecessor) row.
    var resumeCandidate by remember { mutableStateOf<GameEntity?>(null) }
    LaunchedEffect(serviceState.gameActive) {
        resumeCandidate = if (!serviceState.gameActive) {
            withContext(Dispatchers.IO) { app.gameRepository.findActive() }
        } else {
            null
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(gameState.moves.size) {
        if (gameState.moves.isNotEmpty()) {
            val chunkCount = (gameState.moves.size + 1) / 2
            listState.animateScrollToItem(chunkCount - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Blindfold Chess") },
                actions = { TextButton(onClick = onOpenSettings) { Text("Settings") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusRow(gameState, serviceState.gameActive)

            resumeCandidate?.let { candidate ->
                ResumeCard(
                    game = candidate,
                    onResume = {
                        ChessGameService.resumeGame(context, candidate.id)
                        resumeCandidate = null
                    },
                    onDiscard = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                app.gameRepository.markComplete(candidate.id, GameResult.Abandoned)
                            }
                            resumeCandidate = null
                        }
                    },
                )
            }

            HelpText()

            HorizontalDivider()

            Text("Moves", style = MaterialTheme.typography.labelMedium)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (gameState.moves.isEmpty()) {
                    Text(
                        if (serviceState.gameActive) "No moves yet — tap your headset to play."
                        else "Start a game to begin.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(8.dp),
                    )
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(gameState.moves.chunked(2)) { idx, pair ->
                            val moveNumber = idx + 1
                            val white = pair[0]
                            val black = pair.getOrNull(1) ?: ""
                            Text(
                                text = "%2d. %-6s   %s".format(moveNumber, white, black),
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

            HorizontalDivider()

            ControlRow(
                serviceState = serviceState,
                gameState = gameState,
                micGranted = micGranted,
                onRequestMic = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                onStartGame = { ChessGameService.startGame(context) },
                onStopGame = { ChessGameService.stopGame(context) },
            )
        }
    }
}

@Composable
private fun StatusRow(gameState: GameController.State, gameActive: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val statusText = when {
            !gameActive -> "Stopped"
            gameState.status == GameController.Status.Idle -> "Idle"
            gameState.status == GameController.Status.Loading -> "Loading..."
            gameState.status == GameController.Status.WaitingForUser -> "Your move (${gameState.whoseTurn.name})"
            gameState.status == GameController.Status.Listening -> "Listening..."
            gameState.status == GameController.Status.Thinking -> "Engine thinking..."
            gameState.status == GameController.Status.GameOver -> "Game over"
            gameState.status == GameController.Status.Error -> "Error: ${gameState.message ?: ""}"
            else -> ""
        }
        if (
            gameState.status == GameController.Status.Loading ||
            gameState.status == GameController.Status.Listening ||
            gameState.status == GameController.Status.Thinking
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(statusText, style = MaterialTheme.typography.titleMedium)
    }
    if (gameState.lastEngineMove != null) {
        Text(
            text = "last engine move: ${gameState.lastEngineMove}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (gameState.status == GameController.Status.Listening && gameState.lastPartialText.isNotBlank()) {
        Text(
            text = "hearing: \"${gameState.lastPartialText}\"",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ResumeCard(
    game: GameEntity,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
) {
    val moveCount = game.movesUci.split(' ').count { it.isNotBlank() }
    val fullMoves = (moveCount + 1) / 2
    val whoseTurn = if (moveCount % 2 == 0) "your turn" else "engine's turn"
    val createdLabel = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(game.createdAt))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Unfinished game", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "Started $createdLabel · $fullMoves full moves · $whoseTurn",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResume) { Text("Resume") }
                OutlinedButton(onClick = onDiscard) { Text("Discard") }
            }
        }
    }
}

@Composable
private fun HelpText() {
    Text(
        "Headset bindings while a game is active:\n" +
            "  • play/pause  →  open listen window (tap, speak move, pause)\n" +
            "  • next        →  re-speak last engine move\n" +
            "  • previous    →  cancel current listen window\n\n" +
            "Starting a game takes audio focus — other media pauses for the session.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ControlRow(
    serviceState: ChessGameService.State,
    gameState: GameController.State,
    micGranted: Boolean,
    onRequestMic: () -> Unit,
    onStartGame: () -> Unit,
    onStopGame: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            !micGranted -> Button(onClick = onRequestMic) { Text("Grant mic permission") }

            serviceState.gameActive -> Button(onClick = onStopGame) { Text("Stop game") }

            else -> Button(
                onClick = onStartGame,
                enabled = gameState.status != GameController.Status.Loading,
            ) { Text("Start game") }
        }
    }
}
