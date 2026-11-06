package com.universalremote

import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private lateinit var remotes: Map<Brand, TvRemote>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remotes = mapOf(Brand.Samsung to SamsungRemote(this), Brand.LG to LgRemote(this), Brand.Roku to RokuRemote(this))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { RemoteScreen(remotes) }
            }
        }
    }

    override fun onDestroy() {
        remotes.values.forEach { it.disconnect() }
        super.onDestroy()
    }
}

@Composable
fun RemoteScreen(remotes: Map<Brand, TvRemote>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("remote", android.content.Context.MODE_PRIVATE) }
    var brand by remember { mutableStateOf(Brand.values().firstOrNull { it.name == prefs.getString("brand", "") } ?: Brand.Samsung) }
    val remote = remotes.getValue(brand)
    var state by remember { mutableStateOf(ConnState.Disconnected) }
    var message by remember { mutableStateOf<String?>(null) }
    var ip by remember { mutableStateOf(prefs.getString("ip", "") ?: "") }
    var mac by remember { mutableStateOf("") }
    var needMac by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<FoundTv>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    val ui = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    DisposableEffect(brand) {
        remotes.forEach { (b, r) -> if (b != brand) { r.onState = { _, _ -> }; r.disconnect() } }
        remote.onState = { s, m -> ui.post { state = s; message = m } }
        state = ConnState.Disconnected; message = null
        if (ip.isNotBlank()) remote.connect(ip.trim())
        onDispose { }
    }

    /** Powers the TV on while it is off: Wake-on-LAN, then reconnect. */
    fun wake() {
        val host = ip.trim()
        if (host.isBlank()) { message = "Enter the TV's IP first"; return }
        Wol.normalize(mac)?.let { Wol.save(prefs, host, listOf(it)) }
        val macs = Wol.get(prefs, host)
        if (macs.isEmpty()) {
            needMac = true
            message = "Unknown TV MAC. Enter it below (TV Settings > Network > status), or connect once while the TV is on."
            return
        }
        needMac = false
        message = "Wake signal sent… connecting"
        thread { Wol.send(host, macs) }
        for (delay in longArrayOf(6000, 14000)) {
            ui.postDelayed({ if (state != ConnState.Connected) remote.connect(host) }, delay)
        }
    }

    val vibrator = remember { context.getSystemService(Vibrator::class.java) }
    fun press(key: String) {
        vibrator?.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
        if (key == "KEY_POWER" && state != ConnState.Connected) wake() else remote.key(key)
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        ConnectionBar(
            state, message, ip, found, scanning, scanned, brand, mac, needMac,
            onMac = { mac = it }, onWake = { wake() },
            onBrand = { brand = it; prefs.edit().putString("brand", it.name).apply() },
            onIp = { ip = it },
            onPick = { tv ->
                ip = tv.ip
                if (tv.brand != null) {
                    brand = tv.brand
                    prefs.edit().putString("brand", tv.brand.name).apply()
                } else {
                    android.widget.Toast.makeText(context, "${tv.label} found, but this app can't control it yet (supported: Samsung, LG, Roku)", android.widget.Toast.LENGTH_LONG).show()
                }
            },
            onConnect = { remote.connect(ip.trim()) },
            onScan = {
                scanning = true
                thread {
                    val r = Discovery.scan(context)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        found = r; scanning = false; scanned = true
                        val only = r.singleOrNull()
                        if (only != null) {
                            ip = only.ip
                            only.brand?.let { brand = it; prefs.edit().putString("brand", it.name).apply() }
                        }
                    }
                }
            },
        )
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Remote") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Keyboard") })
            Tab(tab == 2, { tab = 2 }, text = { Text("Touchpad") })
            Tab(tab == 3, { tab = 3 }, text = { Text("Apps") })
        }
        Box(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
            when (tab) {
                0 -> RemoteTab(::press)
                1 -> KeyboardTab(remote, ::press)
                2 -> TouchpadTab(remote, ::press)
                else -> AppsTab(::press)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionBar(
    state: ConnState, message: String?, ip: String, found: List<FoundTv>, scanning: Boolean, scanned: Boolean,
    brand: Brand, mac: String, needMac: Boolean, onMac: (String) -> Unit, onWake: () -> Unit,
    onBrand: (Brand) -> Unit, onPick: (FoundTv) -> Unit,
    onIp: (String) -> Unit, onConnect: () -> Unit, onScan: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ip, onValueChange = onIp, singleLine = true, label = { Text("TV IP address") },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onConnect() }),
            )
            Button(onClick = onConnect, enabled = ip.isNotBlank()) {
                Text(if (state == ConnState.Connected) "Reconnect" else "Connect")
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onScan, enabled = !scanning) { Text(if (scanning) "Scanning…" else "Find TV") }
            TextButton(onClick = onWake) { Text("Power on") }
            Brand.values().forEach { FilterChip(selected = brand == it, onClick = { onBrand(it) }, label = { Text(it.label) }) }
        }
        if (found.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                found.forEach { AssistChip(onClick = { onPick(it) }, label = { Text("${it.name ?: it.label} · ${it.ip}", maxLines = 1) }) }
            }
        } else if (scanned && !scanning) {
            Text("No TV found. Check that phone and TV are on the same Wi-Fi, then type the IP manually.",
                style = MaterialTheme.typography.labelSmall)
        }
        Text(
            when (state) {
                ConnState.Connected -> "● Connected"
                ConnState.Connecting -> "Connecting…"
                ConnState.WaitingForApproval -> message ?: "Waiting for TV approval…"
                ConnState.Disconnected -> message ?: "Not connected"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (state == ConnState.Connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (needMac) {
            OutlinedTextField(
                value = mac, onValueChange = onMac, singleLine = true, label = { Text("TV MAC address (AA:BB:CC:DD:EE:FF)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onWake() }),
            )
        }
    }
}

@Composable
fun RemoteBtn(label: String? = null, icon: ImageVector? = null, key: String, onKey: (String) -> Unit, modifier: Modifier = Modifier.size(64.dp), round: Boolean = true) {
    FilledTonalButton(
        onClick = { onKey(key) }, modifier = modifier,
        shape = if (round) CircleShape else ButtonDefaults.filledTonalShape,
        contentPadding = PaddingValues(4.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = label ?: key)
        else Text(label ?: key, fontSize = 12.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
fun RemoteTab(onKey: (String) -> Unit) {
    val s = Arrangement.spacedBy(10.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RemoteBtn(icon = Icons.Default.PowerSettingsNew, label = "Power", key = "KEY_POWER", onKey = onKey)
            RemoteBtn("SOURCE", key = "KEY_SOURCE", onKey = onKey)
            RemoteBtn(icon = Icons.Default.Menu, label = "Menu", key = "KEY_MENU", onKey = onKey)
        }
        // Numbers
        for (row in listOf("123", "456", "789")) {
            Row(horizontalArrangement = s) { row.forEach { RemoteBtn(it.toString(), key = "KEY_$it", onKey = onKey) } }
        }
        Row(horizontalArrangement = s) {
            RemoteBtn("LIST", key = "KEY_CH_LIST", onKey = onKey)
            RemoteBtn("0", key = "KEY_0", onKey = onKey)
            RemoteBtn("PRE-CH", key = "KEY_PRECH", onKey = onKey)
        }
        // D-pad
        RemoteBtn(icon = Icons.Default.KeyboardArrowUp, label = "Up", key = "KEY_UP", onKey = onKey)
        Row(horizontalArrangement = s, verticalAlignment = Alignment.CenterVertically) {
            RemoteBtn(icon = Icons.Default.KeyboardArrowLeft, label = "Left", key = "KEY_LEFT", onKey = onKey)
            RemoteBtn("OK", key = "KEY_ENTER", onKey = onKey, modifier = Modifier.size(80.dp))
            RemoteBtn(icon = Icons.Default.KeyboardArrowRight, label = "Right", key = "KEY_RIGHT", onKey = onKey)
        }
        RemoteBtn(icon = Icons.Default.KeyboardArrowDown, label = "Down", key = "KEY_DOWN", onKey = onKey)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RemoteBtn(icon = Icons.Default.ArrowBack, label = "Back", key = "KEY_RETURN", onKey = onKey)
            RemoteBtn(icon = Icons.Default.Home, label = "Home", key = "KEY_HOME", onKey = onKey)
            RemoteBtn("EXIT", key = "KEY_EXIT", onKey = onKey)
            RemoteBtn("INFO", key = "KEY_INFO", onKey = onKey)
        }
        // Volume / channel
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = s) {
                RemoteBtn(icon = Icons.Default.Add, label = "Vol +", key = "KEY_VOLUP", onKey = onKey)
                Text("VOL")
                RemoteBtn(icon = Icons.Default.Remove, label = "Vol -", key = "KEY_VOLDOWN", onKey = onKey)
            }
            RemoteBtn(icon = Icons.Default.VolumeOff, label = "Mute", key = "KEY_MUTE", onKey = onKey, modifier = Modifier.size(64.dp).align(Alignment.CenterVertically))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = s) {
                RemoteBtn(icon = Icons.Default.KeyboardArrowUp, label = "Ch +", key = "KEY_CHUP", onKey = onKey)
                Text("CH")
                RemoteBtn(icon = Icons.Default.KeyboardArrowDown, label = "Ch -", key = "KEY_CHDOWN", onKey = onKey)
            }
        }
        // Media
        Row(horizontalArrangement = s) {
            RemoteBtn(icon = Icons.Default.FastRewind, label = "Rewind", key = "KEY_REWIND", onKey = onKey)
            RemoteBtn(icon = Icons.Default.PlayArrow, label = "Play", key = "KEY_PLAY", onKey = onKey)
            RemoteBtn(icon = Icons.Default.Pause, label = "Pause", key = "KEY_PAUSE", onKey = onKey)
            RemoteBtn(icon = Icons.Default.Stop, label = "Stop", key = "KEY_STOP", onKey = onKey)
            RemoteBtn(icon = Icons.Default.FastForward, label = "FF", key = "KEY_FF", onKey = onKey)
        }
        // Colour keys
        Row(horizontalArrangement = s) {
            listOf("A" to "KEY_RED", "B" to "KEY_GREEN", "C" to "KEY_YELLOW", "D" to "KEY_CYAN").zip(
                listOf(0xFFE53935, 0xFF43A047, 0xFFFDD835, 0xFF1E88E5)
            ).forEach { (p, c) ->
                Button(
                    onClick = { onKey(p.second) },
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(c), contentColor = androidx.compose.ui.graphics.Color.Black),
                    modifier = Modifier.size(width = 64.dp, height = 40.dp),
                    contentPadding = PaddingValues(0.dp),
                ) { Text(p.first) }
            }
        }
        Row(horizontalArrangement = s) {
            RemoteBtn("GUIDE", key = "KEY_GUIDE", onKey = onKey)
            RemoteBtn("TOOLS", key = "KEY_TOOLS", onKey = onKey)
            RemoteBtn("CC", key = "KEY_CAPTION", onKey = onKey)
            RemoteBtn("SMART", key = "KEY_CONTENTS", onKey = onKey)
        }
    }
}

@Composable
fun KeyboardTab(remote: TvRemote, onKey: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var digits by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Text / characters", style = MaterialTheme.typography.titleMedium)
        Text(
            "Open a search box on the TV, type here and press Send. Then use the TV's own Done/OK. (Enter can type the highlighted on-screen key.)",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Letters, symbols, any language") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (text.isNotEmpty()) { remote.text(text); text = "" } }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { remote.text(text); text = "" }, enabled = text.isNotEmpty()) { Text("Send") }
            OutlinedButton(onClick = { text = "" }) { Text("Clear") }
        }

        HorizontalDivider()
        Text("Channel / digits", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = digits, onValueChange = { digits = it.filter(Char::isDigit) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Channel number or PIN") },
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val d = digits
                thread { d.forEach { remote.digit(it); Thread.sleep(250) }; remote.key("KEY_ENTER") }
                digits = ""
            }, enabled = digits.isNotEmpty()) { Text("Go") }
            OutlinedButton(onClick = { digits = "" }) { Text("Clear") }
        }

        HorizontalDivider()
        Text("Quick edit keys", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onKey("KEY_ENTER") }) { Text("OK") }
            OutlinedButton(onClick = { remote.backspace() }) { Text("⌫ (LG)") }
            OutlinedButton(onClick = { onKey("KEY_RETURN") }) { Text("Back") }
            OutlinedButton(onClick = { onKey("KEY_LEFT") }) { Text("←") }
            OutlinedButton(onClick = { onKey("KEY_RIGHT") }) { Text("→") }
        }
    }
}

@Composable
fun AppsTab(onKey: (String) -> Unit) {
    val keys = listOf(
        "Smart Hub" to "KEY_HOME", "Netflix" to "KEY_NETFLIX", "Prime Video" to "KEY_AMAZON",
        "HDMI 1" to "KEY_HDMI1", "HDMI 2" to "KEY_HDMI2", "HDMI 3" to "KEY_HDMI3",
        "TV" to "KEY_TV", "Input" to "KEY_SOURCE", "Settings" to "KEY_MENU",
        "Sleep" to "KEY_SLEEP", "Ambient" to "KEY_AMBIENT", "Picture" to "KEY_PICTURE_SIZE",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, key) ->
                    FilledTonalButton(onClick = { onKey(key) }, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
fun TouchpadTab(remote: TvRemote, onKey: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Drag to move the pointer, tap to click. Use this in the TV's browser or web apps.", style = MaterialTheme.typography.bodySmall)
        Box(
            Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(Unit) { detectTapGestures(onTap = { remote.click() }) }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        remote.move((drag.x * 2).roundToInt(), (drag.y * 2).roundToInt())
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Text("Touchpad", style = MaterialTheme.typography.labelLarge) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { remote.click() }) { Text("Click") }
            OutlinedButton(onClick = { remote.scroll(-10) }) { Text("Scroll ↑") }
            OutlinedButton(onClick = { remote.scroll(10) }) { Text("Scroll ↓") }
            OutlinedButton(onClick = { onKey("KEY_RETURN") }) { Text("Back") }
        }
    }
}
