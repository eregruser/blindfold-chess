package io.github.eregruser.blindfoldchess.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.eregruser.blindfoldchess.R

/**
 * Settings → About. Shows app version, links out to the public source repository,
 * privacy policy, an optional donation page, and an in-app licenses screen.
 *
 * All external links open in the system browser via ACTION_VIEW intents. No in-app
 * browser, no in-app billing — the donation row is a plain link to whatever URL is
 * configured in `R.string.donate_url` (empty hides the row).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onOpenLicenses: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val sourceUrl = stringResource(R.string.source_url)
    val privacyUrl = stringResource(R.string.privacy_policy_url)
    val donateUrl = stringResource(R.string.donate_url)

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    fun openUrl(url: String) {
        if (url.isBlank()) return
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
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
                .fillMaxSize(),
        ) {
            // Header — app name + version.
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (versionName.isNotEmpty()) {
                    Text(
                        text = "version $versionName",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Source code") },
                supportingContent = { Text("Open the GitHub repository in your browser") },
                modifier = Modifier.clickable { openUrl(sourceUrl) },
            )
            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Privacy policy") },
                supportingContent = { Text("No data leaves your device") },
                modifier = Modifier.clickable { openUrl(privacyUrl) },
            )
            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Open source licenses") },
                supportingContent = { Text("GPLv3 + third-party notices") },
                modifier = Modifier.clickable(onClick = onOpenLicenses),
            )
            HorizontalDivider()

            if (donateUrl.isNotBlank()) {
                ListItem(
                    headlineContent = { Text("Support development") },
                    supportingContent = { Text("Tip the developer if you find this useful") },
                    modifier = Modifier.clickable { openUrl(donateUrl) },
                )
                HorizontalDivider()
            }
        }
    }
}
