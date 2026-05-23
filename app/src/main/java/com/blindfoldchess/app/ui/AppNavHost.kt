package com.blindfoldchess.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

object Routes {
    const val Main = "main"
    const val Settings = "settings"
    const val HeadphoneTest = "settings/headphone_test"
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
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.HeadphoneTest) {
            HeadphoneTestScreen(onBack = { nav.popBackStack() })
        }
    }
}
