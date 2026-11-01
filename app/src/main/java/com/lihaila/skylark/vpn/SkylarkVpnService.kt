package com.lihaila.skylark.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import com.lihaila.skylark.R
import com.lihaila.skylark.core.ConfigBuilder
import com.lihaila.skylark.data.AppStore
import com.lihaila.skylark.ui.MainActivity
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet6Address
import java.net.InetSocketAddress
import io.nekohasekai.libbox.NetworkInterface as BoxInterface
import io.nekohasekai.libbox.Notification as BoxNotification

class SkylarkVpnService : VpnService(), PlatformInterface, CommandServerHandler {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_RELOAD = "reload"
        private const val CHANNEL = "vpn"
        private const val NOTIFY_ID = 1

        @Volatile private var libboxReady = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandServer: CommandServer? = null
    private var commandClient: CommandClient? = null
    private var tunFd: ParcelFileDescriptor? = null
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> scope.launch { stopVpn() }
            ACTION_RELOAD -> scope.launch { reload() }
            else -> {
                startForegroundCompat()
                scope.launch { startVpn() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        scope.launch { stopVpn() }
    }

    override fun onDestroy() {
        closeAll()
        scope.cancel()
        super.onDestroy()
    }

    // ---------- lifecycle ----------

    @Synchronized
    private fun startVpn() {
        if (VpnState.status.value == Status.STARTED || VpnState.status.value == Status.STARTING) return
        VpnState.status.value = Status.STARTING
        try {
            AppStore.init(this)
            ensureLibbox()
            val server = Libbox.newCommandServer(this, this)
            server.start()
            commandServer = server
            server.startOrReloadService(ConfigBuilder.build(this, AppStore.state.value), OverrideOptions())
            VpnState.connectedSince.value = System.currentTimeMillis()
            VpnState.status.value = Status.STARTED
            startTrafficClient()
            updateNotification()
            fetchHomeIpListIfMissing()
        } catch (e: Throwable) {
            VpnState.error.value = e.message ?: e.toString()
            stopVpn()
        }
    }

    /** If the home country's IP list isn't on disk yet, fetch it and apply it without a reconnect. */
    private fun fetchHomeIpListIfMissing() {
        val s = AppStore.state.value.settings
        if (!s.bypassHome) return
        val home = com.lihaila.skylark.core.Country.effective(this, s)
        if (home.isBlank() || home == "CN" || com.lihaila.skylark.core.Country.geoipFile(this, home).exists()) return
        scope.launch {
            if (com.lihaila.skylark.core.Country.ensureGeoip(this@SkylarkVpnService, home)) reload()
        }
    }

    @Synchronized
    private fun reload() {
        val server = commandServer ?: return
        try {
            server.startOrReloadService(ConfigBuilder.build(this, AppStore.state.value), OverrideOptions())
            updateNotification()
        } catch (e: Throwable) {
            VpnState.error.value = e.message ?: e.toString()
        }
    }

    @Synchronized
    private fun stopVpn() {
        if (VpnState.status.value == Status.STOPPED && commandServer == null) {
            stopSelf(); return
        }
        VpnState.status.value = Status.STOPPING
        closeAll()
        VpnState.traffic.value = Traffic()
        VpnState.status.value = Status.STOPPED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeAll() {
        runCatching { commandClient?.disconnect() }
        commandClient = null
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        runCatching { tunFd?.close() }
        tunFd = null
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    private fun ensureLibbox() {
        if (libboxReady) return
        val work = File(filesDir, "work").apply { mkdirs() }
        val options = SetupOptions().apply {
            basePath = filesDir.absolutePath
            workingPath = work.absolutePath
            tempPath = cacheDir.absolutePath
            fixAndroidStack = false
            logMaxLines = 2000
            crashReportSource = "Skylark"
        }
        Libbox.setup(options)
        libboxReady = true
    }

    private fun startTrafficClient() {
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus)
            addCommand(Libbox.CommandLog)
            statusInterval = 1_000_000_000L
        }
        val client = Libbox.newCommandClient(object : CommandClientHandler {
            override fun connected() {}
            override fun disconnected(message: String?) {}
            override fun setDefaultLogLevel(level: Int) {}
            override fun clearLogs() { VpnState.logs.value = emptyList() }
            override fun writeLogs(messageList: LogIterator?) {
                val list = messageList ?: return
                val lines = ArrayList<String>()
                while (list.hasNext()) list.next()?.message?.let { lines.add(it); android.util.Log.d("sing-box", it) }
                VpnState.appendLogs(lines)
            }
            override fun writeStatus(message: StatusMessage) {
                VpnState.traffic.value = Traffic(message.uplink, message.downlink, message.uplinkTotal, message.downlinkTotal)
            }
            override fun writeGroups(message: OutboundGroupIterator?) {}
            override fun writeOutbounds(message: OutboundGroupItemIterator?) {}
            override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}
            override fun updateClashMode(newMode: String?) {}
            override fun writeConnectionEvents(events: ConnectionEvents?) {}
        }, options)
        commandClient = client
        scope.launch { runCatching { client.connect() } }
    }

    // ---------- notification ----------

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SkylarkVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val nodeName = AppStore.state.value.selected?.name ?: getString(R.string.mode_direct)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.notif_connected))
            .setContentText(nodeName)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.disconnect), stop)
            .build()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFY_ID, buildNotification())
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFY_ID, buildNotification())
    }

    // ---------- PlatformInterface ----------

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("VPN permission not granted")
        val builder = Builder().setSession(getString(R.string.app_name)).setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

        val inet4 = options.inet4Address
        while (inet4.hasNext()) inet4.next().let { builder.addAddress(it.address(), it.prefix()) }
        val inet6 = options.inet6Address
        val hasV6 = inet6.hasNext()
        while (inet6.hasNext()) inet6.next().let { builder.addAddress(it.address(), it.prefix()) }

        if (options.autoRoute) {
            val dns = options.dnsServerAddress
            while (dns.hasNext()) builder.addDnsServer(dns.next())
            val r4 = options.inet4RouteAddress
            if (r4.hasNext()) while (r4.hasNext()) r4.next().let { builder.addRoute(it.address(), it.prefix()) }
            else builder.addRoute("0.0.0.0", 0)
            if (hasV6) {
                val r6 = options.inet6RouteAddress
                if (r6.hasNext()) while (r6.hasNext()) r6.next().let { builder.addRoute(it.address(), it.prefix()) }
                else builder.addRoute("::", 0)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                val e4 = options.inet4RouteExcludeAddress
                while (e4.hasNext()) e4.next().let { builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName(it.address()), it.prefix())) }
                val e6 = options.inet6RouteExcludeAddress
                while (e6.hasNext()) e6.next().let { builder.excludeRoute(android.net.IpPrefix(java.net.InetAddress.getByName(it.address()), it.prefix())) }
            }

            val include = options.includePackage
            if (include.hasNext()) {
                while (include.hasNext()) runCatching { builder.addAllowedApplication(include.next()) }
            } else {
                val exclude = options.excludePackage
                while (exclude.hasNext()) runCatching { builder.addDisallowedApplication(exclude.next()) }
                runCatching { builder.addDisallowedApplication(packageName) }
            }
        }

        if (options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= 29) {
            builder.setHttpProxy(ProxyInfo.buildDirectProxy(options.httpProxyServer, options.httpProxyServerPort))
        }

        val pfd = builder.establish() ?: error("Android refused to create the VPN interface")
        tunFd = pfd
        return pfd.fd
    }

    override fun usePlatformAutoDetectInterfaceControl() = true

    override fun autoDetectInterfaceControl(fd: Int) {
        if (!protect(fd)) error("protect failed")
    }

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun findConnectionOwner(
        ipProtocol: Int, sourceAddress: String, sourcePort: Int, destinationAddress: String, destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < 29) error("unsupported")
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == android.os.Process.INVALID_UID) error("not found")
        val owner = ConnectionOwner()
        owner.userId = uid
        packageManager.getPackagesForUid(uid)?.firstOrNull()?.let { owner.userName = it }
        return owner
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = report(network)
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = report(network)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = report(network)
            override fun onLost(network: Network) {
                listener.updateDefaultInterface("", -1, false, false)
            }

            private fun report(network: Network) {
                val lp = connectivity.getLinkProperties(network) ?: return
                val name = lp.interfaceName ?: return
                val caps = connectivity.getNetworkCapabilities(network)
                val index = runCatching { java.net.NetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
                val expensive = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                listener.updateDefaultInterface(name, index, expensive, false)
            }
        }
        // Report the current network right away: services that start immediately
        // (NTP, rule-set downloads) fail with "no available network interface" otherwise.
        @Suppress("DEPRECATION")
        connectivity.allNetworks.firstOrNull { n ->
            connectivity.getNetworkCapabilities(n)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true && n == connectivity.activeNetwork
        }?.let { n ->
            val lp = connectivity.getLinkProperties(n)
            val name = lp?.interfaceName
            if (name != null) {
                val index = runCatching { java.net.NetworkInterface.getByName(name)?.index ?: -1 }.getOrDefault(-1)
                val caps = connectivity.getNetworkCapabilities(n)
                listener.updateDefaultInterface(name, index, caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false, false)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        if (Build.VERSION.SDK_INT >= 31) {
            connectivity.registerBestMatchingNetworkCallback(request, callback, android.os.Handler(mainLooper))
        } else {
            connectivity.requestNetwork(request, callback)
        }
        networkCallback = callback
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    @Suppress("DEPRECATION")
    override fun getInterfaces(): NetworkInterfaceIterator {
        val list = connectivity.allNetworks.mapNotNull { network ->
            val lp = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val name = lp.interfaceName ?: return@mapNotNull null
            val jni = runCatching { java.net.NetworkInterface.getByName(name) }.getOrNull() ?: return@mapNotNull null
            BoxInterface().apply {
                this.name = name
                index = jni.index
                mtu = runCatching { jni.mtu }.getOrDefault(1500)
                addresses = Strings(lp.linkAddresses.map { "${it.address.hostAddress?.substringBefore('%')}/${it.prefixLength}" })
                var f = 0
                if (jni.isUp) f = f or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                if (jni.isLoopback) f = f or OsConstants.IFF_LOOPBACK
                if (jni.isPointToPoint) f = f or OsConstants.IFF_POINTOPOINT
                if (jni.supportsMulticast()) f = f or OsConstants.IFF_MULTICAST
                flags = f
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                dnsServer = Strings(lp.dnsServers.mapNotNull { it.hostAddress })
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        return object : NetworkInterfaceIterator {
            val it = list.iterator()
            override fun hasNext() = it.hasNext()
            override fun next(): BoxInterface = it.next()
        }
    }

    override fun underNetworkExtension() = false
    override fun includeAllNetworks() = false
    override fun readWIFIState(): WIFIState? = null
    override fun clearDNSCache() {}
    override fun sendNotification(notification: BoxNotification?) {}
    override fun cancelNotification(identifier: String?, typeID: Int) {}
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun registerMyInterface(name: String?) {}
    override fun usePlatformShell() = false
    override fun checkPlatformShell() = error("unsupported")
    override fun openShellSession(
        user: PlatformUser?, command: String?, environ: StringIterator?, term: String?, rows: Int, cols: Int,
    ): ShellSession = error("unsupported")
    override fun lookupUser(username: String?): PlatformUser = error("unsupported")
    override fun lookupSFTPServer(): String = error("unsupported")
    override fun readSystemSSHHostKey(): String = error("unsupported")
    override fun tailscaleHostname(): String = ""
    override fun usePlatformBridge() = false
    override fun createBridge(options: BridgeOptions?): BridgeSession = error("unsupported")

    // ---------- CommandServerHandler ----------

    override fun serviceStop() {
        scope.launch { stopVpn() }
    }

    override fun serviceReload() {
        scope.launch { reload() }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
    override fun setSystemProxyEnabled(enabled: Boolean) {}
    override fun triggerNativeCrash() {}
    override fun writeDebugMessage(message: String?) {}
    override fun connectSSHAgent(): Int = error("unsupported")

    private class Strings(list: List<String>) : StringIterator {
        private val values = list
        private var i = 0
        override fun len() = values.size
        override fun hasNext() = i < values.size
        override fun next() = values[i++]
    }

    @Suppress("unused")
    private fun isIpv6(a: java.net.InetAddress) = a is Inet6Address

    @Suppress("unused")
    private fun hasPermission(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
}
