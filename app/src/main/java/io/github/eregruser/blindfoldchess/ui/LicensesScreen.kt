package io.github.eregruser.blindfoldchess.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Plain-text view of the project's license and third-party notices. Kept human-readable
 * (not legalese minimum) so users can verify what's bundled and under what terms.
 *
 * Mirrors the content of LICENSE (GPLv3) and THIRD_PARTY_NOTICES.md in the repo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open source licenses") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section(
                title = "This app",
                body = "Blindfold Chess is distributed under the GNU General Public License " +
                    "version 3 (GPLv3). The full license text is bundled with the source " +
                    "repository as LICENSE. Source is available at the link in the About " +
                    "screen — anyone who has this app may obtain, modify, and redistribute " +
                    "the source under the same terms.",
            )
            HorizontalDivider()
            Section(
                title = "Stockfish chess engine",
                body = "Source vendored as a git submodule under app/src/main/cpp/stockfish " +
                    "at the sf_18 release tag. The companion NNUE network files " +
                    "(nn-c288c895ea92.nnue and nn-37f18f62d772.nnue) are part of the " +
                    "Stockfish project and inherit its license. Licensed under " +
                    "GPLv3 — this app's license is GPLv3 in part because it links Stockfish.",
            )
            HorizontalDivider()
            Section(
                title = "Cburnett chess piece artwork",
                body = "The 12 chess piece vector drawables are adapted from the Cburnett " +
                    "piece set by Colin M.L. Burnett, distributed by Lichess. SVGs were " +
                    "converted to Android Vector Drawable XML via " +
                    "scripts/svg_to_vector_drawable.py. Licensed under " +
                    "Creative Commons Attribution-ShareAlike 3.0 Unported (CC-BY-SA 3.0).",
            )
            HorizontalDivider()
            Section(
                title = "Vosk speech recognition",
                body = "com.alphacephei:vosk-android (Android library) and " +
                    "vosk-model-small-en-us-0.15 (English acoustic model). Both distributed " +
                    "by Alpha Cephei under Apache License 2.0.",
            )
            HorizontalDivider()
            Section(
                title = "chesslib",
                body = "com.github.bhlangonijr:chesslib — used for UCI → SAN move conversion " +
                    "for display and TTS. Published via JitPack. Apache License 2.0.",
            )
            HorizontalDivider()
            Section(
                title = "AndroidX, Jetpack Compose, Material 3, Room, DataStore",
                body = "Various Google-published libraries, all under Apache License 2.0.",
            )
        }
    }
}

@Composable
private fun Section(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(body, style = MaterialTheme.typography.bodySmall)
    }
}
