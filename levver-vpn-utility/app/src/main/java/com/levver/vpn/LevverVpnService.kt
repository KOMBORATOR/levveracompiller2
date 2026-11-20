package com.levver.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.ComponentName
import android.service.quicksettings.TileService
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import io.nekohasekai.libbox.*
import java.io.File
import java.net.Inet6Address
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.Collections
import kotlinx.coroutines.*

/**
 * LEVVER's Android VPN host for the official sing-box libbox engine.
 * Accepts normalized sing-box JSON generated from supported share links or imported
 * user configurations. All remote servers must be supplied by the user and authorized.
 */
class LevverVpnService : VpnService(), PlatformInterface, CommandServerHandler {
    companion object {
        const val ACTION_START = "com.levver.vpn.START"
        const val ACTION_STOP = "com.levver.vpn.STOP"
        const val EXTRA_CONFIG = "config"
        private const val CHANNEL = "levver_vpn"
        private const val NOTIFICATION_ID = 741
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engineMutex = kotlinx.coroutines.sync.Mutex()
    private var tunFd: ParcelFileDescriptor? = null
    private var commandServer: CommandServer? = null
    private var currentConfig: String? = null
    @Volatile private var engineInitialized = false
    @Volatile private var startGeneration = 0L

    @Synchronized
    private fun nextGeneration(): Long {
        startGeneration += 1
        return startGeneration
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            return START_NOT_STICKY
        }
        val config = intent?.getStringExtra(EXTRA_CONFIG)
        if (config.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val generation = nextGeneration()
        currentConfig = config
        startForeground(NOTIFICATION_ID, notification("Подключаемся…"))
        scope.launch {
            engineMutex.withLock {
                if (generation != startGeneration) return@withLock
                try {
                    closeEngineLocked()
                    setupEngine()
                    if (generation != startGeneration) return@withLock

                    val server = CommandServer(this@LevverVpnService, this@LevverVpnService)
                    commandServer = server
                    server.start()
                    if (generation != startGeneration) {
                        closeEngineLocked()
                        return@withLock
                    }
                    server.startOrReloadService(config, OverrideOptions())
                    if (generation != startGeneration) {
                        closeEngineLocked()
                        return@withLock
                    }
                    setVpnActive(true)
                    withContext(Dispatchers.Main) {
                        if (generation == startGeneration) {
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, notification("VPN запущен"))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("LEVVER", "VPN start failed", e)
                    if (generation == startGeneration) {
                        setVpnActive(false)
                        closeEngineLocked()
                        withContext(Dispatchers.Main) {
                            if (generation == startGeneration) {
                                getSystemService(NotificationManager::class.java).notify(
                                    NOTIFICATION_ID + 1,
                                    notification("Ошибка подключения: ${e.message ?: "проверь конфигурацию"}")
                                )
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }
                    } else {
                        closeEngineLocked()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun closeEngineLocked() {
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        commandServer = null
        runCatching { tunFd?.close() }
        tunFd = null
    }

    private fun setupEngine() {
        val base = filesDir
        val work = File(getExternalFilesDir(null) ?: filesDir, "levver-work").apply { mkdirs() }
        val temp = cacheDir
        val options = SetupOptions().apply {
            basePath = base.absolutePath
            workingPath = work.absolutePath
            tempPath = temp.absolutePath
            fixAndroidStack = true
            logMaxLines = 1000
            debug = false
        }
        if (!engineInitialized) {
            Libbox.setup(options)
            engineInitialized = true
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "LEVVER VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(message: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, LevverVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_levver_mark)
            .setContentTitle("LEVVER Utility")
            .setContentText(message)
            .setOngoing(message != "VPN отключён" && !message.startsWith("Ошибка"))
            .addAction(0, "Отключить", stopIntent)
            .build()
    }

    private fun setVpnActive(active: Boolean) {
        getSharedPreferences("levver_runtime", MODE_PRIVATE).edit().putBoolean("vpn_active", active).apply()
        runCatching { TileService.requestListeningState(this, ComponentName(this, QuickSettingsTileService::class.java)) }
    }

    private fun stopTunnel() {
        val generation = nextGeneration()
        setVpnActive(false)
        scope.launch {
            engineMutex.withLock { closeEngineLocked() }
            withContext(Dispatchers.Main) {
                if (generation == startGeneration) {
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID, notification("VPN отключён")
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        nextGeneration()
        scope.cancel()
        runCatching { commandServer?.closeService() }
        runCatching { commandServer?.close() }
        runCatching { tunFd?.close() }
        commandServer = null
        tunFd = null
        super.onDestroy()
    }

    // CommandServerHandler
    override fun serviceStop() { stopTunnel() }
    override fun serviceReload() {
        val config = currentConfig ?: return
        val generation = startGeneration
        scope.launch {
            engineMutex.withLock {
                if (generation == startGeneration) {
                    runCatching { commandServer?.startOrReloadService(config, OverrideOptions()) }
                        .onFailure { Log.e("LEVVER", "VPN reload failed", it) }
                }
            }
        }
    }
    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }
    override fun setSystemProxyEnabled(enabled: Boolean) {}
    override fun triggerNativeCrash() { throw IllegalStateException("LEVVER diagnostic request") }
    override fun writeDebugMessage(message: String?) { Log.d("LEVVER-libbox", message.orEmpty()) }
    override fun connectSSHAgent(): Int = -1

    // PlatformInterface
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun autoDetectInterfaceControl(fd: Int) { protect(fd) }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("Android VPN permission has not been granted")
        val builder = Builder().setSession("LEVVER Utility").setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)

        val v4 = options.inet4Address
        while (v4.hasNext()) {
            val address = v4.next()
            builder.addAddress(address.address(), address.prefix())
        }
        val v6 = options.inet6Address
        while (v6.hasNext()) {
            val address = v6.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dns = options.dnsServerAddress
                while (dns.hasNext()) builder.addDnsServer(dns.next())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val routes4 = options.inet4RouteAddress
                while (routes4.hasNext()) {
                    val route = routes4.next()
                    builder.addRoute(route.address(), route.prefix())
                }
                val routes6 = options.inet6RouteAddress
                while (routes6.hasNext()) {
                    val route = routes6.next()
                    builder.addRoute(route.address(), route.prefix())
                }
            } else {
                val routes4 = options.inet4RouteRange
                while (routes4.hasNext()) {
                    val route = routes4.next()
                    builder.addRoute(route.address(), route.prefix())
                }
                val routes6 = options.inet6RouteRange
                while (routes6.hasNext()) {
                    val route = routes6.next()
                    builder.addRoute(route.address(), route.prefix())
                }
            }
        }
        val descriptor = builder.establish() ?: error("Android did not establish the VPN interface")
        tunFd = descriptor
        return descriptor.fd
    }

    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String, sourcePort: Int,
        destinationAddress: String, destinationPort: Int): ConnectionOwner {
        val owner = ConnectionOwner()
        if (Build.VERSION.SDK_INT >= 29) {
            owner.userId = runCatching {
                getSystemService(android.net.ConnectivityManager::class.java)
                    .getConnectionOwnerUid(ipProtocol, java.net.InetSocketAddress(sourceAddress, sourcePort),
                        java.net.InetSocketAddress(destinationAddress, destinationPort))
            }.getOrDefault(-1)
        }
        return owner
    }
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {}
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {}

    override fun getInterfaces(): NetworkInterfaceIterator {
        val items = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
        try {
            val all = Collections.list(JavaNetworkInterface.getNetworkInterfaces())
            all.filter { it.isUp && !it.isLoopback }.forEach { ni ->
                val item = io.nekohasekai.libbox.NetworkInterface()
                item.name = ni.name
                item.index = ni.index
                item.mtu = ni.mtu
                item.addresses = StringArray(ni.interfaceAddresses.map {
                    "${it.address.hostAddress.substringBefore('%')}/${it.networkPrefixLength}"
                })
                item.flags = 1
                item.type = Libbox.InterfaceTypeOther
                item.dnsServer = StringArray(emptyList())
                item.gateway = StringArray(emptyList())
                items.add(item)
            }
        } catch (e: Exception) { Log.w("LEVVER", "Interface enumeration", e) }
        return object : NetworkInterfaceIterator {
            private val iterator = items.iterator()
            override fun hasNext() = iterator.hasNext()
            override fun next() = iterator.next()
        }
    }
    override fun underNetworkExtension() = false
    override fun includeAllNetworks() = false
    override fun readWIFIState(): WIFIState? = null
    override fun clearDNSCache() {}
    override fun sendNotification(notification: io.nekohasekai.libbox.Notification) {}
    override fun cancelNotification(identifier: String, typeID: Int) {}
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun registerMyInterface(name: String?) {}

    override fun usePlatformShell() = false
    override fun checkPlatformShell() { error("Platform shell is not supported") }
    override fun openShellSession(user: PlatformUser?, command: String?, environ: StringIterator?,
        term: String?, rows: Int, cols: Int): ShellSession { error("Platform shell is not supported") }
    override fun lookupUser(username: String?): PlatformUser { error("Platform users are not supported") }
    override fun lookupSFTPServer(): String { error("SFTP is not supported") }
    override fun readSystemSSHHostKey(): String { error("SSH host keys are not supported") }
    override fun tailscaleHostname(): String = "LEVVER-Android"
    override fun usePlatformBridge() = false
    override fun createBridge(options: BridgeOptions?): BridgeSession { error("Platform bridge is not supported") }

    private class StringArray(private val values: List<String>) : StringIterator {
        private var index = 0
        override fun len(): Int = values.size
        override fun hasNext(): Boolean = index < values.size
        override fun next(): String = values[index++]
    }
}
