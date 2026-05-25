package io.github.eregruser.blindfoldchess

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.eregruser.blindfoldchess.service.ChessGameService
import io.github.eregruser.blindfoldchess.ui.AppNavHost
import io.github.eregruser.blindfoldchess.ui.theme.BlindfoldChessTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result not acted on */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
        setContent {
            BlindfoldChessTheme {
                AppNavHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If the user just returned from a music/video app that stole audio focus,
        // try to win it back so headset routing reaches us again. No-op when no
        // session is active or focus is already held.
        ChessGameService.tryReacquireFocus(this)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
