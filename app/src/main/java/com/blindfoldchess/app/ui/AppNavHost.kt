package com.blindfoldchess.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

object Routes {
    const val Main = "main"
    const val Settings = "settings"
    const val HeadphoneTest = "settings/headphone_test"
    const val EngineSelfTest = "settings/engine_self_test"
    const val VoiceTest = "settings/voice_test"
    const val GameHistory = "settings/game_history"
    const val GameDetail = "settings/game_history/{gameId}"
    const val Preferences = "settings/preferences"
    const val Board = "settings/board"

    fun gameDetail(gameId: Long) = "settings/game_history/$gameId"
}

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.Main) {
        composable(Routes.Main) {
            MainScreen(onOpenSettings = { nav.navigate(Routes.Settings) })
        }
        composable(Routes.Settings) {
            SettingsScreen(
                onOpenHeadphoneTest = { nav.navigate(Routes.HeadphoneTest) },
                onOpenEngineSelfTest = { nav.navigate(Routes.EngineSelfTest) },
                onOpenVoiceTest = { nav.navigate(Routes.VoiceTest) },
                onOpenGameHistory = { nav.navigate(Routes.GameHistory) },
                onOpenPreferences = { nav.navigate(Routes.Preferences) },
                onOpenBoard = { nav.navigate(Routes.Board) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.Preferences) {
            PreferencesScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.Board) {
            com.blindfoldchess.app.ui.board.BoardScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.HeadphoneTest) {
            HeadphoneTestScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.EngineSelfTest) {
            EngineSelfTestScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.VoiceTest) {
            VoiceTestScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.GameHistory) {
            GameHistoryScreen(
                onBack = { nav.popBackStack() },
                onOpenDetail = { id -> nav.navigate(Routes.gameDetail(id)) },
            )
        }
        composable(
            route = Routes.GameDetail,
            arguments = listOf(navArgument("gameId") { type = NavType.LongType }),
        ) { entry ->
            val gameId = entry.arguments?.getLong("gameId") ?: 0L
            GameDetailScreen(gameId = gameId, onBack = { nav.popBackStack() })
        }
    }
}
