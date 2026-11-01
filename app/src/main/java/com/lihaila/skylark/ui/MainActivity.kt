package com.lihaila.skylark.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.lihaila.skylark.R
import com.lihaila.skylark.vpn.VpnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

val Brand = Color(0xFF3B5BFD)

/** Actions screens can trigger that need the Activity (permissions, clipboard, camera). */
interface AppActions {
    fun toggleVpn()
    fun pasteFromClipboard()
    fun scanQr()
    fun toast(text: String)
}

class MainActivity : ComponentActivity(), AppActions {

    private val snackbar = SnackbarHostState()

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (VpnService.prepare(this) == null) VpnState.start(this) else toast(getString(R.string.vpn_denied))
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { importText(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { SkylarkTheme { Root(this, snackbar) } }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        importText(text)
    }

    fun importText(text: String) {
        lifecycleScope.launch { showImportResult(Importer.import(text)) }
    }

    private fun showImportResult(r: ImportResult) {
        val msg = when (r) {
            is ImportResult.Nodes -> getString(R.string.imported_n, r.count)
            is ImportResult.Sub -> getString(R.string.sub_added, r.count)
            is ImportResult.Failed -> getString(R.string.sub_failed, r.message)
            ImportResult.Nothing -> getString(R.string.nothing_found)
        }
        toast(msg)
    }

    override fun toggleVpn() {
        when (VpnState.status.value) {
            com.lihaila.skylark.vpn.Status.STOPPED -> {
                if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                val prepare = VpnService.prepare(this)
                if (prepare != null) vpnPermission.launch(prepare) else VpnState.start(this)
            }
            com.lihaila.skylark.vpn.Status.STARTED -> VpnState.stop(this)
            else -> {}
        }
    }

    override fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        importText(text.orEmpty())
    }

    override fun scanQr() {
        qrScanner.launch(ScanOptions().setBeepEnabled(false).setOrientationLocked(false).setPrompt(""))
    }

    override fun toast(text: String) {
        lifecycleScope.launch { snackbar.showSnackbar(text) }
    }
}

@Composable
fun SkylarkTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(primary = Color(0xFF8EA2FF), secondary = Color(0xFF8EA2FF))
    else lightColorScheme(
        primary = Brand, secondary = Brand,
        primaryContainer = Color(0xFFE3E8FF), secondaryContainer = Color(0xFFE3E8FF),
        onSecondaryContainer = Color(0xFF1A2A80),
        background = Color(0xFFF6F7FB), surface = Color(0xFFF6F7FB),
        surfaceContainer = Color(0xFFFFFFFF),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun Root(actions: AppActions, snackbar: SnackbarHostState) {
    var tab by remember { mutableIntStateOf(0) }
    val scope: CoroutineScope = rememberCoroutineScope()
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                val items = listOf(
                    Icons.Default.Home to R.string.tab_home,
                    Icons.Default.Dns to R.string.tab_servers,
                    Icons.Default.Rule to R.string.tab_rules,
                    Icons.Default.Settings to R.string.tab_settings,
                )
                items.forEachIndexed { i, (icon, label) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icon, null) }, label = { Text(stringResource(label)) },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            0 -> HomeScreen(actions, onOpenServers = { tab = 1 }, modifier = m)
            1 -> ServersScreen(actions, scope, modifier = m)
            2 -> RulesScreen(modifier = m)
            else -> SettingsScreen(modifier = m)
        }
    }
}
