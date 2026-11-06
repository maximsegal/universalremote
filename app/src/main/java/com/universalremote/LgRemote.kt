package com.universalremote

import android.content.Context
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * LG webOS TVs (SSAP). Main socket (wss :3001, falling back to ws :3000) handles pairing, volume, apps and text;
 * a second "pointer" socket handles the remote buttons (arrows, OK, digits, ...).
 * The TV shows a pairing prompt the first time; the returned client-key is saved.
 */
class LgRemote(context: Context) : TvRemote {
    private val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private var main: WebSocket? = null
    private var pointer: WebSocket? = null
    private val ids = AtomicInteger()
    @Volatile private var generation = 0

    override var onState: (ConnState, String?) -> Unit = { _, _ -> }

    private fun keyKey(ip: String) = "lg_key_$ip"

    override fun connect(ip: String) {
        disconnect()
        val gen = ++generation
        onState(ConnState.Connecting, null)
        open(ip, secure = true, gen = gen)
    }

    private fun open(ip: String, secure: Boolean, gen: Int) {
        val url = if (secure) "wss://$ip:3001" else "ws://$ip:3000"
        var opened = false
        main = Net.client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) return
                opened = true
                webSocket.send(registerMessage(prefs.getString(keyKey(ip), null)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation) return
                try {
                    val json = JSONObject(text)
                    val payload = json.optJSONObject("payload")
                    when (json.optString("type")) {
                        "registered" -> {
                            payload?.optString("client-key")?.takeIf { it.isNotEmpty() }
                                ?.let { prefs.edit().putString(keyKey(ip), it).apply() }
                            prefs.edit().putString("ip", ip).apply()
                            onState(ConnState.Connected, null)
                            Wol.save(prefs, ip, listOfNotNull(Wol.arp(ip)))
                            webSocket.send(JSONObject().put("type", "request").put("id", "conn")
                                .put("uri", "ssap://com.webos.service.connectionmanager/getinfo").toString())
                            webSocket.send(JSONObject().put("type", "request").put("id", "pointer")
                                .put("uri", "ssap://com.webos.service.networkinput/getPointerInputSocket").toString())
                        }
                        "response" -> {
                            if (json.optString("id") == "conn") {
                                Wol.save(prefs, ip, Wol.macsIn(payload))
                            } else if (json.optString("id") == "pointer") {
                                payload?.optString("socketPath")?.takeIf { it.isNotEmpty() }?.let { openPointer(it, gen) }
                            } else if (payload?.optString("pairingType") == "PROMPT") {
                                onState(ConnState.WaitingForApproval, "Accept the prompt on your TV")
                            }
                        }
                        "error" -> onState(ConnState.Disconnected, "TV: ${json.optString("error")}")
                    }
                } catch (_: Exception) {}
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (gen == generation) onState(ConnState.Disconnected, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen != generation) return
                if (secure && !opened) open(ip, secure = false, gen = gen)
                else onState(ConnState.Disconnected,
                    "Can't reach an LG TV at $ip (${t.message ?: "error"}). Turn on 'LG Connect Apps' in the TV's network settings, or switch brand if it's Samsung.")
            }
        })
    }

    private fun openPointer(path: String, gen: Int) {
        pointer = Net.client.newWebSocket(Request.Builder().url(path).build(), object : WebSocketListener() {
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen == generation) pointer = null
            }
        })
    }

    override fun disconnect() {
        generation++
        main?.close(1000, null); main = null
        pointer?.close(1000, null); pointer = null
    }

    private fun registerMessage(clientKey: String?): String {
        val perms = JSONArray(listOf(
            "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE", "CONTROL_AUDIO", "CONTROL_DISPLAY",
            "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_MEDIA_RECORDING",
            "CONTROL_INPUT_TV", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_POWER",
            "READ_APP_STATUS", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE",
            "READ_RUNNING_APPS", "READ_TV_CHANNEL_LIST", "READ_POWER_STATE", "WRITE_NOTIFICATION_TOAST",
        ))
        val payload = JSONObject()
            .put("forcePairing", false).put("pairingType", "PROMPT")
            .put("manifest", JSONObject().put("manifestVersion", 1).put("appVersion", "1.0").put("permissions", perms))
        if (clientKey != null) payload.put("client-key", clientKey)
        return JSONObject().put("type", "register").put("id", "register_0").put("payload", payload).toString()
    }

    private fun ssap(uri: String, payload: JSONObject? = null): Boolean {
        val m = JSONObject().put("type", "request").put("id", "r${ids.incrementAndGet()}").put("uri", uri)
        if (payload != null) m.put("payload", payload)
        return main?.send(m.toString()) ?: false
    }

    private fun button(name: String): Boolean = pointer?.send("type:button\nname:$name\n\n") ?: false

    private fun launch(id: String) = ssap("ssap://system.launcher/launch", JSONObject().put("id", id))
    private fun input(id: String) = ssap("ssap://tv/switchInput", JSONObject().put("inputId", id))

    override fun key(code: String): Boolean = when (code) {
        "KEY_POWER" -> ssap("ssap://system/turnOff")
        "KEY_SOURCE" -> launch("com.webos.app.inputpicker")
        "KEY_MENU" -> button("MENU")
        "KEY_UP" -> button("UP")
        "KEY_DOWN" -> button("DOWN")
        "KEY_LEFT" -> button("LEFT")
        "KEY_RIGHT" -> button("RIGHT")
        "KEY_ENTER" -> button("ENTER")
        "KEY_RETURN" -> button("BACK")
        "KEY_HOME" -> button("HOME")
        "KEY_EXIT" -> button("EXIT")
        "KEY_INFO" -> button("INFO")
        "KEY_VOLUP" -> ssap("ssap://audio/volumeUp")
        "KEY_VOLDOWN" -> ssap("ssap://audio/volumeDown")
        "KEY_MUTE" -> button("MUTE")
        "KEY_CHUP" -> ssap("ssap://tv/channelUp")
        "KEY_CHDOWN" -> ssap("ssap://tv/channelDown")
        "KEY_REWIND" -> ssap("ssap://media.controls/rewind")
        "KEY_PLAY" -> ssap("ssap://media.controls/play")
        "KEY_PAUSE" -> ssap("ssap://media.controls/pause")
        "KEY_STOP" -> ssap("ssap://media.controls/stop")
        "KEY_FF" -> ssap("ssap://media.controls/fastForward")
        "KEY_RED" -> button("RED")
        "KEY_GREEN" -> button("GREEN")
        "KEY_YELLOW" -> button("YELLOW")
        "KEY_CYAN" -> button("BLUE")
        "KEY_GUIDE" -> button("GUIDE")
        "KEY_TOOLS" -> button("QMENU")
        "KEY_CH_LIST" -> button("LIST")
        "KEY_PRECH" -> button("FLASHBACK")
        "KEY_NETFLIX" -> launch("netflix")
        "KEY_AMAZON" -> launch("amazon")
        "KEY_TV" -> launch("com.webos.app.livetv")
        "KEY_HDMI1" -> input("HDMI_1")
        "KEY_HDMI2" -> input("HDMI_2")
        "KEY_HDMI3" -> input("HDMI_3")
        else -> if (code.length == 5 && code.startsWith("KEY_") && code[4].isDigit()) button(code.substring(4)) else false
    }

    override fun move(dx: Int, dy: Int): Boolean = pointer?.send("type:move\ndx:$dx\ndy:$dy\ndown:0\n\n") ?: false
    override fun click(): Boolean = pointer?.send("type:click\n\n") ?: false
    override fun scroll(dy: Int): Boolean = pointer?.send("type:scroll\ndx:0\ndy:$dy\n\n") ?: false

    override fun text(text: String): Boolean =
        ssap("ssap://com.webos.service.ime/insertText", JSONObject().put("text", text).put("replace", 0))

    override fun backspace(): Boolean =
        ssap("ssap://com.webos.service.ime/deleteCharacters", JSONObject().put("count", 1))
}
