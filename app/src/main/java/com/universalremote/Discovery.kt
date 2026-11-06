package com.universalremote

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class FoundTv(val ip: String, val brand: Brand)

/** Finds TVs on the LAN: SSDP multicast, plus a port sweep of the /24 (works when multicast is blocked). */
object Discovery {
    private val SSDP_TARGETS = listOf(
        "ssdp:all",
        "urn:dial-multiscreen-org:service:dial:1",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:samsung.com:device:RemoteControlReceiver:1",
        "urn:lge-com:service:webos-second-screen:1",
    )

    /** Blocking; call from a background thread. */
    fun scan(context: Context): List<FoundTv> {
        val found = ConcurrentHashMap<String, Brand>()
        val t1 = Thread { ssdp(context, found) }.apply { start() }
        sweep(context, found)
        t1.join()
        return found.map { FoundTv(it.key, it.value) }.sortedBy { it.ip }
    }

    private fun brandOf(text: String): Brand? {
        val t = text.lowercase()
        return when {
            "samsung" in t || "tizen" in t -> Brand.Samsung
            "webos" in t || "lge" in t || "lg electronics" in t || "lgtv" in t -> Brand.LG
            else -> null
        }
    }

    private fun ssdp(context: Context, found: MutableMap<String, Brand>) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("remote-ssdp").apply { setReferenceCounted(false); acquire() }
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 500
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) {
                    for (st in SSDP_TARGETS) {
                        val req = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                            "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n").toByteArray()
                        s.send(DatagramPacket(req, req.size, group, 1900))
                    }
                    Thread.sleep(150)
                }
                val end = System.currentTimeMillis() + 3500
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val ip = p.address.hostAddress ?: continue
                        brandOf(String(p.data, 0, p.length))?.let { found.putIfAbsent(ip, it) }
                    } catch (_: SocketTimeoutException) {}
                }
            }
        } catch (_: Exception) {
        } finally {
            lock.release()
        }
    }

    private fun sweep(context: Context, found: MutableMap<String, Brand>) {
        @Suppress("DEPRECATION")
        val ipInt = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo.ipAddress
        if (ipInt == 0) return
        val prefix = "${ipInt and 0xff}.${ipInt shr 8 and 0xff}.${ipInt shr 16 and 0xff}"
        val pool = Executors.newFixedThreadPool(64)
        for (i in 1..254) {
            val ip = "$prefix.$i"
            pool.execute {
                when {
                    open(ip, 8002) || open(ip, 8001) -> found.putIfAbsent(ip, Brand.Samsung)
                    open(ip, 3001) || open(ip, 3000) -> found.putIfAbsent(ip, Brand.LG)
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(15, TimeUnit.SECONDS)
    }

    private fun open(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), 350); true }
    } catch (_: Exception) { false }
}
