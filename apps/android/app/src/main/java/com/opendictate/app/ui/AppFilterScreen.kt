package com.opendictate.app.ui

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.opendictate.app.R
import com.opendictate.app.model.AppFilter
import com.opendictate.app.model.AppFilterMode

@Composable
internal fun AppFilterScreen(
    apps: List<InstalledApp>,
    appFilter: AppFilter,
    loading: Boolean,
    onBack: () -> Unit,
    onModeChange: (AppFilterMode) -> Unit,
    onSelectedChange: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val visibleApps = filterAndPrioritizeApps(apps, query, appFilter.selectedPackages)
    val packageManager = LocalContext.current.packageManager

    LazyColumn(
        modifier.fillMaxSize().background(SettingsCanvas).statusBarsPadding().navigationBarsPadding().imePadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.app_filter_back))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.app_filter_title),
                    color = SettingsText,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                )
            }
        }
        item {
            Column(Modifier.selectableGroup().padding(horizontal = 12.dp, vertical = 8.dp)) {
                AppFilterMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(
                                selected = appFilter.mode == mode,
                                onClick = { onModeChange(mode) },
                                role = Role.RadioButton,
                            ).padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = appFilter.mode == mode, onClick = null)
                        Text(
                            stringResource(
                                when (mode) {
                                    AppFilterMode.ALL_EXCEPT_SELECTED -> R.string.app_filter_mode_exclude
                                    AppFilterMode.ONLY_SELECTED -> R.string.app_filter_mode_allow
                                },
                            ),
                            color = SettingsText,
                        )
                    }
                }
            }
        }
        if (appFilter.mode == AppFilterMode.ONLY_SELECTED && appFilter.allowedPackages.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.app_filter_allow_empty),
                    color = SettingsText,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                )
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                label = { Text(stringResource(R.string.app_filter_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.app_filter_clear_search))
                        }
                    }
                } else null,
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
        }
        if (loading) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (visibleApps.isEmpty()) {
            item {
                Text(
                    stringResource(if (apps.isEmpty()) R.string.app_filter_empty else R.string.app_filter_no_matches),
                    color = SettingsMuted,
                    modifier = Modifier.padding(20.dp),
                )
            }
        } else {
            items(visibleApps, key = InstalledApp::packageName) { app ->
                val selected = app.packageName in appFilter.selectedPackages
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(
                        value = selected,
                        role = Role.Switch,
                        onValueChange = { onSelectedChange(app.packageName, it) },
                    ).padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AndroidView(
                        factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                        update = { image ->
                            image.setImageDrawable(runCatching {
                                packageManager.getApplicationIcon(app.packageName)
                            }.getOrNull())
                        },
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        app.label,
                        modifier = Modifier.weight(1f),
                        color = SettingsText,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Switch(
                        checked = selected,
                        onCheckedChange = null,
                    )
                }
            }
        }
    }
}

internal fun filterAndPrioritizeApps(
    apps: List<InstalledApp>,
    query: String,
    selectedPackages: Set<String>,
): List<InstalledApp> {
    val matching = apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
    val (selected, unselected) = matching.partition { it.packageName in selectedPackages }
    return selected + unselected
}
