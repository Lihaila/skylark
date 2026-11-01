package com.lihaila.skylark.vpn

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.lihaila.skylark.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Quick Settings toggle: connect/disconnect from the notification shade. */
class QuickTile : TileService() {
    private var job: Job? = null

    override fun onStartListening() {
        job = CoroutineScope(Dispatchers.Main).launch {
            VpnState.status.collect { s ->
                qsTile?.apply {
                    state = if (s == Status.STARTED || s == Status.STARTING) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    updateTile()
                }
            }
        }
    }

    override fun onStopListening() {
        job?.cancel()
    }

    @Suppress("DEPRECATION")
    override fun onClick() {
        when (VpnState.status.value) {
            Status.STARTED, Status.STARTING -> VpnState.stop(this)
            else -> {
                if (VpnService.prepare(this) != null) {
                    // Permission not granted yet: open the app to ask.
                    startActivityAndCollapse(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    VpnState.start(this)
                }
            }
        }
    }
}
