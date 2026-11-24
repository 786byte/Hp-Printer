package app.quickprint

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Sends the app's printer traffic over the Wi-Fi that can actually reach the printer, even when mobile data
 * is on and is Android's default network (the printer's own Wi-Fi has no internet, so Android prefers mobile data).
 */
object Net {
    /** Kept for old callers: the best Wi-Fi network when the printer address isn't known. */
    fun wifi(ctx: Context): Network? = forIp(ctx, null)

    /**
     * The network to use for [ip]: the Wi-Fi whose subnet contains it (home Wi-Fi or the printer's Wi-Fi Direct),
     * else any Wi-Fi, else null (use Android's default).
     */
    fun forIp(ctx: Context, ip: String?): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val target = try { if (ip.isNullOrBlank()) null else InetAddress.getByName(ip) as? Inet4Address } catch (e: Exception) { null }
        val wifis = ArrayList<Network>()
        PrinterWifi.network?.let { wifis.add(it) }
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && n !in wifis) wifis.add(n)
        }
        if (target != null) {
            for (n in wifis) {
                val lp = cm.getLinkProperties(n) ?: continue
                for (la in lp.linkAddresses) {
                    val a = la.address as? Inet4Address ?: continue
                    if (sameSubnet(a, target, la.prefixLength)) return n
                }
            }
        }
        return wifis.firstOrNull()
    }

    private fun sameSubnet(a: Inet4Address, b: Inet4Address, prefix: Int): Boolean {
        val x = a.address; val y = b.address
        var bits = prefix.coerceIn(0, 32)
        var i = 0
        while (bits > 0) {
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((x[i].toInt() and mask) != (y[i].toInt() and mask)) return false
            bits -= 8; i++
        }
        return true
    }

    /** Fast "is something listening on the printer port" check, over the right network. */
    fun reachable(ctx: Context, ip: String, port: Int = 631, timeout: Int = 1500): Boolean = try {
        val net = forIp(ctx, ip)
        val s = net?.socketFactory?.createSocket() ?: Socket()
        s.use { it.connect(InetSocketAddress(ip, port), timeout) }
        true
    } catch (e: Exception) {
        false
    }

    /** Which kind of network reaches [ip], for showing the user. */
    fun describe(ctx: Context, ip: String): String {
        val n = forIp(ctx, ip) ?: return "phone's default network"
        if (n == PrinterWifi.network) return "printer's Wi-Fi (joined by Quick Print)"
        return if (ip.startsWith("192.168.223.")) "printer's Wi-Fi Direct" else "Wi-Fi"
    }
}

/**
 * Joins the printer's own Wi-Fi (Wi-Fi Direct, "DIRECT-xx-HP Smart Tank 580") from inside the app.
 * Android keeps mobile data for the internet, and phones that support two Wi-Fi connections keep the home
 * Wi-Fi too. Android shows its own "connect to device" prompt the first time.
 */
object PrinterWifi {
    @Volatile var network: Network? = null
        private set
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun connect(ctx: Context, ssid: String, password: String, result: (Boolean, String) -> Unit) {
        disconnect(ctx)
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return result(false, "No connectivity service")
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
            if (password.isNotEmpty()) setWpa2Passphrase(password)
        }.build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { network = n; result(true, "Connected to $ssid") }
            override fun onLost(n: Network) { if (network == n) network = null }
            override fun onUnavailable() { network = null; result(false, "Couldn't join $ssid (wrong password, or you declined the prompt)") }
        }
        callback = cb
        try {
            cm.requestNetwork(req, cb, 60_000)
        } catch (e: Exception) {
            callback = null
            result(false, e.message ?: "Couldn't request the printer's Wi-Fi")
        }
    }

    fun disconnect(ctx: Context) {
        val cb = callback ?: return
        callback = null
        network = null
        try { ctx.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } catch (e: Exception) { }
    }
}

object Http {
    init {
        // HP printers often drop idle kept-alive connections; reusing one makes the next request fail at random.
        System.setProperty("http.keepAlive", "false")
    }

    // The printer uses a self-signed certificate; this is only ever used for the printer's local address.
    private val trustAll: SSLSocketFactory by lazy {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
        ctx.socketFactory
    }

    fun open(url: String, net: Network?): HttpURLConnection {
        val u = URL(url)
        val c = (if (net != null) net.openConnection(u) else u.openConnection()) as HttpURLConnection
        c.setRequestProperty("Connection", "close")
        c.useCaches = false
        if (c is HttpsURLConnection) {
            c.sslSocketFactory = trustAll
            c.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        return c
    }

    fun getText(url: String, net: Network?, timeout: Int = 4000): String? {
        return try {
            val c = open(url, net)
            c.connectTimeout = timeout
            c.readTimeout = timeout
            try {
                if (c.responseCode == 200) c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) } else null
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}
