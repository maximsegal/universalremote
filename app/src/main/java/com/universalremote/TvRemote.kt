package com.universalremote

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

enum class ConnState { Disconnected, Connecting, WaitingForApproval, Connected }

enum class Brand(val label: String) { Samsung("Samsung"), LG("LG"), Roku("Roku") }

interface TvRemote {
    var onState: (ConnState, String?) -> Unit
    fun connect(ip: String)
    fun disconnect()
    /** Sends a key using Samsung-style KEY_* codes; each driver translates it for its TV. */
    fun key(code: String): Boolean
    fun text(text: String): Boolean
    fun backspace(): Boolean
    /** Pointer/mouse control for browsers and web apps on the TV. */
    fun move(dx: Int, dy: Int): Boolean = false
    fun click(): Boolean = false
    fun scroll(dy: Int): Boolean = false
}

fun TvRemote.digit(d: Char): Boolean = key("KEY_$d")

object Net {
    /** TVs use self-signed certificates; we only talk to the LAN IP the user chose. */
    val client: OkHttpClient = run {
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
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
