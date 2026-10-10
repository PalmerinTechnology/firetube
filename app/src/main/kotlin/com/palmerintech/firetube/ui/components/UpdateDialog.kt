package com.palmerintech.firetube.ui.components

import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.palmerintech.firetube.R
import com.palmerintech.firetube.update.AppUpdater
import com.palmerintech.firetube.update.UpdateInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * "FireTube X is available": the release notes, then Update downloads and installs it, reporting
 * progress through [onProgress] (null when done or failed).
 */
@Composable
fun UpdateDialog(
    updater: AppUpdater,
    update: UpdateInfo,
    /** The caller's: the install outlives the dialog. */
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onProgress: (Float?) -> Unit,
    onShowMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_title, update.versionName)) },
        text = { Text(update.notes.ifBlank { stringResource(R.string.update_default_notes) }) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                if (!context.packageManager.canRequestPackageInstalls()) {
                    onShowMessage(resources.getString(R.string.update_allow_install))
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
                    return@TextButton
                }
                scope.launch {
                    onProgress(0f)
                    runCatching { updater.install(update) { p -> onProgress(p) } }
                        .onFailure { onShowMessage(resources.getString(R.string.update_failed, it.message)) }
                    onProgress(null)
                }
            }) { Text(stringResource(R.string.update_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) } },
    )
}
