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

/** Holds all effect settings and the effect chains attached to audio sessions. */
object Fx {
    class EqInfo(val freqs: List<Int>, val min: Int, val max: Int)

    private var sp: SharedPreferences? = null
    var eqOn by mutableStateOf(true)
    var bassOn by mutableStateOf(false)
    var bass by mutableIntStateOf(500)
    var virtOn by mutableStateOf(false)
    var virt by mutableIntStateOf(500)
    var loudOn by mutableStateOf(false)
    var loud by mutableIntStateOf(0) // millibel
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
                bb?.let { if (it.strengthSupported) it.setStrength(bass.toShort()); it.enabled = bassOn }
            }
            runCatching {
                vz?.let { if (it.strengthSupported) it.setStrength(virt.toShort()); it.enabled = virtOn }
            }
            runCatching { ld?.let { it.setTargetGain(loud); it.enabled = loudOn } }
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
            ?.putBoolean("eqOn", eqOn)?.putBoolean("bassOn", bassOn)?.putInt("bass", bass)
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
