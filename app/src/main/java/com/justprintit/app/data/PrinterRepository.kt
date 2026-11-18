package com.justprintit.app.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.justprintit.core.ipp.IppClient
import com.justprintit.core.ipp.PrinterInfo
import com.justprintit.core.ledm.HpUsage
import com.justprintit.core.scan.EsclClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

data class PrinterStatus(
    val host: String = "",
    val info: PrinterInfo? = null,
    val usage: HpUsage? = null,
    val error: String? = null,
    val loading: Boolean = false,
    val updatedAt: Long = 0,
) {
    val online get() = info != null && error == null
}

data class FoundPrinter(val name: String, val host: String, val port: Int, val path: String, val model: String?)

class PrinterRepository(private val context: Context, private val settings: AppSettings) {
    private val _status = MutableStateFlow(PrinterStatus())
    val status: StateFlow<PrinterStatus> = _status.asStateFlow()
    private val refreshLock = Mutex()

    fun client(): IppClient {
        val s = settings.current
        require(s.printerHost.isNotBlank()) { "Choose your printer in Settings first" }
        return IppClient(s.printerHost, s.printerPort, s.printerPath, userName = s.userName.ifBlank { "Android" })
    }

    fun scanner(): EsclClient {
        val s = settings.current
        require(s.printerHost.isNotBlank()) { "Choose your printer in Settings first" }
        return EsclClient(s.printerHost)
    }

    /** Reads live status + ink levels. Never throws; errors land in [status]. */
    suspend fun refresh(withUsage: Boolean = true): PrinterStatus = refreshLock.withLock {
        val host = settings.current.printerHost
        if (host.isBlank()) return PrinterStatus().also { _status.value = it }
        _status.value = _status.value.copy(loading = true, host = host)
        val result = withContext(Dispatchers.IO) {
            try {
                val info = PrinterInfo.parse(client().getPrinterAttributes(*PrinterInfo.REQUESTED))
                val usage = if (withUsage) HpUsage.fetch(host) else _status.value.usage
                PrinterStatus(host, info, usage, null, false, System.currentTimeMillis())
            } catch (e: Exception) {
                PrinterStatus(host, _status.value.info, _status.value.usage,
                    "Can't reach the printer at $host. Is it switched on and on the same Wi-Fi? (${e.message})",
                    false, System.currentTimeMillis())
            }
        }
        _status.value = result
        result
    }

    /** Finds IPP printers on the Wi-Fi with mDNS / Bonjour. */
    fun discover(): Flow<List<FoundPrinter>> = callbackFlow {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("justprintit-discovery").apply { setReferenceCounted(false); acquire() }
        val found = LinkedHashMap<String, FoundPrinter>()
        val pending = ConcurrentLinkedQueue<NsdServiceInfo>()
        var resolving = false

        fun resolveNext() {
            if (resolving) return
            val next = pending.poll() ?: return
            resolving = true
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) { resolving = false; resolveNext() }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = info.host?.hostAddress?.substringBefore('%')
                    if (host != null) {
                        val txt = info.attributes.mapValues { it.value?.toString(Charsets.UTF_8) }
                        val path = "/" + (txt["rp"] ?: "ipp/print").trimStart('/')
                        found[info.serviceName] = FoundPrinter(info.serviceName, host, info.port, path, txt["ty"])
                        trySend(found.values.toList())
                    }
                    resolving = false
                    resolveNext()
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, code: Int) { close(IllegalStateException("Discovery failed ($code)")) }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { pending += info; resolveNext() }
            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName); trySend(found.values.toList())
            }
        }
        trySend(emptyList())
        nsd.discoverServices("_ipp._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            lock.release()
        }
    }
}
