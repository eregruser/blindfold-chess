package com.blindfoldchess.app.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Static cheat sheet of every voice-command phrase the parser accepts (see
 * [com.blindfoldchess.app.voice.VoiceCommandParser]) plus the move-utterance shape
 * enforced by [com.blindfoldchess.app.voice.ChessGrammar]. Hand-curated; if you add a new
 * command or trigger phrase, add it here too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCommandsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice commands") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            item { Intro() }

            item { SectionHeader("Moves") }
            items(MOVES) { it.Row() }

            item { SectionHeader("Queries") }
            items(QUERIES) { it.Row() }

            item { SectionHeader("Control") }
            items(CONTROL) { it.Row() }
        }
    }
}

private data class CommandHelp(val phrase: String, val description: String) {
    @Composable
    fun Row() {
        ListItem(
            headlineContent = {
                Text(
                    text = phrase,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                )
            },
            supportingContent = {
                Text(description, style = MaterialTheme.typography.bodySmall)
            },
        )
        HorizontalDivider()
    }
}

@Composable
private fun Intro() {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "Tap a headset button during a game to open a listen window, then say any of the " +
                "following. The recognizer is constrained to chess-move utterances plus these " +
                "commands — anything else returns \"didn't catch that\".",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

// ---------- command data ----------

private val MOVES = listOf(
    CommandHelp(
        phrase = "echo two echo four",
        description = "Long-algebraic move with NATO files (recommended). Files: alpha, bravo, " +
            "charlie, delta, echo, foxtrot, golf, hotel. Ranks: one … eight.",
    ),
    CommandHelp(
        phrase = "castle king side",
        description = "Castle kingside. Also: \"short castle\".",
    ),
    CommandHelp(
        phrase = "castle queen side",
        description = "Castle queenside. Also: \"long castle\".",
    ),
    CommandHelp(
        phrase = "echo seven echo eight promote queen",
        description = "Pawn promotion. Piece: queen, rook, bishop, or knight.",
    ),
)

private val QUERIES = listOf(
    CommandHelp(
        phrase = "whose turn",
        description = "Announce side to move. Also: \"whose turn is it\".",
    ),
    CommandHelp(
        phrase = "how many moves",
        description = "Announce full-move count.",
    ),
    CommandHelp(
        phrase = "what is on echo three",
        description = "Announce the piece on the named square (or \"empty\"). Also: " +
            "\"whats on echo three\".",
    ),
    CommandHelp(
        phrase = "list my pieces",
        description = "List your pieces by type and square. Also: \"list pieces\".",
    ),
    CommandHelp(
        phrase = "describe board",
        description = "List every piece on the board, white then black. Long announcement — " +
            "tap any headset button to interrupt. Also: \"describe the board\".",
    ),
    CommandHelp(
        phrase = "read moves",
        description = "Read every move in chronological order with a 2-second gap between " +
            "each. White moves are prefixed with the move number. Tap any headset button " +
            "to interrupt. Also: \"read the moves\".",
    ),
)

private val CONTROL = listOf(
    CommandHelp(
        phrase = "repeat",
        description = "Re-speak the engine's last move. Also: \"repeat that\", " +
            "\"repeat the move\". Same effect as the headset \"next\" button.",
    ),
    CommandHelp(
        phrase = "undo",
        description = "Take back your last move and the engine's reply. Also: \"take back\", " +
            "\"undo that\".",
    ),
    CommandHelp(
        phrase = "new game",
        description = "End the current game (marks Abandoned) and start a fresh one with " +
            "current settings.",
    ),
    CommandHelp(
        phrase = "resign",
        description = "End the current game as a loss for you.",
    ),
)
