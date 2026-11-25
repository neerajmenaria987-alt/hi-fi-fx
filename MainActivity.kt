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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fx.init(applicationContext)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
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
            ServiceRow()
            TabRow(selectedTabIndex = tab) {
                listOf("EQ", "Effects", "Hi-Fi").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) { 0 -> EqTab(); 1 -> FxTab(); else -> HiFiTab() }
        }
    }
}

@Composable
fun ServiceRow() {
    val ctx = LocalContext.current
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    Row(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("System-wide effects", style = MaterialTheme.typography.titleMedium)
            Text("Works with players that share their audio session", style = MaterialTheme.typography.bodySmall)
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

private val PRESETS = mapOf(
    "Flat" to listOf(0, 0, 0, 0, 0),
    "Bass" to listOf(600, 400, 0, 0, 0),
    "Vocal" to listOf(-200, 0, 400, 300, 0),
    "Treble" to listOf(0, 0, 0, 400, 600),
    "Rock" to listOf(500, 300, -200, 300, 500)
)

@Composable
fun EqTab() {
    val info = Fx.eqInfo
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Equalizer", style = MaterialTheme.typography.titleMedium)
            Switch(checked = Fx.eqOn, onCheckedChange = { Fx.eqOn = it; Fx.save(); Fx.applyAll() })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PRESETS.forEach { (name, src) ->
                OutlinedButton(onClick = {
                    val n = info.freqs.size
                    for (b in 0 until n) {
                        val v = src[if (n == 1) 0 else b * (src.size - 1) / (n - 1)]
                        Fx.levels[b] = v.coerceIn(info.min, info.max)
                    }
                    Fx.save(); Fx.applyAll()
                }) { Text(name) }
            }
        }
        info.freqs.forEachIndexed { b, hz ->
            val label = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
            Text("$label   ${"%.1f".format(Fx.levels[b] / 100f)} dB", Modifier.padding(top = 8.dp))
            Slider(
                value = Fx.levels[b].toFloat(),
                onValueChange = { Fx.levels[b] = it.toInt(); Fx.applyAll() },
                onValueChangeFinished = { Fx.save() },
                valueRange = info.min.toFloat()..info.max.toFloat()
            )
        }
    }
}

@Composable
fun FxTab() {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Block("Bass boost", Fx.bassOn, { Fx.bassOn = it }, Fx.bass, 0, 1000, { Fx.bass = it }, "${Fx.bass / 10}%")
        Block("Virtualizer (spatial widening)", Fx.virtOn, { Fx.virtOn = it }, Fx.virt, 0, 1000, { Fx.virt = it }, "${Fx.virt / 10}%")
        Block("Loudness / volume boost", Fx.loudOn, { Fx.loudOn = it }, Fx.loud, 0, 1500, { Fx.loud = it }, "+${"%.1f".format(Fx.loud / 100f)} dB")
        Text("Keep loudness low if you hear distortion. High values can clip and strain your headphones.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun Block(title: String, on: Boolean, setOn: (Boolean) -> Unit, v: Int, min: Int, max: Int, setV: (Int) -> Unit, shown: String) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Switch(checked = on, onCheckedChange = { setOn(it); Fx.save(); Fx.applyAll() })
            }
            Text(shown)
            Slider(
                value = v.toFloat(),
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

@Composable
fun HiFiTab() {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    var devs by remember { mutableStateOf(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()) }
    var msg by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
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
