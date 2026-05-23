package com.blindfoldchess.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blindfoldchess.app.BlindfoldChessApp
import com.blindfoldchess.app.data.SettingsRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreferencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as BlindfoldChessApp
    val repo = app.settingsRepository
    val settings by repo.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Game preferences") },
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SkillSection(
                level = settings.skillLevel,
                onChange = { v -> scope.launch { repo.setSkillLevel(v) } },
            )
            HorizontalDivider()
            MoveTimeSection(
                ms = settings.moveTimeMs,
                onChange = { v -> scope.launch { repo.setMoveTimeMs(v) } },
            )
            HorizontalDivider()
            NotationSection(
                notation = settings.notation,
                onChange = { v -> scope.launch { repo.setNotation(v) } },
            )
            HorizontalDivider()
            VerbositySection(
                verbose = settings.verbose,
                onChange = { v -> scope.launch { repo.setVerbose(v) } },
            )
            HorizontalDivider()
            FogModeSection(
                fogMode = settings.fogMode,
                onChange = { v -> scope.launch { repo.setFogMode(v) } },
            )
            Text(
                "Changes apply immediately. Skill level applies to new games only — " +
                    "in-progress games keep the level they started with.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SkillSection(level: Int, onChange: (Int) -> Unit) {
    val label = when (level) {
        in 0..4 -> "beginner"
        in 5..10 -> "club"
        in 11..15 -> "strong"
        else -> "master"
    }
    Column {
        Text("Engine skill: $level ($label)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Stockfish \"Skill Level\" UCI option (0–20). Higher = stronger play.",
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = level.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..20f,
            steps = 19,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveTimeSection(ms: Long, onChange: (Long) -> Unit) {
    val options = listOf(100L, 500L, 1_000L, 3_000L, 10_000L)
    Column {
        Text("Engine think time: ${formatMs(ms)}", style = MaterialTheme.typography.titleMedium)
        Text(
            "How long the engine searches per move. Longer = stronger but slower replies.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { opt ->
                FilterChip(
                    selected = ms == opt,
                    onClick = { onChange(opt) },
                    label = { Text(formatMs(opt)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotationSection(
    notation: SettingsRepository.Notation,
    onChange: (SettingsRepository.Notation) -> Unit,
) {
    val entries = SettingsRepository.Notation.entries
    Column {
        Text("Spoken notation", style = MaterialTheme.typography.titleMedium)
        Text(
            "How engine moves are announced via TTS. NATO is more distinctive over noisy " +
                "audio; letter-by-letter is shorter.",
            style = MaterialTheme.typography.bodySmall,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(top = 8.dp)) {
            entries.forEachIndexed { idx, opt ->
                SegmentedButton(
                    selected = opt == notation,
                    onClick = { onChange(opt) },
                    shape = SegmentedButtonDefaults.itemShape(idx, entries.size),
                    label = {
                        Text(
                            when (opt) {
                                SettingsRepository.Notation.LetterByLetter -> "letter (e 7 e 5)"
                                SettingsRepository.Notation.Nato -> "NATO (echo 7 echo 5)"
                                SettingsRepository.Notation.Standard -> "standard (e5 / Nf3)"
                            }
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun VerbositySection(verbose: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Verbose announcements", style = MaterialTheme.typography.titleMedium)
            Text(
                if (verbose) "\"black plays echo seven echo five. your turn.\""
                else "\"echo seven echo five\"",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = verbose, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FogModeSection(
    fogMode: SettingsRepository.FogMode,
    onChange: (SettingsRepository.FogMode) -> Unit,
) {
    val entries = SettingsRepository.FogMode.entries
    Column {
        Text("Board fog default", style = MaterialTheme.typography.titleMedium)
        Text(
            "Initial fog state when opening Board view. Per-square taps override after entry.",
            style = MaterialTheme.typography.bodySmall,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(top = 8.dp)) {
            entries.forEachIndexed { idx, opt ->
                SegmentedButton(
                    selected = opt == fogMode,
                    onClick = { onChange(opt) },
                    shape = SegmentedButtonDefaults.itemShape(idx, entries.size),
                    label = {
                        Text(
                            when (opt) {
                                SettingsRepository.FogMode.FogAll -> "all"
                                SettingsRepository.FogMode.FogOpponent -> "opponent"
                                SettingsRepository.FogMode.RevealAll -> "none"
                            }
                        )
                    },
                )
            }
        }
    }
}

private fun formatMs(ms: Long): String = when {
    ms >= 1_000 -> "${ms / 1000.0}s".replace(".0s", "s")
    else -> "${ms}ms"
}
