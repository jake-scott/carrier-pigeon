// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalMaterial3Api::class)

package dev.jakethesnake.carrierpigeon.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jakethesnake.carrierpigeon.R

/** The About page, opened from the home screen's menu: name, icon, version and project links. */
class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { AboutRoute(onBack = ::finish) } }
    }
}

/** What this install is, as Android reports it, so it is right for every flavor and build type. */
internal data class AppVersion(val name: String, val code: Long)

internal fun Context.appVersion(): AppVersion {
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0)
    }
    return AppVersion(info.versionName.orEmpty(), info.longVersionCode)
}

private const val SOURCE_URL = "https://github.com/jake-scott/carrier-pigeon"
private const val ISSUES_URL = "$SOURCE_URL/issues"
private const val PRIVACY_URL = "$SOURCE_URL/blob/main/docs/privacy.md"
private const val LICENSE_URL = "$SOURCE_URL/blob/main/LICENSE"

@Composable
private fun AboutRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember { context.appVersion() }
    val open: (String) -> Unit = { url ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            // No browser installed: nothing to do.
        }
    }
    AboutScreen(version, onBack, open)
}

@Composable
private fun AboutScreen(version: AppVersion, onBack: () -> Unit, open: (String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.about_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            AppIcon(96.dp)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.widget_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(16.dp))
            // Selectable, so the exact version can be copied into a bug report.
            SelectionContainer {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.about_version, version.name), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.about_version_code, version.code),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            LinkRow(stringResource(R.string.about_privacy)) { open(PRIVACY_URL) }
            LinkRow(stringResource(R.string.about_source)) { open(SOURCE_URL) }
            LinkRow(stringResource(R.string.about_report_problem)) { open(ISSUES_URL) }
            HorizontalDivider()
            TextButton(onClick = { open(LICENSE_URL) }, modifier = Modifier.padding(vertical = 8.dp)) {
                Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}
