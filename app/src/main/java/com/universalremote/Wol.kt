package com.universalremote

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Wake-on-LAN: lets the app power a TV on while it is off (needs the TV's MAC, learned while it's on). */
object Wol {
    fun normalize(mac: String): String? {
        val hex = mac.filter { it.isLetterOrDigit() }.lowercase()
        if (hex.length != 12 || !hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return hex.chunked(2).joinToString(":")
    }

    fun get(prefs: SharedPreferences, ip: String): List<String> =
        prefs.getString("mac_$ip", "")!!.split(',').filter { it.isNotBlank() }

    fun save(prefs: SharedPreferences, ip: String, macs: Collection<String>) {
        val all = (get(prefs, ip) + macs.mapNotNull { normalize(it) })
            .filter { it != "00:00:00:00:00:00" }.distinct()
        if (all.isNotEmpty()) prefs.edit().putString("mac_$ip", all.joinToString(",")).apply()
    }

    /** Works only on older Android; newer versions hide /proc/net/arp. */
    fun arp(ip: String): String? = try {
        File("/proc/net/arp").readLines().drop(1).map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size >= 4 && it[0] == ip }?.get(3)?.let { normalize(it) }
    } catch (_: Exception) { null }

    /** Collects every "macAddress"/"wifiMac"-style string in a JSON tree. */
    fun macsIn(node: Any?, out: MutableList<String> = mutableListOf()): List<String> {
        when (node) {
            is JSONObject -> node.keys().forEach { k ->
                val v = node.opt(k)
                if (v is String && k.contains("mac", ignoreCase = true)) out.add(v) else macsIn(v, out)
            }
            is JSONArray -> for (i in 0 until node.length()) macsIn(node.opt(i), out)
        }
        return out
    }

    /** Blocking. Sends the magic packet to the global, subnet and direct addresses. */
    fun send(ip: String, macs: List<String>) {
        val targets = linkedSetOf("255.255.255.255", ip)
        ip.substringBeforeLast('.', "").takeIf { it.isNotEmpty() }?.let { targets.add("$it.255") }
        DatagramSocket().use { s ->
            s.broadcast = true
            for (mac in macs) {
                val bytes = mac.split(':').map { it.toInt(16).toByte() }.toByteArray()
                val packet = ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { bytes[it % 6] }
                repeat(3) {
                    for (t in targets) for (port in intArrayOf(9, 7)) {
                        try { s.send(DatagramPacket(packet, packet.size, InetAddress.getByName(t), port)) } catch (_: Exception) {}
                    }
                    Thread.sleep(100)
                }
            }
        }
    }
}
