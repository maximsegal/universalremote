package com.universalremote

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** [brand] is null when the TV was found but this app has no control driver for it. */
data class FoundTv(val ip: String, val brand: Brand?, val label: String, val name: String? = null)

/**
 * Finds TVs of any make on the LAN: SSDP/UPnP multicast (with device descriptions),
 * plus a port sweep of the /24 for the control ports TVs and streamers are known to open.
 */
object Discovery {
    private const val GENERIC = "TV / media device"

    private val SSDP_TARGETS = listOf(
        "ssdp:all",
        "urn:dial-multiscreen-org:service:dial:1",
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:samsung.com:device:RemoteControlReceiver:1",
        "urn:lge-com:service:webos-second-screen:1",
        "roku:ecp",
    )

    private data class Port(val port: Int, val brand: Brand?, val label: String)

    private val PORTS = listOf(
        Port(8002, Brand.Samsung, "Samsung TV"), Port(8001, Brand.Samsung, "Samsung TV"),
        Port(3001, Brand.LG, "LG TV"), Port(3000, Brand.LG, "LG TV"),
        Port(8060, Brand.Roku, "Roku"),
        Port(6466, null, "Android TV"), Port(6467, null, "Android TV"),
        Port(8009, null, "Chromecast / Google TV"), Port(8008, null, "Chromecast / Google TV"),
        Port(7345, null, "Vizio TV"), Port(9000, null, "Vizio TV"),
        Port(1925, null, "Philips TV"), Port(1926, null, "Philips TV"),
        Port(55000, null, "Panasonic / Samsung TV"),
    )

    private val found = ConcurrentHashMap<String, FoundTv>()

    /** Blocking; call from a background thread. */
    fun scan(context: Context): List<FoundTv> {
        found.clear()
        val t = Thread { ssdp(context) }.apply { start() }
        sweep(context)
        t.join()
        return found.values.sortedWith(compareBy({ it.brand == null }, { it.ip }))
    }

    @Synchronized
    private fun merge(ip: String, brand: Brand?, label: String, name: String?) {
        val old = found[ip]
        if (old == null) { found[ip] = FoundTv(ip, brand, label, name); return }
        val better = (old.brand == null && brand != null) || (old.label == GENERIC && label != GENERIC)
        found[ip] = FoundTv(
            ip,
            old.brand ?: brand,
            if (better) label else old.label,
            old.name ?: name,
        )
    }

    /** Returns (brand, label) for text that looks like a TV/media device; null for routers, printers, etc. */
    private fun classify(text: String): Pair<Brand?, String>? {
        val t = text.lowercase()
        if ("internetgatewaydevice" in t || "wanconnectiondevice" in t) return null
        return when {
            "samsung" in t || "tizen" in t -> Brand.Samsung to "Samsung TV"
            "webos" in t || "lge" in t || "lg electronics" in t || "lgtv" in t -> Brand.LG to "LG TV"
            "roku" in t -> Brand.Roku to "Roku"
            "bravia" in t || "sony" in t -> null to "Sony TV"
            "vizio" in t -> null to "Vizio TV"
            "philips" in t -> null to "Philips TV"
            "panasonic" in t -> null to "Panasonic TV"
            "hisense" in t -> null to "Hisense TV"
            "tcl" in t -> null to "TCL TV"
            "sharp" in t || "toshiba" in t || "xiaomi" in t -> null to "TV"
            "androidtv" in t || "android tv" in t || "chromecast" in t || "googlecast" in t || "google cast" in t -> null to "Android TV / Chromecast"
            "mediarenderer" in t || "dial-multiscreen" in t || "dial" in t && "multiscreen" in t -> null to GENERIC
            else -> null
        }
    }

    private fun fetchDescription(location: String): String = try {
        val c = URL(location).openConnection() as HttpURLConnection
        c.connectTimeout = 800; c.readTimeout = 800
        c.inputStream.use { it.readBytes().take(16384).toByteArray().toString(Charsets.UTF_8) }
    } catch (_: Exception) { "" }

    private fun ssdp(context: Context) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("remote-ssdp").apply { setReferenceCounted(false); acquire() }
        val pool = Executors.newFixedThreadPool(16)
        val seen = ConcurrentHashMap.newKeySet<String>()
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
                val end = System.currentTimeMillis() + 4000
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val ip = p.address.hostAddress ?: continue
                        val text = String(p.data, 0, p.length)
                        val location = Regex("(?im)^location:\\s*(\\S+)").find(text)?.groupValues?.get(1)
                        pool.execute {
                            classify(text)?.let { (b, l) -> merge(ip, b, l, null) }
                            // Fetch the device description once per device to get its name/manufacturer.
                            if (location != null && seen.add(ip)) {
                                val xml = fetchDescription(location)
                                if (xml.isNotEmpty()) {
                                    val name = Regex("<friendlyName>([^<]*)<").find(xml)?.groupValues?.get(1)
                                    classify(text + xml)?.let { (b, l) -> merge(ip, b, l, name) }
                                }
                            }
                        }
                    } catch (_: SocketTimeoutException) {}
                }
            }
        } catch (_: Exception) {
        } finally {
            lock.release()
            pool.shutdown()
            pool.awaitTermination(4, TimeUnit.SECONDS)
        }
    }

    private fun sweep(context: Context) {
        @Suppress("DEPRECATION")
        val ipInt = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo.ipAddress
        if (ipInt == 0) return
        val prefix = "${ipInt and 0xff}.${ipInt shr 8 and 0xff}.${ipInt shr 16 and 0xff}"
        val pool = Executors.newFixedThreadPool(200)
        for (i in 1..254) for (p in PORTS) {
            val ip = "$prefix.$i"
            pool.execute { if (open(ip, p.port)) merge(ip, p.brand, p.label, null) }
        }
        pool.shutdown()
        pool.awaitTermination(15, TimeUnit.SECONDS)
    }

    private fun open(ip: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), 250); true }
    } catch (_: Exception) { false }
}
