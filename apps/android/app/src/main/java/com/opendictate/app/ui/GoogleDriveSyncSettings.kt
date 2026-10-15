package com.opendictate.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.Identity
import com.opendictate.app.OpenDictateApplication
import com.opendictate.app.R
import com.opendictate.app.data.GoogleDriveSync
import com.opendictate.app.data.SettingsSyncDocument

@Composable
internal fun GoogleDriveSyncSettings() {
    val context = LocalContext.current
    val sync = (context.applicationContext as OpenDictateApplication).driveSync
    val state by sync.state.collectAsState()
    var authorizing by remember { mutableStateOf(false) }
    // Feedback belongs to the user's sign-in action, never to persisted sync failures.
    fun showAuthorizationFailure() {
        authorizing = false
        sync.authorizationFailed()
        Toast.makeText(context, R.string.drive_sync_sign_in_again, Toast.LENGTH_LONG).show()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        authorizing = false
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                val token = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data).accessToken
                if (token != null) sync.connect(token) else showAuthorizationFailure()
            } catch (_: Exception) { showAuthorizationFailure() }
        }
    }
    if (state.needsSourceSelection) {
        DriveSourceSelectionDialog(onChoose = sync::chooseSource, onCancel = sync::disconnect)
    }
    val checked = state.enabled && !state.needsAuthorization
    val authorizationStatus = stringResource(R.string.drive_sync_authorizing)
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = !authorizing, role = Role.Switch, onValueChange = { enabled ->
                if (!enabled) sync.disconnect()
                else {
                    val activity = context.findActivity()
                    if (activity == null) showAuthorizationFailure()
                    else {
                        authorizing = true
                        Identity.getAuthorizationClient(activity).authorize(GoogleDriveSync.request(selectAccount = true))
                            .addOnSuccessListener { result ->
                                if (result.hasResolution()) {
                                    val intent = result.pendingIntent
                                    if (intent != null) launcher.launch(IntentSenderRequest.Builder(intent).build())
                                    else showAuthorizationFailure()
                                } else {
                                    authorizing = false
                                    result.accessToken?.let(sync::connect) ?: showAuthorizationFailure()
                                }
                            }.addOnFailureListener { showAuthorizationFailure() }
                    }
                }
            })
            .semantics { if (authorizing) stateDescription = authorizationStatus }
            .heightIn(min = 80.dp)
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_google_drive_24), null, Modifier.size(24.dp), tint = Color.Unspecified)
        Spacer(Modifier.width(14.dp))
        Text(stringResource(R.string.drive_sync_provider), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = !authorizing)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
internal fun DriveSourceSelectionDialog(
    onChoose: (SettingsSyncDocument.ConnectionSource) -> Unit,
    onCancel: () -> Unit,
) {
    var source by remember { mutableStateOf(SettingsSyncDocument.ConnectionSource.CLOUD) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.drive_sync_source_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.drive_sync_source_message), color = MaterialTheme.colorScheme.onSurface)
                Column(Modifier.selectableGroup()) {
                    SettingsSyncDocument.ConnectionSource.entries.forEach { option ->
                        val cloud = option == SettingsSyncDocument.ConnectionSource.CLOUD
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = source == option, role = Role.RadioButton, onClick = { source = option })
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            RadioButton(selected = source == option, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(stringResource(if (cloud) R.string.drive_sync_source_cloud else R.string.drive_sync_source_local),
                                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                Text(stringResource(if (cloud) R.string.drive_sync_source_cloud_description else R.string.drive_sync_source_local_description),
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChoose(source) }) {
                Text(stringResource(if (source == SettingsSyncDocument.ConnectionSource.CLOUD)
                    R.string.drive_sync_use_cloud else R.string.drive_sync_use_local))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
    )
}
