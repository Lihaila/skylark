package com.lihaila.skylark.vpn

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Status { STOPPED, STARTING, STARTED, STOPPING }

data class Traffic(val up: Long = 0, val down: Long = 0, val upTotal: Long = 0, val downTotal: Long = 0)

/** In-process view of the VPN for the UI. */
object VpnState {
    val status = MutableStateFlow(Status.STOPPED)
    val error = MutableStateFlow<String?>(null)
    val traffic = MutableStateFlow(Traffic())
    val connectedSince = MutableStateFlow(0L)
    val logs = MutableStateFlow<List<String>>(emptyList())

    fun appendLogs(lines: List<String>) {
        if (lines.isEmpty()) return
        logs.value = (logs.value + lines).takeLast(500)
    }

    val statusFlow: StateFlow<Status> get() = status

    fun start(context: Context) {
        error.value = null
        ContextCompat.startForegroundService(context, Intent(context, SkylarkVpnService::class.java).setAction(SkylarkVpnService.ACTION_START))
    }

    fun stop(context: Context) {
        context.startService(Intent(context, SkylarkVpnService::class.java).setAction(SkylarkVpnService.ACTION_STOP))
    }

    /** Apply new settings to a running connection. No-op when stopped. */
    fun reload(context: Context) {
        if (status.value != Status.STARTED) return
        context.startService(Intent(context, SkylarkVpnService::class.java).setAction(SkylarkVpnService.ACTION_RELOAD))
    }
}
