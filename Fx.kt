package com.example.hififx

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.ln

/** Holds all effect settings, presets and the effect chains attached to audio sessions. */
object Fx {
    class EqInfo(val freqs: List<Int>, val min: Int, val max: Int)
    class Preset(val name: String, val cat: String, val curve: List<Int>, val bass: Int, val surround: Int, val loud: Int)

    private var sp: SharedPreferences? = null
    var eqOn by mutableStateOf(true)
    var hifi by mutableStateOf(false)      // Hi-Fi mode: only EQ runs
    var preset by mutableStateOf("")       // selected preset name, empty = custom
    var bassOn by mutableStateOf(false)
    var bass by mutableIntStateOf(500)
    var virtOn by mutableStateOf(false)    // 3D surround
    var virt by mutableIntStateOf(500)
    var loudOn by mutableStateOf(false)
    var loud by mutableIntStateOf(0)       // millibel
    val levels = mutableStateListOf<Int>() // millibel per band

    val eqInfo: EqInfo by lazy {
        try {
            val e = Equalizer(0, 0)
            val n = e.numberOfBands.toInt()
            val r = e.bandLevelRange
            val f = (0 until n).map { e.getCenterFreq(it.toShort()) / 1000 }
            e.release()
            EqInfo(f, r[0].toInt(), r[1].toInt())
        } catch (t: Throwable) {
            EqInfo(listOf(60, 230, 910, 3600, 14000), -1500, 1500)
        }
    }

    // Approximate curves (dB) at 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz.
    // Format: name|category|10 values|bass 0-1000 (optional)|3D 0-1000 (optional)|loudness mB (optional)
    private val RAW = """
Reference|Hi-Fi|0,0,0,0,0,0,0,0,0,0
Warm|Hi-Fi|2,2,1,1,0,0,-1,-1,-2,-2
Bright|Hi-Fi|-1,-1,0,0,0,1,2,3,3,3
Analytical|Hi-Fi|-2,-1,0,0,0,1,2,2,1,0
Rock|Music|5,4,2,-1,-2,-1,2,4,5,5
Pop|Music|-1,1,3,4,3,0,-1,-1,1,2
Jazz|Music|3,2,1,2,-1,-1,0,1,2,3
Classical|Music|4,3,2,2,-1,-1,0,2,3,4
EDM|Music|7,6,3,0,-1,0,1,3,5,5|400
Hip Hop|Music|7,6,3,2,0,-1,0,1,2,2|400
R&B|Music|5,6,3,1,0,0,1,1,2,2
Dance|Music|6,5,3,0,0,0,1,3,4,4
Metal|Music|5,4,1,-1,-3,-1,3,5,5,3
Country|Music|3,2,1,1,0,0,2,3,3,2
Blues|Music|3,3,2,1,0,-1,0,1,2,2
Reggae|Music|5,4,0,-2,0,1,2,2,0,0
Latin|Music|3,3,1,0,0,1,2,3,3,3
Folk|Music|2,2,1,1,0,1,2,2,2,1
Acoustic|Music|3,3,2,1,1,1,2,2,3,2
Piano|Music|1,1,0,0,1,2,3,3,2,1
Lounge|Music|2,2,1,0,-1,0,1,1,1,0
Indie|Music|2,2,1,0,0,1,2,3,3,2
Techno|Music|6,5,2,0,-1,0,2,4,5,4
Trance|Music|5,4,2,1,0,0,2,4,5,5
Soul|Music|4,4,2,1,0,0,1,2,2,2
Opera|Music|3,2,1,0,0,1,2,3,3,2
Bollywood|Indian|4,4,3,1,0,1,2,3,3,2
Qawwali|Indian|1,1,1,2,3,4,4,2,1,0
Ghazal|Indian|2,2,2,2,2,3,3,2,1,0
Sufi|Indian|2,2,2,3,3,3,3,2,1,0
Bhajan|Indian|1,2,2,3,3,3,2,1,0,0
Punjabi|Indian|7,6,3,1,0,1,2,3,3,2|400
Filmi Dance|Indian|6,5,3,1,0,1,2,3,4,3
Indian Classical|Indian|2,2,2,2,2,2,3,3,3,2
Dhol Folk|Indian|6,6,2,0,0,1,2,2,1,0
Devotional|Indian|2,2,2,2,2,2,2,1,0,0
Movie|Use|6,5,2,0,0,2,3,3,2,1||500
Gaming|Use|3,2,0,-1,-1,1,4,5,4,3||400
Podcast|Use|-6,-4,-2,1,3,4,4,2,0,-2
Audiobook|Use|-5,-3,-1,2,3,4,3,1,0,-1
Night Mode|Use|-2,-1,0,1,1,1,0,-1,-2,-3|0|0|0
Car Audio|Use|6,5,2,0,-1,0,1,3,4,3|||400
Party|Use|6,5,3,1,0,0,2,3,4,4|500||300
Workout|Use|5,5,3,1,0,1,2,3,4,3
Sleep|Use|1,1,0,0,-1,-2,-3,-4,-5,-6
Study|Use|-2,-1,0,1,1,1,0,-1,-2,-2
Karaoke|Use|-2,-1,0,0,1,2,3,3,2,1
Voice Call|Use|-8,-6,-3,0,3,4,4,2,0,-4
News|Use|-6,-4,-1,1,3,4,4,2,0,-2
Phone Speaker|Use|-4,-3,0,3,4,3,2,2,1,0
Laptop Speaker|Use|-3,-2,1,3,3,2,1,2,1,0
TV|Use|3,3,1,0,0,2,3,2,1,1
Earphone Fix|Use|3,2,1,0,0,0,1,0,-1,-1
Headphone Fix|Use|2,2,1,0,0,0,0,-1,-1,-2
Live Concert|Use|3,3,2,1,0,1,2,3,3,3||400
Studio|Use|0,0,0,0,0,1,1,1,1,0
Bass Boost|Tone|8,7,5,2,0,0,0,0,0,0|700
Bass Reducer|Tone|-6,-5,-3,-1,0,0,0,0,0,0|0
Treble Boost|Tone|0,0,0,0,0,1,3,5,7,8
Treble Reducer|Tone|0,0,0,0,0,-1,-3,-5,-6,-7
Vocal Booster|Tone|-2,-2,-1,1,3,4,4,2,0,-1
Loudness|Tone|6,4,1,0,-1,0,0,3,5,6|||500
Clarity|Tone|0,0,0,-1,-1,0,2,4,4,3
Presence|Tone|0,0,0,0,0,2,4,4,2,0
Deep Bass|Tone|7,6,4,1,0,-1,-2,-2,-3,-3|600
Small Speakers|Tone|-5,-3,1,3,3,2,1,1,0,0
3D Wide|Tone|3,2,1,0,0,0,1,2,3,3||800
"""
    private val FQ10 = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    val PRESETS: List<Preset> = RAW.trim().lines().map { line ->
        val p = line.split("|")
        fun opt(i: Int) = p.getOrNull(i)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: -1
        Preset(p[0], p[1], p[2].split(",").map { it.toInt() }, opt(3), opt(4), opt(5))
    }

    private fun curveAt(c: List<Int>, hz: Int): Float {
        val xs = FQ10.map { ln(it.toFloat()) }
        val x = ln(hz.toFloat())
        if (x <= xs.first()) return c.first().toFloat()
        if (x >= xs.last()) return c.last().toFloat()
        val i = xs.indexOfLast { it <= x }
        val t = (x - xs[i]) / (xs[i + 1] - xs[i])
        return c[i] + (c[i + 1] - c[i]) * t
    }

    fun applyPreset(p: Preset) {
        val info = eqInfo
        for (b in info.freqs.indices) {
            levels[b] = (curveAt(p.curve, info.freqs[b]) * 100).toInt().coerceIn(info.min, info.max)
        }
        if (p.bass >= 0) { bass = p.bass; bassOn = p.bass > 0 }
        if (p.surround >= 0) { virt = p.surround; virtOn = p.surround > 0 }
        if (p.loud >= 0) { loud = p.loud; loudOn = p.loud > 0 }
        preset = p.name
        save(); applyAll()
    }

    fun resetFlat() {
        for (b in levels.indices) levels[b] = 0
        preset = ""
        save(); applyAll()
    }

    private val chains = HashMap<Int, Chain>()

    class Chain(val session: Int) {
        private val eq = runCatching { Equalizer(1000, session) }.getOrNull()
        private val bb = runCatching { BassBoost(1000, session) }.getOrNull()
        private val vz = runCatching { Virtualizer(1000, session) }.getOrNull()
        private val ld = runCatching { LoudnessEnhancer(session) }.getOrNull()

        fun apply() {
            runCatching {
                eq?.let { e ->
                    for (b in 0 until e.numberOfBands) {
                        val v = levels.getOrElse(b) { 0 }.coerceIn(eqInfo.min, eqInfo.max)
                        e.setBandLevel(b.toShort(), v.toShort())
                    }
                    e.enabled = eqOn
                }
            }
            runCatching {
                bb?.let { if (it.strengthSupported) it.setStrength(bass.toShort()); it.enabled = bassOn && !hifi }
            }
            runCatching {
                vz?.let { if (it.strengthSupported) it.setStrength(virt.toShort()); it.enabled = virtOn && !hifi }
            }
            runCatching { ld?.let { it.setTargetGain(loud); it.enabled = loudOn && !hifi } }
        }

        fun release() {
            eq?.release(); bb?.release(); vz?.release(); ld?.release()
        }
    }

    fun init(c: Context) {
        if (sp != null) return
        val p = c.getSharedPreferences("fx", Context.MODE_PRIVATE)
        sp = p
        eqOn = p.getBoolean("eqOn", true)
        hifi = p.getBoolean("hifi", false)
        preset = p.getString("preset", "").orEmpty()
        bassOn = p.getBoolean("bassOn", false); bass = p.getInt("bass", 500)
        virtOn = p.getBoolean("virtOn", false); virt = p.getInt("virt", 500)
        loudOn = p.getBoolean("loudOn", false); loud = p.getInt("loud", 0)
        levels.clear()
        val saved = p.getString("lv", "").orEmpty().split(",").mapNotNull { it.toIntOrNull() }
        levels.addAll(saved)
        while (levels.size < eqInfo.freqs.size) levels.add(0)
    }

    fun save() {
        sp?.edit()
            ?.putBoolean("eqOn", eqOn)?.putBoolean("hifi", hifi)?.putString("preset", preset)
            ?.putBoolean("bassOn", bassOn)?.putInt("bass", bass)
            ?.putBoolean("virtOn", virtOn)?.putInt("virt", virt)
            ?.putBoolean("loudOn", loudOn)?.putInt("loud", loud)
            ?.putString("lv", levels.joinToString(","))
            ?.apply()
    }

    fun applyAll() = chains.values.forEach { it.apply() }

    fun attach(session: Int) {
        if (chains.containsKey(session)) return
        chains[session] = Chain(session).also { it.apply() }
    }

    fun release(session: Int) { chains.remove(session)?.release() }

    fun releaseAll() { chains.values.forEach { it.release() }; chains.clear() }
}
