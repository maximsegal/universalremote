package com.universalremote

import android.content.Context
import android.util.Base64
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Samsung Smart TV (Tizen, 2016+) WebSocket remote API.
 * The first connection makes the TV show an "Allow" prompt, then returns a token we reuse.
 */
class SamsungRemote(context: Context) : TvRemote {
    private val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private var socket: WebSocket? = null
    @Volatile private var generation = 0

    override var onState: (ConnState, String?) -> Unit = { _, _ -> }

    private fun tokenKey(ip: String) = "samsung_token_$ip"
    @Volatile private var currentIp: String? = null

    /** Learns the TV's MAC (for Wake-on-LAN) from its REST info endpoint. */
    private fun learnMac(ip: String) = Thread {
        try {
            Net.client.newCall(Request.Builder().url("http://$ip:8001/api/v2/").build()).execute().use { r ->
                val macs = Wol.macsIn(JSONObject(r.body?.string().orEmpty()).optJSONObject("device"))
                Wol.save(prefs, ip, macs + listOfNotNull(Wol.arp(ip)))
            }
        } catch (_: Exception) { Wol.save(prefs, ip, listOfNotNull(Wol.arp(ip))) }
    }.start()

    /** Launches an app over REST; tries each known app id (they differ by TV year) until one succeeds. */
    private fun launchApp(vararg ids: String): Boolean {
        val ip = currentIp ?: return false
        Thread {
            for (id in ids) {
                try {
                    Net.client.newCall(Request.Builder().url("http://$ip:8001/api/v2/applications/$id")
                        .post(okhttp3.RequestBody.create(null, ByteArray(0))).build()).execute().use { if (it.isSuccessful) return@Thread }
                } catch (_: Exception) {}
            }
        }.start()
        return true
    }

    override fun connect(ip: String) {
        disconnect()
        val gen = ++generation
        currentIp = ip
        onState(ConnState.Connecting, null)
        val name = Base64.encodeToString("UniversalRemote".toByteArray(), Base64.NO_WRAP)
        val token = prefs.getString(tokenKey(ip), null)
        val tokenPart = if (token != null) "&token=$token" else ""
        val url = "wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$name$tokenPart"
        socket = Net.client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen == generation) onState(ConnState.WaitingForApproval, "Accept the prompt on your TV")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                try {
                    val json = JSONObject(text)
                    when (json.optString("event")) {
                        "ms.channel.connect" -> {
                            json.optJSONObject("data")?.optString("token")?.takeIf { it.isNotEmpty() }
                                ?.let { prefs.edit().putString(tokenKey(ip), it).apply() }
                            prefs.edit().putString("ip", ip).apply()
                            learnMac(ip)
                            onState(ConnState.Connected, null)
                        }
                        "ms.channel.timeOut" -> onState(ConnState.Disconnected, "TV pairing timed out")
                        "ms.error" -> onState(ConnState.Disconnected, "TV error: ${json.optJSONObject("data")?.optString("message")}")
                    }
                } catch (_: Exception) {}
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen == generation) onState(ConnState.Disconnected, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen == generation) onState(ConnState.Disconnected,
                    "Can't reach a Samsung TV at $ip (${t.message ?: "error"}). Is this an LG TV? Switch to LG above.")
            }
        })
    }

    override fun disconnect() {
        generation++
        socket?.close(1000, null)
        socket = null
    }

    private fun send(params: JSONObject): Boolean {
        val msg = JSONObject().put("method", "ms.remote.control").put("params", params)
        return socket?.send(msg.toString()) ?: false
    }

    override fun key(code: String): Boolean = when (code) {
        "KEY_NETFLIX" -> launchApp("3201907018807", "11101200001")
        "KEY_AMAZON" -> launchApp("3201910019365", "3201512006785")
        "KEY_HDMI1", "KEY_HDMI2", "KEY_HDMI3" -> rawKey("KEY_HDMI") // Samsung only has a cycling HDMI key
        else -> rawKey(code)
    }

    private fun rawKey(code: String): Boolean = send(
        JSONObject().put("Cmd", "Click").put("DataOfCmd", code)
            .put("Option", "false").put("TypeOfRemote", "SendRemoteKey")
    )

    /** Types text into the TV's focused text field. Does NOT press Enter (on the TV's keyboard that types the highlighted key). */
    override fun text(text: String): Boolean = send(
        JSONObject().put("Cmd", Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP))
            .put("DataOfCmd", "base64").put("Option", "false").put("TypeOfRemote", "SendInputString")
    )

    override fun backspace(): Boolean = false
}
