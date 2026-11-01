package com.lihaila.skylark.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.drawable.toBitmap
import androidx.core.os.LocaleListCompat
import com.lihaila.skylark.R
import com.lihaila.skylark.data.AppStore
import com.lihaila.skylark.data.PerAppMode
import com.lihaila.skylark.vpn.VpnState
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val state by AppStore.state.collectAsState()
    val s = state.settings
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    var editDns by remember { mutableStateOf<Boolean?>(null) } // true = remote, false = direct
    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    val clear = ListItemDefaults.colors(containerColor = Color.Transparent)
    val versionName = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_settings)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section(stringResource(R.string.per_app))
            Card(colors = cardColors) {
                listOf(PerAppMode.OFF to R.string.per_app_off, PerAppMode.BYPASS to R.string.per_app_bypass, PerAppMode.ONLY to R.string.per_app_only)
                    .forEach { (mode, label) ->
                        ListItem(
                            colors = clear,
                            modifier = Modifier.clickable { AppStore.setSettings { it.copy(perAppMode = mode) }; VpnState.reload(context) },
                            headlineContent = { Text(stringResource(label)) },
                            leadingContent = { RadioButton(s.perAppMode == mode, onClick = null) },
                        )
                    }
                if (s.perAppMode != PerAppMode.OFF) {
                    ListItem(
                        colors = clear,
                        modifier = Modifier.clickable { picking = true },
                        headlineContent = { Text(stringResource(R.string.choose_apps, s.perAppPackages.size), color = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    )
                }
            }

            Section(stringResource(R.string.dns))
            Card(colors = cardColors) {
                ListItem(colors = clear, modifier = Modifier.clickable { editDns = true },
                    headlineContent = { Text(stringResource(R.string.remote_dns)) }, supportingContent = { Text(s.remoteDns) })
                ListItem(colors = clear, modifier = Modifier.clickable { editDns = false },
                    headlineContent = { Text(stringResource(R.string.direct_dns)) }, supportingContent = { Text(s.directDns) })
                ToggleRow(stringResource(R.string.ipv6), stringResource(R.string.ipv6_desc), s.ipv6) { v ->
                    AppStore.setSettings { it.copy(ipv6 = v) }; VpnState.reload(context)
                }
            }
            Text(stringResource(R.string.settings_apply_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 4.dp))

            Section(stringResource(R.string.language))
            Card(colors = cardColors) {
                val current = AppCompatDelegate.getApplicationLocales()[0]
                // Traditional = Hant script or a TW/HK/MO region; anything else in "zh" is Simplified.
                val currentTag = when {
                    current == null -> ""
                    current.language != "zh" -> current.language
                    current.script == "Hant" || current.country in setOf("TW", "HK", "MO") -> "zh-TW"
                    else -> "zh-CN"
                }
                // Language names stay in their own language so people can always find theirs.
                listOf("" to stringResource(R.string.lang_system), "en" to "English", "zh-CN" to "简体中文", "zh-TW" to "繁體中文").forEach { (tag, label) ->
                    val selected = currentTag == tag
                    ListItem(
                        colors = clear,
                        modifier = Modifier.clickable {
                            AppCompatDelegate.setApplicationLocales(
                                if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
                            )
                        },
                        headlineContent = { Text(label) },
                        leadingContent = { RadioButton(selected, onClick = null) },
                    )
                }
            }

            Section(stringResource(R.string.about))
            Card(colors = cardColors) {
                ListItem(colors = clear, headlineContent = { Text(stringResource(R.string.version)) }, trailingContent = { Text(versionName ?: "") })
                ListItem(colors = clear, headlineContent = { Text(stringResource(R.string.core_version)) },
                    trailingContent = { Text("sing-box " + runCatching { Libbox.version() }.getOrDefault("?")) })
                ListItem(
                    colors = clear,
                    modifier = Modifier.clickable {
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(SOURCE_URL)))
                        }
                    },
                    headlineContent = { Text(stringResource(R.string.source_code)) },
                    supportingContent = { Text(SOURCE_URL.removePrefix("https://")) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                )
                ListItem(colors = clear, headlineContent = { Text(stringResource(R.string.license_note), style = MaterialTheme.typography.bodySmall) })
            }
            Row(Modifier.heightIn(min = 16.dp)) {}
        }
    }

    if (picking) AppPicker(s.perAppPackages, onDismiss = { picking = false }) { pkgs ->
        AppStore.setSettings { it.copy(perAppPackages = pkgs) }; picking = false; VpnState.reload(context)
    }
    editDns?.let { remote ->
        var text by remember(remote) { mutableStateOf(if (remote) s.remoteDns else s.directDns) }
        AlertDialog(
            onDismissRequest = { editDns = null },
            title = { Text(stringResource(if (remote) R.string.remote_dns else R.string.direct_dns)) },
            text = { OutlinedTextField(text, { text = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = text.isNotEmpty(), onClick = {
                    AppStore.setSettings { if (remote) it.copy(remoteDns = text) else it.copy(directDns = text) }
                    editDns = null; VpnState.reload(context)
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { editDns = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private const val SOURCE_URL = "https://github.com/Lihaila/skylark"

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

private data class AppEntry(val pkg: String, val label: String, val system: Boolean, val info: ApplicationInfo)

@Composable
private fun AppPicker(initial: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var selected by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != context.packageName }
                .map { AppEntry(it.packageName, it.loadLabel(pm).toString(), (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0, it) }
                .sortedWith(compareByDescending<AppEntry> { it.pkg in initial }.thenBy { it.label.lowercase() })
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.choose_apps, selected.size)) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text(stringResource(R.string.search)) }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { showSystem = !showSystem }) {
                    Checkbox(showSystem, onCheckedChange = { showSystem = it }); Text(stringResource(R.string.show_system))
                }
                val list = apps.orEmpty().filter { (showSystem || !it.system || it.pkg in selected) &&
                    (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true)) }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.pkg }) { app ->
                        val icon = remember(app.pkg) { runCatching { app.info.loadIcon(pm).toBitmap(64, 64).asImageBitmap() }.getOrNull() }
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = if (app.pkg in selected) selected - app.pkg else selected + app.pkg }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            icon?.let { Image(it, null, Modifier.size(32.dp)) }
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(app.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                            }
                            Checkbox(app.pkg in selected, onCheckedChange = null)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
