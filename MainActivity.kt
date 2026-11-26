package com.example.hififx

import android.Manifest
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.math.exp
import kotlin.math.ln

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fx.init(applicationContext)
        setContent {
            val dark = when (Fx.theme) { 1 -> true; 2 -> false; else -> isSystemInDarkTheme() }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface { App() }
            }
        }
    }
}

@Composable
fun App() {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold { pad ->
        Column(Modifier.padding(pad)) {
            EnhancerRow()
            HiFiModeRow()
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                listOf("EQ", "Pro", "Master", "Headphones", "Profiles", "Effects", "Hi-Fi").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) { 0 -> EqTab(); 1 -> ProTab(); 2 -> MasterTab(); 3 -> HeadphonesTab(); 4 -> ProfilesTab(); 5 -> FxTab(); else -> HiFiTab() }
        }
    }
}

/** Master Enhancer switch: ON starts the background effects service, OFF stops it. */
@Composable
fun EnhancerRow() {
    val ctx = LocalContext.current
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Enhancer", style = MaterialTheme.typography.titleMedium)
            Text("ON: effects apply to your music player", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = EffectService.running, onCheckedChange = { on ->
            val i = Intent(ctx, EffectService::class.java)
            if (on) {
                if (Build.VERSION.SDK_INT >= 33) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                ContextCompat.startForegroundService(ctx, i)
            } else ctx.stopService(i)
        })
    }
}

/** Hi-Fi mode: bass, 3D and loudness off, only the EQ runs. */
@Composable
fun HiFiModeRow() {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Hi-Fi mode", style = MaterialTheme.typography.titleMedium)
            Text("Bass, 3D and loudness off. Only EQ runs, for clean sound.", style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = Fx.hifi, onCheckedChange = { Fx.hifi = it; Fx.save(); Fx.applyAll() })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqTab() {
    val info = Fx.eqInfo
    var cat by remember { mutableStateOf("All") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Equalizer", style = MaterialTheme.typography.titleMedium)
            Switch(checked = Fx.eqOn, onCheckedChange = { Fx.eqOn = it; Fx.save(); Fx.applyAll() })
        }
        Text(
            "Presets (${Fx.PRESETS.size})  ·  " + if (Fx.preset.isEmpty()) "Custom" else Fx.preset,
            Modifier.padding(top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("All", "Hi-Fi", "Music", "Indian", "Use", "Tone").forEach { c ->
                if (c == cat) Button(onClick = { cat = c }) { Text(c) }
                else OutlinedButton(onClick = { cat = c }) { Text(c) }
            }
        }
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Fx.PRESETS.filter { cat == "All" || it.cat == cat }.forEach { p ->
                if (Fx.preset == p.name) Button(onClick = { Fx.applyPreset(p) }) { Text(p.name) }
                else OutlinedButton(onClick = { Fx.applyPreset(p) }) { Text(p.name) }
            }
        }
        OutlinedButton(onClick = { Fx.resetFlat() }, Modifier.padding(top = 12.dp)) { Text("Reset to flat") }
        Text("Clarity (highs)   ${"%.1f".format(Fx.clar)} dB", Modifier.padding(top = 12.dp))
        Slider(
            value = Fx.clar,
            onValueChange = { Fx.clar = Math.round(it * 2) / 2f; Fx.applyAll() },
            onValueChangeFinished = { Fx.save() },
            valueRange = -6f..12f
        )
        Text("Preamp   ${"%.1f".format(Fx.pre)} dB")
        Slider(
            value = Fx.pre,
            onValueChange = { Fx.pre = Math.round(it * 2) / 2f; Fx.applyAll() },
            onValueChangeFinished = { Fx.save() },
            valueRange = -12f..12f
        )
        info.freqs.forEachIndexed { b, hz ->
            val label = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
            Text("$label   ${"%.1f".format(Fx.levels[b] / 100f)} dB", Modifier.padding(top = 8.dp))
            Slider(
                value = Fx.levels[b].toFloat(),
                onValueChange = { Fx.levels[b] = it.toInt(); Fx.preset = ""; Fx.applyAll() },
                onValueChangeFinished = { Fx.save() },
                valueRange = info.min.toFloat()..info.max.toFloat()
            )
        }
    }
}

private fun fmtHz(c: Double): String {
    fun n(x: Double) = if (x % 1.0 == 0.0) x.toInt().toString() else x.toString()
    return if (c >= 1000) n(c / 1000) + " kHz" else n(c) + " Hz"
}

@Composable
fun CurveView() {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline
    Canvas(Modifier.fillMaxWidth().height(120.dp).padding(vertical = 8.dp)) {
        val w = size.width
        val h = size.height
        drawLine(grid, Offset(0f, h / 2), Offset(w, h / 2), 1f)
        val n = 120
        var prev: Offset? = null
        for (i in 0 until n) {
            val f = 20.0 * Math.pow(1000.0, i / (n - 1.0))
            val db = Fx.proDb(f).coerceIn(-15.0, 15.0)
            val p = Offset(i / (n - 1f) * w, h / 2 - (db / 15.0 * (h / 2 - 4)).toFloat())
            prev?.let { drawLine(line, it, p, 4f) }
            prev = p
        }
    }
}

@Composable
fun FilterCard(i: Int, f: Fx.PF) {
    fun set(n: Fx.PF) { Fx.pf[i] = n; Fx.applyAll() }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Filter ${i + 1}", style = MaterialTheme.typography.titleSmall)
                Switch(checked = f.on, onCheckedChange = { set(f.copy(on = it)); Fx.save() })
            }
            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Peak", "Low shelf", "High shelf").forEachIndexed { t, name ->
                    if (t == f.type) Button(onClick = { set(f.copy(type = t)); Fx.save() }) { Text(name) }
                    else OutlinedButton(onClick = { set(f.copy(type = t)); Fx.save() }) { Text(name) }
                }
            }
            Text("Frequency  ${f.freq.toInt()} Hz")
            Slider(
                value = ln(f.freq),
                onValueChange = { set(f.copy(freq = exp(it))) },
                onValueChangeFinished = { Fx.save() },
                valueRange = ln(20f)..ln(20000f)
            )
            Text("Gain  ${"%.1f".format(f.gain)} dB")
            Slider(
                value = f.gain,
                onValueChange = { set(f.copy(gain = Math.round(it * 2) / 2f)) },
                onValueChangeFinished = { Fx.save() },
                valueRange = -12f..12f
            )
            Text("Q  ${"%.2f".format(f.q)}")
            Slider(
                value = f.q,
                onValueChange = { set(f.copy(q = Math.round(it * 20) / 20f)) },
                onValueChangeFinished = { Fx.save() },
                valueRange = 0.3f..10f
            )
        }
    }
}

@Composable
fun ProTab() {
    var show31 by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pro engine", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (Fx.proOk) "Active: parametric EQ, 31-band EQ and limiter."
                    else "Not active yet. Turn Enhancer ON, then play music or the test sweep. Some phones do not support the Pro engine.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = Fx.proOn, onCheckedChange = { Fx.proOn = it; Fx.save(); Fx.applyAll() })
        }
        CurveView()
        Fx.pf.forEachIndexed { i, f -> FilterCard(i, f) }
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Limiter and boost", style = MaterialTheme.typography.titleSmall)
                    Switch(checked = Fx.limOn, onCheckedChange = { Fx.limOn = it; Fx.save(); Fx.applyAll() })
                }
                Text("Ceiling  ${"%.1f".format(Fx.limCeil)} dB")
                Slider(
                    value = Fx.limCeil,
                    onValueChange = { Fx.limCeil = Math.round(it * 2) / 2f; Fx.applyAll() },
                    onValueChangeFinished = { Fx.save() },
                    valueRange = -12f..0f
                )
                Text("Boost  ${"%.1f".format(Fx.boost)} dB (off in Hi-Fi mode)")
                Slider(
                    value = Fx.boost,
                    onValueChange = { Fx.boost = Math.round(it * 2) / 2f; Fx.applyAll() },
                    onValueChangeFinished = { Fx.save() },
                    valueRange = 0f..12f
                )
                Text("Keep the limiter ON when you use boost, so peaks do not clip.", style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedButton(onClick = { show31 = !show31 }, Modifier.padding(top = 8.dp)) {
            Text(if (show31) "Hide 31-band EQ" else "Show 31-band EQ")
        }
        if (show31) {
            Fx.PRO_C.forEachIndexed { i, c ->
                Text("${fmtHz(c)}   ${"%.1f".format(Fx.g31[i])} dB", Modifier.padding(top = 6.dp))
                Slider(
                    value = Fx.g31[i],
                    onValueChange = { Fx.g31[i] = Math.round(it * 2) / 2f; Fx.applyAll() },
                    onValueChangeFinished = { Fx.save() },
                    valueRange = -12f..12f
                )
            }
        }
    }
}

@Composable
fun HeadphonesTab() {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    var q by remember { mutableStateOf("") }
    val connected = remember {
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
        }?.productName?.toString()
    }
    val all = remember(q) {
        val tokens = q.trim().lowercase().split(" ").filter { it.isNotEmpty() }
        if (tokens.isEmpty()) Fx.HP else Fx.HP.filter { m -> val n = m.name.lowercase(); tokens.all { n.contains(it) } }
    }
    val list = all.take(150)
    LazyColumn(Modifier.padding(horizontal = 16.dp)) {
        item {
            Column {
                Text("Headphone model", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pick your exact model to load its correction curve into the EQ. If your earbuds are not listed (many local brands are not), use a similar type from the Profiles tab or tune by ear.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (connected != null) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Connected: $connected", Modifier.weight(1f))
                        OutlinedButton(onClick = { q = connected }) { Text("Search") }
                    }
                }
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    label = { Text("Search model, e.g. Sony WH-1000XM4") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (all.size > 150) "Showing 150 of ${all.size}" else "${all.size} found") + if (Fx.preset.isNotEmpty()) "  ·  Now: ${Fx.preset}" else "",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = { Fx.resetFlat() }) { Text("Remove") }
                }
            }
        }
        items(list) { m ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { Fx.applyHeadphone(m) }) {
                Text(
                    (if (Fx.preset == m.name) "\u2713 " else "") + m.name,
                    Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, n ->
            if (i == selected) Button(onClick = { onSelect(i) }) { Text(n) }
            else OutlinedButton(onClick = { onSelect(i) }) { Text(n) }
        }
    }
}

@Composable
fun MasterTab() {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (!Fx.proOn) Text("Turn on the Pro engine in the Pro tab. These controls use it.", Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodyMedium)
        Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto headroom", style = MaterialTheme.typography.titleSmall)
                    Text("Lowers the level by the biggest EQ boost, so peaks do not clip.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = Fx.headroom, onCheckedChange = { Fx.headroom = it; Fx.save(); Fx.applyAll() })
            }
        }
        Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Multiband compression", style = MaterialTheme.typography.titleSmall)
                    Switch(checked = Fx.mbcOn, onCheckedChange = { Fx.mbcOn = it; Fx.save(); Fx.applyAll() })
                }
                Text("Bands", Modifier.padding(top = 8.dp))
                Chips(listOf("3 bands", "4 bands", "5 bands"), Fx.mbcBands - 3) { Fx.mbcBands = it + 3; Fx.save(); Fx.applyAll() }
                Text("Character", Modifier.padding(top = 8.dp))
                Chips(listOf("Transparent", "Glue", "Punch"), Fx.mbcChar) { Fx.mbcChar = it; Fx.save(); Fx.applyAll() }
                Text("Amount  ${Fx.mbcAmt.toInt()}%", Modifier.padding(top = 8.dp))
                Slider(value = Fx.mbcAmt, onValueChange = { Fx.mbcAmt = it; Fx.applyAll() }, onValueChangeFinished = { Fx.save() }, valueRange = 0f..100f)
            }
        }
        Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Output protection", style = MaterialTheme.typography.titleSmall)
                    Switch(checked = Fx.limOn, onCheckedChange = { Fx.limOn = it; Fx.save(); Fx.applyAll() })
                }
                Text("Protection mode", Modifier.padding(top = 8.dp))
                Chips(listOf("Transparent", "Safe", "Loud"), Fx.limMode) { Fx.limMode = it; Fx.save(); Fx.applyAll() }
                Text("Peak limit  ${"%.1f".format(Fx.limCeil)} dB", Modifier.padding(top = 8.dp))
                Slider(value = Fx.limCeil, onValueChange = { Fx.limCeil = Math.round(it * 2) / 2f; Fx.applyAll() }, onValueChangeFinished = { Fx.save() }, valueRange = -12f..0f)
            }
        }
        Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(16.dp)) {
                val b = Fx.balance.toInt()
                Text("Balance  " + if (b == 0) "Center" else if (b < 0) "Left ${-b}" else "Right $b", style = MaterialTheme.typography.titleSmall)
                Slider(value = Fx.balance, onValueChange = { Fx.balance = Math.round(it).toFloat(); Fx.applyAll() }, onValueChangeFinished = { Fx.save() }, valueRange = -100f..100f)
                OutlinedButton(onClick = { Fx.balance = 0f; Fx.save(); Fx.applyAll() }) { Text("Center") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilesTab() {
    var q by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf("All") }
    val cats = listOf("All", "Root/Magisk", "Spatial", "Hi-Fi/DAC", "Studio", "Mobile/Car", "IEM/Planar")
    val list = Fx.PROFILES.filter {
        (cat == "All" || it.cat == cat) &&
            (q.isBlank() || it.name.contains(q, true) || it.desc.contains(q, true) || it.cat.contains(q, true))
    }
    LazyColumn(Modifier.padding(horizontal = 16.dp)) {
        item {
            Column {
                OutlinedTextField(
                    value = q, onValueChange = { q = it }, singleLine = true,
                    label = { Text("Search Dolby, Viper, 64 Audio, Moondrop...") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    cats.forEach { c ->
                        val n = if (c == "All") Fx.PROFILES.size else Fx.PROFILES.count { it.cat == c }
                        if (c == cat) Button(onClick = { cat = c }) { Text("$c ($n)") }
                        else OutlinedButton(onClick = { cat = c }) { Text("$c ($n)") }
                    }
                }
                Text("${list.size} / ${Fx.PROFILES.size} profiles", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
                Text(
                    "Approximate tuning styles named after products. They are not the real Dolby, Viper or measured headphone curves.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }
        items(list) { p ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { Fx.applyProfile(p) }) {
                Column(Modifier.padding(12.dp)) {
                    Text((if (Fx.preset == p.name) "\u2713 " else "") + p.name, style = MaterialTheme.typography.titleSmall)
                    Text(p.desc, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun FxTab() {
    val on = !Fx.hifi
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (Fx.hifi) Text("Hi-Fi mode is ON, so these are switched off. Turn it off at the top to use them.", Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodyMedium)
        Block("Bass Boost", on, Fx.bassOn, { Fx.bassOn = it }, Fx.bass, 0, 1000, { Fx.bass = it }, "${Fx.bass / 10}%")
        Block("3D Surround", on, Fx.virtOn, { Fx.virtOn = it }, Fx.virt, 0, 1000, { Fx.virt = it }, "${Fx.virt / 10}%")
        Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Natural" to 300, "Expanded" to 600, "Immersive" to 900).forEach { (n, v) ->
                OutlinedButton(enabled = on, onClick = { Fx.virt = v; Fx.virtOn = true; Fx.save(); Fx.applyAll() }) { Text(n) }
            }
        }
        Block("Loudness Boost", on, Fx.loudOn, { Fx.loudOn = it }, Fx.loud, 0, 1500, { Fx.loud = it }, "+${"%.1f".format(Fx.loud / 100f)} dB")
        Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Reverb (experimental)", style = MaterialTheme.typography.titleSmall)
                    Switch(checked = Fx.rvOn && on, enabled = on, onCheckedChange = { Fx.rvOn = it; Fx.save(); Fx.applyAll() })
                }
                Text("Mix: ${Fx.rvMix.toInt()}%")
                Slider(value = Fx.rvMix, enabled = on, onValueChange = { Fx.rvMix = it; Fx.applyAll() }, onValueChangeFinished = { Fx.save() }, valueRange = 0f..100f)
                Text("Time: ${Fx.rvTime.toInt()}%")
                Slider(value = Fx.rvTime, enabled = on, onValueChange = { Fx.rvTime = it; Fx.applyAll() }, onValueChangeFinished = { Fx.save() }, valueRange = 0f..100f)
                Text("Many phones do not allow reverb on other apps. If you hear no change, your phone does not support it.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Keep loudness low if you hear distortion. High values can clip and strain your headphones.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { Fx.theme = (Fx.theme + 1) % 3; Fx.save() }, Modifier.padding(top = 16.dp)) {
            Text("Theme: " + listOf("System", "Dark", "Light")[Fx.theme])
        }
    }
}

@Composable
fun Block(title: String, enabled: Boolean, on: Boolean, setOn: (Boolean) -> Unit, v: Int, min: Int, max: Int, setV: (Int) -> Unit, shown: String) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Switch(checked = on && enabled, enabled = enabled, onCheckedChange = { setOn(it); Fx.save(); Fx.applyAll() })
            }
            Text(shown)
            Slider(
                value = v.toFloat(),
                enabled = enabled,
                onValueChange = { setV(it.toInt()); Fx.applyAll() },
                onValueChangeFinished = { Fx.save() },
                valueRange = min.toFloat()..max.toFloat()
            )
        }
    }
}

private fun typeName(t: Int) = when (t) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (A2DP)"
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB DAC / device"
    else -> "Output ($t)"
}

private fun encName(e: Int) = when (e) {
    AudioFormat.ENCODING_PCM_16BIT -> "PCM16"
    AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
    AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM24"
    AudioFormat.ENCODING_PCM_32BIT -> "PCM32"
    else -> "enc $e"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HiFiTab() {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    var devs by remember { mutableStateOf(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()) }
    var msg by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Test sweep", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A 6 second tone from 35 Hz to 18 kHz. Use it to hear your EQ and effects without a music player. Keep the volume low first.",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(onClick = { if (Sweep.playing) Sweep.stop() else Sweep.start() }, Modifier.padding(top = 8.dp)) {
                    Text(if (Sweep.playing) "Stop sweep" else "Play test sweep")
                }
            }
        }
        Text("Output devices", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { devs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }) { Text("Refresh") }
        devs.forEach { d ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("${typeName(d.type)} · ${d.productName}", style = MaterialTheme.typography.titleSmall)
                    Text("Sample rates: ${d.sampleRates.joinToString().ifEmpty { "any" }} Hz")
                    Text("Formats: ${d.encodings.joinToString { encName(it) }.ifEmpty { "any" }}")
                    Text("Channels: ${d.channelCounts.joinToString().ifEmpty { "any" }}")
                    if (Build.VERSION.SDK_INT >= 34 &&
                        (d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET)
                    ) {
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { msg = bitPerfect(am, d, true) }) { Text("Bit-perfect ON") }
                            OutlinedButton(onClick = { msg = bitPerfect(am, d, false) }) { Text("OFF") }
                        }
                    }
                }
            }
        }
        if (msg.isNotEmpty()) Text(msg, Modifier.padding(vertical = 8.dp))
        Text("LDAC / Bluetooth codec", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(
            "Android does not let apps change the Bluetooth codec. Set it in Developer options: " +
                "Bluetooth audio codec = LDAC, sample rate = 96 kHz, bits per sample = 24 or 32, " +
                "LDAC playback quality = Best quality. Your headphones must support LDAC.",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }, Modifier.padding(top = 8.dp)) {
            Text("Open Developer options")
        }
        Text("Bit-perfect output needs Android 14 or newer and a USB DAC that supports it.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        val mixerRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) ?: "unknown"
        val usb = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        val btOut = devs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
        Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Hi-Res check", style = MaterialTheme.typography.titleMedium)
                Text("System mixer rate: $mixerRate Hz. Android resamples normal apps to this rate.", Modifier.padding(top = 4.dp))
                val topRate = usb?.sampleRates?.maxOrNull()
                if (usb != null) {
                    Text("USB DAC found" + (if (topRate != null) ", up to $topRate Hz" else "") + ". Turn Bit-perfect ON above (Android 14 or newer) to keep Hi-Res untouched.", Modifier.padding(top = 4.dp))
                } else if (btOut) {
                    Text("Bluetooth headphones connected. Pick LDAC or LHDC in Developer options.", Modifier.padding(top = 4.dp))
                } else {
                    Text("No USB DAC or Bluetooth output right now. Connect one to use Hi-Res.", Modifier.padding(top = 4.dp))
                }
                Text(
                    "Hi-Res means 24-bit and 96 kHz or more. The music file must be Hi-Res too (FLAC or WAV). MP3 and most streaming are not.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Bluetooth codecs", style = MaterialTheme.typography.titleMedium)
                Text("LDAC: up to 24-bit / 96 kHz, 330 / 660 / 990 kbps", Modifier.padding(top = 4.dp))
                Text("LHDC: up to 24-bit / 96 kHz, up to 900 kbps. Newer LHDC-V reaches 192 kHz on supported phones.")
                Text("aptX HD: 24-bit / 48 kHz, 576 kbps")
                Text("aptX Adaptive: 24-bit / 96 kHz, 279 to 420 kbps")
                Text("AAC: 16-bit, about 256 to 320 kbps")
                Text("SBC: 16-bit / 48 kHz, about 328 kbps. Works on every device.")
                Text(
                    "This app cannot add codecs. Both your phone and your headphones must support the codec. Choose it in Developer options under Bluetooth audio codec, then check with the root button below if your phone is rooted.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Bluetooth settings") }
                }
            }
        }
        Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Root tools (optional)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Needs a rooted phone. These only read system info or add this app to the battery whitelist. They do not edit system files.",
                    style = MaterialTheme.typography.bodySmall
                )
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { Root.run("Root check", "id") }) { Text("Check root") }
                    OutlinedButton(onClick = {
                        Root.run("Audio engine", "dumpsys media.audio_flinger", Regex("(?i)(sample rate|hal format|channel mask|latency)"), 24)
                    }) { Text("Audio engine info") }
                    OutlinedButton(onClick = {
                        Root.run("Bluetooth codec", "dumpsys bluetooth_manager", Regex("(?i)codec"), 16)
                    }) { Text("Bluetooth codec") }
                    OutlinedButton(onClick = {
                        Root.run("Keep app alive", "dumpsys deviceidle whitelist +com.example.hififx")
                    }) { Text("Keep app alive") }
                }
                if (Root.out.isNotEmpty()) Text(Root.out, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun bitPerfect(am: AudioManager, d: AudioDeviceInfo, on: Boolean): String {
    if (Build.VERSION.SDK_INT < 34) return "Needs Android 14 or newer"
    val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    return try {
        if (!on) {
            am.clearPreferredMixerAttributes(attrs, d); "Bit-perfect OFF"
        } else {
            val m = am.getSupportedMixerAttributes(d)
                .firstOrNull { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            when {
                m == null -> "This DAC does not offer bit-perfect mode"
                am.setPreferredMixerAttributes(attrs, d, m) -> "Bit-perfect ON (${m.format.sampleRate} Hz)"
                else -> "Could not enable bit-perfect"
            }
        }
    } catch (t: Throwable) { "Error: ${t.message}" }
}
