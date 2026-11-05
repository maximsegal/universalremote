package com.universalremote

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

enum class ConnState { Disconnected, Connecting, WaitingForApproval, Connected }

/**
 * Client for the Samsung Smart TV (Tizen, 2016+) WebSocket remote API.
 * Pairing: the first connection makes the TV show an "Allow" prompt; the TV
 * then returns a token which we persist and reuse.
 */
class SamsungRemote(private val context: Context) {
    private val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)

    /** Samsung TVs use a self-signed certificate; this client only talks to the user's chosen LAN IP. */
    private val client: OkHttpClient = run {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    private var socket: WebSocket? = null
    @Volatile private var generation = 0

    var onState: (ConnState, String?) -> Unit = { _, _ -> }

    var savedIp: String
        get() = prefs.getString("ip", "") ?: ""
        set(v) = prefs.edit().putString("ip", v).apply()

    private fun tokenKey(ip: String) = "token_$ip"

    fun connect(ip: String) {
        disconnect()
        val gen = ++generation
        onState(ConnState.Connecting, null)
        val name = Base64.encodeToString("UniversalRemote".toByteArray(), Base64.NO_WRAP)
        val token = prefs.getString(tokenKey(ip), null)
        val tokenPart = if (token != null) "&token=$token" else ""
        val url = "wss://$ip:8002/api/v2/channels/samsung.remote.control?name=$name$tokenPart"
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
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
                            savedIp = ip
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
                if (gen == generation) onState(ConnState.Disconnected, "Can't reach TV (${t.message ?: "error"})")
            }
        })
    }

    fun disconnect() {
        generation++
        socket?.close(1000, null)
        socket = null
    }

    private fun send(params: JSONObject): Boolean {
        val msg = JSONObject().put("method", "ms.remote.control").put("params", params)
        return socket?.send(msg.toString()) ?: false
    }

    fun key(code: String): Boolean = send(
        JSONObject().put("Cmd", "Click").put("DataOfCmd", code)
            .put("Option", "false").put("TypeOfRemote", "SendRemoteKey")
    )

    /** Types text into the TV's currently focused text field (keyboard/search box on screen). */
    fun text(text: String): Boolean = send(
        JSONObject().put("Cmd", Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP))
            .put("DataOfCmd", "base64").put("Option", "false").put("TypeOfRemote", "SendInputString")
    )

    fun digit(d: Char): Boolean = key("KEY_$d")

    companion object {
        /** Finds Samsung TVs on the LAN through SSDP. Blocking: call from a background thread. */
        fun discover(context: Context, timeoutMs: Int = 3000): List<String> {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("remote-ssdp").apply { setReferenceCounted(false); acquire() }
            val found = linkedSetOf<String>()
            try {
                DatagramSocket().use { s ->
                    s.soTimeout = 800
                    val req = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:samsung.com:device:RemoteControlReceiver:1\r\n\r\n").toByteArray()
                    s.send(DatagramPacket(req, req.size, InetAddress.getByName("239.255.255.250"), 1900))
                    val end = System.currentTimeMillis() + timeoutMs
                    val buf = ByteArray(2048)
                    while (System.currentTimeMillis() < end) {
                        try {
                            val p = DatagramPacket(buf, buf.size)
                            s.receive(p)
                            found.add(p.address.hostAddress ?: continue)
                        } catch (_: SocketTimeoutException) {}
                    }
                }
            } catch (_: Exception) {
            } finally {
                lock.release()
            }
            return found.toList()
        }
    }
}
