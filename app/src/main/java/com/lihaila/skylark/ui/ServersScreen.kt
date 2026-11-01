package com.lihaila.skylark.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lihaila.skylark.R
import com.lihaila.skylark.core.Network
import com.lihaila.skylark.data.AppStore
import com.lihaila.skylark.data.Node
import com.lihaila.skylark.vpn.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Latency results survive tab switches but not app restarts. -1 = timeout, -2 = testing. */
object Latency {
    val map = mutableStateMapOf<String, Int>()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(actions: AppActions, scope: CoroutineScope, modifier: Modifier = Modifier) {
    val state by AppStore.state.collectAsState()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var showSub by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Node?>(null) }
    var updating by remember { mutableStateOf(false) }

    fun testAll() = scope.launch {
        state.nodes.forEach { Latency.map[it.id] = -2 }
        Network.pingAll(state.nodes) { id, ms -> Latency.map[id] = ms }
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.tab_servers)) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            actions = {
                IconButton(onClick = { testAll() }) { Icon(Icons.Default.Speed, stringResource(R.string.test_latency)) }
                if (state.subs.isNotEmpty()) {
                    IconButton(enabled = !updating, onClick = {
                        updating = true
                        scope.launch {
                            val (ok, err) = Importer.updateAll()
                            updating = false
                            actions.toast(err?.let { context.getString(R.string.sub_failed, it) } ?: context.getString(R.string.sub_updated, ok))
                            VpnState.reload(context)
                        }
                    }) {
                        if (updating) CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, stringResource(R.string.update_subs))
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.Add, stringResource(R.string.add)) }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem({ Text(stringResource(R.string.add_paste)) }, leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                            onClick = { menu = false; actions.pasteFromClipboard() })
                        DropdownMenuItem({ Text(stringResource(R.string.add_scan)) }, leadingIcon = { Icon(Icons.Default.QrCodeScanner, null) },
                            onClick = { menu = false; actions.scanQr() })
                        DropdownMenuItem({ Text(stringResource(R.string.add_sub)) }, leadingIcon = { Icon(Icons.Default.Link, null) },
                            onClick = { menu = false; showSub = true })
                        DropdownMenuItem({ Text(stringResource(R.string.add_manual)) }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menu = false; showManual = true })
                    }
                }
            },
        )

        if (state.nodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.servers_empty), color = MaterialTheme.colorScheme.outline)
            }
        } else {
            val selectedId = state.selected?.id
            val groups = buildList {
                val manual = state.nodes.filter { it.subId == null }
                if (manual.isNotEmpty()) add(Triple<String?, String, List<Node>>(null, context.getString(R.string.manual_servers), manual))
                state.subs.forEach { sub -> add(Triple(sub.id, sub.name, state.nodes.filter { it.subId == sub.id })) }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                groups.forEach { (subId, title, nodes) ->
                    item(key = "h-${subId ?: "manual"}") {
                        GroupHeader(title, nodes.size, subId?.let { id ->
                            {
                                var m by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { m = true }) { Icon(Icons.Default.MoreVert, null) }
                                    DropdownMenu(m, onDismissRequest = { m = false }) {
                                        DropdownMenuItem({ Text(stringResource(R.string.update)) }, onClick = {
                                            m = false
                                            scope.launch {
                                                val sub = AppStore.state.value.subs.first { it.id == id }
                                                runCatching { Network.updateSubscription(sub) }
                                                    .onSuccess { actions.toast(context.getString(R.string.sub_updated, 1)); VpnState.reload(context) }
                                                    .onFailure { actions.toast(context.getString(R.string.sub_failed, it.message)) }
                                            }
                                        })
                                        DropdownMenuItem({ Text(stringResource(R.string.delete)) }, onClick = { m = false; AppStore.deleteSub(id) })
                                    }
                                }
                            }
                        })
                    }
                    items(nodes, key = { it.id }) { node ->
                        NodeRow(
                            node, node.id == selectedId, Latency.map[node.id],
                            onSelect = { AppStore.select(node.id); VpnState.reload(context) },
                            onEdit = { editing = node },
                            onDelete = { AppStore.deleteNode(node.id) },
                        )
                    }
                }
            }
        }
    }

    if (showSub) SubscriptionDialog(onDismiss = { showSub = false }) { name, url ->
        showSub = false
        scope.launch {
            val r = Importer.addSubscription(url, name)
            actions.toast(
                when (r) {
                    is ImportResult.Sub -> context.getString(R.string.sub_added, r.count)
                    is ImportResult.Failed -> context.getString(R.string.sub_failed, r.message)
                    else -> context.getString(R.string.nothing_found)
                }
            )
        }
    }
    if (showManual) ManualDialog(onDismiss = { showManual = false }) { AppStore.upsertNode(it); showManual = false }
    editing?.let { n ->
        EditJsonDialog(n, onDismiss = { editing = null }) { AppStore.upsertNode(it); editing = null; VpnState.reload(context) }
    }
}

@Composable
private fun GroupHeader(title: String, count: Int, trailing: (@Composable () -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$title · $count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
private fun NodeRow(node: Node, selected: Boolean, latency: Int?, onSelect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(node.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(typeLabel(node.type), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        LatencyText(latency)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, null) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.edit)) }, onClick = { menu = false; onEdit() })
                DropdownMenuItem({ Text(stringResource(R.string.delete)) }, onClick = { menu = false; onDelete() })
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun LatencyText(ms: Int?) {
    when {
        ms == null -> {}
        ms == -2 -> CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp)
        ms < 0 -> Text(stringResource(R.string.timeout), color = Color(0xFFE5484D), style = MaterialTheme.typography.labelMedium)
        else -> Text(
            "$ms ms", style = MaterialTheme.typography.labelMedium,
            color = when { ms < 150 -> Color(0xFF30A46C); ms < 400 -> Color(0xFFF5A524); else -> Color(0xFFE5484D) },
        )
    }
}

@Composable
private fun SubscriptionDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_sub)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.sub_url)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.sub_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = url.startsWith("http"), onClick = { onAdd(name, url.trim()) }) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private val ssMethods = listOf(
    "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305",
    "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "xchacha20-ietf-poly1305",
)

@Composable
private fun ManualDialog(onDismiss: () -> Unit, onSave: (Node) -> Unit) {
    val types = listOf("shadowsocks", "vmess", "vless", "trojan", "hysteria2")
    var type by remember { mutableStateOf("shadowsocks") }
    var name by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("aes-256-gcm") }
    var tls by remember { mutableStateOf(true) }
    var sni by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("tcp") }
    var path by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val usesTlsToggle = type == "vmess" || type == "vless"
    val usesTransport = type == "vmess" || type == "vless" || type == "trojan"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_manual)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.type), style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth().horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    types.forEach { t -> FilterChip(type == t, onClick = { type = t }, label = { Text(typeLabel(t)) }) }
                }
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(server, { server = it.trim() }, label = { Text(stringResource(R.string.address)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.port)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it.trim() },
                    label = { Text(if (type == "vmess" || type == "vless") "UUID" else stringResource(R.string.password)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (type == "shadowsocks") {
                    Text(stringResource(R.string.method), style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth().horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ssMethods.forEach { m -> FilterChip(method == m, onClick = { method = m }, label = { Text(m) }) }
                    }
                }
                if (usesTlsToggle) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.tls), Modifier.weight(1f)); Switch(tls, { tls = it })
                    }
                }
                if (type != "shadowsocks" && (tls || !usesTlsToggle)) {
                    OutlinedTextField(sni, { sni = it.trim() }, label = { Text(stringResource(R.string.sni)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (usesTransport) {
                    Text(stringResource(R.string.transport), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("tcp", "ws", "grpc").forEach { t -> FilterChip(transport == t, onClick = { transport = t }, label = { Text(t.uppercase()) }) }
                    }
                    if (transport != "tcp") {
                        OutlinedTextField(path, { path = it.trim() }, label = { Text(if (transport == "grpc") "Service name" else stringResource(R.string.path)) },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    if (transport == "ws") {
                        OutlinedTextField(host, { host = it.trim() }, label = { Text(stringResource(R.string.host)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (error) Text(stringResource(R.string.fill_required), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = port.toIntOrNull()
                if (server.isEmpty() || p == null || secret.isEmpty()) { error = true; return@TextButton }
                val o = JSONObject().put("type", type).put("server", server).put("server_port", p)
                when (type) {
                    "shadowsocks" -> o.put("method", method).put("password", secret)
                    "vmess" -> o.put("uuid", secret).put("security", "auto").put("alter_id", 0)
                    "vless" -> o.put("uuid", secret)
                    else -> o.put("password", secret)
                }
                if (type != "shadowsocks" && (tls || !usesTlsToggle)) {
                    o.put("tls", JSONObject().put("enabled", true).put("server_name", sni.ifEmpty { server }))
                }
                if (usesTransport) when (transport) {
                    "ws" -> o.put("transport", JSONObject().put("type", "ws").put("path", path.ifEmpty { "/" }).apply {
                        if (host.isNotEmpty()) put("headers", JSONObject().put("Host", host))
                    })
                    "grpc" -> o.put("transport", JSONObject().put("type", "grpc").put("service_name", path))
                }
                onSave(Node(name = name.ifBlank { "$server:$p" }, outbound = o.toString()))
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun EditJsonDialog(node: Node, onDismiss: () -> Unit, onSave: (Node) -> Unit) {
    var name by remember { mutableStateOf(node.name) }
    var json by remember { mutableStateOf(runCatching { JSONObject(node.outbound).toString(2) }.getOrDefault(node.outbound)) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.advanced_json), style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(json, { json = it; error = false }, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp), isError = error)
                if (error) Text(stringResource(R.string.invalid_json), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val o = runCatching { JSONObject(json) }.getOrNull()
                if (o == null || !o.has("type")) { error = true; return@TextButton }
                o.remove("tag")
                onSave(node.copy(name = name.ifBlank { node.name }, outbound = o.toString()))
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))
