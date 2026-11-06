package com.universalremote

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import kotlin.concurrent.thread

/** Roku devices / Roku TVs via the External Control Protocol (plain HTTP on port 8060, no pairing). */
class RokuRemote(context: Context) : TvRemote {
    private val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)
    @Volatile private var ip: String? = null
    @Volatile private var generation = 0

    override var onState: (ConnState, String?) -> Unit = { _, _ -> }

    override fun connect(ip: String) {
        val gen = ++generation
        this.ip = null
        onState(ConnState.Connecting, null)
        thread {
            try {
                Net.client.newCall(Request.Builder().url("http://$ip:8060/query/device-info").build()).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}")
                    if (gen != generation) return@thread
                    this.ip = ip
                    prefs.edit().putString("ip", ip).apply()
                    Wol.save(prefs, ip, Regex("<(?:wifi|ethernet)-mac>([^<]+)<").findAll(body).map { it.groupValues[1] }.toList())
                    onState(ConnState.Connected, null)
                }
            } catch (e: Exception) {
                if (gen == generation) onState(ConnState.Disconnected,
                    "Can't reach a Roku at $ip (${e.message}). In Roku settings enable Network access / Control by mobile apps.")
            }
        }
    }

    override fun disconnect() { generation++; ip = null }

    private fun post(path: String): Boolean {
        val host = ip ?: return false
        thread {
            try {
                Net.client.newCall(Request.Builder().url("http://$host:8060$path")
                    .post("".toRequestBody("text/plain".toMediaType())).build()).execute().close()
            } catch (_: Exception) {}
        }
        return true
    }

    override fun key(code: String): Boolean {
        val k = when (code) {
            "KEY_POWER" -> "Power"
            "KEY_SOURCE" -> "InputHDMI1"
            "KEY_MENU" -> "Info"
            "KEY_UP" -> "Up"; "KEY_DOWN" -> "Down"; "KEY_LEFT" -> "Left"; "KEY_RIGHT" -> "Right"
            "KEY_ENTER" -> "Select"; "KEY_RETURN" -> "Back"; "KEY_HOME" -> "Home"; "KEY_EXIT" -> "Home"
            "KEY_INFO" -> "Info"
            "KEY_VOLUP" -> "VolumeUp"; "KEY_VOLDOWN" -> "VolumeDown"; "KEY_MUTE" -> "VolumeMute"
            "KEY_CHUP" -> "ChannelUp"; "KEY_CHDOWN" -> "ChannelDown"
            "KEY_REWIND" -> "Rev"; "KEY_FF" -> "Fwd"; "KEY_PLAY", "KEY_PAUSE" -> "Play"; "KEY_STOP" -> "Play"
            "KEY_PRECH" -> "InstantReplay"
            "KEY_TV" -> "InputTuner"
            "KEY_HDMI1" -> "InputHDMI1"; "KEY_HDMI2" -> "InputHDMI2"; "KEY_HDMI3" -> "InputHDMI3"
            "KEY_NETFLIX" -> return post("/launch/12")
            "KEY_AMAZON" -> return post("/launch/13")
            else -> if (code.length == 5 && code.startsWith("KEY_") && code[4].isDigit()) "Lit_${code[4]}" else return false
        }
        return post("/keypress/$k")
    }

    override fun text(text: String): Boolean {
        val host = ip ?: return false
        thread {
            for (c in text) {
                try {
                    val lit = URLEncoder.encode(c.toString(), "UTF-8").replace("+", "%20")
                    Net.client.newCall(Request.Builder().url("http://$host:8060/keypress/Lit_$lit")
                        .post("".toRequestBody("text/plain".toMediaType())).build()).execute().close()
                } catch (_: Exception) {}
            }
        }
        return true
    }

    override fun backspace(): Boolean = post("/keypress/Backspace")
}
