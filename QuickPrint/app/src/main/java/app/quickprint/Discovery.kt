package app.quickprint

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.util.LinkedList

/** Finds printers on the Wi-Fi via mDNS (_ipp._tcp). Calls [onFound] on the main thread. */
class Discovery(ctx: Context, private val onFound: (name: String, ip: String) -> Unit) {
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val queue = LinkedList<NsdServiceInfo>()
    private val seen = HashSet<String>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    private val handler = Handler(Looper.getMainLooper())

    fun start() {
        stop()
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(this@Discovery) {
                    if (seen.add(serviceInfo.serviceName)) queue.add(serviceInfo)
                }
                next()
            }
        }
        listener = l
        try {
            nsd.discoverServices("_ipp._tcp", NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            listener = null
        }
    }

    private fun next() {
        var svc: NsdServiceInfo? = null
        synchronized(this) {
            if (resolving || queue.isEmpty()) return
            resolving = true
            svc = queue.poll()
        }
        val s = svc ?: run { done(); return }
        nsd.resolveService(s, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                done()
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val h = serviceInfo.host
                if (h is Inet4Address) {
                    val addr = h.hostAddress ?: ""
                    val name = serviceInfo.serviceName
                    handler.post { onFound(name, addr) }
                }
                done()
            }
        })
    }

    private fun done() {
        synchronized(this) { resolving = false }
        next()
    }

    fun stop() {
        val l = listener
        if (l != null) {
            try {
                nsd.stopServiceDiscovery(l)
            } catch (e: Exception) {
            }
        }
        listener = null
        synchronized(this) {
            queue.clear()
            seen.clear()
            resolving = false
        }
    }
}
