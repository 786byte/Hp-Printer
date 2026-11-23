package app.quickprint

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
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
 * Sends the app's printer traffic over Wi-Fi even when mobile data is on.
 * Android normally sends everything over mobile data if the Wi-Fi has no internet;
 * binding each connection to the Wi-Fi network avoids that.
 */
object Net {
    fun wifi(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n
        }
        return null
    }
}

object Http {
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
