package com.lihaila.skylark.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lihaila.skylark.R
import com.lihaila.skylark.data.AppStore
import com.lihaila.skylark.data.Mode
import com.lihaila.skylark.vpn.Status
import com.lihaila.skylark.vpn.VpnState
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(actions: AppActions, onOpenServers: () -> Unit, modifier: Modifier = Modifier) {
    val state by AppStore.state.collectAsState()
    val status by VpnState.status.collectAsState()
    val error by VpnState.error.collectAsState()
    val traffic by VpnState.traffic.collectAsState()
    val since by VpnState.connectedSince.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(error) { error?.let { actions.toast(context.getString(R.string.error_prefix, it)) } }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.app_name), fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(28.dp))

        if (state.nodes.isEmpty()) {
            Welcome(actions)
            return@Column
        }

        ConnectButton(status) { actions.toggleVpn() }
        Spacer(Modifier.height(16.dp))
        Text(
            when (status) {
                Status.STOPPED -> stringResource(R.string.tap_to_connect)
                Status.STARTING -> stringResource(R.string.status_starting)
                Status.STARTED -> stringResource(R.string.status_on)
                Status.STOPPING -> stringResource(R.string.status_stopping)
            },
            style = MaterialTheme.typography.titleMedium,
        )
        if (status == Status.STARTED) ElapsedTime(since)
        Spacer(Modifier.height(24.dp))

        // Current server
        Card(
            Modifier.fillMaxWidth().clickable(onClick = onOpenServers),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.current_server), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                    Text(state.selected?.name ?: stringResource(R.string.no_server), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    state.selected?.let { Text(typeLabel(it.type), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
        Spacer(Modifier.height(16.dp))

        // Mode
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.mode), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                val modes = listOf(Mode.RULE to R.string.mode_rule, Mode.GLOBAL to R.string.mode_global, Mode.DIRECT to R.string.mode_direct)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = state.mode == mode,
                            onClick = { AppStore.setMode(mode); VpnState.reload(context) },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        ) { Text(stringResource(label)) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(when (state.mode) { Mode.RULE -> R.string.mode_rule_desc; Mode.GLOBAL -> R.string.mode_global_desc; Mode.DIRECT -> R.string.mode_direct_desc }),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // Traffic
        if (status == Status.STARTED) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TrafficCard(Icons.Default.ArrowUpward, stringResource(R.string.upload), traffic.up, traffic.upTotal, Modifier.weight(1f))
                TrafficCard(Icons.Default.ArrowDownward, stringResource(R.string.download), traffic.down, traffic.downTotal, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Welcome(actions: AppActions) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(120.dp).clip(CircleShape).background(Brand.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PowerSettingsNew, null, tint = Brand, modifier = Modifier.size(56.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.welcome_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = actions::pasteFromClipboard, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.add_paste))
        }
        Spacer(Modifier.height(12.dp))
        FilledTonalButton(onClick = actions::scanQr, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.add_scan))
        }
    }
}

@Composable
private fun ConnectButton(status: Status, onClick: () -> Unit) {
    val on = status == Status.STARTED
    val busy = status == Status.STARTING || status == Status.STOPPING
    val color by animateColorAsState(if (on) Brand else Color(0xFF9AA3B5), label = "c")
    val pulse = rememberInfiniteTransition(label = "p").animateFloat(
        1f, 1.08f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "s",
    )
    Box(
        Modifier.size(200.dp).scale(if (busy) pulse.value else 1f).clip(CircleShape).background(color.copy(alpha = 0.15f))
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(150.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PowerSettingsNew, null, tint = Color.White, modifier = Modifier.size(64.dp))
        }
    }
}

@Composable
private fun ElapsedTime(since: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val s = ((now - since) / 1000).coerceAtLeast(0)
    Text("%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun TrafficCard(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, speed: Long, total: Long, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(16.dp), tint = Brand); Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            }
            Text("${formatBytes(speed)}/s", style = MaterialTheme.typography.titleMedium)
            Text(formatBytes(total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

fun formatBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

fun typeLabel(type: String) = when (type) {
    "shadowsocks" -> "Shadowsocks"
    "vmess" -> "VMess"
    "vless" -> "VLESS"
    "trojan" -> "Trojan"
    "hysteria2" -> "Hysteria2"
    "tuic" -> "TUIC"
    "anytls" -> "AnyTLS"
    "socks" -> "SOCKS5"
    "http" -> "HTTP"
    else -> type
}
