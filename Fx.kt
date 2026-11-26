package com.example.hififx

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.EnvironmentalReverb
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import androidx.compose.runtime.getValue
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.ln

/** Holds all effect settings, presets and the effect chains attached to audio sessions. */
object Fx {
    class EqInfo(val freqs: List<Int>, val min: Int, val max: Int)
    class Profile(val name: String, val cat: String, val desc: String, val curve: List<Float>, val bass: Float, val clar: Float, val wide: Float, val pre: Float)
    class Preset(val name: String, val cat: String, val curve: List<Float>, val bass: Int, val surround: Int, val loud: Int)

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
    var clar by mutableFloatStateOf(0f)    // clarity: high-shelf offset in dB
    var pre by mutableFloatStateOf(0f)     // preamp offset in dB
    var theme by mutableIntStateOf(0)      // 0 system, 1 dark, 2 light
    // Pro engine (Android DynamicsProcessing): parametric EQ, 31-band EQ, limiter and boost
    var proOn by mutableStateOf(false)
    var proOk by mutableStateOf(false)     // true once the phone accepted the Pro engine
    var limOn by mutableStateOf(true)
    var limCeil by mutableFloatStateOf(-1f)
    var boost by mutableFloatStateOf(0f)
    var headroom by mutableStateOf(false)   // auto headroom
    var balance by mutableFloatStateOf(0f)  // -100 left .. 100 right
    var mbcOn by mutableStateOf(false)      // multiband compression
    var mbcBands by mutableIntStateOf(3)
    var mbcChar by mutableIntStateOf(0)     // 0 transparent, 1 glue, 2 punch
    var mbcAmt by mutableFloatStateOf(40f)
    var limMode by mutableIntStateOf(0)     // 0 transparent, 1 safe, 2 loud
    var rvOn by mutableStateOf(false)       // reverb (experimental)
    var rvTime by mutableFloatStateOf(40f)
    var rvMix by mutableFloatStateOf(30f)
    data class PF(val type: Int, val freq: Float, val gain: Float, val q: Float, val on: Boolean) // type 0 peak, 1 low shelf, 2 high shelf
    val pf = mutableStateListOf<PF>()
    val g31 = mutableStateListOf<Float>()
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
Audiophile Reference|Hi-Fi|1,0.5,0,0,0,0,1,1,2,3
Sub-Bass Extreme|Tone|7,6,4,1,0,0,0,1,2,2|800
Vocal and Acoustic|Music|-2,-1,0,1,4,4,3,2,1,0
Rock and Metal|Music|5,4,2,-1,-2,0,2,4,5,5
EDM and Club|Music|6,5,2,0,-2,1,2,4,5,6
Classical Hall|Music|1,1,0,0,0,0,-1,-2,-1,-2
Night Loudness|Use|5,4,1,0,-1,0,0,2,4,2
"""
    private val FQ10 = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    val PRESETS: List<Preset> = RAW.trim().lines().map { line ->
        val p = line.split("|")
        fun opt(i: Int) = p.getOrNull(i)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: -1
        Preset(p[0], p[1], p[2].split(",").map { it.toFloat() }, opt(3), opt(4), opt(5))
    }

    private fun curveAt(c: List<Float>, hz: Int): Float {
        val xs = FQ10.map { ln(it.toFloat()) }
        val x = ln(hz.toFloat())
        if (x <= xs.first()) return c.first()
        if (x >= xs.last()) return c.last()
        val i = xs.indexOfLast { it <= x }
        val t = (x - xs[i]) / (xs[i + 1] - xs[i])
        return c[i] + (c[i + 1] - c[i]) * t
    }

    // Approximate tuning styles named after products and brands. Not measured data and not the real engines.
    // Format: name|category|description|10 EQ values (dB)|sub-bass dB|clarity dB|3D stage %|preamp dB
    private val PRAW = """
Dolby Atmos|Root/Magisk|3D Spatial Surround + Dialogue|3,2,1,0,1,2,2.5,3,3.5,3|2|2.5|65|-1.5
ViPER4Android FX|Root/Magisk|Dynamic Bass + Clarity + Tube|5,4,2,0,-1,1,2,3,4,4.5|4.5|3|50|-2.5
JamesDSP|Root/Magisk|Arbitrary Response + Crossfeed|2,1.5,0.5,0,0,0.5,1.5,2,2.5,3|2|2|40|-1
Ainur Sauron|Root/Magisk|Pure Bit-Exact Audio Modification|1,0.5,0,0,0,0,0.5,1,1.5,1.5|1|1|15|0
Ainur Narsil|Root/Magisk|Warm Analog & Deep Stage Mod|3,2.5,1.5,0.5,0,-0.5,0.5,1.5,2,2.5|2.5|1.5|35|-1
NLSound|Root/Magisk|Qualcomm Aqstic DAC Unlock|2,1,0,0,0.5,1,1.5,2,3,3.5|1.5|2.5|30|-1
Cyanogen DSP+|Root/Magisk|Classic Dynamic Range & Bass|4,3,1.5,0,0,0.5,1.5,2.5,3,3|3|2|35|-1.5
ICEsound Pure|Root/Magisk|ICEpower Clarity & Bass Enhancer|3.5,2.5,1,0,0.5,1,2,3,3.5,4|3|3|45|-2
XtremeMusic Pro|Root/Magisk|All-in-One Loudness & Surround|5,4,2,1,0,1,2.5,3.5,4.5,5|4|3.5|60|-2.5
ARISE Sound System|Root/Magisk|Deuteronomy Audiophile Suite|3,2,1,0.5,0.5,1,2,2.5,3.5,4|2.5|2.5|45|-1.5
Dolby Digital+|Spatial|7.1 Cinema Dynamic Surround|3.5,2.5,1,0,1,1.5,2,2.5,3,2.5|2.5|2|55|-1.5
DTS:X Ultra|Spatial|Wide Headphone Stage + Punch|4,3,1.5,0,-0.5,1,2,3,4,4.5|3.5|2.5|75|-2
DTS-HD Master|Spatial|Lossless Studio Master Dynamics|2,1.5,0.5,0,0,0.5,1.5,2,2.5,3|1.5|2|45|-1
Dirac HD Sound|Spatial|Impulse & Phase Correction|1.5,1,0.5,0,0,0.5,1,1.5,2,2|1|2|25|-0.5
Dirac Live|Spatial|Room & Headphone Curve Calibration|2,1,0,0,0.5,0.5,1,1.5,2,2.5|1.5|2|30|-1
SRS WOW HD|Spatial|TruBass + FOCUS 3D Stereo|4.5,3.5,1.5,0,0.5,1.5,2.5,3,3.5,3.5|4|2.5|70|-2
THX Spatial Audio|Spatial|360° Positional Cinema Field|3.5,2.5,1,0,0.5,1,2,3,3.5,4|3|2.5|80|-2
Sony 360 Reality|Spatial|Spherical Object-Based Soundstage|2.5,1.5,0.5,0,0.5,1,2,3,3.5,4|2|3|85|-1.5
Nahimic 3D|Spatial|Virtual Surround + Voice Stabilizer|3,2,1,0,1.5,2,2.5,3,3,3|2.5|2.5|65|-1.5
Creative Super X-Fi|Spatial|Holographic Headphone Audio|3,2,1,0,0.5,1,2,2.5,3,3.5|2.5|2|80|-1.5
Audyssey MultEQ|Spatial|Dynamic Reference Acoustic EQ|2.5,1.5,0.5,0,0,0.5,1.5,2,2.5,2.5|2|1.5|35|-1
IMAX Enhanced|Spatial|Deep Sub-Bass & Theater Scale|5,4,2,0.5,0,0.5,1.5,2.5,3,3.5|4.5|2|60|-2
Auro-3D|Spatial|Natural Height Channel Reflection|2,1.5,0.5,0,0.5,1,1.5,2.5,3,3.5|1.5|2|70|-1
Sony DSEE HX|Hi-Fi/DAC|High-Frequency Bitrate Upscaler|1,0.5,0,0,0.5,1,2,3.5,5,6|1|4.5|30|-1.5
Sony S-Master HX|Hi-Fi/DAC|Walkman Digital Amp Purity|2,1.5,0.5,0,0,0.5,1.5,2.5,3,3.5|1.5|2.5|25|-1
Sennheiser HD|Hi-Fi/DAC|Balanced Reference & Airy Mids|1.5,1,0.5,0,0.5,1,1.5,2,2.5,3|1|2|30|-0.5
Bose Active EQ|Hi-Fi/DAC|Full-Volume Warmth at Low Level|4.5,3.5,2,0.5,0,0.5,1.5,2.5,3,2.5|3.5|1.5|40|-1.5
JBL Signature|Hi-Fi/DAC|Punchy Club Bass & Crisp Highs|5,4,2,0,-1,0.5,2,3,4,4|4|2.5|40|-2
Harman Kardon|Hi-Fi/DAC|Harman Target Curve Balance|3.5,2.5,1,0,0,0.5,1.5,2.5,3,2.5|2.5|2|35|-1.5
Bang & Olufsen|Hi-Fi/DAC|Beosonic Warmth & Sparkle|2.5,2,1,0.5,0.5,1,2,3,3.5,4|2|3|45|-1.5
Bowers & Wilkins|Hi-Fi/DAC|True Sound Diamond Tweeter|2,1.5,0.5,0,0,0.5,1.5,2.5,3.5,4|1.5|3|35|-1
KEF Uni-Q|Hi-Fi/DAC|Coherent Point-Source Imaging|1.5,1,0.5,0,0,0.5,1,1.5,2,2.5|1|2|40|-0.5
Focal Clear|Hi-Fi/DAC|Dynamic Open-Back Punch & Detail|2,1.5,0.5,0,0.5,1,2,2.5,3,3.5|1.5|2.5|45|-1
AKG Reference|Hi-Fi/DAC|Austrian Studio Flat & Open Air|1,0.5,0,0,0.5,1,1.5,2,2.5,3|0.5|2|40|-0.5
Audio-Technica|Hi-Fi/DAC|M-Series Crisp Articulation|2,1.5,0.5,0,0,0.5,1.5,2.5,3.5,4|1.5|3|25|-1
Beyerdynamic|Hi-Fi/DAC|Tesla Driver Detail & Treble|2,1,0,0,0,0.5,1.5,3,4.5,5|1.5|3.5|35|-1.5
Shure Sound|Hi-Fi/DAC|Vocal Presence & Stage Isolation|1.5,1,0.5,1,2,2.5,2,1.5,2,2|1|2|20|-0.5
Klipsch Horn|Hi-Fi/DAC|Live Concert Horn Efficiency|3.5,2.5,1,0,0.5,1.5,2.5,3.5,4,4|3|3|45|-1.5
Marshall Amp|Hi-Fi/DAC|Crunchy Guitar Mids & Warmth|3.5,3,2,1,1.5,2,2.5,3,2.5,2|3|1.5|30|-1.5
Beats Audio|Hi-Fi/DAC|Heavy Hip-Hop Sub-Bass & Snap|6.5,5,3,0.5,-1,0,1.5,3,4,4|5.5|2|35|-2.5
Skullcandy Crusher|Hi-Fi/DAC|Sensory Haptic Sub-Woofer Boost|8,6.5,3.5,0,-1.5,0,1.5,2.5,3.5,3.5|7|2|40|-3
FiiO DAC|Hi-Fi/DAC|Dual ESS Sabre Neutral Precision|1.5,1,0.5,0,0,0.5,1,1.5,2,2.5|1|2|25|-0.5
iFi XBass+|Hi-Fi/DAC|Analog Sub-Bass + XSpace Stage|4.5,3.5,1.5,0,0,0.5,1,1.5,2,2.5|4|1.5|50|-1.5
Chord FPGA|Hi-Fi/DAC|WTA Tap-Length Transient Speed|1,0.5,0,0,0,0.5,1,2,2.5,3|1|2.5|35|-0.5
McIntosh Tube|Hi-Fi/DAC|Rich Vacuum Tube Harmonic Glow|3,2.5,1.5,0.5,0.5,0.5,1,1.5,2,1.5|2.5|1|35|-1
Devialet SAM|Hi-Fi/DAC|Speaker Active Matching Sub-Punch|5,3.5,1.5,0,0,0.5,1.5,2.5,3,3.5|4.5|2|40|-2
Sonos Trueplay|Hi-Fi/DAC|Balanced Multi-Room Acoustic Tune|2.5,2,1,0,0.5,0.5,1.5,2,2.5,2.5|2|1.5|35|-1
Waves MaxxAudio|Studio|MaxxBass + MaxxTreble + Stereo|4,3,1.5,0,0.5,1,2,3,3.5,4|3.5|3|55|-2
iZotope Ozone|Studio|Mastering Exciter & Imager|2,1.5,0.5,0,0,0.5,1.5,2.5,3.5,4|1.5|3|50|-1
FabFilter Pro-Q|Studio|Linear-Phase Surgical Precision|1,0.5,0,0,0,0.5,1,1.5,2,2.5|1|2|20|-0.5
UAD Neve 1073|Studio|Classic Console Transformer Warmth|3,2.5,1.5,0.5,0.5,1,1.5,2,2.5,2|2.5|1.5|25|-1
SSL 4000G|Studio|Punchy Mix-Bus Glue & Presence|2.5,2,1,0,0.5,1,2,2.5,3,3|2|2.5|35|-1
Genelec GLM|Studio|Nearfield Studio Monitor Flatness|1,0.5,0,0,0,0,0.5,1,1.5,1.5|0.5|1.5|20|0
Yamaha NS-10M|Studio|Midrange Vocal Mix Check Curve|-2,-1.5,-0.5,1,2.5,3,2,1,0,-1|-1|1.5|15|0
KRK Rokit|Studio|Hip-Hop Producer Low-End Punch|4.5,3.5,2,0,-0.5,0.5,1.5,2.5,3,3|4|2|30|-1.5
Neumann KH|Studio|Ultra-Neutral Reference Mastering|0.5,0.5,0,0,0,0,0.5,1,1.5,2|0.5|1.5|20|0
Abbey Road TG|Studio|Vintage EMI Console Air & Sheen|2,1.5,1,0.5,0.5,1,1.5,2.5,3.5,4|1.5|2.5|40|-1
Samsung UHQ|Mobile/Car|Ultra High Quality Bit Upscaler|2,1.5,0.5,0,0.5,1,1.5,2.5,3.5,4|1.5|3|35|-1
Apple Spatial|Mobile/Car|Dynamic Head-Tracked AirPods Tune|2.5,1.5,0.5,0,0.5,1,2,2.5,3,3.5|2|2.5|70|-1.5
LG Quad DAC|Mobile/Car|32-bit Hi-Fi Sabre Digital Filter|1.5,1,0.5,0,0,0.5,1,1.5,2.5,3|1|2.5|30|-0.5
Meridian Horizon|Mobile/Car|British Luxury Acoustic Upmix|2.5,2,1,0.5,0.5,1,1.5,2.5,3,3.5|2|2.5|55|-1
HTC BoomSound|Mobile/Car|Dual-Amp Loudness & Bass Boost|4.5,3.5,2,0.5,0.5,1,2,3,3.5,3.5|4|2.5|45|-2
ASUS SonicMaster|Mobile/Car|ICEpower Wide Range Audio|3,2.5,1,0,0.5,1,2,2.5,3,3.5|2.5|2|40|-1.5
Xiaomi Mi Sound|Mobile/Car|Dirac HD Mi Earphone Optimizer|3,2,1,0,0.5,1,1.5,2.5,3,3|2.5|2|35|-1
OnePlus Audio|Mobile/Car|OReality Dynamic Bass & Clarity|3.5,2.5,1,0,0,0.5,1.5,2.5,3.5,3.5|3|2.5|45|-1.5
Huawei Histen|Mobile/Car|3D Surreal Headphone Algorithm|3,2,1,0,0.5,1,2,2.5,3,3.5|2.5|2.5|65|-1.5
Burmester 3D|Mobile/Car|Mercedes-Benz High-End Surround|3.5,2.5,1.5,0.5,0.5,1,2,3,3.5,4|3|3|70|-1.5
64 Audio Fourté|IEM/Planar|1DD+3BA tia • Apex Core • Copper Patina|3,2,0.5,-0.5,0,1,2,3,4,4.5|2.5|3|65|-1.5
64 Audio Volür|IEM/Planar|10-Driver Hybrid (2DD+7BA+1 tia)|4.5,3.5,1.5,0,0.5,1,2,2.5,3.5,4|4|2.5|55|-2
64 Audio Trió|IEM/Planar|2BA+1DD tia • Apex Pressure Relief|3.5,2.5,1,0,0,1,1.5,2.5,3.5,4|3|2.5|50|-1.5
64 Audio Solo|IEM/Planar|14.2mm Planar • Helmholtz + Apex Core|2.5,2,0.5,0,0.5,1,1.5,2.5,3.5,4|2|3|55|-1
FiiO FA19|IEM/Planar|10 Knowles BA • Monitor/Hi-Fi Switch|2,1.5,0.5,0,0.5,1,1.5,2.5,3,3.5|1.5|2.5|40|-1
FiiO FP3|IEM/Planar|14.5mm Planar • 14 N52 Magnets • Rosewood|3,2,1,0,0.5,1,1.5,2.5,3,3.5|2.5|2.5|45|-1
FiiO FH3|IEM/Planar|1 Beryllium DD + 2BA Hybrid|3.5,2.5,1,0,0.5,1,2,2.5,3,3|3|2|35|-1.5
Sennheiser IE 600|IEM/Planar|TrueResponse • Amorphous Zirconium|3,2,0.5,0,0,1,2,3,4,4|2.5|3|40|-1.5
Sennheiser IE 900|IEM/Planar|7mm TrueResponse • X3R Triple Chamber|3.5,2.5,0.5,-0.5,0,1,2,3.5,4.5,5|3|3.5|55|-1.5
Kiwi Ears Aether|IEM/Planar|15.3mm Planar • Wide Open-Back Stage|2.5,1.5,0.5,0,0.5,1,1.5,2.5,3,3.5|2|2.5|65|-1
Kiwi Ears Cadenza|IEM/Planar|10mm Beryllium Diaphragm DD|3,2,1,0,0.5,1,1.5,2,2.5,2.5|2.5|2|30|-1
Kefine Arnar|IEM/Planar|14.5mm Planar + Knowles BA Hybrid|2.5,2,1,0,0.5,1,2,2.5,3,3.5|2|2.5|45|-1
7Hz x Crinacle Divine|IEM/Planar|14.5mm Planar • +10dB Sub-Bass Tune|5.5,4,1.5,0,0,0.5,1.5,2.5,3,3.5|5|2.5|45|-2
7Hz x Crinacle Diablo|IEM/Planar|14.5mm Planar • +12dB Slam Bass Tune|6.5,5,2,0,-0.5,0.5,1.5,2.5,3.5,3.5|6|2.5|50|-2.5
Campfire Chimera|IEM/Planar|Bone Conduction + Glass DD + BA + EST|4,3,1.5,0.5,0.5,1,2,3,4,5|3.5|3.5|70|-2
UM x MusicTeck Pentara|IEM/Planar|13-Driver Summit-Fi Hybrid|3,2,1,0.5,0.5,1,2,3,4,4.5|2.5|3.5|65|-1.5
Xenns Mangird Top Pro|IEM/Planar|10-Driver Hybrid (2DD + 8BA)|4,3,1,0,0.5,1,2,2.5,3.5,4|3.5|3|50|-1.5
I/O Audio SOGNO|IEM/Planar|2DD + 6BA Hybrid • 3D Resin|3.5,2.5,1,0,0.5,1.5,2,2.5,3,3.5|3|2.5|45|-1.5
SIMGOT SuperMix 4|IEM/Planar|Quad-Brid (1DD + 1BA + Planar + PZT)|3.5,2.5,1,0,0.5,1,2,3,3.5,4|3|3|50|-1.5
Truthear Hexa|IEM/Planar|1DD + 3BA • Neutral Reference|1.5,1,0,0,0.5,1,1.5,2,2.5,2.5|1|2|30|-0.5
Snowsky FH3 LUMI|IEM/Planar|Cashmere-Wood DD + 2BA Warm Vocal|2.5,2,1,1,2,2.5,2,1.5,2,2|2|2|35|-1
ZiiGaat x Vivir RUMBA|IEM/Planar|10mm Graphene Diaphragm DD|3.5,2.5,1,0,0.5,1,1.5,2.5,3,3|3|2|40|-1.5
KZ ZSN Pro|IEM/Planar|1BA + 1DD V-Shape Energetic Tune|4.5,3.5,1.5,-0.5,-1,0.5,2,3,4,4|4|3|35|-2
KZ Sonata|IEM/Planar|14 Balanced Armature • Tuning Switches|3,2,1,0,0.5,1,2,3,3.5,4|2.5|3|45|-1.5
Noble Audio Kronos|IEM/Planar|9-Driver Quadbrid • Titanium + Damascus|3.5,2.5,1,0.5,0.5,1.5,2,3,4,4.5|3|3.5|65|-1.5
Noble Audio Sultan|IEM/Planar|Tribrid (1DD + 4BA + 2EST)|3.5,2.5,1,0,0.5,1,2,3,4,4.5|3|3.5|60|-1.5
THIEAUDIO Valhalla|IEM/Planar|19 Drivers/Side + PBS Summit-Fi|3,2,1,0.5,0.5,1,2,3,4,5|2.5|4|70|-1.5
ThieAudio Monarch MKIV|IEM/Planar|10-Driver Tribrid (2DD+6BA+2EST)|4,3,1,0,0.5,1,2,3,4,4.5|3.5|3.5|60|-1.5
THIEAUDIO Hype 4 MKII|IEM/Planar|Dual 8mm Isobaric Sub + Knowles BA|4.5,3.5,1.5,0,0.5,1,2,2.5,3.5,4|4|3|50|-2
ThieAudio V16 Divinity|IEM/Planar|16 Balanced Armatures/Side • 18Ω|2.5,2,1,0.5,0.5,1,2,3,3.5,4|2|3|55|-1
THIEAUDIO Prestige LTD|IEM/Planar|9-Driver Tribrid (1DD + 4BA + 4EST)|3,2,0.5,0,0.5,1,2,3.5,4.5,5|2.5|4|65|-1.5
Final A10000 Collector|IEM/Planar|Pure Diamond Driver • Shibo Finish|2,1.5,0.5,0,0.5,1,2,3,4,5|1.5|4|65|-1
Sony IER-M500|IEM/Planar|5mm Stage Monitoring Dynamic Driver|1.5,1,0.5,0.5,1,1.5,2,2.5,2.5,3|1|2|35|-0.5
Moondrop CHU II|IEM/Planar|Alloy Composite DD • Brass CNC Nozzle|2.5,2,0.5,0,0.5,1,2,2.5,3,3|2|2.5|35|-1
Moondrop Blessing 3|IEM/Planar|HODDDUS Dual DD + 4BA Hybrid|2.5,1.5,0.5,0,0.5,1.5,2,2.5,3.5,4|2|3|50|-1
Shure SE846 Gen 2|IEM/Planar|Quad-Driver True Sub-Woofer • 37dB Iso|4.5,3.5,1.5,0.5,1,1.5,1.5,2,2.5,2.5|4|2|30|-1.5
"""

    val PROFILES: List<Profile> = PRAW.trim().lines().map { line ->
        val p = line.split("|")
        Profile(p[0], p[1], p[2], p[3].split(",").map { it.toFloat() }, p[4].toFloat(), p[5].toFloat(), p[6].toFloat(), p[7].toFloat())
    }

    /** Level actually sent to the equalizer: band level + preamp + clarity (a high shelf above 2 kHz). */
    fun eff(b: Int): Int {
        val hz = eqInfo.freqs.getOrElse(b) { 1000 }
        val shape = (ln(hz / 2000f) / ln(2f) / 3f).coerceIn(0f, 1f)
        val v = levels.getOrElse(b) { 0 } + (pre * 100).toInt() + (clar * 100 * shape).toInt()
        return v.coerceIn(eqInfo.min, eqInfo.max)
    }

    // 31 band centres (1/3 octave) and band upper edges used by the Pro engine
    val PRO_C = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0,
        800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0
    )
    val PRO_EDGE = FloatArray(PRO_C.size) { i ->
        if (i < PRO_C.size - 1) Math.sqrt(PRO_C[i] * PRO_C[i + 1]).toFloat() else 22000f
    }

    fun defaultPf() = listOf(
        PF(1, 80f, 0f, 0.7f, false), PF(0, 200f, 0f, 1f, false), PF(0, 800f, 0f, 1f, false),
        PF(0, 2500f, 0f, 1f, false), PF(0, 6000f, 0f, 1f, false), PF(2, 12000f, 0f, 0.7f, false)
    )

    /** Response in dB of one RBJ biquad filter at frequency f (assumes 48 kHz). */
    fun filterDb(f: Double, p: PF): Double {
        val fs = 48000.0
        val a = Math.pow(10.0, p.gain / 40.0)
        val w0 = 2 * Math.PI * p.freq / fs
        val cw0 = Math.cos(w0)
        val alpha = Math.sin(w0) / (2 * p.q)
        val sa = 2 * Math.sqrt(a) * alpha
        val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
        when (p.type) {
            1 -> {
                b0 = a * ((a + 1) - (a - 1) * cw0 + sa); b1 = 2 * a * ((a - 1) - (a + 1) * cw0); b2 = a * ((a + 1) - (a - 1) * cw0 - sa)
                a0 = (a + 1) + (a - 1) * cw0 + sa; a1 = -2 * ((a - 1) + (a + 1) * cw0); a2 = (a + 1) + (a - 1) * cw0 - sa
            }
            2 -> {
                b0 = a * ((a + 1) + (a - 1) * cw0 + sa); b1 = -2 * a * ((a - 1) + (a + 1) * cw0); b2 = a * ((a + 1) + (a - 1) * cw0 - sa)
                a0 = (a + 1) - (a - 1) * cw0 + sa; a1 = 2 * ((a - 1) - (a + 1) * cw0); a2 = (a + 1) - (a - 1) * cw0 - sa
            }
            else -> {
                b0 = 1 + alpha * a; b1 = -2 * cw0; b2 = 1 - alpha * a
                a0 = 1 + alpha / a; a1 = -2 * cw0; a2 = 1 - alpha / a
            }
        }
        val w = 2 * Math.PI * f / fs
        val cw = Math.cos(w); val c2 = Math.cos(2 * w); val sw = Math.sin(w); val s2 = Math.sin(2 * w)
        val nr = b0 + b1 * cw + b2 * c2; val ni = b1 * sw + b2 * s2
        val dr = a0 + a1 * cw + a2 * c2; val di = a1 * sw + a2 * s2
        return 10 * Math.log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    /** Combined response of all enabled parametric filters at frequency f. */
    fun proDb(f: Double): Double = pf.filter { it.on && it.gain != 0f }.sumOf { filterDb(f, it) }

    // Headphone and earphone correction curves (EQ gain in dB at 16 frequencies)
    class HpModel(val name: String, val v: FloatArray)
    private val HP_F = doubleArrayOf(36.0, 50.0, 75.0, 100.0, 150.0, 250.0, 350.0, 500.0, 750.0, 1100.0, 1600.0, 2500.0, 3600.0, 5000.0, 8000.0, 12000.0)
    private val HP_RAW = listOf(
"""64 Audio A12t|-0.7,-0.5,-0.5,-1.1,-1.8,-1.2,-0.7,-0.8,0.2,0.9,0,1.5,5.1,1.2,5.2,1.9
64 Audio A12t (CIEM)|-2.6,-2.3,-1.9,-2.2,-2.7,-1.9,-1.4,-1.2,0.1,1.1,0.3,2.8,6.9,4.1,7.3,2.8
64 Audio A12t (m15 Apex module)|-0.3,-0.5,-0.7,-1.3,-1.9,-0.8,-0.1,-0.1,0,0,-0.1,2.9,5.3,0,3.5,-0.3
64 Audio A12t (m20 Apex module)|-0.9,-0.8,-0.9,-1.4,-1.8,-0.7,0.1,0.1,0.2,0.2,0,3.1,5.5,0,3.6,-0.2
64 Audio A12t (mX Apex module)|4.3,2.8,1,-0.6,-2.5,-2.5,-2,-2,-1.2,-0.5,-0.5,2.3,3.1,0,2.2,-2.8
64 Audio A18s (CIEM)|-0.9,-1.8,-2.7,-4.1,-5.3,-4.1,-2.5,-0.8,0.6,0.6,-0.2,2,4.3,3.8,6.5,6.5
64 Audio A2e|1.7,0.7,-0.8,-2.5,-4.8,-5.3,-4.5,-3.1,-1.4,-0.8,0,1.5,1.4,4.5,3.9,4.1
64 Audio A4t|-0.6,-0.8,-1.1,-1.7,-2.3,-1,0.3,1,0.9,-0.4,-2.6,-0.1,3.9,1.4,5.5,3.6
64 Audio A6|-1.8,-1.5,-1.1,-1.6,-2.3,-1.9,-1.6,-1.4,-0.1,1.2,1.3,2.8,3.8,2.2,4.9,2.3
64 Audio A6t|-2.1,-1.9,-1.8,-2.4,-3,-2.4,-1.8,-1.1,0.6,1.8,1.9,2.7,5,3.4,5.6,2.9
64 Audio A6t (m20 Apex module)|-1.5,-1.7,-1.9,-2.6,-3.3,-2.7,-2.1,-1.4,0.2,1.6,1.7,2.5,5.2,3.9,5,2.1
64 Audio Duo|-0.3,-1,-2,-3.3,-4.6,-3.5,-1.9,0.1,2.1,2.4,1.3,2.7,4.9,3.7,4.6,0.7
64 Audio Fourte Blanc|-1.2,-0.4,-0.1,-0.8,-2.3,-2.8,-2.9,-3.3,-2.4,4.1,4.5,5.5,7,3.4,0.1,-3.4
64 Audio N8  (CIEM)|2.7,1.8,0.4,-1.1,-3.3,-4,-3.5,-2.5,-0.9,-0.7,-2.3,0.8,3.3,2.1,5.6,1.6
64 Audio N8  (m15 Apex module)|-1.6,-1.3,-1.2,-1.8,-2.5,-1.8,-0.8,0.2,1.1,1.1,0.4,2.9,3.6,0.5,2.7,2.7
64 Audio N8 (CIEM, m15 Apex module)|-1.5,-1.5,-1.8,-2.7,-3.8,-3.4,-2.3,-1,0.7,1.1,-0.6,2.1,4.9,3.6,6.6,2.8
64 Audio N8 (CIEM, m20 Apex module)|-1,-0.5,-0.2,-0.9,-2.4,-2.9,-2.3,-1.4,0.2,0.4,-1.2,2,3.7,2.8,6.5,2.8
64 Audio N8 (dd disabled)|5,5,5,4.8,0.1,-3.2,-3.7,-3.3,-2.1,-2.3,-4.2,-1.6,1.3,-0.5,4.4,0.1
64 Audio N8 (m20 Apex module, modded)|0.3,1.3,1.8,1,-0.8,-2.1,-2.2,-1.7,-0.4,-0.4,-2.4,0.6,3.5,2.3,5.5,0.7
64 Audio N8 (modded)|-0.1,1,1.5,0.7,-0.9,-2,-2,-1.4,-0.1,-0.1,-2,0.9,3.8,2.6,5.3,0.2
64 Audio N8 (vent blocked)|4.9,4.9,4.9,4.9,1,-2.6,-3.5,-3.4,-2.6,-2.8,-4.9,-1.9,1.1,-0.3,1.9,-1.6
64 Audio N8t (m15 Apex module)|-1.7,-1.7,-1.8,-2.4,-3,-2.1,-1,-0.1,0.9,1,0.4,2.5,3.5,0.7,2.3,2.5
64 Audio Nio (Fir Audio black module)|-0.7,-1,-1.5,-2.4,-3.2,-2.1,-1,0.2,1,0.7,0.8,3.6,4.3,-1.2,2.3,0.7
64 Audio Nio (Fir Audio red module)|0.2,-0.3,-1.2,-2.1,-3,-2.2,-1.1,0,0.8,0.4,0.6,3.2,3.9,-1.6,1.9,0.3
64 Audio Nio (m12 Apex module)|-0.2,-0.6,-1.1,-2.1,-3.1,-2.2,-1.1,-0.1,0.9,0.5,0.6,3.4,3.9,-1.5,2.2,0.5
64 Audio Nio (m15 Apex module)|-2.8,-2.2,-1.7,-2,-2.4,-1,0.2,1.3,2.4,1.7,1.5,3.4,4.6,0.5,2.3,0.4
64 Audio Nio (m20 Apex module)|-3.1,-2.4,-1.9,-2.2,-2.4,-1.1,0.1,1.3,2.4,1.9,1.7,3.7,4.7,0.5,2.5,0.8
64 Audio Nio (mX Apex module)|3.1,2.6,1.5,0.2,-1.4,-1.4,-0.8,0,0.5,-0.5,-0.9,1,2.1,-2,-0.2,-2
64 Audio Tia Fourte|0.3,0.9,1.1,0.4,-1,-1.5,-1.7,-1.8,-0.7,6.4,4.6,3.2,6.8,0.8,-2.7,-3.8
64 Audio Tia Fourte Noir|-0.4,0.1,0.4,-0.2,-1.1,-1.1,-1,-1.7,-0.6,6.8,4.4,3.1,5.5,1.5,-2.9,-3.4
64 Audio Tia Trio|0.1,0,-0.4,-1.3,-2.2,-1.3,-0.3,-0.1,0.2,3.8,1.4,0.6,4,4.1,2.9,-2.3
64 Audio U12t|-0.4,-0.5,-0.5,-1.1,-1.8,-1.2,-0.8,-0.8,0.1,0.5,-0.6,1.7,5,1.3,5.9,1.8
64 Audio U12t (Fir Audio red module)|2,0.9,-0.2,-1.2,-2.7,-2.2,-1.5,-1.4,-0.5,0,-0.2,2.1,4.1,1.3,3.1,-2
64 Audio U12t (m12 Apex module)|1.6,0.7,-0.2,-1.2,-2.5,-2,-1.3,-1.2,-0.5,0.1,0,2.7,4.4,0.7,2.8,-2.1
64 Audio U12t (m15 Apex module)|-1.7,-1.4,-1.1,-1.6,-1.9,-0.9,-0.2,-0.1,0.2,0.4,0.5,3.2,6.1,1.8,5,1
64 Audio U12t (m20 Apex module)|-2.2,-1.6,-1.2,-1.5,-1.9,-0.9,-0.2,-0.1,0.3,0.4,0.4,3.2,6.9,2,4.9,0.9
64 Audio U12t (mX Apex module)|2.8,1.8,0.5,-0.9,-2.4,-2.1,-1.6,-1.6,-1.1,-0.9,-0.7,2.4,4.3,0.2,3.8,-0.4
64 Audio U18S|-0.3,-0.4,-0.6,-1.3,-2.2,-1.7,-1.2,-0.9,-0.4,-1,-1.3,1.5,4.3,3.4,5.5,2.6
64 Audio U18s (m20 Apex module)|-1.6,-1.6,-2,-2.8,-3.4,-2.4,-1.2,-0.4,0.2,0,0.6,2.5,5.1,2.6,4.2,1.4
64 Audio U18t|1.3,0.7,-0.6,-2.1,-4,-4.4,-3.8,-3,-2.1,-2.8,-2.4,2.1,4.1,5.8,5.8,5.3
64 Audio U18t (2014)|1.7,1.2,0.3,-0.9,-2.6,-2.7,-2,-1.1,0.2,0.3,-0.2,2.7,2,1,3,1
64 Audio U18t (2017)|0.5,-0.1,-1,-2.1,-3.5,-3.1,-2,-0.6,1,1.1,0.7,3.2,2.5,3.4,3.6,1.4
64 Audio U18t (m15 Apex module)|-1.6,-1.9,-2.5,-3.4,-4.2,-3.3,-2,-0.2,1.6,1.9,2.6,5.1,5.4,2.2,1.4,-0.1
64 Audio U18t (m20 Apex module)|1.1,0,-1.1,-2.4,-3.9,-3.6,-2.5,-1,0.7,0.8,0.4,2.8,3.6,2.7,3.5,1.6
64 Audio U18t (mX Apex module)|3.6,1.8,-0.7,-2.6,-4.7,-4.6,-3.2,-1.8,0.2,0.6,1.4,4.2,2.4,1.6,0.2,-1.6
64 Audio U4S (Fir Audio black module)|-0.1,-0.4,-1,-2,-2.9,-1.9,-0.7,0.2,0.9,1,-0.1,4.1,5.9,3,1.4,-2
64 Audio U4S (Fir Audio red module)|0.7,0.2,-0.7,-1.8,-2.9,-2.1,-0.9,0,0.6,0.7,-0.3,3.8,5.6,2.7,1.1,-2.2
64 Audio U4S (m12 Apex module)|0.1,-0.2,-0.8,-1.8,-2.7,-1.8,-0.8,0.1,0.6,0.6,-0.5,3.7,5.7,3.7,1.2,-2.1
64 Audio U4S (m15 Apex module)|-1.4,-1.2,-1.5,-2.2,-2.9,-1.6,-0.4,0.4,1.1,1.1,-0.1,4.2,6.3,4.4,1.8,-1.7
64 Audio U4S (m20 Apex module)|-1.9,-1.7,-1.7,-2.4,-2.9,-1.5,-0.3,0.7,1.3,1.4,0.2,4.4,6.5,4.7,2,-1.5
64 Audio U4S (mX Apex module)|3.2,2.3,0.8,-0.7,-2.5,-2.4,-1.7,-1.1,-0.6,-0.5,-1.7,2.6,4.6,2.8,0.2,-3.3
64 Audio U6t (m15 Apex module)|-2.5,-2.2,-2.1,-2.6,-3.2,-2.6,-1.8,-1.1,0.5,1.7,2.2,3.4,4.6,1.8,4.5,0.9
64 Audio Volur|-1.6,-1.1,-0.9,-1.4,-2,-1.1,0,1.3,3,2.9,2.3,3.2,5.1,2,4.1,-1.4
64 Audio Volur (m12 Apex module)|0.9,0.7,0.1,-1,-2.5,-2.7,-2.1,-1,0.6,0.6,0.4,3.7,5.1,1.9,5.2,-1.1
64 Audio Volur (m15 Apex module)|-1.3,-1,-1.1,-1.8,-2.9,-2.5,-1.8,-0.3,1.4,1.5,1.3,4.6,6,2.7,6.1,0
64 Audio Volur (m20 Apex module)|-1.9,-1.4,-1.4,-2.1,-3.1,-2.5,-1.6,-0.1,1.7,1.8,1.5,4.9,6.2,2.8,6.4,0.2
64 Audio Volur (mX Apex module)|4.8,3.9,2.3,0.5,-1.9,-3,-2.8,-1.9,-0.8,-0.8,-1.2,2.1,3.5,0.3,3.6,-2.5
7Hz Legato|-4,-3.5,-3.3,-3.7,-3.9,-2.1,-0.1,2,3.6,3.2,2.3,3.9,5.5,7.5,1.9,0.1
7Hz Salnotes Dioko|1.4,2,2.2,1.3,-0.1,-0.5,-0.3,0.1,0.5,-0.5,-1.3,0.4,2.5,4.3,-0.9,-4.9
7Hz Salnotes Dioko Spring|0.7,2,2.1,1.2,-0.2,-0.2,0.1,0.5,0.5,-0.6,-0.8,2.1,0.4,2.4,-1.5,-4.7
7Hz Salnotes Zero|0.8,1.1,1,0.3,-1,-1.1,-0.7,-0.2,0.4,-0.3,-1.4,-0.6,-0.3,0.9,2.1,0.3
7Hz Sonus|-0.2,0.8,1,0.3,-0.4,-0.2,0.4,0.9,1.1,-0.8,-2.4,1.1,2.8,1.5,-0.2,-3.4
7Hz Timeless|-0.1,0,-0.2,-1,-1.7,-1,0.2,1.4,2,1,-0.5,-0.5,2.3,3.7,1,-3.3
7Hz Timeless AE|-1.1,-0.6,-0.2,-0.7,-1.1,0,1.2,2.2,2.7,1.6,-0.4,0.1,3.3,2.4,1.5,-2.8
7Hz x Crinacle Zero 2|-1.2,-1,-0.8,-1.5,-2.2,-1.7,-0.9,-0.2,0.8,0.2,-0.9,-0.1,1,3.3,3.2,2.5
7th Acoustics Proxima|4.3,4.3,3.7,1.8,-0.9,-2.1,-2.3,-1.8,-1.5,-2.3,-2.8,-1.5,-0.5,-1.4,-2.6,-1.7
7th Acoustics Supernova|0.2,0.6,0.6,-0.2,-1.4,-1.2,-0.5,0.3,1.1,0.6,0.4,2.8,3.8,2.3,0.7,-2.8
A-Audio Elite (ANC on)|-0.3,0,0,-0.6,-1.3,-1.5,-1,-1.6,-3,-5,-2.9,-1.1,-2.8,-2.7,-2,-1.5
A-Audio Elite (bass mode)|-2,-1.9,-2.1,-2.5,-3.2,-3.5,-2.9,-3.5,-3.8,-5,-2.2,0,-1.7,-1.2,0,0
A-Audio Elite (passive)|-3.4,-3.5,-3.6,-4,-5,-3,-2,-3.1,-3.3,-3.3,-1.7,0,-2.9,-2.6,-0.5,-0.1
AAW (Advanced AcousticWerkes) ASH|-2.2,-2.4,-2.8,-3.6,-4.5,-4.8,-4.8,-5,-4.4,-2.8,-3,-1.7,0,-0.5,-1.8,-4.1
AAW (Advanced AcousticWerkes) Canary|-2.9,-3.1,-3.6,-4.2,-5,-4.8,-4.4,-3.8,-3.4,-3.8,-4.2,-1.5,0,-0.7,-1,-2
AAW A2H V2|-4.2,-4.1,-4.2,-4.6,-5,-4.7,-4.1,-3.2,-2.1,-1.7,-2,-0.6,-2,-1,-0.4,0
AAW A3H|-2.1,-2.5,-3.1,-3.9,-4.8,-5,-4.6,-4,-3.2,-2.8,-3.2,-2.1,0,-2.1,-0.1,0
AAW A3H (2019)|0,-0.2,-1.5,-2.8,-4.4,-5,-4.6,-3.6,-2.3,-1.9,-2.5,-1.8,-2.5,-5,-4.6,-3.9
AAW A3H Plus|-1.2,-1.6,-2.5,-3.6,-5,-4.9,-3.9,-2.4,-1,-2.5,-3.6,-2.6,-0.7,0,-1.2,-1.6
AAW A3H V2|-2.2,-2.5,-3.1,-3.8,-4.8,-5,-4.6,-4.1,-3.4,-3.3,-4.2,-2.7,-1.4,-1.6,0,0
AAW ACH|0,-0.1,-1.4,-2.6,-4.1,-5,-5,-4.4,-2.9,-1.6,-1.2,-1.6,-1.9,-1.2,-2,-3.6
AAW AXH|-1.7,-2.1,-2.7,-3.5,-4.5,-4.8,-4.7,-4.4,-4.1,-4.4,-3.1,-2.3,0,-3.2,-4.7,-5
AAW AXH (2019)|-0.4,-0.7,-1.3,-2,-2.9,-3.2,-3.3,-3.3,-2.7,-2.4,-2.7,-2,0,-0.5,-3.1,-5
AAW Halcyon (bass filter)|-3.2,-3.2,-3.7,-4.3,-5,-4.7,-4.2,-3.6,-3.2,-3.1,-3.1,-0.4,0,-1.8,-1.7,-2.8
AAW Halcyon (norm filter)|-1.1,-1.4,-2.2,-3.3,-4.8,-5,-4.2,-3.1,-2.3,-2.4,-2.9,-0.2,0,-1.6,-1.5,-2.8
AAW Halcyon (Vokal filter)|-0.4,-0.8,-1.9,-3.1,-4.8,-5,-4.1,-3.1,-2.4,-2.7,-3,-0.6,0,-2.1,-2,-3
AAW Kingfisher|0,-0.9,-1.9,-3,-4.3,-5,-5,-4.4,-2.8,-1.6,-1.3,-1.2,-1.8,-0.3,-1.4,-2.9
AAW Mockingbird|-1.4,-1.6,-2.2,-3.1,-4.4,-5,-4.9,-4.6,-4.1,-3.6,-3.5,-1.7,-0.7,0,-1.3,-3.9
AAW Nightingale|-0.5,-0.8,-1.6,-2.5,-3.8,-4.6,-4.7,-4.9,-5,-4.8,-3.8,-0.1,0,0,-0.3,0
AAW W500|-2.1,-2.4,-3.1,-3.9,-5,-5,-4.6,-3.8,-3.1,-3.3,-4.7,-0.3,-1.7,0,-0.1,0
AAW W900|-1.5,-1.4,-1.6,-2.1,-2.8,-3.1,-3,-2.7,-2.2,-1.9,-0.9,-0.9,-0.2,0,-2.6,-5
AAW x Custom Art Project 4+2 (bass filter)|-3,-3.2,-3.7,-4.3,-4.9,-4.6,-3.9,-3,-2.3,-3.8,-5,-1.3,0,-3.4,-2,-0.6
AAW x Custom Art Project 4+2 (norm filter)|-1.9,-2.4,-3.2,-4,-5,-4.9,-4.2,-3.1,-2.1,-3.4,-4.9,-1.2,0,-3.4,-2.1,-0.6
AAW x Custom Art Project 4+2 (Vokal filter)|0,0,-0.7,-2,-3.9,-5,-4.4,-3.2,-1.7,-2.4,-4.6,-1.6,-0.8,-3.6,-2.3,-1
Abyss AB-1266|-1.2,-1.6,-1.3,-1.6,-2.1,-2.7,-2.9,-2.2,-3.3,-3.7,-2.1,-1.4,-1.7,0,-3.2,-5
Abyss AB-1266 Phi|-1.3,-1.8,-1.8,-2.1,-2.8,-3.4,-3.7,-2.6,-4.2,-5,-2,-2.3,-0.3,0,-2.3,-4.9
Abyss AB-1266 Phi CC|-1,-1.5,-1.8,-2.2,-3,-3.7,-4.2,-2.7,-4.5,-5,-2,-1.3,0,-3.9,-0.9,-4.2
Abyss AB-1266 Phi CC Lite|-1.8,-1.9,-2,-2.5,-3.2,-3.8,-4.5,-2.9,-4.8,-5,-2.4,-2.7,0,-3.6,-1.4,-4.5
Abyss AB1-266 Phi TC|-0.6,-0.6,-0.4,-0.8,-2,-3.1,-3.7,-2,-4.1,-5,-3,-2.4,0,-3.1,-1.1,-3.8
Abyss Diana MR|-0.3,-1,-1.8,-2.6,-3.4,-4.2,-4.4,-4.1,-3.9,-4.6,-3.3,-3,0,-1.2,-2.6,-5
Abyss Diana Phi|0,-0.6,-1.1,-1.7,-2.8,-3.6,-3.2,-3.1,-3.8,-5,-4.7,-1,-0.1,-1.3,-0.9,-1.9
Abyss Diana V2|-0.8,-1.1,-2.2,-2.6,-3.5,-4.4,-4.4,-4,-4.4,-5,-4.7,-2,0,-0.9,-1.2,-2.5
Accidentally Extraordinary 51st Studios|-1.5,-2.5,-3.4,-4,-5,-4.6,-2.8,-2.3,-2.7,-3.3,-2.9,-0.8,-1.3,-0.2,0,-0.3
Accidentally Extraordinary Bamboo|-0.2,-1,-2.2,-3.3,-4.6,-5,-4.3,-3.2,-1.5,-1.8,-3.1,-2.4,0,-1.1,-2.3,-2
ACEFAST Crystal T8|-4,-4.5,-4.9,-5,-4.7,-3.5,-2.7,-1.9,-1.3,-1.4,-0.8,-0.4,0,-2.9,-0.5,-0.2
Acoustic Research AR-H1|0,0,-1.1,-2.1,-3.5,-4.3,-4.1,-4.4,-4.2,-4.5,-3.7,-2.3,-0.3,-3.2,-4.5,-5
Acoustune HS1001|-4.3,-4.3,-4.4,-4.7,-5,-4.2,-3.1,-1.8,-0.4,0,-0.5,-0.5,-0.9,-2.2,-2.3,-1.4
Acoustune HS1003|-4,-3.9,-4,-4.5,-5,-4,-2.9,-1.5,-0.1,0,-0.5,-1.2,-2.4,-4.4,-3.4,-1.7
Acoustune HS1004|-2.6,-3,-3.6,-4.3,-5,-4.6,-3.9,-3.1,-2.3,-2.6,-3.6,-1.4,0,-2.5,-3.5,-3
Acoustune HS1300|-2.4,-2.5,-3,-3.6,-4.4,-3.9,-3,-2.1,-1.3,-1.7,-3,-3.4,-0.7,-5,0,-0.9
Acoustune HS1501|-3.1,-3.3,-3.7,-4.3,-5,-4.6,-3.8,-3,-2.3,-2.8,-4,-2.7,0,-2.8,-1.9,-1.1
Acoustune HS1503|-5,-4.6,-4.4,-4.7,-4.9,-3.9,-2.7,-1.3,-0.1,0,-0.1,-0.5,-3.4,-4.4,-4.2,-2.3
Acoustune HS1551|-2.3,-2.7,-3.3,-4.1,-5,-4.7,-4,-3.1,-2.2,-2.6,-4.1,-1.9,0,-2,-1.4,-0.6
Acoustune HS1650|-3,-3.1,-3.5,-4.2,-5,-3.7,-2.1,-1.3,-1.8,-2.1,-3.2,-4.3,0,-2.4,-0.6,-1.5
Acoustune HS1655|-3.3,-3.4,-3.7,-4.4,-5,-4.1,-2.6,-1.2,-0.9,-1.5,-2.7,-3.3,0,-4.2,-1.2,-2
Acoustune HS1657|-3.7,-3.7,-4,-4.5,-5,-3.7,-2.2,-2.1,-2.1,-2.3,-3.8,-4.9,0,-4.9,-1.6,-2
Acoustune HS1670|-0.8,-1.2,-2.1,-3.3,-4.8,-4.1,-2.6,-1.3,-1.9,-2.8,-4.1,-5,0,-2.7,-1.3,-2.5
Acoustune HS1677|-2.7,-2.8,-3.3,-4.2,-5,-4,-2.3,-1.1,-1.9,-2.4,-3.6,-4.8,0,-4.7,-1.9,-2
Acoustune HS1695|-1,-1.5,-2.5,-3.7,-5,-4.5,-3.2,-2.9,-3.5,-3.5,-3.9,-2.9,0,-4.3,-2.3,-3.1
Acoustune HS1697|-1.4,-1.8,-2.6,-3.7,-5,-4.7,-3.2,-1.8,-3.3,-3.6,-3.4,-2,0,-3.6,-1.9,-1.9
Acoustune HS1750|-1.8,-2.1,-2.9,-3.9,-5,-4,-2.3,-2.1,-2.6,-2.6,-4,-3.7,-0.3,-4.3,0,-1.2
Acoustune HS1790|0,-0.6,-1.8,-3,-4.7,-5,-3.8,-2.8,-3.9,-4.1,-4.5,-2.9,-0.3,-4.8,-1.4,-2.3
Acoustune HS2000MX|0,-0.5,-1.3,-2.6,-4.3,-5,-5,-5,-4.3,-3.4,-2.3,-1.8,-2.5,-4,-4.5,-3.4
Adam SP-5|0,-2.2,-3.6,-2.7,-3.6,-2.3,-2.4,-1.6,-1.9,-3,-4.2,-3.8,-2.2,-2,-4.4,-5
Advanced 747 (ANC off)|-3.6,-3.7,-4,-4.5,-5,-4.6,-3.8,-2.7,-1.2,-0.2,-0.3,-0.7,-0.7,-1,-1,0
Advanced 747 (ANC on)|-3.6,-3.9,-4.3,-4.7,-5,-4.3,-3.5,-2.4,-1.1,-0.2,-0.1,-0.2,-0.4,-0.6,-0.6,0
Advanced Alpha (shallow earpads)|0,-0.2,-0.6,-1.2,-2.4,-3.2,-2.8,-2.3,-1.7,-1.9,-1.3,-3.8,-5,-3.6,-2.4,-2.9
Advanced Elise|-3.5,-3.7,-4,-4.5,-5,-4.6,-3.8,-2.8,-1.5,-0.5,-0.7,0,-0.4,-0.5,-2.1,-2
Advanced Evo X|-1.1,-1.4,-2.4,-3.5,-4.9,-5,-4.2,-2.9,-1.8,-2.1,-3.4,-2.6,-0.2,-2.5,-1.8,0
Advanced Furai|-3.5,-3.4,-3.5,-3.7,-3.8,-4.6,-3.6,-3.3,-3.3,-3.4,-4.9,-5,-1.7,-2.4,-1.2,0
Advanced GT-R|-1.7,-2,-2.5,-3.2,-4.1,-5,-3.7,-4.9,-5,-3.3,-1,-2.9,0,0,-1.7,-3
Advanced GT3 (bass)|-2.8,-2.5,-2.7,-3.4,-4.6,-4.7,-4.1,-2.9,-1.4,-0.8,-0.5,0,-2.7,-4.6,-4.6,-5
Advanced GT3 (ref)|-2.1,-1.9,-2.3,-3,-4.3,-4.5,-3.7,-2.5,-0.8,-0.2,0,-0.2,-4.2,-5,-4.6,-5
Advanced GT3 (treble)|-1.9,-1.8,-2,-2.8,-4.1,-4.3,-3.6,-2.3,-0.7,-0.1,0,-0.6,-4.9,-5,-4.7,-4.8
Advanced GT3 Superbass (bass)|-3.6,-3.5,-3.5,-3.9,-4.7,-5,-4.8,-4.4,-3.7,-3.1,-2.4,-0.2,-0.9,-0.4,0,-0.6
Advanced GT3 Superbass (ref)|-3.7,-3.5,-3.4,-3.9,-4.7,-5,-4.8,-4.4,-3.7,-3.3,-2.6,-0.5,-1.8,-0.9,0,-0.2
Advanced GT3 Superbass (treble)|-3.7,-3.5,-3.4,-3.9,-4.7,-5,-4.8,-4.4,-3.8,-3.4,-2.6,-0.6,-1.9,-1.1,0,-0.2
Advanced M3|-4,-4.1,-4.3,-4.7,-5,-4.4,-3.5,-2.5,-1.3,-0.9,-1.1,-1.6,-1.1,-1.3,-0.6,0
Advanced M4|0,-0.4,-1.4,-2.8,-4.7,-4.7,-3.6,-2.1,-0.5,-0.6,-0.6,-1.8,-3.4,-3.1,-5,-4.3
Advanced M51D|0,0,-0.8,-1.9,-3.6,-4.6,-5,-4.9,-3.8,-3.2,-4.1,-2.7,-1.8,-0.4,-0.4,-0.8
Advanced M55D|-3.2,-3.3,-3.4,-4.1,-4.9,-5,-4.6,-3.9,-3.1,-2.6,-1.8,-1.2,0,-2.5,-4.4,-4.6
Advanced Model X|-5,-4.8,-4.6,-4.8,-4.8,-3.8,-2.7,-1.5,-0.4,-0.1,-0.1,0,0,-1.8,-2.4,-0.3
Advanced S2000|-4.7,-4.5,-4.5,-4.8,-5,-4.3,-3.4,-2.5,-1.8,-1.8,-3.3,-4.9,-2.7,-2.1,0,0
Advanced Sleeper|-5,-4.9,-4.8,-4.7,-4.6,-3.3,-3.5,-3,-2.6,-3.2,-3.8,-4.7,-0.2,0,-1.3,-2.5
Aedle VK1|0,-0.9,-3.9,-4.8,-4.8,-4.3,-3.4,-2.9,-2.1,-3.6,-5,-3,-1.6,-0.1,-0.6,-0.1
AfterShokz Aeropex|0,0,0,0,-0.9,-4.1,-5,-3.4,-1.5,-1.4,-1.4,-1.5,-2.9,-2.8,-3.5,-3.9
AfterShokz Trekz Air|0,0,0,0,-2,-3.6,-3.5,-1.8,-1.6,-1.5,0,-2.4,-2.9,-5,-2.9,-3.7
AFUL Acoustics MagicOne|-0.9,-1.2,-2,-3.3,-5,-4.4,-3.2,-2.1,-0.5,-0.3,-0.3,0,-0.4,-1.1,-1.6,-3.1
AFUL MagicOne (foam eartips)|0,-0.8,-1.9,-3.3,-5,-4.6,-3.5,-2.6,-1.5,-1.6,-1.5,-1.1,-0.8,-0.3,-0.9,-2
AFUL Performer 5|-4.4,-3.9,-3.6,-4,-4.5,-3.7,-3,-2.7,-2.6,-3.2,-3.6,0,-0.5,-3.1,-3.1,-5
AFUL Performer 8|-2.9,-2.6,-2.8,-3.5,-4.4,-4.1,-3.5,-3.5,-4.1,-3.6,-3.2,0,-0.2,-0.8,-1,-5
Aiaiai TMA-1|-1.4,-2.6,-3.5,-3.8,-4.6,-5,-5,-4.4,-2.2,-3.1,-1.2,0,-0.2,-1.7,-0.2,0
Aiaiai TMA-1 Studio|-1.4,-2.9,-4,-4.2,-4.8,-5,-4.4,-4.1,-2.7,-3.6,-1.7,0,-2.2,-0.3,-1.9,-2.6
Aiaiai TMA-2 HD Wireless|-1.9,-2.4,-3.1,-3.9,-5,-5,-4.1,-3,-1.4,-2.3,-1.1,-0.1,-0.6,-0.9,0,-0.2
Aiderlot M5 (bass filter)|0,-0.3,-0.9,-1.7,-3.7,-4,-3.8,-3.2,-2.7,-3.5,-5,-2.1,-0.8,-1.9,-1.2,-0.5
Aiderlot M5 (no filter)|0,-0.2,-0.8,-1.7,-3,-3.4,-3.2,-2.8,-2.4,-3.1,-5,-2.8,-3.3,-2.4,-2.7,-2.3
Aiderlot M5 (reference filter)|0,-0.2,-0.8,-1.5,-3,-3.4,-3.2,-2.7,-2.3,-3.1,-5,-2.7,-3.1,-2.2,-2.6,-2.2
Aiderlot M5 (treble filter)|0,-0.4,-0.9,-1.6,-3.1,-3.4,-3.2,-2.8,-2.4,-3.2,-5,-2.7,-2.9,-2.1,-2.4,-2
Aiderlot M5 Pro|-4.6,-4.5,-4.5,-4.7,-5,-3.9,-2.3,-2.2,-2.4,-2.6,-4.3,-3.3,-3.2,-4.6,0,0
Akai MPC|-1.1,-1.3,-2,-2.9,-4.1,-3.8,-3.2,-3.4,-3.5,-4.3,-3.4,-2.6,-5,0,-0.8,-0.3
AKG K1000|0,0,-0.9,-2.7,-2.8,-2.7,-2.2,-2.2,-2.1,-2.7,-4,-3.3,-3.3,-4.2,-5,-4.2
AKG K167 Tiesto|-0.4,-0.6,0,-0.7,-1.4,-2.1,-2.6,-3.3,-3.8,-4.7,-5,-2.7,-3.8,-1.4,-4.3,-5
AKG K240 MKII|0,0,-1.3,-2.8,-4.6,-5,-4.7,-3.9,-3.5,-3,-1,-3.6,-0.6,-1.7,-3.3,-3
AKG K240 Monitor|0,-0.8,-1.9,-2.6,-3.7,-3.9,-3.7,-3.5,-2.6,-2.4,-2.1,-1.4,-5,-0.9,-1.8,-2
AKG K240 Sextett|0,-0.8,-2.5,-4.7,-4.9,-5,-4.1,-3.2,-2.1,-1.5,-1.2,-1.7,-0.1,-2.9,-3.6,-2.1
AKG K240 Sextett (Dekoni Choice Leather DT earpads)|-2.5,-3.2,-3.8,-4.9,-5,-4.6,-3.3,-2.1,-1.9,-0.8,0,-1.5,0,-2.8,-1.9,-1.2
AKG K240 Studio|0,0,-0.1,-1.9,-4.2,-5,-4.8,-4,-4.4,-3.4,-0.8,-3.8,-1.1,-1.5,-3.9,-3.5
AKG K240DF|0,0,0,-0.7,-2.5,-3.6,-3.5,-3.6,-3,-2.8,-2.6,-2.2,-5,-1.9,-2.6,-3.9
AKG K250|0,0,-1.1,-3.1,-4.5,-5,-4.4,-3.9,-3.7,-3.6,-4.6,-4.9,-3.7,-1.7,-1.6,-3.8
AKG K267 Tiesto (club)|-0.6,-0.3,0,-1.8,-3.8,-3.8,-0.9,-3.5,-3.5,-4.6,-5,-1.2,-2.6,-0.4,-1.8,-1.8
AKG K267 Tiesto (stage)|-3.2,-2.9,-2.5,-3.4,-5,-4.6,-0.1,-2.1,-2.2,-2.8,-4.4,-0.5,-1.9,0,-0.7,-0.8
AKG K267 Tiesto (studio)|-0.2,0,-0.4,-2.3,-4.3,-4.7,-2.9,-4.5,-4.4,-5,-4.7,-1.8,-3.1,-2.7,-2.3,-2.6
AKG K271 MKII|0,0,-0.4,-0.6,0,-3.4,-4.1,-4.3,-3.7,-3.9,-5,-2.4,-1.3,-0.7,-4.1,-3.8
AKG K272HD|0,-1.4,-3.4,-2,-4.2,-4.9,-4.7,-5,-3.8,-4.2,-4.6,-1.2,-2.2,-0.3,-4.2,-3.6
AKG K280 Parabolic|0,-0.4,-1.8,-2.8,-4,-4.9,-4.9,-4.9,-5,-3.5,-3,-2.3,-3.1,-4,-1.4,-1.9
AKG K3003|-2.9,-3,-3.5,-4.1,-5,-4.9,-4.3,-3.4,-2.4,-2.1,-1.8,-0.6,0,-2.2,-3,-2.3
AKG K3003 (bass boost filter)|-1.8,-2.3,-3.2,-4.1,-5,-5,-4.2,-3.3,-1.9,-1.4,-0.7,-1.1,0,-2.5,-1.1,-0.8
AKG K3003 (high boost filter)|0,-0.8,-2,-3,-4.4,-4.7,-4.1,-3.6,-2.5,-2.4,-1.8,-1.9,-1.5,-5,-4.6,-4.9
AKG K3003 (reference filter)|-0.4,-1.2,-2.3,-3.4,-4.7,-5,-4.5,-3.9,-2.9,-2.8,-2.4,-1.7,0,-3.8,-3.4,-3.4
AKG K340|0,0,-0.5,-2.2,-4,-5,-4.4,-3.5,-2,-2.9,-2.7,-4.9,-3.1,-1.7,-4.6,-4.6
AKG K361|-5,-4.6,-3.5,-3.8,-4.6,-3.7,-3.2,-3.5,-3.9,-3.9,-4.3,-3.6,-0.4,0,-2.3,-3.3
AKG K371|-5,-3.8,-2.7,-3.8,-5,-4.4,-3.5,-2.9,-3.1,-3.8,-3.1,-2.8,0,-2.3,-2.4,0
AKG K371 (Brainwavz oval earpads)|-5,-4,-2.3,-2.5,-1.5,-1.8,-2.1,-2.4,-2.8,-3.9,-4,-2.8,0,-3.8,-1.9,-1.6
AKG K376|-5,-4.7,-4.5,-4.5,-4.5,-3.7,-2.8,-2.1,-1.2,-1.3,-1.3,-2.6,-1.1,-2.5,-0.3,0
AKG K44|-1.5,-2.2,-2.7,-2.9,-3.5,-4.1,-4,-4,-3.9,-5,-2.4,-0.2,0,-1.2,-3.5,-4.5
AKG K450|-0.6,-1.5,-2.3,-2.7,-3.7,-4.5,-4.3,-4.4,-4,-3.7,-1,-0.6,-5,-1.1,0,0
AKG K501|0,0,0,-1,-2.1,-2.7,-2.3,-2.4,-1.9,-3.2,-3.3,-3,-5,-2.9,-3.5,-2.7
AKG K518LE|0,-0.9,-2.3,-3,-5,-4.5,-3.8,-3.1,-3.2,-3.8,-3.2,-1.7,-2.3,-1.9,-0.5,-0.7
AKG K52|-3.7,-3.6,-1.9,-3.4,-5,-3.4,-1.5,-2.3,-1.5,-1.6,0,0,-0.5,-1.7,-2.4,-2.1
AKG K550|-1.6,-1.3,0,-2.1,-3.7,-3.5,-2.8,-2.1,-0.7,-3,-2.3,-0.9,-1.1,-1.2,-5,-4
AKG K553|-4,-3.8,-3.3,-4.9,-5,-4.1,-3.5,-2.5,-2,-2.3,-1.7,-2.2,0,-2.7,-2.3,-2.2
AKG K601|0,0,-0.1,-1.1,-2.2,-2.7,-2.4,-2.1,-1.8,-2.7,-3.8,-5,-3.1,-1.9,-1.9,-1.9
AKG K601 (2007)|0,0,-0.2,-1.2,-2.6,-3.4,-2.9,-2.7,-2.4,-2.8,-2.4,-3.9,-4.5,-3.9,-5,-4
AKG K601 (Dekoni sheepskin earpads)|-0.4,-1.4,-2.6,-3.3,-4.4,-5,-4.1,-2.8,-1.4,-2.8,-3,-4.6,0,-2.6,-2.9,-2.8
AKG K601 (K701 earpads)|0,-0.1,-1.3,-1.4,-3.1,-3.6,-3.2,-2.7,-1.9,-2.8,-3.4,-5,-2,-2.7,-1.5,-2
AKG K612|0,-0.5,-1.3,-2.1,-3.7,-4.3,-3.7,-3.3,-2.3,-3.3,-3.8,-5,-3.7,-3.5,-3.2,-2.4
AKG K612 Pro|0,-0.9,-1.8,-2.4,-4,-4.7,-4,-3.6,-2.1,-3.6,-3.3,-5,-3.6,-3.8,-3.3,-1.9
AKG K619|-5,-5,-4.3,-3.7,-2.8,-2.6,-2.9,-3.6,-3.7,-4,-3,-0.8,-0.9,-0.4,0,-0.9
AKG K67 Tiesto|-3.6,-3.4,-4.1,-5,-4.3,-0.2,0,-1.8,-3,-4.5,-3.6,-0.6,-1.9,-1,-2.2,-2.1
AKG K701|0,-1,-1.9,-2.6,-4.1,-5,-4.3,-3.5,-1.5,-1.2,-1.6,-3.7,-0.1,-3,-2.5,-1.7
AKG K701 (sample A)|0,-0.1,-0.9,-2.2,-4,-5,-4.5,-4.1,-2.6,-3.2,-2.3,-3.7,-3.4,-4,-4.7,-2.9
AKG K701 (sample B)|0,-0.1,-0.7,-1.9,-3.2,-3.8,-3.2,-2.7,-1.5,-3,-2.4,-3.7,-3.9,-3.7,-5,-3.9
AKG K702|0,-0.8,-1.5,-2.1,-3.9,-4.5,-4,-3.2,-2,-2.9,-3.4,-5,-1.7,-4.3,-2.4,-2
AKG K702 (Brainwavz XL earpads)|-2.1,-2.5,-3.1,-3.7,-4.9,-5,-4.1,-2.4,0,-2.5,-3.4,-4.2,0,-3.2,-1.9,-2
AKG K702 (Geekria earpads)|0,0,-0.2,-0.7,-1.9,-2.6,-2.3,-2.1,-1.5,-2.7,-4.6,-5,-3.3,-4.6,-2.8,-3.2
AKG K702 65th Anniversary Edition|0,-0.8,-2.1,-2.8,-4.1,-5,-4.5,-3.9,-3.2,-2.1,-3.1,-3.9,-0.9,-3.7,-3.5,-2
AKG K712|-0.1,-0.9,-2,-2.7,-3.8,-5,-4.5,-3.8,-2.4,0,-0.9,-1.8,-0.7,-3.6,-2,-0.6
AKG K712 PRO|0,-0.9,-2,-2.9,-4,-5,-4.7,-4.3,-3.3,-0.9,-1.6,-3.3,-1.8,-2.2,-2.3,-1.3
AKG K72|-2.9,-2.8,-2.3,-3.1,-5,-4.3,-3.1,-3.6,-2.2,-0.6,0,0,-0.9,-1.9,-2.5,-1.3
AKG K7XX|-1.5,-2.2,-2.9,-3.4,-4.3,-5,-4.2,-3.5,-1.6,-1.3,-1.4,-0.8,-0.5,-2.3,-1.2,0
AKG K81 DJ|0,-1,-2.4,-2.5,-5,-4.1,-3,-2.4,-3,-3.8,-3.8,-2.5,-3.1,-2,-1.5,-2.9
AKG K812|-0.6,-1.1,-2,-2.9,-4.4,-5,-4.4,-3.9,-3.1,-2.5,-1.4,0,-3.9,-4.1,-3.6,-3.2
AKG K812 (serial number 001130)|-1.7,-2.2,-2.9,-3.5,-4.5,-5,-4.4,-4,-2.8,-1.8,-1.1,0,-4.1,-4.5,-4.3,-2.6
AKG K872|-1.5,-1.9,-4.7,-5,-4.6,-4,-3.3,-2.9,-2.9,-2.7,-2.9,0,-3.2,-3.9,-2.5,-1.3
AKG K872 Pro|0,0,-1.4,-3.6,-5,-4.2,-3.4,-2.9,-2.5,-2.3,-2.6,0,-2.9,-3.6,-1.6,-0.3
AKG K92|-3.4,-3.1,-1.4,-3.2,-5,-3.9,-2.9,-3.5,-1.6,-0.6,0,0,-0.8,-1.9,-1.8,-0.8
AKG N20|-2,-2.5,-3.3,-4.1,-5,-4.1,-2.8,-1.3,0,0,-0.4,-2.9,-0.3,-1.6,-3.2,-2.5
AKG N200|-3.4,-3.8,-3.9,-3.9,-2.6,-1.6,-1.8,-1.3,0,-0.9,-1,-1.7,-1,-1.1,-2.5,-5
AKG N400|-2.2,-1.6,-0.7,-0.8,-1,-0.5,-0.5,-0.5,-0.1,-0.1,-0.2,-0.6,-1.5,0,-2.2,-1.2
AKG N5005|-2.1,-1.6,-0.9,-1,-1.5,-2,-1.9,-1.4,-0.5,-0.4,-1.9,-2.7,-1.5,-3.8,0,-0.9
AKG N5005 (bass boost)|-5,-4.7,-4.1,-4,-3.8,-3.6,-3.5,-3.2,-2.9,-3.6,-4.3,-1.3,-0.8,0,-0.5,-3.6
AKG N5005 (high)|-2.5,-2.1,-1.5,-1.3,-1.1,-0.9,-0.8,-0.4,0,-0.7,-1.4,-2,-1.8,-1.4,-3.5,-5
AKG N5005 (mid high)|-2.8,-2.3,-1.6,-1.5,-1.3,-0.9,-0.7,-0.4,0,-0.9,-1.8,-0.9,-1.1,0,-3,-5
AKG N5005 (reference)|-3.4,-3,-2.5,-2.3,-2.2,-1.9,-1.8,-1.5,-1.2,-2,-2.8,-0.4,-1.1,0,-3.1,-5
AKG N60 NC (wired, passive)|-3.1,-3.4,-3.6,-4.3,-4.8,-4.2,-3.6,-3.8,-2.9,-3,-2.6,-3.4,-5,-2.3,0,0
AKG N60 NC Wireless|0,-0.9,-2.4,-3,-3.5,-3.3,-2.5,-1.8,-1.9,-2.4,-1.9,-2.5,-5,-4.7,-1.7,-1.4
AKG N700NC|-1,-0.7,-0.4,-0.3,-0.6,-0.6,0,-0.5,-1.3,-1.1,-2.5,-4.1,-3.7,-5,-4.8,-4
AKG N700NC M2|-5,-4.5,-4.5,-4.9,-4.5,-4.2,-2.5,-2.1,-2.5,-1.7,-1.4,-0.5,-2.1,-0.7,-0.4,0
AKG N700NC M2 (ANC on)|-4.8,-4.7,-4.1,-4.4,-4.2,-5,-4,-2.8,-3.1,-3.2,-3.7,-3.2,-2.7,0,-2.4,-2.3
AKG N700NC M2 (passive)|-3.9,-4.2,-4.1,-4.4,-5,-3.4,-1.7,-0.7,-1.6,-1.5,0,-0.1,-2.2,-0.8,-2.2,-1.2
AKG N90Q|-0.2,0,-0.3,-0.9,-1.6,-2,-1.7,-2.1,-2.1,-1.6,-0.9,-2.9,-5,-2.3,-2,-3.7
AKG Q350 Quincy Jones|-3.1,-3.4,-4,-4.5,-5,-4.6,-3.7,-2.8,-1.4,-0.8,0,0,-2.6,-3.6,-2.9,-2
AKG Q460 Quincy Jones|-1.8,-2.5,-3,-3.4,-4.3,-5,-4.9,-4.7,-2.3,-3.6,-1.7,0,-4.5,-1.3,0,0
AKG Q701|0,-0.8,-1.9,-2.4,-3.8,-4.3,-3.6,-3.1,-2.1,-2.8,-3.5,-5,-2.1,-2.9,-4.1,-3.7
AKG Q701 Quincy Jones|0,0,-0.7,-1.5,-3,-3.6,-3.2,-2.9,-2,-2.6,-3.5,-4,-3.6,-3.5,-5,-3.3
AKG Y50BT|0,-1.1,-1.9,-2.2,-1,-2.2,-4.3,-3.4,-4.2,-4.4,-5,-0.7,-2.6,-4.3,-1.5,-2.3
Akoustyx R-120|0,0,0,-1.3,-3.1,-4,-4,-3.8,-3.4,-3.9,-4.1,-4.1,-4.9,-4.8,-4.2,-5
Akoustyx R-220|0,0,0,-0.7,-2.3,-3.9,-3.7,-3.4,-3.2,-4,-4.2,-4.3,-3.7,-2.2,-5,-4.7
Akoustyx S-6|-3.4,-2.6,-2,-2,-2,-1.6,-1,-0.5,0,-0.4,-0.8,-2.8,-3.9,-5,-2.8,-3.5
Alclair Electro|-3.8,-3.6,-3.6,-4,-4.8,-4.8,-4.4,-3.8,-3.3,-4,-5,-1.4,-1.8,-1.3,-0.6,0
Alclair Studio3|-3.2,-3.4,-3.7,-4.3,-5,-4.8,-4.1,-3.5,-2.9,-3.5,-4.4,-1.6,-2.4,-2.5,-0.5,0
Alclair Studio4|-2.7,-2.7,-3,-3.7,-4.7,-5,-4.6,-4,-3.2,-3.1,-2.8,-0.6,-0.8,-2,-0.7,0
Alclair Versa|-2.6,-2.9,-3.4,-4.1,-5,-4.9,-4.3,-3.3,-2.2,-1.6,-1.3,-0.5,0,-0.9,-1.8,-0.6
Allen & Heath XD-53|0,0,-0.4,-2,-2.3,-2.1,-1.6,-1,-1.4,-4.8,-5,-2.6,-2.6,-2.7,0,-1.8
Alpha & Delta AD01|-5,-4.7,-4.6,-4.8,-5,-4.3,-3.5,-2.9,-1.9,-2.3,-2.3,-2.7,-0.4,-1.1,0,-1.8
Alpha & Delta D6|-4.2,-4,-4,-4.4,-4,-4.8,-3.4,-3,-2.2,-1.7,-1.2,-1.3,-1.8,-5,-2.2,0
Alpha & Delta KS100|-4.9,-5,-4.5,-3.9,-2.3,-1.9,-2.2,-2,-1.1,-2.4,-1.8,-2.7,-3.2,-3.6,0,-1.3
Alpha & Delta KS3|0,-0.1,-0.9,-1.7,-2.9,-3.3,-3,-2.4,-1.7,-2.2,-3.9,-5,-1.1,-2.5,-0.5,-0.7
Alpha Design Labs H128|-3.1,-3.7,-4.2,-4.5,-5,-4.7,-4,-3.9,-3.7,-3.3,-2.2,-1.8,-1.5,0,-1.7,-0.1
Altec Lansing True Evo|-1.4,-2.2,-3.8,-5,-4.9,-3.4,-2.1,-0.8,0,-0.4,-0.2,-0.2,-1.1,-0.8,-2.3,-2
Altiat Cal.1H|-0.6,-1.6,-2.5,-2.9,-3.2,-4.3,-3.5,-3.2,-2.5,-1.7,0,0,-1.9,-5,-2.6,-1.4
Amazon Echo Buds|-4.9,-5,-4.6,-2.3,0,-0.7,-1.8,-2.4,-2.2,-2.8,-2.1,-4.1,-2.8,-1,-0.9,-2.2
Amazon Echo Buds Gen 2|-1.3,-2.1,-1.4,-0.2,0,-1.3,-3.6,-4.4,-3.4,-3.6,-3.6,-5,-3.4,-2.8,-4.1,-4.2
Amazon Echo Buds Gen 3|0,0,-1.1,-3.1,-4.8,-4.6,-4.5,-4.9,-4.6,-3.4,-3.9,-2.4,-3.1,-3.2,-5,-4.7
AmazonBasics Lightweight On Ear|-4.3,-4.7,-5,-4.7,-3.7,-1.7,-1.4,-4,-4.5,-3.7,-1.7,-0.4,0,-0.1,-3.4,-2.2
Ambient Acoustics AM16|-2.3,-2.6,-3.1,-3.9,-4.9,-4.9,-4.4,-3.9,-3.3,-3.9,-4.5,-1.8,0,-5,-3.9,-2.5
Ambient Acoustics AM24|-1.9,-2.2,-2.8,-3.6,-4.7,-5,-4.8,-4.3,-3.7,-4,-4.6,-0.1,-1.1,0,-1.8,-1.9
Ambient Acoustics AM7|0,-0.7,-1.7,-2.9,-4.4,-5,-4.5,-3.6,-2.4,-2.6,-4.7,-3.9,-4.1,-1.5,-2.6,-0.2
Ambient Acoustics AM7 (blue)|-2.1,-2.6,-3.1,-3.9,-5,-5,-4.3,-3.1,-1.7,-1.9,-3.6,-2.9,-3.3,-0.8,-1.7,0
Ambient Acoustics AM7 (red)|-3.2,-3.4,-3.8,-4.3,-5,-4.8,-4,-2.9,-1.6,-1.2,-2.6,-2.2,-2.4,-0.4,-1.2,0
Ambient Acoustics AM7 (yellow)|0,0,-0.1,-0.9,-1.9,-2.2,-2.1,-2,-2.3,-3.1,-5,-3.8,-2.5,-0.2,-0.8,0
Ambient Acoustics MAD16|-3.1,-3.2,-3.6,-4.2,-5,-4.9,-4.3,-3.2,-1.7,-1.3,-2.6,-2.4,-2,0,-0.4,-1.3
Ambient Acoustics MAD24|-2.8,-3,-3.5,-4.1,-5,-5,-4.5,-3.5,-2.2,-1.6,-1.6,-1.2,-0.3,0,-1.6,-1.6
Ambient Dynamics AD-006 Lyndale|-2.5,-2.6,-2.9,-3.7,-4.7,-5,-4.6,-3.7,-2.8,-2.9,-3.6,-2.4,-1,-2.1,0,0
Andover Audio PM-50 (medium squared earpads)|-1.2,-1.7,-2.6,-3.1,-3.9,-4.7,-4.5,-4.3,-4.2,-5,-4.7,-1.9,-1.2,0,-1.6,-1.7
Andover Audio PM-50 (small rounded earpads)|-1.1,-1.5,-2.4,-2.9,-3.7,-4.3,-4,-4.1,-4.2,-5,-4.1,-0.8,0,0,-0.3,-0.9
Anew U1|-4.8,-4.6,-4.5,-4.8,-5,-4.2,-3.4,-2.6,-2.2,-2.9,-4.5,-1.4,0,-3.8,-3.9,-3.6
Anker Life P2i|-5,-5,-4.8,-4.6,-4,-2.9,-2.2,-1.9,-1.8,-2.1,-2.1,-2.4,-1.9,0,-0.6,-2.6
Anker P3i|-4.9,-5,-4.2,-4,-3.3,-1.4,-0.1,-0.3,-0.4,-2,-3.6,-2.8,0,-2,-3.3,-2.9
Anker SoundBuds Curve|-5,-4.2,-3.6,-3.9,-4.2,-0.5,-1.1,-0.8,0,-0.5,0,-0.4,-0.9,-1.9,-1.4,-0.3
Anker SoundBuds Curve Upgraded 2019|-5,-4.7,-4.2,-4.4,-4.1,-1.4,-0.9,-0.2,0,-1.5,-1.9,-1.7,-1.9,-4.2,-2.5,-1.7
Anker SoundBuds Life|-5,-4.4,-3.9,-4,-4.2,-2.4,-1.5,-1.1,-0.3,-1,-1.5,-2.3,-3.4,-5,-1.6,0
Anker SoundBuds Sport|0,-1.6,-3.5,-4.4,-5,-4.7,-3.8,-2.6,-1.2,-1.4,-0.8,-0.4,-0.3,0,-1.7,-3.2
Anker Soundcore Aerofit Pro|0,0,0,0,-0.2,-0.8,-1.5,-1.8,-2.2,-2.6,-2.6,-5,-1.9,-2.8,-1.9,0
Anker Soundcore Liberty 2 Pro|-5,-4.7,-4.6,-4.5,-3.7,-1.5,-0.8,-0.4,-0.4,0,-0.8,-3.4,-0.9,-2.4,-2.9,-4.4
Anker Soundcore Liberty 3 Pro|-4.8,-4.5,-4,-3.9,-3.1,-1.1,-0.2,0,-0.2,-1.7,-2.6,-4.3,-0.1,-1,-2.9,-5
Anker Soundcore Liberty 4|-0.6,-0.9,-1.2,-1.2,-1.4,-0.7,-0.1,0,-0.3,-1.1,-1.7,-1.6,-1.4,-2.1,-4.1,-5
Anker Soundcore Liberty 4 NC|-3.2,-3.7,-4.5,-4.4,-3.5,-2.5,-1.6,-0.6,0,-0.7,-0.3,-0.1,-0.1,-2.3,-5,-4.7
Anker Soundcore Liberty Air|-4.7,-4.9,-4.9,-4.9,-3.3,-3.8,-3.5,-1.9,-0.3,-1.8,-2.9,-0.3,-3.5,0,-5,-4.4
Anker Soundcore Liberty Air 2|-4.7,-4.4,-3.9,-3.3,-5,-2.3,-1.3,-0.9,-0.4,-0.1,0,-0.4,-2.4,-2.2,-2.1,-3.7
Anker Soundcore Liberty Air 2 Pro|-4.6,-4.5,-3.8,-3.7,-2.6,-1.4,-1.5,-1.8,0,-0.7,-0.3,0,-1,-1.2,-5,-3.1
Anker Soundcore Liberty Lite|-5,-4,-3.4,-3.8,-4.7,-1.8,-0.7,-0.4,0,-1.3,-2,-0.9,-3.5,0,-3.3,-4.5
Anker Soundcore Life 2|-2.8,-3.2,-3.9,-3.7,-2.5,-0.5,0,-2,-1.9,-1.5,-2.1,-1.2,-3.3,-1.3,-4.4,-5
Anker Soundcore Life A2 NC|-4,-3.9,-4.2,-4.4,-3.8,-2.3,-1.5,-1.4,0,-0.3,-1.5,-2.3,-0.8,-3.2,-4.3,-5
Anker Soundcore Life Dot 2|-4.8,-4.7,-4.7,-5,-4.7,-2.5,-2.6,-2.1,-1,-2.4,-2.1,-2.3,-1,0,-4.1,-5
Anker Soundcore Life P2|-4.8,-4.8,-5,-4.9,-4,-2,-1.2,-0.7,-0.3,-0.8,-1,0,-2.8,-1.7,-2.9,-5
Anker Soundcore Life P3|-5,-4.4,-3.4,-2.2,-1.2,-1.7,-2.1,-2.3,-0.9,-2.2,-2.4,-2.7,-0.8,0,-3.5,-4.4
Anker Soundcore Life Q20|-3.7,-4.1,-5,-4.4,-2.8,-1,-0.3,0,-0.7,-1.9,-0.4,-2.1,-2.1,-0.3,-2.8,-1.9
Anker Soundcore Life Q30|-4.3,-5,-4.9,-5,-4.6,-2.4,-2.5,-4.3,-1.4,-1.8,-1.6,-3.3,-0.6,0,-1.5,-3
Anker Soundcore Life Q30 (ANC off)|-4.8,-4.4,-4.7,-5,-4.3,-2.3,-1.3,-0.3,-0.4,-1,-1.4,-1.4,0,-0.8,-1.9,-3.6
Anker Soundcore Life Q30 (ANC on)|-4.1,-4.9,-4.9,-5,-4.6,-2.4,-2.5,-4,-1.4,-1.4,-1.9,-2.3,0,-1.4,-2.3,-4.6
Anker Soundcore Life Q35|-4.2,-4.3,-4.6,-4.5,-3.3,-1.5,-1.7,-2.2,-1.8,-2.2,-2,-2.5,0,-1,-3.3,-5
Anker Soundcore Life Q35 (ANC off)|-4.7,-5,-4.2,-3.9,-3.3,-2.4,-1.3,-0.8,-1.3,-2.2,-2.1,-2.8,0,-0.9,-2,-3.6
Anker Soundcore Life Q35 (ANC on)|-5,-4.4,-3.9,-3.8,-3,-1.6,-1.7,-2.2,-1.7,-1.9,-2.3,-2.8,0,-1.2,-1.8,-2.9
Anker Soundcore Life Q35 (wired, passive)|-4,-4.2,-4,-4.3,-5,-4.7,-2.9,-1.1,-0.7,-0.6,0,0,0,-0.4,-0.8,-1.2
Anker Soundcore Space A40|0,-0.6,-1.4,-2.3,-2.6,-1.6,-2.8,-5,-4,-3,-1.8,-0.4,-0.2,-0.5,-1.5,-0.2
Anker Soundcore Space NC|-5,-4.7,-4.7,-4.5,-3.8,-1.9,-0.8,-0.9,-1,-1.7,-2.4,-3.3,-2.6,0,-1.4,-2.4
Anker Soundcore Space One|-2.8,-3,-2.9,-3.1,-2.3,-0.2,-0.8,0,-0.5,-2.2,-2.8,-0.8,-0.2,-1.4,-4.3,-5
Anker Soundcore Space Q45|-5,-4.1,-4.3,-4.9,-3.6,-1.8,-1.5,-1.8,-3.4,-4.8,-4.5,-2.4,0,-2.8,-2.9,-4.2
Anker Soundcore Space Q45 (ANC on)|-5,-4.2,-4.4,-4.9,-3.7,-2.1,-1.9,-2.2,-3.3,-4.3,-3.8,-2.4,0,-2.9,-1,-3
Anker Soundcore Spirit X|-5,-4.6,-4,-3.7,-3,-0.1,-0.4,-0.8,-0.1,-1.3,-1.5,-0.8,-1.7,-2.6,-2,0
Anker Zolo Liberty+|-1.8,-2.2,-3.2,-4,-4.1,-1.9,-0.5,-0.1,0,-1.3,-1.3,-0.6,-2.8,-1.1,-4.8,-5
Anthem Five E2 (CIEM)|-4.6,-4.5,-4.6,-4.8,-5,-4.3,-3.4,-2.5,-1.5,-1.3,-2,-1.5,-2.4,-1.5,0,0
Anthem Five E4 (CIEM)|-3.9,-3.9,-4,-4.4,-5,-4.7,-4.2,-3.6,-3.3,-3.8,-5,-3.1,-2.4,0,-0.1,0
AOSHIDA E20|-1.3,-1,-0.5,-0.3,0,-3,-3.9,-3.6,-1.8,-1.3,-3.7,-3.4,-4.6,-2.6,-3.2,-4.5
Apple AirPods (1st generation)|0,0,0,-1,-2.2,-2.5,-2.4,-2.3,-2,-2.2,-3.9,-4.1,-3,-3.3,-4.8,-5
Apple AirPods (2nd generation)|0,0,0,-0.9,-2.1,-2.5,-2.5,-2.1,-2.1,-2.1,-3.6,-4.3,-2.9,-3.2,-4.4,-5
Apple AirPods (3rd generation)|0,0,0,-1.4,-4.2,-4.8,-3.7,-3.2,-3.3,-4,-5,-3.8,-3.1,-4.5,-4.6,-4.3
Apple AirPods 2|0,0,0,-0.1,-1.7,-2,-1.8,-1.7,-2.1,-2.8,-4.9,-5,-2.7,-3.3,-3.5,-3.2
Apple AirPods 3|0,0,0,-1.4,-3.2,-3.4,-2.7,-2.5,-2.4,-3.5,-5,-4.1,-2.5,-4.2,-2.6,-2.4
Apple AirPods Max|-5,-4.5,-4.4,-4,-4.5,-4.9,-3.5,-3.9,-4.1,-4.5,-2.6,-1.2,0,-1.9,-0.2,-3.4
Apple AirPods Max (3rd party pleather earpads)|-3,-2.5,-2.3,-2,-2.3,-2.4,-1.9,-2.2,-2.9,-5,-1.7,-0.5,0,-1,-0.9,-2.3
Apple AirPods Pro|-1.6,-1.7,-1.5,-1.6,-2,-3,-4,-5,-4.4,-3.6,-3.7,-2.8,-1.9,-0.8,0,-1
Apple AirPods Pro (2nd generation)|0,0,-0.2,-1.8,-3.9,-4.6,-5,-3.5,-2,-2.7,-2,-0.4,-0.8,-1.3,-3.1,-3.2
Apple Airpods Pro 2|-3.5,-2.4,-1.2,-1.8,-3.4,-4.3,-4.3,-4.4,-3.7,-3,-2.6,0,-2.3,-0.6,-2.5,-3.8
Apple AirPods Pro 2 (51dB + ANC)|-5,-4.3,-2.6,-3.6,-4.4,-3.3,-2.6,-2.3,-2,-0.7,-1.3,-0.4,-0.4,-2.3,-0.4,0
Apple AirPods Pro 2 (56dB + ANC)|-5,-4.2,-2.7,-3.5,-4.3,-3.4,-2.7,-2.3,-2.2,-0.9,-1.4,-0.7,-0.6,-2.4,-0.6,0
Apple AirPods Pro 2 (60dB + ANC)|-5,-4.2,-2.7,-3.9,-4.9,-4.1,-3.5,-3.2,-3,-1.7,-2.3,-1.4,-1.4,-3.1,-0.9,0
Apple AirPods Pro 2 (65dB + ANC)|-4.6,-4,-2.8,-3.9,-5,-4.4,-4,-3.7,-3.5,-2.3,-2.9,-2.1,-2,-3.5,-1,0
Apple AirPods Pro 2 (69dB + ANC)|-4,-3.6,-2.6,-3.9,-5,-4.7,-4.2,-4,-3.9,-2.8,-3.3,-2.6,-2.4,-3.7,-1.1,0
Apple AirPods Pro 2 (73dB + ANC)|-3.6,-3.3,-2.6,-3.8,-5,-4.7,-4.4,-4.2,-4.1,-3.1,-3.5,-2.9,-2.7,-3.8,-1.2,0
Apple AirPods Pro 2 (77dB + ANC)|-3.2,-3,-2.5,-3.8,-5,-4.8,-4.5,-4.4,-4.3,-3.3,-3.8,-3.1,-2.9,-3.9,-1.2,0
Apple AirPods Pro 2 (81dB + ANC)|-2.8,-2.7,-2.4,-3.7,-5,-4.8,-4.5,-4.4,-4.3,-3.4,-3.8,-3.2,-3,-3.9,-1,0
Apple AirPods Pro 2 (84dB + ANC)|-2.3,-2.4,-2.2,-3.7,-5,-4.9,-4.6,-4.5,-4.4,-3.4,-3.9,-3.2,-3,-3.9,-0.8,0
Apple AirPods Pro 2 (88dB + ANC)|-2.4,-2.5,-2.2,-3.7,-5,-4.9,-4.6,-4.5,-4.4,-3.4,-3.9,-3.2,-3,-3.9,-0.8,0""",
"""Apple AirPods Pro 2 (91dB + ANC)|-2.4,-2.5,-2.2,-3.7,-5,-4.9,-4.7,-4.5,-4.4,-3.4,-3.9,-3.2,-3,-3.9,-0.7,0
Apple AirPods Pro 2 (ANC mode)|-2.3,-2,-1.2,-1.9,-3.1,-4.3,-4.8,-5,-4.6,-2.4,-3,-1.2,-1.5,0,-1.3,-1.3
Apple AirPods Pro 2 (passive mode)|-1.3,-1.1,-0.8,-1.9,-3.5,-4.4,-5,-4.6,-3.7,-2.3,-2.2,-0.5,-1,0,-0.3,-0.2
Apple AirPods Pro 2 (transparency mode)|-2.2,-1.9,-1.1,-1.8,-3.2,-4.3,-4.9,-5,-4.6,-2.3,-2.9,-1.1,-1.3,0,-1,-1.1
Apple EarPods|0,0,0,0,-1.1,-1.7,-1.7,-1.8,-2.4,-3.2,-5,-3.5,-1.3,-3.3,-2.1,-0.7
Apple In-Ear (2013)|-1.1,-1.2,-1.9,-2.9,-4.3,-5,-4.8,-4.6,-3.6,-3.4,-2.4,-1.7,-0.8,0,-0.6,-3
Apple iPod Ear Buds|0,0,0,0,0,-3.6,-4.1,-3.7,-3.7,-3.4,-2.8,-3.2,-5,-3.2,-4.1,-3
Apple iPod Ear Buds (sample A)|0,0,0,0,0,-4,-4.4,-3.8,-3.9,-3.5,-2.8,-3.5,-5,-3.7,-4.6,-3.7
Apple iPod Ear Buds (sample B)|0,0,0,0,0,-3.2,-3.8,-3.6,-3.6,-3.3,-2.8,-3,-5,-3.4,-3.7,-2.4
Apple ME186 In-Ear Headphones|-1.3,-1.4,-2,-3.1,-4.5,-5,-4.6,-3.8,-2.7,-2.6,-1.8,-1.9,-1.4,0,-0.1,-2.1
ARC Mira|-1.1,-1.6,-2.4,-3.4,-4.6,-5,-4.5,-3.6,-2.5,-2.6,-3.4,-3.1,-1.7,-4.2,-1.8,0
ARC Oculus|-1.4,-1.9,-2.5,-3.5,-4.7,-5,-4.6,-3.8,-2.8,-3,-3.6,-1,-0.8,0,-0.5,-1.2
ARC Pollux|0,-0.1,-0.8,-1.8,-3.4,-4.1,-4.2,-4,-3.7,-4.3,-5,-2.6,0,-1.8,-1.9,0
Aroma Audio Ace (off-off)|-0.4,-0.9,-1.9,-3.1,-4.6,-5,-4.6,-3.9,-3.4,-4.5,-4.6,0,-2.4,-2.2,-3.7,-4
Aroma Audio Ace (off-on)|-1.2,-1.6,-2.4,-3.3,-4.6,-5,-4.7,-4.1,-3.4,-3.7,-3.9,0,-1.6,-1.3,-2.3,-2.4
Aroma Audio Ace (on-off)|-2.5,-2.8,-3.4,-4.1,-5,-4.8,-4,-3.1,-2.2,-2.9,-3.2,0,-1.8,-1.6,-2.5,-2.6
Aroma Audio Ace (on-on)|-2.3,-2.7,-3.3,-4,-5,-5,-4.5,-3.7,-2.7,-3,-3.3,0,-1.2,-0.9,-1.7,-1.7
Aroma Audio Early|-3.2,-3.5,-3.9,-4.4,-5,-4.7,-4,-3.3,-2.2,-1.8,-2.5,-2.6,-1.6,-1.5,-1.5,0
Aroma Audio Shock|-3,-3.1,-3.6,-4.2,-5,-4.9,-4.3,-3.4,-2.2,-1.6,-1.3,-1.7,-2.4,-2,0,-1.3
Aroma Audio Star|-2.3,-2.5,-3,-3.8,-4.8,-5,-4.6,-3.8,-2.9,-2.5,-2.4,-1.9,-1.2,-0.6,-2.2,0
Aroma Audio Thunder|-3.6,-3.3,-3.4,-4,-4.9,-5,-4.6,-4,-3,-1.9,-1.5,0,-2.1,-2.9,-3.3,-2.1
Aroma Audio Twins (bass)|-2.4,-2.6,-3.2,-3.9,-4.8,-5,-4.6,-3.9,-2.7,-1.7,-0.7,0,-1,-0.4,-1.6,-2
Aroma Audio Twins (treble)|0,-0.2,-1.1,-2.2,-3.7,-4.4,-4.2,-3.5,-2.5,-2.2,-2.6,-2.7,-4.2,-4.6,-5,-5
Aroma Audio Witch Girl Pro|-2.6,-2.8,-3.2,-3.9,-4.8,-5,-4.7,-4.1,-3.6,-3.3,-3.2,-0.1,-0.6,-0.1,-0.1,0
Aroma Audio Witch Girl S|-1.2,-1.7,-2.6,-3.5,-4.7,-5,-4.6,-3.9,-2.9,-2.6,-3.2,-1.5,0,-1.4,-1.1,0
Aroma Audio Witch Girl W12|-1.6,-2.1,-2.9,-3.7,-4.9,-5,-4.3,-3.2,-2.2,-2.2,-1.6,-0.5,-1,0,-1.9,-1.4
Aroma Audio Yao|-2.4,-2.7,-3.3,-4,-5,-5,-4.5,-3.7,-2.7,-2.1,-1.6,0,-1,-0.5,-1.7,-1.3
ARTTI R1|-3.4,-3.2,-3.4,-4.2,-5,-3.8,-2.3,-0.9,-0.1,-1.4,-2.1,-0.8,-0.4,0,-0.7,-0.9
Astell & Kern (Astell&Kern) T8iE|-3.7,-3.8,-4,-4.5,-5,-4.5,-3.8,-2.9,-2,-1.9,-2.1,-0.6,-0.1,-2.6,-1.5,0
Astell & Kern AURA|-1.5,-1.4,-1.8,-2.6,-3.5,-2.9,-1.8,-0.7,0,-0.8,-1.2,-1.3,-2.3,-2.6,-3.4,-5
Astell & Kern Odyssey|-4.6,-4.5,-4.5,-4.7,-5,-4.2,-3.4,-2.6,-1.7,-2.2,-2.9,-2,0,-0.1,-1.9,-2.5
Astell & Kern Rosie (2 o'clock)|-2.4,-2.7,-3.4,-4.1,-5,-4.9,-4.3,-3.4,-2.2,-1.7,-2.1,-0.9,0,-0.9,-2.6,-1.5
Astell & Kern Rosie (max)|-3.9,-3.9,-4.2,-4.6,-5,-4.6,-3.9,-3,-1.8,-1.2,-1.5,-0.7,0,-0.1,-1.1,-0.3
Astell & Kern Rosie (min)|-1.3,-1.9,-2.7,-3.7,-4.8,-5,-4.4,-3.4,-2.1,-1.6,-2,-0.2,0,-1.2,-3.2,-2
Astell & Kern T9iE|-4.1,-3.6,-3.4,-3.8,-4.6,-5,-4.8,-4.4,-4,-4,-3.6,-0.6,0,-2.9,-1.6,-0.5
ASTRO Gaming A10|-1.8,-2.8,-3.5,-3.6,-3.4,-2.7,-1.9,-0.8,-1.2,-5,-3.1,-1.4,0,-1.1,-3.7,-3.7
ASTRO Gaming A10 (Gen 2)|0,-1,-2.3,-3.4,-5,-4.4,-3.6,-2.9,-1.5,-3.7,-2.9,-2,-2.8,-1.8,-1.9,-1.1
ASTRO Gaming A20|-0.9,-1.9,-2.9,-3.1,-3.1,-2,-0.7,-0.9,-1.2,-1,0,-0.3,-2.7,-4.6,-5,-3.7
ASTRO Gaming A20 (Gen 2)|-2,-1.8,-1.9,-1.5,-1.4,-1.2,-0.5,0,-1.6,-2.2,-1.4,-1.8,-3.5,-3.3,-5,-3.8
ASTRO Gaming A30|-2.8,-2.4,-1.7,-1.7,-2.1,-1.8,-2,-2.9,-1,-0.1,-0.8,-0.9,0,-0.6,-5,-4.1
ASTRO Gaming A40 TR|-1.1,-1.4,-2.8,-3.8,-5,-4.9,-4.9,-4.3,-3.5,-3.5,-4,-2.3,-2.5,-2.2,-1.1,0
ASTRO Gaming A50 (Gen 3 2017)|0,-0.7,-1.6,-2.2,-2.9,-2.8,-2.1,-1.3,-1.3,-3.6,-2.1,-1.4,-0.7,-2.4,-5,-4.2
ASTRO Gaming A50 (Gen 4 2019)|0,-1.5,-2.7,-3.4,-4.1,-3.4,-1.8,-1.8,-0.6,-4.1,-2.4,-2.8,-3,-1.4,-5,-4.1
Astrotec AM90|-1.3,-1.5,-2.2,-3.1,-4.3,-5,-4.8,-4.7,-4,-4,-2.3,-3.6,-0.8,0,-0.5,-1.9
Astrotec AX35|0,0,-1.3,-2.6,-4.2,-5,-4.8,-4.3,-3.2,-2.9,-3.1,-1.8,-0.2,0,-0.7,-0.7
Astrotec AX7|0,0,-0.8,-2,-3.8,-4.9,-5,-5,-4.5,-4.7,-4.2,-3.1,0,-2.3,-2,-1.5
Astrotec Delphinus 5|-0.7,-1,-1.7,-2.4,-3.4,-3.9,-3.9,-3.8,-4.2,-5,-2.6,-2,0,0,-0.1,0
Astrotec Lyra Nature|0,0,0,0,-2.1,-3.8,-4.4,-4.6,-5,-4.7,-4.5,-1.9,-1.4,-2.5,-1.1,0
Astrotec Lyra Nature (no foam)|0,0,0,0,0,-1.4,-2.3,-2.7,-3.5,-4,-5,-4.2,-2.8,-3.5,-4,-2.7
Astrotec Volans|-3.4,-3.3,-3.3,-4.2,-5,-4.9,-4.4,-3.6,-2.8,-2.9,-3.9,-2.9,-2.1,-1.4,0,0
Atomic Floyd SuperDarts|-5,-4.7,-4.4,-4.5,-4.5,-3.7,-3,-2.2,-1.5,-1.8,-2,0,-0.1,-0.5,-3.7,-3.8
Audeze CRBN|0,-0.4,-1.4,-2.2,-3.3,-4.1,-3.9,-3.8,-4,-5,-3.1,-4.1,-4.4,-2.4,-0.7,-1.2
Audeze EL-8 Closed Back|-1.4,-1.2,-2.5,-3,-2.7,-1.9,-1.8,-2.3,-2.8,-3.3,-3.5,-2.9,-5,-4.7,0,-3.1
Audeze EL-8 Open Back|-0.6,-1,-2,-2.6,-3.6,-4.5,-4.1,-4.3,-4.6,-5,-3.8,-4.3,-4,-4.7,0,-2.5
Audeze Euclid|-0.6,-0.6,-1.1,-2,-3.1,-2.9,-2.5,-2.2,-2.3,-3.5,-5,-3.5,-0.1,-3.9,-2.9,0
Audeze iSINE 10|0,0,-0.5,-1.3,-2.5,-3.2,-3.5,-3.8,-4.6,-5,-4.8,0,-0.1,0,-2.4,-4.1
Audeze iSINE 10 (Cipher cable)|0,-1.2,-2.6,-3.6,-4.1,-3.6,-3.4,-2.8,-2.6,-2.9,-3,-2.1,-1.9,-0.5,-3,-5
Audeze iSINE 20|0,-0.2,-0.6,-1.4,-2.4,-3.1,-3.3,-3.6,-4.4,-5,-4.3,0,0,0,-1.7,-3.4
Audeze iSINE 20 (Cipher cable)|-2.9,-2.7,-2.8,-2.5,-3.3,-3.1,-2.3,-2.4,-2.7,-3.1,-5,-4.5,-3.3,-2.1,0,-1.9
Audeze LCD-1|0,-0.5,-2.2,-3,-4.1,-5,-4.9,-4.8,-4.4,-4.6,-1.8,-3.1,-3.9,-1.4,-0.4,-3.2
Audeze LCD-1 (2009)|0,0,0,-0.9,-1.7,-2.2,-1.9,-2.3,-2.2,-2.8,-2.6,-2.8,-4.6,-5,-3.8,-3.2
Audeze LCD-2|0,-0.9,-1.7,-2.5,-4,-5,-4.4,-4.6,-4.3,-4.2,-3.4,-3,-0.5,-1.5,-1.1,-3.2
Audeze LCD-2 (Dekoni fenestrated sheepskin earpads)|-1.5,-1.8,-2.4,-3.6,-4.5,-5,-4.6,-5,-4.6,-4,0,-1.2,0,-3,-2.6,-3.1
Audeze LCD-2 (Fazor)|-0.8,-1,-2,-2.9,-4.2,-5,-4.2,-4.6,-4.6,-4.3,-3.9,-1.3,-0.4,0,-2.7,-4.7
Audeze LCD-2 (pre-2017)|-0.6,-1.1,-2,-2.8,-4.1,-4.9,-4.7,-4.4,-5,-4.3,-3.1,-2.7,0,-1.7,-1.1,-2.9
Audeze LCD-2 (Rev 2)|0,-0.1,-1.7,-2.6,-4.1,-5,-4.6,-4.7,-4.9,-2.7,-2.7,-1.5,-2,-2.6,-1,-1
Audeze LCD-2 (sample 1)|0,-0.3,-1.9,-2.4,-3.5,-4.5,-4.4,-4.6,-5,-3.6,-1.1,-1.1,-1.1,-0.1,-1.7,-1.3
Audeze LCD-2 (serial number 5312123)|0,-1,-1.8,-2.5,-3.9,-5,-4.5,-4.2,-3.9,-2.4,-1.4,-1.1,-2.4,-2.3,-1.4,-1.4
Audeze LCD-2 (serial number 53211704)|0,-0.6,-1.9,-2.5,-3.8,-5,-4.5,-4.1,-4.9,-3,-2.5,-1.3,-2.3,-1.1,-2.1,-3.1
Audeze LCD-2 (serial number 5325928)|0,-0.5,-1.6,-2.4,-3.7,-4.9,-4.5,-4.9,-5,-4.9,-4.4,-2.6,-2,-1.6,-0.4,-2.5
Audeze LCD-2 Classic|-0.5,-1.1,-1.8,-2.4,-3.4,-4.3,-4.2,-3.9,-5,-4.5,-2.3,-1.5,0,-0.2,-0.4,-1
Audeze LCD-2 Closed Back|0,-0.6,-2.5,-3.4,-4.8,-4.6,-2.7,-2.8,-3.7,-4.8,-5,-2.2,-0.4,-3.2,-1.9,-2.6
Audeze LCD-24|-0.9,-1.5,-2.2,-2.8,-3.9,-4.7,-4.4,-4.6,-5,-4.9,-3,-1.2,0,-1.8,-2.5,-2.5
Audeze LCD-3|-1.1,-1.5,-2.1,-2.9,-4.1,-5,-4.6,-4.6,-4.8,-4.2,-3.4,-1.6,-0.1,0,-0.6,-2.9
Audeze LCD-3 (Fazor)|0,-1.2,-2.5,-3.1,-4.1,-5,-4.7,-4.9,-4.5,-4.6,-4.6,-2.9,-3.2,-4,-1,-3.1
Audeze LCD-3 (Rev 2)|-0.6,-1,-2.1,-2.7,-3.9,-5,-4.6,-5,-4.4,-4,-3.9,-2.7,-1.6,-0.4,0,-2.7
Audeze LCD-3 (serial number 2312260)|-0.1,-1,-1.9,-2.6,-4,-5,-3.6,-4.9,-3.6,-1.7,-0.5,-0.1,-0.9,-0.9,0,-1.6
Audeze LCD-3 (serial number 2312341)|-0.2,-1.1,-1.9,-2.8,-4.3,-5,-4.2,-4.9,-4,-2.1,-0.5,-1,-2.5,0,-0.4,-1.8
Audeze LCD-3 (serial number 2312454)|-0.5,-0.9,-2.2,-2.9,-4,-5,-4.8,-4.7,-4.8,-3.6,-2.5,-2.4,-3.7,-0.5,0,-2.6
Audeze LCD-3 (serial number 2312488)|-0.8,-1.3,-2.2,-2.9,-4,-5,-4.5,-4.7,-4.5,-3.3,-2.3,-1.8,-3.8,0,-0.6,-1.7
Audeze LCD-3 (serial number 2717210)|0,-0.7,-1.4,-2.4,-3.8,-5,-4.7,-4.7,-4.3,-3.8,-4,-1.6,-1.5,-1.3,-0.6,-2
Audeze LCD-4|-1.1,-1.5,-2.3,-3,-4,-4.8,-4.5,-4.6,-4.9,-5,-3.3,-1.7,0,-0.6,-0.9,-3
Audeze LCD-4z|-1.4,-1.6,-2.1,-2.5,-3.5,-4.1,-4,-4.2,-4.6,-5,-3.6,-2.2,0,-1.5,-0.9,-2.1
Audeze LCD-5|-0.2,-0.9,-2.1,-2.6,-3.8,-4.6,-4.3,-4.2,-4.5,-5,-4.7,-3.4,-4.5,-3.3,0,-0.9
Audeze LCD-GX|0,-0.9,-2,-2.6,-3.8,-4.7,-4.5,-3.9,-5,-3.9,-2.1,-2,-0.4,-0.2,-0.2,-1.3
Audeze LCD-i3|-0.1,-0.2,-0.8,-1.6,-2.7,-3.4,-3.7,-4,-4.5,-5,-4,-0.4,0,0,-2.4,-4.2
Audeze LCD-i3 (Cipher bluetooth)|-1.5,-1.5,-1.8,-2.5,-3.6,-4.1,-4.1,-4.2,-5,-4.5,-4.5,-1.6,-0.3,0,0,-1.7
Audeze LCD-i4|-0.2,-0.4,-1,-1.7,-2.9,-3.6,-3.8,-4.2,-5,-4.9,-4.7,-0.1,0,0,-1.7,-4.7
Audeze LCD-MX4|-1,-1.3,-1.9,-2.5,-3.6,-4.5,-4.4,-4.3,-5,-4.7,-2.9,-1.7,0,-1.9,-0.5,-1.4
Audeze LCD-R|0,-0.7,-1.3,-2.2,-3.6,-4.7,-4.3,-4.1,-5,-3.8,-4,-2.8,0,-0.6,-1.2,-2.8
Audeze LCD-R (bass boost)|-1.3,-1.9,-2.2,-2.8,-3.9,-4.7,-4.3,-3.9,-5,-3,-4.1,-2.3,0,-0.3,-0.8,-3.1
Audeze LCD-X|-1.3,-1.6,-2.3,-3.2,-4.6,-5,-4.9,-4.8,-4.4,-3.6,-1.1,-0.6,0,-1.1,-3.3,-3.4
Audeze LCD-X (2021)|-1.5,-1.9,-2.5,-3.1,-4.1,-4.9,-4.7,-4.5,-5,-3.7,-3.1,-2.4,0,-1.5,-2.1,-3.3
Audeze LCD-X (Dekoni sheepskin Earpads)|-0.5,-0.9,-1.8,-2.7,-4.1,-5,-4.5,-4.1,-4.4,-4.5,-2.8,-1.8,0,-3.3,-2.1,-3.6
Audeze LCD-X (pre-2021)|-1.4,-1.8,-2.4,-3,-3.9,-4.7,-4.6,-4.4,-5,-4.4,-2.4,-1.1,0,-3.1,-1.9,-2.7
Audeze LCD-X (pre-2021) (worn earpads)|0,-1,-3.6,-4.4,-5,-4.7,-4.8,-4.5,-4.7,-4.3,-3.2,-2.8,0,-0.1,-0.5,-1.9
Audeze LCD-X (sample 3)|-0.6,-2,-2.2,-2.8,-4,-5,-4.2,-4.5,-4,-3.2,-2.6,-2.1,-0.7,-2.3,0,-2.6
Audeze LCD-X (serial number 7454971)|-1.1,-1.3,-2.2,-3,-4.1,-5,-4.4,-4.5,-3.8,-3.8,-3.4,-2.4,-2.7,0,-0.3,-3
Audeze LCD-X (serial number 7456406)|0,-1.2,-1.9,-2.8,-4.1,-5,-4.7,-4.5,-3.8,-3.2,-2.8,-2.4,-2.7,-4.2,-1.6,-2.5
Audeze LCD-XC|0,-1,-1.7,-2.4,-3.1,-3.6,-2.7,-2.9,-3.9,-4.2,-5,-3.9,-1.8,-2.8,-2.7,-3.6
Audeze LCD-XC (pre-2021)|-0.2,-0.9,-2.2,-3.2,-4.3,-3.9,-2.2,-1.4,-2.3,-4.1,-5,-2.8,0,-2.6,-1.4,-3.6
Audeze LCD-XC (sample 1)|-1,-1.4,-2.6,-3.1,-4,-3.9,-2.6,-2.5,-2.5,-4.1,-5,-2.5,-4.9,-1.1,0,-3.3
Audeze LCD-XC (sample 2)|0,0,-1.8,-2.2,-2.7,-3,-2.5,-3,-3.2,-4.3,-5,-2.9,-2.9,-5,-1,-3.3
Audeze Maxwell|0,-1.5,-1.5,-1.1,-1.3,-1.1,-2.4,-1.9,-1,-1.6,-2.2,-4,-0.4,-1.8,-2.1,-2.8
Audeze MM-500|-0.7,-1.2,-2.1,-2.8,-4.1,-5,-4.6,-4.2,-4.8,-5,-4.1,-4.2,-3.3,-2.8,0,-1.4
Audeze Mobius|-4.6,-4.5,-4.1,-4.2,-4.8,-4.4,-2.6,-1.9,-2.5,-2.7,-3.1,-4.3,-3.8,0,-2.8,-5
Audeze Mobius (ballistics preset)|-2.2,-3.1,-3.7,-3.9,-4.6,-5,-3.5,-2.9,-2.6,-3.1,-2.9,-2.6,-2.2,0,-0.2,-2.5
Audeze Mobius (default preset)|-3.3,-3.9,-4,-4.3,-5,-4.2,-2.8,-2.1,-2.6,-3.2,-3.4,-3,-1.9,-1,0,-4.1
Audeze Mobius (flat preset)|-2.3,-2.8,-3.4,-3.9,-4.9,-5,-1.9,-1.8,-3,-4.1,-4,-3.9,-3.4,-1.1,0,-3.3
Audeze Mobius (footsteps preset)|-0.4,-1.5,-2.3,-3,-4.6,-5,-3,-2.1,-2.1,-2.9,-2.6,-2.3,-1.5,0,-0.3,-2.8
Audeze Mobius (music preset)|-2,-2.9,-3.2,-3.5,-4.6,-5,-2.7,-2.2,-2,-2.5,-2.2,-1.8,-1.4,-0.3,0,-3.1
Audeze Mobius (racing preset)|-2.1,-2.6,-3.2,-3.6,-4.5,-5,-3.1,-2.3,-2.1,-2.8,-2.6,-2.5,-1.9,0,0,-2.5
Audeze Mobius (RPG preset)|-1.2,-2.3,-3.4,-4,-4.8,-5,-3,-2.3,-2.1,-3.9,-4.5,-4.7,-3.6,-0.6,0,-2.9
Audeze MX-4|-1.4,-1.8,-2.2,-2.9,-3.6,-4.1,-3.8,-4,-4.6,-5,-3.5,-2.8,-1.5,-0.5,0,-0.3
Audeze Penrose|-1.3,-1.9,-2.2,-2.2,-2.3,-2.4,-2.9,-3.5,-2.7,-3.2,-3.8,-2.2,-2.2,0,-3.4,-5
Audeze SINE|-0.3,-0.9,-1.8,-2.4,-3.5,-4,-3.8,-3.6,-3.3,-3.8,-3.8,-3.6,-3.6,0,-2.6,-5
Audeze SINE (Cipher cable)|-2.3,-2.9,-3.9,-4.4,-5,-4.8,-4.4,-4.1,-3.7,-4.3,-4.3,-4.2,-4.1,0,-2.7,-4.9
Audeze SINE (MSR-7 earpads)|0,-0.1,-1.5,-2.1,-3.4,-3.8,-3.1,-2.5,-2.2,-3.2,-3.1,-3.3,-3.1,-1.3,-1.8,-5
Audeze SINE DX|-1.5,-1.8,-2.1,-2.7,-3.7,-4.4,-4.2,-4.2,-4.3,-5,-4.8,-2.2,-1.7,0,-0.7,-2.4
Audient EVO SR2000|0,-3.4,-4.7,-4.2,-5,-4.2,-2.7,-4.5,-5,-4.8,-3.4,-1.8,-0.9,-3.6,-2.6,-4.4
Audio Genetic AG2|-1.5,-1.9,-2.6,-3.4,-4.6,-5,-4.7,-4.1,-3.1,-2.8,-2.6,-0.9,0,0,-0.6,-0.1
Audio Zenith PMx2 (v1)|0,-0.6,-1.8,-2.7,-4.2,-4.9,-4.1,-4.3,-5,-4.5,-3.3,-1.4,-1.9,-2.9,-2.4,-2.2
Audio Zenith PMx2 (v2)|0,-0.6,-1.6,-2.4,-3.7,-4.3,-3.5,-4.2,-5,-3.7,-1.6,-1,-1.4,-1.6,-1.3,-1.5
Audio-Technica ATH-A1000X|-2,-3.4,-3.8,-3.7,-5,-4.1,-3.7,-4.1,-4.2,-4.6,-3.5,-2.9,-0.6,-0.7,0,-3
Audio-Technica ATH-A1000Z|-0.6,-1.8,-3,-4.2,-5,-3.8,-3.1,-3.1,-3.4,-3.5,-3.1,-1.8,-1.3,-1.3,0,-1.5
Audio-Technica ATH-A2000X|-0.2,-0.1,-2.3,-2.7,-2.7,-1.9,-2.3,-2.7,-3.4,-4.3,-5,-3.3,-1.6,-2.6,0,-3
Audio-Technica ATH-A2000Z|0,-1.3,-3,-4.1,-5,-3.8,-2.8,-3.3,-3.7,-3.9,-4.7,-2.3,-1.3,-0.7,0,-2
Audio-Technica ATH-A55|0,0,0,0,0,-1.8,-5,-4.3,-4.3,-4.7,-3.1,-3.4,-3,-3.8,-3.9,-3.2
Audio-Technica ATH-A700|0,-0.2,-1.6,-2.3,-2.9,-2.5,-2,-4.3,-4,-3.9,-2.7,-1,-1.4,-2.3,-4.4,-5
Audio-Technica ATH-A900|0,-0.9,-1.1,-1.9,-2.3,-2.1,-5,-5,-4.5,-4.7,-3.7,-2.7,-3.7,-1.5,-0.2,-2.4
Audio-Technica ATH-A900X|-1,-2,-2.8,-4.1,-5,-3.9,-3.7,-3.9,-3.4,-3.7,-3.1,-1,0,-1.2,-0.1,-1.4
Audio-Technica ATH-A990Z|-0.4,-2.1,-3.6,-4.3,-5,-4.2,-3.5,-3.6,-3.2,-3.1,-1.5,-0.9,-0.5,-0.1,0,-1.3
Audio-Technica ATH-AD1000X|0,-0.7,-1.5,-2.9,-4.3,-5,-4.7,-4.4,-4.2,-4.3,-3.3,-0.5,-4.5,-1.2,-1.9,-1.8
Audio-Technica ATH-AD2000|0,-0.2,-1.1,-2.2,-3.9,-5,-4.7,-4.6,-4.6,-4.7,-3.4,-0.3,-4.2,-2.5,-3,-2.8
Audio-Technica ATH-AD2000X|-0.1,-0.4,-1.5,-2.3,-3.7,-4.6,-4.4,-4.7,-4.7,-5,-3.6,0,-4.1,-1.1,-1.1,-1.9
Audio-Technica ATH-AD500|0,0,-1,-2.5,-3.8,-4.4,-4,-4.1,-4.1,-5,-4.1,-2.1,-1.9,-2.7,-2.8,-3
Audio-Technica ATH-AD500X|-0.5,-1.4,-2.9,-3.7,-4.6,-5,-4.4,-4.3,-4.3,-4.6,-3.6,-0.5,0,-0.1,0,-1.2
Audio-Technica ATH-AD700|0,0,-0.3,-1.7,-3.2,-4.2,-3.8,-4,-4.1,-5,-4.2,-3.6,-4.3,-3.3,-1.1,-3
Audio-Technica ATH-AD700X|0,0,-1.8,-2.9,-4.2,-4.7,-4.3,-4.4,-4.5,-5,-4.5,-0.8,-1.6,-0.5,-1.8,-4.2
Audio-Technica ATH-AD700X (2013)|0,0,-1.3,-2.3,-3.6,-4,-3.8,-3.8,-4,-4.7,-4.6,-1,-2.2,-2.2,-3.7,-5
Audio-Technica ATH-AD900|0,0,0,-0.7,-2.4,-3,-2.9,-3.1,-3,-3.7,-4.1,-1.8,-5,-4.8,-2.3,-1.8
Audio-Technica ATH-AD900X|0,-0.1,-2.1,-3.2,-4.3,-5,-4.6,-4.4,-4.5,-5,-4.4,-1.3,-0.8,-1.6,-0.2,-1.8
Audio-Technica ATH-ADX5000|0,-1.1,-2.6,-3.5,-4.6,-4.4,-3.4,-2.7,-3.4,-4.2,-2.6,-1.3,-5,-4.1,-3.5,-4.8
Audio-Technica ATH-ADX5000 (Dekoni elite sheepskin earpads)|-0.4,-1.3,-2.7,-3.7,-4.8,-5,-4.4,-4.4,-4.5,-5,-3.3,0,-2.5,-2.4,-0.1,-2.2
Audio-Technica ATH-ADX5000 (Dekoni fenestrated sheepskin earpads)|0,-0.6,-1.7,-2.6,-3.4,-3.6,-2.9,-2.6,-3,-3.3,-2.9,-2,-5,-3.1,-2.2,-3.8
Audio-Technica ATH-ADX5000 (Yaxi A2000Z,AD2000X earpads)|0,-0.8,-2.1,-2.8,-3.7,-3.7,-2.9,-2.4,-2.8,-3.4,-2.9,-1.7,-5,-2.5,-2.2,-3.8
Audio-Technica ATH-ANC23|0,-0.2,-1.3,-2.5,-4.2,-5,-5,-4.3,-2.6,-1.7,-2.6,-0.7,-2.3,-3.8,-4.4,-1.8
Audio-Technica ATH-ANC300TW|0,-0.9,-2.4,-3.9,-5,-4.4,-4.2,-3.6,-0.8,-1.5,-3.3,-1.7,-0.4,-3.7,-3.8,-0.9
Audio-Technica ATH-ANC33iS|0,-0.7,-1.9,-2.9,-4.3,-5,-5,-4.2,-2.2,-0.6,-3.1,0,-1.2,-2.8,-2.6,-1
Audio-Technica ATH-ANC50iS (passive)|-2.9,-3.4,-3.6,-4.1,-4.6,-5,-4.3,-4.1,-1.7,-1.6,0,0,-0.7,0,0,0
Audio-Technica ATH-ANC70|0,0,-0.6,-1.4,-2.4,-2.8,-2.2,-1.5,-1.9,-5,-4.3,-4.1,-3.3,-0.4,-1.5,-3.8
Audio-Technica ATH-ANC7B|-1.9,-2.6,-3.5,-3.9,-4.4,-4.3,-3.6,-3.8,-4.5,-5,-1.4,0,-1.5,-1.4,-1.2,-2.7
Audio-Technica ATH-ANC9|-3.7,-3.2,-3,-3.6,-4.4,-2.9,-1.8,-1.5,0,-2.1,-3.1,-3,-2.9,-5,-3.4,-3.6
Audio-Technica ATH-AWAS|0,0,-0.9,-2.8,-2.9,-5,-4.4,-4,-3.4,-3.7,-4.3,-2.8,-1.8,-2.4,0,-1
Audio-Technica ATH-AWKT|0,-0.5,-1.2,-2.1,-2.4,-2.6,-3.2,-3.3,-3.3,-4.6,-5,-2.3,-2.6,-2.9,0,-1.5
Audio-Technica ATH-CK2000Ti|-3.8,-3.8,-3.9,-4.4,-5,-4.1,-3,-2,-1.1,-1.1,-0.2,0,-3.9,-3.6,-1.3,-0.1
Audio-Technica ATH-CK3TW|-4.2,-4.2,-4.3,-4.7,-5,-4.4,-3.7,-2.6,-1.6,-1.1,-1.3,-0.8,0,0,-2.7,-3.4
Audio-Technica ATH-CKM500|-1.4,-2.1,-3.1,-4,-5,-4.9,-4,-2.7,-0.6,0,-0.4,-1.5,-3.1,-2.8,-2.8,-2
Audio-Technica ATH-CKN50|-0.3,-1.3,-2.7,-3.9,-5,-4.7,-3.8,-2.6,-1.5,-1.3,-1.1,0,-4,-4.5,-4.6,-2.8
Audio-Technica ATH-CKR7TW|-2.9,-2.6,-2.3,-2.4,-2.6,-2.2,-1.7,-1.3,-1,-1.6,-2.6,-4.4,-2.4,-5,-2.4,0
Audio-Technica ATH-CKS5TW|-4.9,-4.7,-4.7,-4.9,-5,-4.3,-3.5,-2.6,-1.7,-1.6,-2.2,-1.7,-0.7,-1.2,0,0
Audio-Technica ATH-CKX5iS|-3.6,-3.3,-3.4,-3.7,-4,-3,-1.8,-1,0,-0.8,-1.5,-2.3,-3.7,-5,-2.6,-4
Audio-Technica ATH-CT200iS|0,0,0,0,0,0,0,-0.4,-1.1,-2.4,-4.2,-3.2,-3.1,-5,-4.2,-2.1
Audio-Technica ATH-DSR9BT|-1.6,-2.3,-3.6,-4.4,-5,-2.3,0,-1.7,-2.2,-2.6,-3.1,-4.8,-3.5,-2.2,-3.6,-4.4
Audio-Technica ATH-E70|-2.1,-2.3,-2.9,-3.6,-4.7,-5,-4.6,-4,-3,-2.8,-3,-0.6,0,0,-2.2,-0.9
Audio-Technica ATH-ES55|0,0,0,-0.8,-2.7,-3.9,-3.4,-3.5,-4,-5,-5,-2.8,-3.5,-0.4,-1,-4.1
Audio-Technica ATH-ESW9|0,0,-0.8,-1.5,-2.1,-2.8,-2.6,-2.3,-3.9,-5,-4.8,-2.9,-3.2,-1.7,-1.6,-3.1
Audio-Technica ATH-ESW9A|0,-1,-1.5,-2.2,-2.6,-3.3,-3.2,-2.8,-3.9,-5,-4.8,-2.9,-3.4,-1.6,-0.3,-0.9
Audio-Technica ATH-EW9|0,0,0,-0.8,-1.9,-2.4,-1.9,-1.7,-1.7,-1.7,-1.6,-4.9,-5,-1.5,-3,-1.9
Audio-Technica ATH-FC700|0,0,-0.2,-1.3,-2.5,-3,-2.9,-2.9,-2.4,-2.8,-3,-3,-2.4,-2.3,-4.6,-5
Audio-Technica ATH-FC700A|0,0,-0.5,-1.7,-2.2,-2.8,-2.8,-2.5,-2.4,-2.9,-2.7,-2.9,-3.2,-2.2,-4.9,-5
Audio-Technica ATH-G1|-3.3,-3.6,-3.9,-4.2,-5,-3.9,-1,-2.5,-2.8,-2.5,-1.7,0,-1,-0.3,-0.9,-2
Audio-Technica ATH-HL7BT|0,-0.9,-1.7,-3.3,-4.5,-5,-4.6,-4.1,-4.1,-4.2,-2.4,-0.1,-2.2,-3.1,-1.8,-2.3
Audio-Technica ATH-IEX1|-3.5,-3.3,-3.2,-3.3,-3.5,-2.6,-1.8,-1.1,-1.2,-3.4,-5,-3.2,-3.2,0,-2,-2.8
Audio-Technica ATH-IM01|-2.1,-2.5,-3,-3.7,-4.7,-5,-4.6,-3.8,-2.6,-2,-1.4,-0.5,-0.9,0,-1.2,0
Audio-Technica ATH-IM02|-1.1,-1.4,-2.2,-3.2,-4.5,-5,-4.7,-4.1,-3.2,-2.9,-3.3,-2.8,0,-0.5,-1.4,-2.8
Audio-Technica ATH-IM03|-2.3,-2.8,-3.3,-4,-4.9,-5,-4.6,-3.8,-2.7,-2.4,-2.3,-1.6,-0.4,0,-1,0
Audio-Technica ATH-L5000|-1.5,-2.1,-2.4,-3.2,-3.9,-3,-3.2,-3.5,-3.3,-4.7,-5,-1.7,-4.4,-2.1,0,-1.7
Audio-Technica ATH-LS200|-1.4,-1.7,-2.6,-3.4,-4.7,-5,-4.5,-3.8,-2.8,-2.7,-3.2,-3.6,-1.1,-2.4,0,-0.3
Audio-Technica ATH-LS300|-3.8,-3.7,-3.8,-4.2,-4.9,-5,-4.7,-4.1,-3.2,-3.1,-3.3,-1.5,-0.5,0,0,0
Audio-Technica ATH-LS400|-2.4,-2.6,-3.1,-3.8,-4.9,-5,-4.6,-3.8,-2.7,-2.2,-1.7,-2.3,-1.8,0,0,0
Audio-Technica ATH-LS50|-3.7,-3.9,-4.1,-4.6,-5,-4.4,-3.6,-2.5,-1.4,-1.9,-4.3,-1.7,-0.2,-1.8,0,0
Audio-Technica ATH-M10|0,0,-0.2,-1.8,-3.6,-4.6,-4.6,-4.8,-5,-4.3,-3.3,-0.4,0,0,-3,-3.5
Audio-Technica ATH-M20x|-1,-2.5,-4,-4.5,-4.5,-2.4,-3.1,-3.7,-4.1,-4.5,-5,-4.6,-0.8,0,-2.6,-3.4
Audio-Technica ATH-M30|-1.5,-3,-3.8,-4.5,-4.3,-3,-4.4,-4.5,-4.3,-4.4,-5,-5,-4.7,0,-1.8,-4
Audio-Technica ATH-M30x|-2.2,-2.6,-2.6,-2.8,-2.8,-0.9,-1.7,-2.3,-2.4,-2.5,-3.2,-3.3,-1.4,0,-3.1,-5
Audio-Technica ATH-M40|-3.2,-4.6,-3.9,-4.9,-4.8,-4.7,-4.2,-3.7,-3.9,-3.6,-3.1,-2.1,-5,-1.7,0,-3.8
Audio-Technica ATH-M40x|-1.4,-2.6,-3.2,-3.8,-4.6,-2.5,-0.8,-1,-1.4,-2.2,-2.4,-1,-0.9,0,-2.8,-5
Audio-Technica ATH-M50|-2,-2.8,0,-3.7,-5,-3,-1.2,-2.6,-3.5,-3.5,-3.6,-2.8,-3.3,-1.5,-2.4,-3.8
Audio-Technica ATH-M50x|-2.5,-2.8,-2.8,-4.4,-5,-2.2,0,-1.5,-2.3,-2.1,-1.5,-2.3,-0.5,-1,-0.8,-2.8
Audio-Technica ATH-M50x (Dekoni sheepskin earpads)|-3.6,-4.2,-4.5,-4.5,-5,-4.4,-2,-3.5,-3.2,-1.9,-1.1,-1.8,0,-0.7,-2.2,-4.8
Audio-Technica ATH-M50x (Massdrop velours earpads)|-1.2,-1.7,-1.5,-1.6,-2.4,-0.1,0,-1.3,-1.4,-1.3,-1.8,-3,-1.8,-2.2,-2.6,-5
Audio-Technica ATH-M50xBT|-2.3,-2.3,-3.8,-4.3,-5,-3.5,-0.8,0,-1.5,-2.7,-3,-3,-1.9,-2.4,-2,-3.3
Audio-Technica ATH-M50xBT2|-0.8,-1.2,-1.7,-2.3,-2.3,-0.1,0,-1.2,-1.7,-2.2,-1.9,-1.7,0,-0.4,-3.1,-5
Audio-Technica ATH-M60x|-1.2,-1.9,-3.6,-4.1,-4.6,-4,0,-0.2,-3.3,-5,-5,-2.7,-1.2,-3.4,-1.3,-0.7
Audio-Technica ATH-M70x|0,-0.1,-1.3,-4.5,-4.5,-4.1,-3.6,-3,-1.5,-2.8,-3.5,-3.9,-3.5,-5,-4.1,-2.7
Audio-Technica ATH-MSR7|0,-0.9,-2,-2.8,-3.9,-4.5,-3.1,-2.4,-3.7,-4.9,-5,-2.5,-2.7,-3.2,-1.4,-0.8
Audio-Technica ATH-MSR7b|-1.4,-3,-4.1,-4.7,-5,-1.5,-2.1,-2.9,-3.5,-4.1,-4.4,-1.8,0,-2.8,-2.3,-2.2
Audio-Technica ATH-MSR7NC|0,-0.3,-1.4,-2.5,-3.6,-3.5,-0.5,-0.8,-2,-3.3,-4.7,-4.9,-1.7,-4.3,-5,-4.2
Audio-Technica ATH-MSR7NC (ANC on)|0,-0.8,-1.6,-2,-3.5,-3.8,-1.7,-0.9,-2.2,-4.2,-5,-3.5,-3,-4.1,-2.2,-1.6
Audio-Technica ATH-MSR7NC (passive)|0,-0.8,-1.7,-2.1,-3.7,-3.9,-1.3,-0.4,-2.4,-4.3,-5,-3.3,-2.6,-3.8,-2.3,-0.7
Audio-Technica ATH-PRO700|0,0,-0.2,-0.2,-0.3,0,-2.4,-4.9,-5,-5,-3.2,-1.1,-2,-1.5,-0.9,-2.7
Audio-Technica ATH-R70x|0,-1.5,-2.7,-3.6,-4.9,-5,-4.3,-4,-3.6,-3.8,-4.1,-3.7,-3.6,-1,-1.8,-2.7
Audio-Technica ATH-SPORT7TW|-1.1,-2.8,-3.9,-4.6,-5,-3.3,-2.3,-1.8,-1,-0.9,-1.6,-1.9,0,-2.6,-1.9,-0.1
Audio-Technica ATH-W1000X|0,-1.2,-3.6,-4.5,-5,-4.8,-3.7,-3,-3.8,-4.1,-4,-2.9,-2.5,-0.9,-0.6,-3.1
Audio-Technica ATH-W5000|0,-0.5,-2.3,-2.9,-3.8,-4.4,-3.8,-3.4,-4.1,-5,-5,-3.8,-2.5,-1.8,0,-1.3
Audio-Technica ATH-W5000 2013|0,-0.1,-2.2,-3.8,-4.5,-4.7,-3.8,-1.8,-3.3,-5,-4.5,-2.4,-0.9,-3.4,-2.1,-4.5
Audio-Technica ATH-WS55|-2.7,-3.4,-3.9,-4.1,-5,-4.3,-3.5,-2.9,-3.3,-3.5,-2.9,-0.1,-0.1,0,-1.8,-2.5
Audio-Technica ATH-WS99|-1.5,-2.3,-3.3,-4.1,-5,-5,-3.8,-2.6,-1,-3,-3.2,-0.1,0,-2.9,-1,-0.3
AudioFly Adagio Club Life|0,-0.6,-1.8,-2.9,-4.3,-5,-4.7,-4.3,-3.9,-4.7,-4.2,-1.8,-3.2,-0.4,-2.3,0
AudioFly AF140|-2.4,-2.7,-3.4,-4.1,-5,-4.8,-4,-3,-1,-1.7,-2.4,-2.2,0,-0.6,0,-2.8
AudioFly AF160|-1,-1.3,-2,-3,-4.3,-5,-4.9,-4.9,-4.3,-4.6,-4.2,-2.8,-3.8,0,0,0
AudioFly AF180|-1.8,-2.1,-2.7,-3.5,-4.5,-5,-4.8,-4.6,-3.8,-3.7,-2.6,-0.8,-1.7,0,-0.9,-2.2
AudioQuest NightHawk|-1.8,-1.6,-2.8,-4,-4.8,-5,-4.4,-3.5,-2.4,-0.4,-0.8,-0.2,0,-3.2,-0.9,-2.3
AudioQuest NightHawk (Brainwavz HM5 hybrid earpads)|0,0,-0.1,-0.8,-2.1,-2.9,-2.9,-2.8,-2.1,-0.8,-1.6,-2,-2.6,-5,-1.7,-2.9
AudioQuest NightHawk (Shure SRH1540 alcantara earpads)|-1.7,-1.7,-2.1,-3,-4.6,-5,-4.7,-3.9,-2.9,-0.8,-1.5,-1.6,-1.9,-4.4,0,-2.4
AudioQuest NightOwl|-2.4,-2.2,-2.8,-4.1,-5,-4.7,-3.9,-3.7,-3.8,-3.2,-0.8,0,-0.6,-0.6,-0.9,-1.6
Audiosense AQ3|-5,-4.2,-3.6,-3.9,-4.6,-4.6,-3.9,-3.1,-1.9,-1.4,-1.4,-2.3,-0.9,-1.6,0,-1.3
Audiosense AQ4|-5,-4.6,-4.4,-4.6,-4.9,-4.5,-4,-3.4,-2.6,-2.3,-2.9,-3,-1.4,-1.6,0,0
Audiosense AQ7|-3.5,-3.3,-3.1,-3.2,-3.3,-2.8,-2.4,-2.4,-3.3,-5,-4.9,-4.1,-1.4,-1.3,0,-0.2
Audiosense DT100|-2.9,-3.1,-3.4,-4,-4.9,-5,-4.6,-3.9,-3,-2.6,-2.5,-2.8,-1.4,-1.7,0,0
Audiosense DT200|-2.8,-2.9,-3.2,-3.8,-4.9,-5,-4.5,-3.7,-2.6,-2.3,-2.5,-3.2,-1.8,-0.9,0,-2.2
Audiosense DT300|-3.5,-3.5,-3.7,-4.1,-4.8,-5,-4.6,-3.9,-3,-2.5,-1.8,-0.8,-0.6,0,-0.9,-3
Audiosense DT600|-5,-4.5,-3.7,-3.5,-3.6,-4.2,-4.2,-3.8,-3,-2.8,-2.5,-2.9,-1.6,-1.4,0,0
Audiosense DT600 (80 ohm resistor)|-4.8,-3.4,-1.3,-0.3,0,-0.9,-1.2,-1,-0.8,-2.4,-4.1,-4.2,-1.7,-2.7,-0.8,-3.8
Audiosense T180|-0.3,-0.6,-1.5,-2.4,-3.7,-4.1,-3.8,-3.2,-2.5,-2.2,-1.7,-2.6,-2.1,-5,-2.8,0
Audiosense T180 Pro|-2.4,-2.5,-2.9,-3.7,-4.8,-5,-4.6,-3.9,-3.2,-3.2,-3,-4.3,-3.7,-2.6,-0.5,0
Audiosense T800|-4.8,-4.5,-4.4,-4.6,-5,-4.6,-4,-3.3,-2.5,-2.7,-3.4,-3.4,-4.9,-1.8,0,-2.2
AuGlamour F300|-4.1,-4.1,-4.3,-4.6,-5,-4.2,-3.4,-2.5,-1.8,-2,-2.3,-2.8,-3.1,-2.6,0,-0.7
AuGlamour RT-3|-3.6,-3.5,-3.8,-4.4,-5,-4.1,-2.9,-1.5,0,-0.5,-1.1,-2.3,-2.8,-3.6,-0.9,-2
AuGlamour T100|-3.4,-3.4,-3.8,-4.3,-5,-4.7,-3.8,-2.7,-1.5,-1.1,-0.9,-0.8,0,-2.5,-2.3,-1.4
AUKEY EP-B44|-5,-4.3,-3.7,-3.7,-3.6,-3,-2.2,-1.5,-0.9,-1.1,-1.4,-2.6,-1.5,0,-2.8,-2.9
AUKEY Latitude|-3.2,-3.2,-3.6,-4.2,-5,-4.8,-3.9,-2.7,-1.5,-2.1,-1.2,0,-3.3,-2.7,-4.8,-4.4
AUKEY Latitude (balanced)|-2.5,-3,-3.7,-4.4,-5,-4.2,-3.1,-1.9,-0.7,-0.6,-0.3,0,-3.3,-3.3,-3,-0.7
AUKEY Latitude (bass)|-3.2,-3.6,-4.1,-4.6,-5,-4.5,-3.6,-2.7,-1.8,-1.5,-0.8,0,-0.2,-2.4,-3.2,-1.8
AUKEY Latitude (treble)|0,-0.3,-0.8,-1.4,-2.1,-2.2,-1.9,-1.1,-1.2,-2.3,-2.2,-2,-5,-4.1,-1.6,0
AUNE AR5000|-1.3,-1.8,-2.5,-3.2,-4.3,-5,-4.4,-3.8,-3,-2.5,-1.1,-2.3,-0.9,-3.8,0,-1.6
AUNE Jasper|-2.3,-2.5,-3.1,-4,-5,-4.8,-4.1,-3.3,-2.5,-3.1,-4.2,-5,-4,-3.9,0,-1.5
Aure Audio Elixir|-2,-2.1,-2.7,-3.5,-4.4,-4,-3.2,-2.4,-2.4,-4,-5,-4.8,-1.3,-2.5,0,-0.3
Aure Audio Elixir (foam eartips)|0,-0.3,-1.3,-2.6,-4.1,-3.5,-2.2,-1,-1.3,-4.6,-4,-4.9,-1.4,-2.2,-2.2,-1
Aure Audio Flame|-2,-2.2,-2.9,-3.8,-5,-4.6,-3.7,-2.8,-2.5,-3.9,-4.5,-3.3,-0.8,0,0,-0.7
Aure Audio Flame (foam eartips)|0,-0.2,-1,-2.2,-3.5,-2.8,-1.7,-0.8,-1.5,-5,-4.1,-3.7,-1.8,-1.9,-3.5,-1.8
Aure Audio Storm|-1.7,-2.1,-2.7,-3.8,-4.4,-3.4,-2.9,-2.9,-2.3,-2,-4,-3.4,0,-3.4,0,-3.8
Aurex HR V9|0,-0.7,-1.7,-2.3,-3.7,-4.4,-4.1,-4.3,-4.4,-5,-2.5,-3.8,-3.9,-2.4,-2.2,-3.3
Auribus Acoustics Everest|-0.9,-1.4,-2,-2.5,-3.5,-3.9,-3.5,-3.7,-4.4,-5,-3.5,-4.9,-1.9,0,-1.8,-2.8
Aurisonics Kicker|-4.5,-4.4,-4.4,-4.7,-5,-4.4,-3.7,-2.9,-2,-2.1,-3,-2.2,0,-2.5,-0.5,0
Aurisonics Rockets|-1.8,-2,-2.6,-3.5,-4.5,-5,-4.7,-4.4,-3.7,-3.6,-3.2,-1.9,-0.4,0,0,-1
Aurorus Australis|0,-0.7,-1.9,-2.9,-4.2,-4.6,-4,-4.4,-4.8,-5,-4.1,-3.1,-3.5,-2.5,-0.7,-0.1
Aurorus Borealis|-0.5,-1.5,-2.5,-3,-3.6,-4,-3.5,-3.6,-4.1,-5,-3.4,-2.4,-2.1,-2.5,-0.5,0
Austrian Audio Hi-X55|-1.2,-1.2,-1.9,-3.8,-5,-3.8,-2.4,-1.9,-2.8,-4.7,-3.2,0,-1.4,-1.8,-2.4,-2.5
Austrian Audio Hi-X60|-3.6,-2,-0.9,-2.8,-4.5,-4.1,-3.2,-3.3,-4,-4.2,0,-3.5,-1.2,-5,-2.8,-3.2
Austrian Audio Hi-X65|-0.6,-0.9,-1.6,-2.5,-4,-5,-4.9,-4.9,-4.3,-2.7,0,-2.7,-2.5,-4.2,-4,-3.9
Avantone Planar|0,-0.4,-1.2,-2,-3.7,-4.1,-3.8,-4.4,-4.5,-5,-3,-2.7,-0.5,-1.5,-0.3,-1.4
Avantree HT5009|-2.5,-2.7,-3,-3.4,-3.8,-3.1,-0.5,0,-1.6,-2.9,-2.2,-3,-3.3,-2.7,-5,-3.2
AVIOT TE-D01b|-4.7,-4.5,-4.5,-4.7,-5,-4.5,-3.7,-2.7,-1.6,-1.4,-1.8,-2.3,-0.1,0,-1.2,-1.3
AVIOT TE-D01d|-5,-4.8,-4.7,-4.8,-5,-4.5,-3.7,-2.7,-1.7,-1.7,-2.2,-3,-0.7,0,-1.8,-1.9
Aya Siren|0,-0.3,-1.2,-2.4,-4.1,-4.9,-4.6,-3.9,-2.8,-2.7,-3.4,-5,-1.4,-1.2,0,-2.5
Aya Trident|-4.2,-3.4,-2.6,-2.6,-3.8,-5,-4.8,-4.1,-2.8,-2.6,-3.1,-4.4,-0.3,-3.8,0,-1.8
AZLA 01R Mk2|-3.5,-3.6,-3.8,-4.1,-4.6,-4,-3.2,-2.3,-1.5,-1.8,-2.8,-4.4,-2.8,-5,-1.9,0
AZLA Azel|-5,-4.5,-4,-3.9,-4.5,-3.1,-1.9,-0.8,0,-0.4,-1.1,-2.5,-0.5,-2.7,-1,-0.7
AZLA Horizon|-4.5,-4.1,-4,-4.5,-5,-4.2,-3,-1.7,-0.4,-0.6,-1.7,-2.6,-1.1,-3.4,-1.4,0
AZLA Orta|-3.4,-3.4,-3.7,-4.3,-4.9,-4.6,-3.9,-3,-2.4,-3,-4.6,-4.5,-1.5,-5,0,0
AüR Audio Aure|-4.7,-3.8,-2.9,-3.1,-3.2,-2.2,-1.6,-1.4,-2.1,-4.4,-5,-1.6,-1.2,-0.6,0,-2.9
Bamboo Forest Bird Jinghong|-2.5,-2.2,-1.9,-2.1,-2.7,-0.9,0.9,2.9,3.9,1.6,-2,-2.5,-2.2,-2.4,3.1,4.9
Bang & Olufsen Beoplay E8|1.7,1.6,2.5,2.3,-0.1,-3.6,-4,-2.6,-0.2,0.2,0.4,3.6,4.4,0.1,-6.7,-3.2
Bang & Olufsen Beoplay E8 2.0|-1.1,-1,0.2,0.5,-1.4,-4,-3.8,-1.8,0.9,1.3,1.4,4.4,5.8,3.1,-2.8,-0.7
Bang & Olufsen Beoplay Earset 3i|4.5,4.5,4.5,2.4,-1.7,-3.7,-4.1,-2.8,-3.5,-3.4,-2.5,3,1.7,-2,-0.5,1.7
Bang & Olufsen Beoplay Earset Wireless|5.9,3.4,-1.9,-3.4,-2.1,-1.6,0.1,2.5,3.9,4.6,3.4,5.9,-2.1,-7.9,-7.6,-6.6
Bang & Olufsen BeoPlay Ex|-0.5,-0.2,0.2,0,-2.3,-2.8,-1.9,-1.1,-0.2,0.3,3.1,-1.5,2.6,1.9,2.2,1.8
Bang & Olufsen Beoplay H3|1.9,0.8,-0.8,-2.5,-4.5,-3.9,-2.8,-1,0.9,1,1,1.5,0.3,-3.2,1.2,3.1
Bang & Olufsen Beoplay H6|-0.7,-0.7,0.5,1.7,0.5,0.6,1.9,0.2,-2.1,-4.4,-3.1,1.3,1.3,5.7,-0.1,0.2
Bang & Olufsen Beoplay H6 (2nd Gen)|-1.7,-1.2,-0.9,-1.3,-0.4,4.9,2.3,0.5,-0.8,-1.4,-0.9,-1.2,3.9,6.6,-2,-2.8
Bang & Olufsen BeoPlay H7|-0.5,0.3,0.2,-1.5,-3,-1.4,2.1,2.5,-0.8,-4.8,-3.6,2.9,5.9,4.1,2.1,-2
Bang & Olufsen Beoplay H9|-5,-2.7,-3.1,-3.9,-4,-2.6,0.2,4,6.3,4.4,4.2,0.8,1,3.7,3,-0.1
Bang & Olufsen Beoplay H9 (Gen 1)|-4.5,-2.5,-1.4,-1,-1,0.8,3.8,6.4,7.2,4,2.5,-2.2,-3.3,1.9,-0.4,-5.3
Bang & Olufsen Beoplay H95|-4,-3.9,-1.3,1.8,-0.1,-2,-0.5,0.9,3.1,1.4,3,4.3,3.1,1,2.1,-1
Bang & Olufsen Beoplay H9i|-5.6,-2.2,-0.4,-0.2,0.3,1.1,2.5,2.7,1.2,-3.2,-5.4,4.1,1.3,7.7,4.8,1.2
Bang & Olufsen Beoplay Portal|4.5,3.8,1.2,2.7,-1.1,-2.1,-1.3,1,1,-2.3,-5.3,-4.9,-3.1,-2.1,1.6,3.8
Bang & Olufsen E8 3.0|2.2,1.9,3.2,4.4,2.5,-2.2,-2.3,-0.4,2.3,0.6,1.2,5.5,3.8,0.6,-9.1,-7
Baseus Bowie (original EQ)|-3.5,-3.5,-3.7,-4.4,-4.7,-3.3,-1.1,1.3,3.6,3.9,4,2.6,0.3,-1.2,3.5,2.1
Baseus M2s|-0.7,-1.3,-1.4,-1.6,-1.6,-0.8,-0.2,0.8,1.8,0.5,-0.7,-2.3,-2.7,-4,2,5.2
BASN Bmaster|-2.5,-2.3,-2.5,-3.2,-4,-2.9,-1.4,-0.2,0.3,-1,-1.9,3,6.4,6.3,2.8,4.8
BASN Bmaster5|0.8,0.5,-0.4,-1.7,-3.4,-2.9,-1.7,-0.1,1.9,2.3,2.1,-0.6,2.3,-0.5,1.2,-0.2
BASN MMCX|-0.3,-1.3,-2,-3,-3.7,-2.9,-1.9,-0.8,-0.4,-2.3,-3.2,1.5,5,4.9,3.8,4.3
Beats Beats Powerbeats 4|-1,-0.6,-0.5,-0.9,-1.1,0.3,1.1,1.2,2.2,-0.9,-0.9,-1.8,3.9,1.6,-0.9,-0.6
Beats EP On-Ear|1.5,1,-0.7,-1.6,-3.2,-3,-0.6,0.7,1.6,0.6,0.4,1,2.8,-0.4,-1.6,-1.7
Beats Fit Pro|-1.6,-1.1,0,0.7,1.1,1.8,2.4,1.6,0.5,-0.6,-1.1,-2.9,0.2,-2.6,0.1,2.5
Beats Flex|-3.4,-3,-2.7,-2.2,-1.7,-0.3,0.8,1.7,2.2,-1.1,0.2,3.2,7.6,7.6,2,-1.4
Beats Mixr|-0.7,-1.5,-2.7,-3.7,-5,-4.7,1.2,3.3,0.7,0,1.8,-0.3,-0.8,1.5,2.8,2.2
Beats Powerbeats 2 Wireless|1.7,1,-0.1,-1.2,-3,-3.8,-2.7,-1.9,0.1,0.7,2.8,5.7,6.6,3.7,-0.1,-3.1
Beats Powerbeats Pro|-0.9,-0.7,-0.7,-1,-1.4,-0.2,0.3,0.1,-0.3,-1.7,-2.2,-3.6,2.6,0.3,2.5,2.8
Beats Powerbeats3 Wireless|-4,-3.8,-2.9,-2.8,-2.1,0.3,1.7,3,1.1,-1.2,-1.6,0.3,5,1.2,0.5,2.5
Beats Pro|-0.6,-1.9,-2.2,-3.4,-4.7,-3.5,0.2,1.8,2.1,3.3,3.6,-4,-0.5,1.2,1,2.7
Beats Solo|-0.7,-1.5,-3.7,-6.4,-8.7,-7.9,-1.6,2,-1.2,-1.2,1.9,4.2,1.5,2.9,6.4,6.4
Beats Solo (sample 1)|-3.7,-4.4,-5.4,-6.7,-8.5,-7,-0.9,3.7,0.4,0.5,3.8,4.3,1.8,2.7,7.2,7.2
Beats Solo (sample 2)|2.5,1.5,-2,-6.2,-8.8,-8.9,-2.3,0.3,-2.8,-3,0.2,4.1,1.3,2.9,5.7,5.7
Beats Solo HD|-4.2,-2.8,-5.9,-7.2,-6.3,-4.1,-0.8,2.2,-0.2,-0.9,1.7,4.6,1.4,2.5,7.1,7.1
Beats Solo Pro|-1.2,-1.5,-1,-0.1,1.6,1.8,2.5,1.8,1.1,0.2,1.2,0.9,-2.1,-1.7,-1.1,-1.3
Beats Solo2 (2014)|-1.2,-1.7,-2.8,-3.7,-5.2,-5.6,-2.9,-1.6,1.6,1.5,2.8,4.6,0.1,1.3,4.7,6
Beats Solo3 (2016, wired)|-1.6,-2,-2.9,-3.7,-5,-4.5,-1.4,0.7,3.1,2.3,2.8,2.7,-2.2,1.4,6.5,6.5
Beats Solo3 (2016, wireless)|-1.3,-2,-2.9,-3.8,-5.2,-4.7,-1.7,0.3,3,2.3,2.6,3,-2.3,0.8,6,6.2
Beats Solo3 (wireless, 2019)|-1.5,-2.2,-3,-3.7,-5.4,-4.3,-0.6,3.5,5.6,3.6,2.1,2.1,0.1,1.5,0.7,2.2
Beats Solo3 Wireless (2018)|-1.1,-1.6,-2.4,-3,-3.8,-2.4,0.9,3.5,5.7,3.5,2,0.7,-1.7,0.1,-1.5,-0.3
Beats Studio|-0.6,-0.2,0,-1.7,-3.1,-3.8,-2.5,-1.8,3.9,2.6,-1.2,1.5,1.2,-0.3,-1.9,0.3
Beats Studio 2 (2014)|-0.6,-1.1,-1.8,-2.6,-2.3,-0.6,3.6,5.6,-4.2,-3.2,-0.5,2.8,-4.3,5.5,0.8,-1.9
Beats Studio 2 (Bluetooth)|2.9,1.6,0.2,-0.7,-1.2,-0.8,1.9,3.7,-4.2,-3.8,-1.3,1.8,-6.2,1.8,0.5,-1.2
Beats Studio Buds|-0.6,0.7,1.6,1.6,2,2.9,1.8,-0.6,-1,-2.9,-3.8,-4.5,0.7,0.7,2.3,-0.9
Beats Studio Buds +|-1.5,-1.1,0,0.4,0.9,1.9,2,0.6,-1.7,-2.2,-2.3,-1.7,1.8,0.5,-1,0.1
Beats Studio Pro|0.4,1.3,1.1,1.2,2.2,1.1,2.4,2.4,1.8,0.8,-1.7,-1.8,-0.4,4.4,-5.3,-6.4
Beats Studio Wireless|0.9,-0.2,-1.5,-2.4,-2.6,-0.3,5.2,3,-1,-1.6,-0.7,2.1,0.2,3.9,-2.1,-5
Beats Studio3 Wireless|3.4,0.6,-2.3,-2.1,-2.1,-4.9,-5.9,-2.5,0.3,0.4,2.3,2.9,0,5.9,0.3,-3.7
Beats Tour|-4,-3.4,-3.1,-3.4,-3.5,-1.6,0.3,1.9,3.8,3.2,2.3,-1.9,3.6,-4.4,3,6.3
Beats Tour (2014)|-3.1,-3.7,-4.6,-5.7,-6.6,-5.4,-3.5,-1.6,1.1,1.6,3,5.7,6,4.8,7,4.3
Beats urBeats|-4.2,-4.3,-5,-6.1,-6.9,-5.9,-3.4,-0.2,3.6,3.9,5.1,6.5,5.7,1.6,5.2,4.6
Beats urBeats3|-3.7,-2.5,-1.5,-1.5,-1.5,0.4,1.8,3.9,5.2,3.1,1.9,-0.9,2.5,1.9,-0.6,-1.5
Beats X|-1.6,-1.5,-1.6,-1.2,-0.6,1.1,1.6,2.1,1.2,-1.5,-0.6,0,7,5.2,0.5,-3.5
Behringer HPS5000|4.9,4.9,4.9,2.5,-0.5,-2.1,-0.9,-2.3,-2.9,-6.2,-7.6,1.6,4.9,1,-0.6,-2.4
Behringer SD251-BT|-3.5,-3.2,-3.5,-4.2,-4.8,-3.2,-1.1,1.1,2.9,2.6,1.7,-0.5,3.7,-4.9,5.7,5.6""",
"""Betron YSM1000|-2.2,-2.6,-3.6,-5,-6.5,-6,-4,-1,2.4,3.8,3.9,6.4,3.6,-2.8,5.5,6.4
Beyerdynamic Amiron Home|1.5,0.4,0.5,-2.4,-4.5,-4.7,-2.9,-1.1,1.3,2.8,3.9,3.6,2.1,-1,-2.3,-0.7
Beyerdynamic Amiron Wireless|5.1,4.5,1.6,-0.1,-2,-1.9,-3.1,-1.4,0.1,1.3,1,1.4,3.3,-0.9,-5.8,-5.4
Beyerdynamic Aventho Wireless|-1.1,-0.4,-4.3,-4.7,-1.7,-2,-0.2,-0.6,-0.9,-1.1,1.4,6,5.9,0.5,-1.3,0.1
Beyerdynamic Blue Byrd|4.3,4.3,2.8,0.2,-2.9,-4.7,-4.5,-3.1,-0.4,1.4,1.3,1.3,-1.9,-2.8,-1.6,2.3
Beyerdynamic Custom One Pro (step 1)|4.2,4.2,4.2,0.7,-6.1,-6.5,-4.5,-3.2,-1.7,-1.1,-1.2,-1.6,-0.7,4.2,1.9,1.4
Beyerdynamic Custom One Pro (step 2)|-1.1,-1.1,1.3,4.8,-2.4,-5.6,-4.3,-3.7,-2.5,-1.8,-1.8,-0.5,1,5.3,4.1,3.2
Beyerdynamic Custom One Pro (step 3)|-1.2,-1.9,-1.5,2.4,-3.9,-4,-3.2,-3.1,-2.3,-1.7,-1.8,0,1.8,5.5,4.8,4
Beyerdynamic Custom One Pro (step 4)|-1.1,-2.3,-2.9,0.4,-6.2,-3.5,-1.7,-2.2,-1.7,-1.4,-1.5,0.5,2.3,5.7,5.2,4.7
Beyerdynamic Custom One Pro Plus (step 3)|0.1,0.6,-0.9,-4.7,-4.6,-0.7,-0.5,-0.1,1.2,0.8,0.9,-0.5,0.9,4,4,2.4
Beyerdynamic DJX-1|4.6,2.8,1.4,0.6,-2,-3.9,-2.4,-2.9,-3.7,-3.2,-0.9,0.1,-1.2,3.7,-1.1,-1.1
Beyerdynamic DT 100 (2x 2k Ohm)|3.5,1.8,1,0.9,-7.1,-8.1,-5.9,-3.4,-0.4,1.1,-0.4,1.9,1.7,4.7,1.6,1
Beyerdynamic DT 1350|1.7,0.8,-0.5,-1.3,-0.8,-2.2,-3.3,-4.6,-2.4,0.8,6.2,6.2,1.1,-3.4,1.1,-0.3
Beyerdynamic DT 150 (250 Ohm)|2.7,2.1,1.4,-2.7,-4.8,-2.1,2,1,0.9,0.9,1.1,-0.4,0.8,-0.2,1.8,-1.2
Beyerdynamic DT 1770 (leatherette earpads)|-1.8,-1.6,-1.9,-5.9,-7.1,1.9,0.3,0.1,0.4,0.4,1.1,1.8,4.6,4.5,-0.2,1.8
Beyerdynamic DT 1770 (velour earpads)|-1.1,-0.9,-0.8,-3.8,-4.3,2.4,-0.1,-0.7,-0.3,-0.6,1.1,1.9,3.5,2,-1.7,0.1
Beyerdynamic DT 1770 Pro (velour earpads)|-1,-1,0.9,-3.4,-3.6,3.1,-0.2,-1.3,-0.6,-1.3,0.3,1.5,2.4,1.5,-0.9,-0.1
Beyerdynamic DT 177X Go (leather earpads)|-1.9,-2.5,-2.5,-4.8,-5.9,-0.8,0.2,0.1,-0.4,-0.2,1.8,6.3,2.2,3.4,0.9,1.8
Beyerdynamic DT 177X Go (velour earpads)|-2.8,-0.7,-2,-6.4,-6.8,-2,-0.5,-0.5,-0.1,0.1,3.3,6.4,3.3,2.8,-0.1,2.9
Beyerdynamic DT 1990 (analytic earpads)|5.1,4,2.6,1.3,-1,-2.3,-1.3,-0.4,0.5,0.4,0.5,0.6,2,0.9,-4.5,-6
Beyerdynamic DT 1990 (balanced earpads)|2.3,1.5,0.8,-0.7,-2.7,-3.2,-1.8,-0.2,1.9,1.9,2.1,2.2,1.6,2.1,-2.4,-3
Beyerdynamic DT 1990 Pro (analytical earpads)|4.6,3.7,2.5,1.1,-1.1,-2.3,-1.4,-0.5,0.1,-0.4,0,0.9,0.8,1.8,-3.5,-4.8
Beyerdynamic DT 1990 Pro (Dekoni Elite velour earpads)|2.5,1.8,0.7,-1,-2.8,-3.4,-1.9,0.1,1.9,1.9,2.6,3.5,3.8,0.9,-3,-4.3
Beyerdynamic DT 231|5.7,5.7,5.7,5.7,2.6,1.2,0.4,-0.6,-2.3,-3,-4.1,3.7,1.5,-7.5,-2.5,-5.6
Beyerdynamic DT 235|5.4,4.4,1,-0.1,-2,-1.3,-0.9,-1,-1.3,-0.6,-2.6,2.8,0.7,-3.3,-2.6,-4.8
Beyerdynamic DT 240|-1.3,-1.9,-2.2,-4.1,-4.7,-3.1,0.1,0.3,0.2,0.1,-0.2,0.3,4.6,6.2,3.7,2.8
Beyerdynamic DT 240 Pro|-1,-2.1,-2.7,-3.5,-4.5,-3.3,0.2,-0.8,-0.3,0,-0.5,0.3,2.8,5.8,3.8,3.8
Beyerdynamic DT 250 (250 Ohm)|0.6,0.9,4.8,2,0.3,0.4,-0.3,-0.7,-1.4,-1.2,-1.3,-1.3,3.1,1.8,-0.3,-3
Beyerdynamic DT 250 (80 Ohm)|2.9,1.6,1,0.8,0.5,-1.7,-2.6,-3.1,-3.6,-3.2,-4.8,-1.9,1,1.7,5,2.3
Beyerdynamic DT 48|6.4,5.6,-5,-2.4,-1.4,-0.7,1.8,4.6,3,-2.3,-4.5,-1.7,1.3,6.4,0.1,-6.3
Beyerdynamic DT 48 (loose)|5.4,5.4,5.4,4.1,-1.7,-4.6,-2,1.9,2.9,-2.2,-5.1,-1.8,-0.4,5.3,0.6,-5.7
Beyerdynamic DT 48 E (120 Ohm)|6.5,5.7,-4.9,-2.3,-1.7,-1.1,1.4,4.1,3.9,-2.3,-4.4,-0.7,1.6,6.5,-0.2,-6.7
Beyerdynamic DT 48 E (earpad holes covered)|6,6.1,-3.6,-2,-1.6,-0.9,1.8,5.2,3.2,-3.6,-4.6,-1.4,0.2,6,-0.4,-6.5
Beyerdynamic DT 48 S (5 Ohm)|6.5,6.5,6.5,6.5,6.5,6.5,3.1,-4.3,-11.5,-9.9,-8.5,-5.4,-3.1,2.2,0.4,-4.1
Beyerdynamic DT 700 Pro X|1,1.4,0.1,-2.2,-2.3,3.1,1.8,0.1,-0.8,-1.3,-1.7,-0.5,4.3,-1.5,0.4,2.2
Beyerdynamic DT 770 (Alpha earpads)|-1.4,-2.1,-2.3,-2.3,-2.2,2.8,1.3,0,-0.2,-0.7,-0.5,1.5,7.3,0.5,4.9,0.2
Beyerdynamic DT 770 M|5,5,4.8,-6.2,-6.4,-5.2,-3.6,-2.2,-1.1,-1,-1.3,-0.3,5,3.3,2.9,0.8
Beyerdynamic DT 770 Pro|-2.6,-2.6,0.1,1.8,-1.2,3.2,1.5,1.4,1.3,0.8,1.5,1.1,6.5,-0.4,-1.9,-1.6
Beyerdynamic DT 770 Pro (250 Ohm)|-3,-2.7,1.1,0.6,-1.8,4,2.3,1.9,1,1,1.6,3.5,6.2,-0.8,-2.8,-2.2
Beyerdynamic DT 770 Pro (32 ohm Limited Edition, pleather earpads)|-0.2,-0.8,0.9,-2,-6.2,0.1,-0.5,0.6,2.2,3,4.1,3.7,2.4,1.4,-0.7,-1.5
Beyerdynamic DT 770 Pro (32 Ohm)|-1.6,-2.3,-0.8,1.7,-0.9,3.1,1.2,1.1,1.3,0.2,1.4,-0.9,6.5,4,-0.7,-2.9
Beyerdynamic DT 770 Pro (600 Ohm)|0.8,-0.1,4.8,-1.6,-0.3,1.3,0.8,0.8,2.2,2.6,3,1.7,4.2,0.6,-5.8,-5.5
Beyerdynamic DT 770 Pro (80 Ohm)|-0.8,-2.5,-1.8,-1.2,0,3.3,1,0.9,0.8,0.6,1.2,0.3,5.3,-2.2,-2,-0.3
Beyerdynamic DT 770 Pro (Dekoni hybrid earpads)|-0.1,-0.3,0.4,1.9,3.5,5.7,2.2,-0.3,-1.2,-0.8,0.1,-0.9,3.4,-1,-1.3,-3.7
Beyerdynamic DT 770 Pro (worn earpads)|0.6,1.5,5.4,0.9,0.2,1.6,-0.3,-0.5,-0.1,0.3,0.8,-0.1,4.2,0.6,-2.5,-4.1
Beyerdynamic DT 860|5.4,5.4,4,2.6,0.1,-1.1,-0.3,0,-0.4,-0.9,-0.7,-1,-1.4,0.1,-4,-2.4
Beyerdynamic DT 880|4.7,3.4,2.2,0.1,-2.3,-3.2,-2,-0.8,0.1,0.7,1.2,0.6,0.7,0,-1.8,-1.1
Beyerdynamic DT 880 (250 Ohm)|5.3,5.3,3.9,1.7,-0.6,-1.9,-1.2,-0.7,-0.1,0.3,1.9,2.8,-2.6,-0.6,-4.7,-4.9
Beyerdynamic DT 880 (32 Ohm)|4.3,3.1,1.6,-0.3,-2.6,-3.7,-2.7,-1.7,-0.6,0.2,1.1,0.7,1,0.8,-1.6,0.6
Beyerdynamic DT 880 (600 Ohm)|5,3.6,2.4,0.1,-2.4,-3.7,-2.5,-1.7,-0.9,-0.6,0.5,-1.5,1.9,1.3,-0.2,1
Beyerdynamic DT 880 (worn earpads)|5,5,4.8,2.6,-0.2,-1.9,-1.6,-1.1,-1.4,-1.6,-1.2,-1.8,0.2,0.9,-1.6,-1.3
Beyerdynamic DT 880 Black Edition|4.6,3.1,1.5,-0.3,-2.9,-3.6,-2.1,-0.4,1,1.8,2.6,2.4,-0.4,-1.7,-2.6,-1.5
Beyerdynamic DT 900 Pro X|3.1,2.6,1.9,0.4,-1.4,-2.4,-1.7,-1.3,-0.6,-0.1,0.4,-0.1,0.9,-1.6,-1.9,1.1
Beyerdynamic DT 990 (600 Ohm)|5.7,5.2,3.5,-0.3,-3.3,-3.8,-1.5,0.8,2.5,1.6,3.1,4.7,-0.6,0.4,-5.4,-6.5
Beyerdynamic DT 990 (fresh earpads)|4.2,2.5,0.9,0,-1.5,-0.4,1.8,2.8,3.3,1.1,1.4,0.8,0.7,-1.4,-3.9,-5.9
Beyerdynamic DT 990 (worn earpads)|3.3,2.3,0.2,-1.2,-2.5,-1.9,0,0.8,1.8,1.2,1.4,2,2.3,1,-2.7,-6.1
Beyerdynamic DT 990 Pro|4.3,2.5,0.9,0,-1.5,-0.4,1.8,2.8,3.4,1.1,1.4,0.8,0.7,-1.4,-3.9,-5.9
Beyerdynamic DT 990 Pro (250 Ohm)|3.4,2.7,-0.3,-1.3,-2.1,-1.3,0.6,1.1,1,-0.9,0.1,1.3,0,2.5,-2.3,-3
Beyerdynamic DT 990 Pro Black Edition|6.1,6.1,5.9,3.9,0.8,0.1,1.5,1.8,1.6,-0.4,1.1,0.9,-0.6,-2.7,-5.5,-8.7
Beyerdynamic DT1990 (Dekoni Elite velour earpads)|3.1,2.4,0.9,-0.8,-2.7,-3.4,-1.9,-0.1,1.3,1.4,1.5,1.6,3.1,0.1,-1.9,-3.2
Beyerdynamic DTX 300p|4.3,4.3,3.9,0.4,-2.6,-4.5,-4.3,-6.3,-5.4,-5,-1.6,3,2.3,0.8,1.5,4.3
Beyerdynamic DTX 350m|3.2,2.1,1.1,0.4,-2.6,3.6,1.5,-0.4,-1.1,-1.4,0.2,-0.5,0.8,0.3,-0.7,-1.2
Beyerdynamic DTX 350p|-0.9,-0.9,-2.1,-4.4,1.1,2.7,4.9,3,0.4,-0.9,0.4,1.5,-0.3,4,-1.1,-3
Beyerdynamic ET1000|5.2,5.2,5.1,0.4,-2,-1.8,-1.3,-0.6,0,-2.5,-2.6,-1.9,-1.1,0.3,2,-2.3
Beyerdynamic Lagoon|0.4,0,-1.3,-2.7,-3.5,-0.4,3.6,3.6,0.6,-1.7,-1.6,2.2,6.5,0,-2.6,-0.7
Beyerdynamic MMX 300|3.2,1.4,3.8,0,-2.7,-0.2,-0.7,-0.1,0.1,0.4,0.8,-1,3.3,0.4,0.3,-2.9
Beyerdynamic MMX 300 (Dekoni Choice leather earpads)|1.2,0.2,-0.5,-1.7,-3.3,1.6,1,0,0,0.8,1.1,2.8,5.6,0.7,0.1,-3.1
Beyerdynamic T1|5.3,3.9,2.2,0.8,-1.2,-2.3,-1.3,-0.8,0.4,0.9,1.7,0.6,-1.3,1.1,-4.7,-5.1
Beyerdynamic T1 (2nd Gen)|3.3,2.3,1.1,-0.7,-2.9,-3.7,-2.9,-1.8,-1.3,-0.6,1.6,4.4,3.6,4.6,-4.4,-3.7
Beyerdynamic T1 (3rd Gen)|-0.7,-1.6,-2.6,-3.8,-6.4,-6.7,-5.4,-3.9,-1.4,2.9,6.6,6.6,6.5,2.4,1.6,3.9
Beyerdynamic T1 (sample 1)|5.4,4.8,2.9,1.3,-0.6,-2,-1,-0.7,0.1,0.7,1.2,1.1,-0.8,1.6,-5.9,-6.8
Beyerdynamic T1 (serial number 3964)|4.3,3.1,1.6,0.2,-1.6,-2.6,-1.5,-0.8,0.7,0.9,2.1,0,-1.8,1,-3.4,-3.3
Beyerdynamic T5 (3rd Gen)|-1.5,-0.2,-3.8,-6.8,-7.7,-2.6,1.6,1,-2.3,-1.4,1.1,6,3.6,4.1,1.6,4.8
Beyerdynamic T50p|4.7,2.3,-0.3,-1,-0.6,-6.7,-7.1,-6.6,-4.3,-3.8,-1.2,5.6,5.4,5.6,3,-0.1
Beyerdynamic T50p (sample A)|5.5,3.4,0.1,-1,-0.7,-6.1,-5.7,-5.7,-3.5,-3.1,-1.3,4.6,2.2,5.5,4,0.4
Beyerdynamic T50p (sample B)|2.8,1,-1.3,-2,-1.7,-7.1,-7.4,-7.2,-5.2,-4.8,-0.5,6,6,6,3.5,1.7
Beyerdynamic T50p (serial number 16912)|5.1,3,0.8,0.4,0.8,-6.4,-7.8,-6.5,-3.8,-3.2,-1.3,4.9,5.6,5.6,1.9,-2.1
Beyerdynamic T51i|0,-1.7,-3.9,-4.8,-6.4,-3.3,-1.8,-2.2,-0.4,1.1,4.5,3.5,3.9,3.3,2,1.2
Beyerdynamic T5p|5,5.7,-2.2,-5.2,-5,-1.7,0.6,1.2,1.2,-0.2,-1.3,-0.7,-0.5,1.1,2,1.7
Beyerdynamic T5p (sample 1)|0.9,1.3,2.7,-2.4,-2.2,-1.1,0.7,0.6,0.2,-1.7,-0.6,-1,-0.2,1.2,0,1.1
Beyerdynamic T5p (serial number 2866)|3,2.5,2.7,0.5,-3.1,-2,0.2,-0.2,1.2,-0.3,-2,0.1,1,3.3,-1.6,-2.6
Beyerdynamic T70|3.8,3.5,2.1,0.5,-1.7,-0.8,-1.9,0,-0.8,-0.9,-0.4,1.7,1.6,-1.1,-5.1,-3.9
Beyerdynamic T70 (sample 1)|4.7,4,3.2,0.5,-1.8,-2.2,-2.4,-2.1,-1.6,-1.6,-0.3,3.4,-2.2,1,-6.2,-4.5
Beyerdynamic T70 (serial number 01111)|3.3,2.3,1,0.3,-2.7,-2.5,-2.3,-2,-2,-2.3,-0.9,3.1,0.1,4.2,-5.2,-3.9
Beyerdynamic T70p|2.4,1.7,0.7,0,-2.5,-1.5,-1.1,-1.2,-0.9,-1.2,-0.4,5,-0.5,3.9,-5.8,-5.1
Beyerdynamic T90|5.6,5.6,4,2.2,-0.2,-1.6,-0.5,0.4,2.4,2.2,2.1,0.3,-3.6,1.4,-6.9,-8.3
Beyerdynamic TYGR 300|2.7,1.5,0.2,-1,-2.5,-2.6,-0.6,0.8,2,1.5,0.8,1.7,0.8,-1.1,-0.7,-1.5
Beyerdynamic TYGR 300 R|2.7,1.4,0.5,-0.5,-2.2,-2.6,-0.7,1.1,2.2,1.1,1.1,2.5,1.8,-0.8,-1.6,-2.7
Beyerdynamic Xelento|-3.2,-3.2,-3.5,-4.4,-5.1,-4,-1.9,0.4,2.5,2.1,1.7,3.4,4.3,-1.3,2.1,5.4
Beyerdynamic Xelento (2nd Gen)|-2.2,-2.1,-2.6,-3.7,-4.8,-3.9,-2.6,-1,0.3,0,1.2,5.9,6.1,1.5,1.2,2.7
BGVP DM6|2.1,0.7,-1,-2.7,-4.9,-5.2,-4.2,-2.1,0.5,1.6,2.2,5.8,5.8,3,1.6,2.3
BGVP DM7|1.9,1.1,-0.1,-1.8,-3.8,-4,-3.1,-1.6,-0.4,-1,-1.7,1,0.6,-1.4,2.7,4.6
BGVP DM8|4.9,2.4,-0.6,-2.7,-4.6,-3.9,-2.3,-0.3,1.9,0.9,-0.1,0.7,1.6,5.5,-0.6,-3.3
BGVP DM9|1.7,1.4,0.4,-1,-2.5,-2.5,-1.3,0.3,3.6,0.4,0.1,1.3,6,-0.9,-0.9,-3.3
BGVP DMG|-0.3,-0.9,-2,-3.2,-4.6,-3.6,-1.6,0.9,3.5,4.7,4,3.7,4,-1,-2.8,-0.8
BGVP DMG (black)|-1.8,-2.1,-2.8,-4,-5.1,-3.9,-1.9,0.6,3.4,4.3,3.3,1.9,2.7,1,0.1,2.2
BGVP DMG (foam eartips)|0.8,0,-1.2,-2.6,-4.1,-3,-1.1,1.2,3.5,4.8,3.6,2.8,2.7,-0.1,-2.9,-2.8
BGVP DMG (gold)|-2,-2.5,-3.3,-4.5,-5.7,-4.5,-2.6,-0.1,2.8,4.2,3.7,3.8,4.7,0.8,0,2.7
BGVP DMG (silver)|-1,-1.5,-2.4,-3.6,-5,-3.8,-2,0.5,3.3,4.2,3.1,1.3,1.4,0.3,-0.9,2
BGVP DMS|-1.2,-1.5,-2.3,-3.5,-4.8,-3.9,-2.3,-0.3,1.4,0.2,-3.4,2.7,-0.2,1.7,5.8,5.8
BGVP DN3|1.3,0.5,-0.7,-2.1,-3.7,-2.9,-1.5,0.2,1.7,1,-1.3,-3.9,0.4,-0.7,4.6,1.7
BGVP DS1 Pro|-1.1,-1.2,-2,-3,-4.1,-2.9,-1,1,2.7,1.7,-1.3,0.3,-0.3,2.2,2.1,6.3
BGVP ES12 (off-off-off)|3.4,2.9,1.4,-0.3,-2.7,-3.4,-3,-2,-1.1,-1.5,-1.3,-0.6,-0.3,4.5,2.8,2
BGVP ES12 (off-off-on)|4.6,4.6,3.4,1.7,-0.5,-1,-0.2,0.4,-0.9,-2.7,-2.3,-2.3,-2.3,0,-3.9,-0.3
BGVP ES12 (off-on-off)|-0.5,-1.2,-2.2,-3.9,-6,-6.1,-4.8,-2.7,-0.2,0.7,2.1,3.5,2.6,6.2,6.2,5.9
BGVP ES12 (on-off-off)|3.1,2.3,1.1,-0.6,-2.8,-3.3,-2.5,-1.2,-0.2,-0.6,-0.9,-0.3,0.3,4,2.1,1.5
BGVP ES12 (on-on-on)|0.6,-0.1,-1.3,-2.9,-4.8,-4.7,-3.1,-0.4,1.6,0.5,1.7,1.4,0.7,3.8,-0.1,3.2
BGVP Melody (balanced nozzle)|-0.6,-0.9,-1.7,-3,-4.3,-3.4,-1.7,0.4,2.6,2.9,2.5,0.6,0.1,-4.7,3.8,5.6
BGVP Melody (hifi nozzle)|-0.6,-0.9,-1.7,-2.9,-4.1,-3.3,-1.4,0.7,3,3.3,3.2,1.9,-0.8,-5,2.6,4.9
BGVP Melody (pop nozzle)|1.9,1.5,0.1,-1.5,-3.6,-3.7,-2.6,-0.8,1.2,1.4,0.8,-1.5,-0.4,-5.4,4.1,5.2
BGVP NS9 (black eartips)|-2.9,-2.7,-2.8,-3.4,-4,-2.2,-0.3,1.9,3.2,1.3,1.4,7.1,6.4,5.2,3.7,-1.2
BGVP NS9 (gray filter)|-3,-2.9,-3,-3.5,-4.2,-2.3,-0.5,1.7,3.1,1,1.2,6.5,5.3,2,2.8,1.1
BGVP NS9 (no mesh, black eartips)|-2.9,-2.7,-2.9,-3.5,-4,-2.3,-0.4,1.8,3.2,1.2,1.3,7.2,6.5,5.7,4.2,-1.2
BGVP NS9 (red filter)|-3,-3,-2.9,-3.5,-4,-2.3,-0.3,1.7,3,1,1.1,6.5,5.2,2.4,2.6,1
BGVP NS9 (silver filter)|-2.9,-2.8,-2.9,-3.6,-4.1,-2.3,-0.3,1.8,3.1,1,1.1,6.5,5.2,2.6,2.4,0.8
BGVP P05 (no filter, standard nozzle)|-0.2,0.2,0.2,-0.6,-1.7,-1.4,-0.8,-0.1,0.8,0.8,-0.7,0.3,-0.7,0.4,2.1,2.1
BGVP P05 (red filter, alternative nozzle)|2.1,1.6,0.5,-1.1,-3.1,-3.4,-3,-2.2,-0.7,-0.3,-3.4,-1.3,-0.6,-0.3,3.3,4.5
BGVP P05 (red filter, standard nozzle)|2.8,2.2,1,-0.5,-2.2,-2.4,-1.7,-0.9,0.1,0.2,-1.5,-0.4,-1.3,0.2,1.8,0
BGVP P05 (silver filter, alternative nozzle)|-0.1,0.2,0.1,-0.7,-1.9,-1.9,-1.4,-0.8,0,-0.2,-2.2,-0.3,0.4,-0.1,3.4,2.7
BGVP P05 (silver filter, standard nozzle)|0.1,0.5,0.5,-0.3,-1.5,-1.4,-0.8,-0.2,0.7,0.6,-1,0,-0.9,0.5,2,1.1
BGVP P05 (taped filter, standard nozzle)|1.7,1.3,0.4,-0.9,-2.4,-2.3,-1.6,-0.9,0.3,0.3,-1.2,-0.2,-1,0.1,1.7,1.9
BGVP Scale|0.9,-0.1,-1.5,-3.2,-5,-4.5,-2.7,-0.4,2.6,0.1,-0.9,0.9,3.3,3.8,-0.1,-0.5
BGVP SGZ-DN1S|-1.3,-1.4,-2.2,-3.5,-4.8,-3.8,-1.9,0.5,3.2,3.7,3.6,6.5,5.7,0.2,-0.7,-1.5
BGVP VG4 (off-off-off)|3.5,2.9,1.6,-0.1,-2.5,-3.3,-2.7,-1.6,-0.1,-0.7,-2.4,-0.5,1,1.3,-1.1,0.3
BGVP VG4 (off-off-on)|1.1,0.7,-0.4,-2.1,-4.4,-4.8,-3.7,-2.2,-0.2,0,-1.2,1.3,3,3.5,1.1,2.7
BGVP VG4 (off-on-off)|3.4,3,1.6,0,-2.5,-3.3,-2.7,-1.6,-0.2,-1,-2.8,-0.8,1,1.2,-1.1,0.5
BGVP VG4 (on-off-off)|3.4,2.9,1.7,-0.1,-2.5,-3.3,-2.7,-1.5,-0.1,-0.7,-2.4,-0.6,0.7,0.7,-2.2,0.4
BGVP ZERO|3,0.8,-2,-4.6,-7.4,-7.9,-6.5,-4.2,-0.8,1.7,3.8,4,-0.9,3.6,5.5,4.7
Big Bus Earbuds|5.2,5.2,5.2,4.5,0.2,-5.5,-10.5,-16.5,-17.9,-6.5,3.3,5.2,5.2,0.3,5.2,5.2
Binary Acoustics x Gizaudio Chopin|-0.9,-1.2,-1.6,-2,-1.7,0.4,0.9,1,1.6,1,1,1.5,2.2,0.9,1.5,-0.5
BKHC BK9|-0.5,-1.5,-2.5,-3,-3.6,-5.9,-5.9,-7.4,-6.7,-3.1,3.5,5,4.1,6.2,6.3,6.3
Bloc Roc Galvanize S2|0,-3.2,-4.8,-4.3,-5.1,-3.7,-2.3,-0.9,2.7,0.5,-1.4,1,2.1,-0.6,5.4,5.9
BLON Annotation|-4.4,-3.7,-2.9,-2.3,-2.8,-1.1,1.9,3,3.5,2.2,-0.9,0.7,5.3,-1.9,0.4,2.3
BLON B-Mini|-1.2,-1.1,-1.6,-2.6,-3.7,-2.4,-0.6,1.4,3.3,2.9,2.7,1.6,4.1,2.8,0.4,1.5
BLON B20|2.9,2.4,1.3,0.2,-1.7,-2.3,-2.4,-1.1,-1.8,-3.5,0.1,0.1,-0.6,3.3,1.6,-2
BLON B60|0.6,-0.4,0.7,-2.9,-2.3,5.9,2.6,0.1,-0.5,-0.8,0.1,-2.7,-0.1,0.5,0.7,-0.8
BLON BL-01|-0.7,-0.9,-1.6,-2.7,-4,-2.9,-1.1,1,2.5,2.1,1.5,1.1,2,0.2,1.3,-0.3
BLON BL-03|-2.9,-2.6,-2.7,-3.4,-4,-2.6,-0.8,1.1,2.3,1.4,0,2,4.9,1.4,1.9,1.1
BLON BL-05|-0.6,-0.4,-0.4,-1.1,-2.1,-1.4,-0.5,0.4,1,-0.5,-3.6,-1.7,2.2,0.2,2.7,1.2
BLON BL-A8 Prometheus|0.8,-0.2,-1.6,-3,-4.6,-3.9,-2.2,0,2.3,2.4,-0.2,0.2,5,1.2,1.9,3.1
BLON BL-Max|-1.2,-1.3,-1.8,-2.8,-3.8,-2.6,-1,0.8,2.3,1.3,-1.8,-0.7,3.9,4.4,3.7,4.2
BLON BL07|-1.9,-2,-2.7,-3.8,-4.8,-3.5,-1.4,1.1,3.8,4.6,3.7,4.9,6.1,0.3,-2.4,-2.3
BLON Fat Girl|-1.5,-1.4,-1.7,-2.5,-3.2,-1.7,-0.2,1.8,3.2,2.2,0.7,1.8,2.2,-1.9,-0.4,1.2
BLON x HBB Z300|-2.5,-2.1,-2.1,-2.7,-3.5,-2.2,-0.8,0.6,1.8,1.5,1.2,4.7,4.6,3.2,1.6,0
BLON x Z Jojo|-1.1,-1.2,-1.8,-2.9,-3.9,-2.8,-1.3,0.3,1.8,1.6,1.1,2.9,3.1,2.6,2.4,-2
BLON Z200|-3.1,-2.7,-2.9,-3.3,-4.3,-2.5,-0.6,1.5,3.4,3,0.2,1.3,6.6,2.6,0.7,1.3
Blue Lola|-0.7,-1.4,-2.4,-3.2,-4.7,-3,-0.8,0.3,-0.8,-2.2,-1.1,1.1,1.4,1.5,5.6,5.9
Blue MOFI (active)|0.6,-0.2,-1.5,-2.7,-4.6,-4.2,-1.7,0.2,-1.5,-3.3,-1.8,0.5,-0.3,3.6,5.3,5.3
Blue MOFI (active+)|-1,-3.3,-5.3,-5,-5.1,-3.5,-0.8,1.2,-0.4,-2.1,-0.6,1.5,0.4,4.7,6,6
Blue MOFI (passive)|0,-0.5,-1.6,-2.6,-4.4,-3.9,-1.5,0,-1.7,-3.4,-2,0.6,0.3,4.4,5.4,5.4
BlueAnt Embrace|4.9,3.9,0,-2.4,-5.4,-5,-2.7,1.4,0.8,-4,-3,1.3,4.3,-2.2,-0.4,3.9
Bluedio R2-WH|-1.9,-3.3,-4.2,-4.5,-6.4,-6,-4.9,-2.8,2.3,-1,1,0.1,0.9,5.5,7,7
Bluedio T2S|-5.8,-5.6,-5.7,-6.1,-7.2,-6.6,-4,0,5.5,4.9,5.5,2.5,7.5,7.6,4.2,3.9
Bluedio T3 Plus|-4.6,-4.5,-6.2,-7.7,-9.3,-4.6,4.7,9.5,9.5,9.5,6.6,-4.4,-2.8,5.7,3,1.9
Bluedio T4 Turbine|-1.4,1.1,3.2,3.9,4.1,4.3,4.4,3.7,1.1,-5.8,-4.1,-6,-1,-4,-0.9,3
Bluedio U Plus|-4,-4.6,-5.7,-6.6,-7.1,1.7,8.8,8.8,8,-0.4,0,-4.9,-1.2,0.4,4.2,3.8
BlueParrott B450-XT|-0.6,-1.8,-2.8,-3.3,-2.8,1.9,0.3,1.5,0.9,-1.6,-1,1.7,5.4,3.8,3.5,1.4
Boltune BT-BH024|-6,-5.9,-4.8,-3.6,-2.4,-0.6,0.1,1.5,3.4,0.8,1.3,1.8,4.3,7.8,5.1,2.9
Boqari Q1|4.6,4.6,3.4,1,-2.4,-5.5,-6.6,-6.8,-4.9,-3.7,-2,2.8,2.3,-4.4,4.6,4.6
Bose AE2|-3.3,-2.8,-1.3,-0.6,-3,-0.6,1.7,1.9,-0.4,-1.2,2.7,2.8,-3.1,3,-1.1,3.6
Bose Hearphones|-2.9,-0.8,-0.3,-1.7,-3.1,-2.9,-1.2,0.8,2.1,2.6,1.1,-0.1,1.3,0.1,-1.4,4.8
Bose IE2|-5.5,-5.9,-6.1,-7.1,-8.6,-8.4,-6.7,-4.9,-1.1,2.7,6.2,8.3,8.3,8.3,8.3,8.3
Bose Noise Cancelling Headphones 700|0.4,-1.6,-2.1,-2.5,-3.6,-2.7,-1.1,-0.3,-0.6,1.7,0.7,0.5,1.6,-1.6,6.1,5
Bose Q25 (ANC off)|4.6,4.6,3.6,-0.6,-7.3,-4.9,-1.9,-2.2,-4.8,-5.2,0.8,4.6,0.6,-1.4,3.8,4.2
Bose QuietComfort|-0.4,-0.5,-0.6,-1.9,-0.7,-0.9,-1,-0.4,-1,-0.6,-1.3,-2.4,-1.2,4.4,1.9,2.6
Bose QuietComfort 15|0.2,-0.3,-0.7,-1.6,-2.2,-1.9,-0.1,1,2.3,0.5,0.8,0.6,1.6,-1.1,2.3,2.3
Bose QuietComfort 20|1.2,1.4,0.2,-1.5,-2.4,-3.1,-2.1,-2.2,-1.1,-1,-4.3,-0.7,-0.8,1.5,1.3,4.7
Bose QuietComfort 20 (passive)|4.7,0.8,-3.5,-5.2,-5.3,-6,-4.9,-1,-0.1,-3.3,-4.5,4.9,2.2,4.1,2.4,4.9
Bose QuietComfort 25|0.3,0,0.1,-0.3,-1.4,-1.2,0.4,1,0.5,-0.7,-1,-1,-0.1,1.7,1.9,1.3
Bose QuietComfort 25 (passive)|-2.2,-4.9,-7,-6.7,-7.9,-4.7,-0.7,-0.5,-2.2,-1,5.7,6.6,0.4,5.4,6.6,6.6
Bose QuietComfort 3|-1.6,-1.6,-2,-3.1,-4.3,-3.3,-1.4,-0.5,-0.8,-0.6,3,7,0.7,4.4,7,2.4
Bose QuietComfort 35|0.5,-0.1,-0.1,-0.1,-0.4,-0.1,1.2,1.9,2.8,3.3,-0.4,-1.9,0.1,2.2,0.3,-0.3
Bose QuietComfort 35 (wired, active)|0.4,-0.2,-0.1,-0.4,-0.8,-0.8,0.5,0.6,0.6,0.4,1.9,-1.3,-3.7,1.5,2.5,1.1
Bose QuietComfort 35 (wired, passive)|1.9,0,-0.7,-1.8,-6,-2,1,-0.8,-3.5,-0.4,5.3,4.2,-5.1,2.1,3.2,0.9
Bose QuietComfort 35 (wireless)|-0.2,-0.3,0,-0.4,-0.7,-0.6,0.8,1.2,1,0.9,4.7,-0.4,-2.3,1.3,2.2,0
Bose QuietComfort 35 II|-0.2,-0.7,-1.6,-1.7,-2.5,-2.5,-1.1,-0.1,1,2.9,2.3,-2.2,0.1,-1.3,3.4,4.7
Bose QuietComfort 35 II (passive)|2,2.5,-1.5,-6,-8.9,-1.3,1.9,1,-2.2,-2.1,4.3,3.1,-1,-1,4.5,4.7
Bose QuietComfort 35 II Gaming Headset​|1.3,0.2,0,0,-0.4,-0.4,0.9,1.4,1.6,1.5,0.3,-0.7,1.4,2.1,-1.8,-3.8
Bose QuietComfort 45|-0.8,-0.7,-0.6,-0.8,-1,0,1.5,1.8,1.6,2.7,2.2,-0.9,1.5,-2.6,3.1,0
Bose QuietComfort 45 (ANC on)|0.2,-0.1,-0.4,-0.8,-1.4,-0.8,1,1.9,2.6,2.8,-2.2,-2.3,1.3,1,2.1,-0.9
Bose QuietComfort 45 (passive)|2.7,2.5,-0.7,-3.4,-8.9,-1.5,1.3,0.6,-3.1,-4.1,-0.3,3,-0.6,2.8,2.7,3.1
Bose QuietComfort Earbuds|0.6,1.3,1.3,0.2,1.3,1.1,0.3,0.3,0.7,-0.3,-0.9,-2.3,-2.1,4.1,-1.4,-3.9
Bose QuietComfort Earbuds II|-2.3,-0.5,0.7,1,1.2,1.4,0.3,-1.2,2.9,4,0.5,-3,0.1,1.4,-1,-1.9
Bose QuietComfort II (100dB)|0,1.1,1.4,1.2,0.9,1.1,0.5,-0.1,-0.8,1.6,-0.3,-4.8,-3.1,0.8,1.7,0
Bose QuietComfort II (80dB)|-6,-3.9,-1.6,-0.3,1.5,3.4,3.4,3,2.1,4.1,1.4,-4.1,-2.8,1,1.5,0.7
Bose QuietComfort II (90dB)|-2.7,-1,0.4,0.9,1.3,1.9,1.5,0.9,0.1,2.5,0.4,-4.3,-2.7,1.1,1.6,0.3
Bose QuietComfort SE (ANC on)|-0.9,-0.3,-0.1,-0.7,-1.1,-0.3,1.4,2,1.6,3.1,2.7,-0.7,0.4,-1.8,3.1,0.7
Bose QuietComfort SE (passive)|1.5,2.4,1.3,-6.1,-8.7,-0.8,2.3,1.1,-2.7,-1.6,4.7,3.6,-1.2,0.2,2.5,2.8
Bose QuietComfort Ultra Earbuds|-4.4,-2.5,-0.6,0.6,1.9,3.4,3.3,3.2,1.9,5.1,-0.1,-7,1.4,0.7,-2.5,-1.8
Bose QuietComfort Ultra Headphones|-3.8,-3.2,-2.3,-2,-1.4,0.3,1.4,1.3,1.1,2.1,2.8,-1.3,6.1,5.5,-0.7,-0.6
Bose QuietControl 30|-0.6,0.1,-0.1,-0.8,-1.5,-1,0,1.9,2.6,0,0,-0.2,0.7,-2.4,3,2.3
Bose SoundLink Around-Ear II|0.5,1.1,1.1,0.6,-0.4,-0.5,0.6,0.9,1,1.2,0,-1.4,-1.5,2.8,2.4,-0.2
Bose SoundLink On-Ear|-1,-1,-1.1,-1.3,-2,-1.2,0.1,2.2,3.5,3.6,4.3,2.5,1.6,2.8,-1.5,-3.4
Bose SoundSport Free|1.9,0.5,-0.4,-1.5,-2.8,-2.5,-1.8,-1.4,-1.5,-1.9,-0.8,-1.3,0.7,2.4,1.6,1.1
Bose SoundSport In-Ear|4.7,4.7,4.6,1.5,-1.3,-2.6,-2.5,-2.7,-1.3,-1.2,-2.4,0.3,1.8,-0.4,-1.3,-1.2
Bose SoundSport Wireless|-4.1,-3.6,-3.2,-3.2,-3.9,-3,-1.3,0.7,1.7,2.5,3.9,2.5,2.2,2.9,3.3,1.4
Bose SoundTrue Around-Ear II|0.7,1.4,1.3,0.3,-2.2,-1.8,1.3,2.7,0.2,-2.6,-2.1,-1.2,-1.9,3.1,-1.7,1.6
Bose SoundTrue Ultra In-Ear|1.4,1.7,1.4,0.4,-1.1,-1.3,0.1,1.4,1.9,-1.3,-0.7,0,0.5,-0.7,-1.4,-0.7
Bose Sport Earbuds|4.9,2.8,0.7,0,-0.9,-0.7,-0.6,-0.7,-0.1,-1.3,-4.4,-4.2,-0.3,4.1,-1.8,-4.3
Bose Sport Open Earbuds|4.9,4.9,4.9,4.2,-2.6,-3.3,-2,-1.3,-1.7,-2,-2.1,-1.9,-2.2,-0.8,4.5,0.7
Bowers & Wilkins C5|-5.9,-4.8,-3.8,-3.7,-3.4,-1.4,0.6,2.2,4.1,3.9,4.7,3.7,-0.8,1.3,4.1,4.8
Bowers & Wilkins C5 Series 2|-6.3,-5.6,-5.1,-5.3,-5.3,-3.3,-1,1.6,4.3,4.7,5.6,5.8,2.1,2.2,5,6
Bowers & Wilkins P3|2,0,-2.4,-4,-5.9,-7.6,-6.7,-4.5,-0.2,3,5.8,5.8,-0.2,4.8,2.2,4.2
Bowers & Wilkins P5|2.7,1.4,0.3,-0.7,-0.6,-0.9,1.2,0.7,-1.5,-4.5,-6.2,-3.6,3,-0.9,0.8,3.9
Bowers & Wilkins P5 Series 2|-1.9,-2.2,-2.7,-2.8,-2,0.2,4.6,3.9,2,-0.5,-3.5,-5.3,1.3,2,3.4,6.2
Bowers & Wilkins P5 Wireless|-1.4,-1.3,-1.6,-1.6,-1.7,-1.7,0.9,3.6,2.5,-0.9,-3.4,1.4,2.8,-0.1,3.7,5.4
Bowers & Wilkins P7|-1.5,-1.3,-1.4,-1.9,-2.7,0.2,2.2,1.1,0.2,-0.3,-1.9,2.8,1.8,3.7,2.3,0.7
Bowers & Wilkins P7 Wireless (wired)|2.9,1.9,0.7,0,-0.8,-0.7,0.4,-0.1,-0.7,-1.7,-3.4,-3,-1.2,-0.1,2.7,1.6
Bowers & Wilkins P7 Wireless (wireless)|1.7,0.5,-0.7,-1.5,-2.7,-2.9,-0.1,-0.1,-0.1,-0.3,-1,-1.6,0.1,-0.2,3.3,2.4
Bowers & Wilkins Pi7|-2.5,-1.9,-1.7,-2.3,-3.2,-2.6,-0.7,1.8,4.9,3.3,3.5,4.1,1.5,6,1.9,-5.9
Bowers & Wilkins Pi7 S2|-2.2,-1.6,-1.5,-2.1,-3.2,-2.3,0,2.9,3.8,3.2,2.9,3.3,0.3,4.9,2,-4.7
Bowers & Wilkins Pi7 S2 (ANC Off)|-3.7,-2.9,-2.4,-2.7,-3.6,-1.5,0.8,2.9,3.8,3,2.2,0.8,0.3,7.7,4.5,-3.3
Bowers & Wilkins Pi7 S2 (ANC On)|-2,-1.5,-1.6,-2.2,-3.5,-3,-1.3,0.6,2.5,2.9,3,2.8,1.3,4.5,4,-3.7
Bowers & Wilkins PX|-0.8,-2.3,-1.9,-5.1,-6.3,-6,-4.1,-1.2,1.5,1.4,2.9,0.7,3.9,2.7,3.9,4.7
Bowers & Wilkins PX5|0.4,-1.4,-3,-4,-5.3,-4,-0.2,0.2,3.6,5,3.4,-1.1,3.2,1.8,-3.7,-0.8
Bowers & Wilkins PX7|-2.9,-3.4,-3.8,-5,-4.3,0.3,2.3,2.3,1.5,3.2,6.2,2,7.5,7.6,-3.9,-5.6
Bowers & Wilkins Px7 S2|-1.8,-0.6,-2.7,-5.1,-4.9,-2.2,0.7,2.5,2.2,1.7,4.7,4.5,1.7,2,1.2,2
Bowers & Wilkins Px7 S2e (ANC off)|-1.7,-2.4,-2.2,-2.3,-2.8,-2.3,-0.1,1.3,1,1,4.1,4.1,1.6,2.2,1.7,0.8
Bowers & Wilkins Px7 S2e (ANC on)|-2.6,-2.4,-3.1,-2.4,-2.3,-1.9,0.9,2.2,1.3,1,4.5,4.6,2.2,2.8,1.9,1.3
Bowers & Wilkins Px8|-0.4,-1.2,-4.1,-4.3,-4,-2.2,0.9,1.4,0.5,1.2,4.4,3,0.6,3.5,-2.4,-2.1
BQEYZ Autumn (bass filter)|0.2,-0.1,-0.9,-2.3,-3.7,-3,-1.5,0.1,1.5,0.8,-1.2,-0.4,1.6,-2.1,5.2,5
BQEYZ Autumn (no filter)|6.2,6.2,6.2,6.2,6.2,6.2,4.1,-2.8,-7.8,-5.3,-5,-3.2,-2.3,-9.3,-4,-3.3
BQEYZ Autumn (normal filter)|2.5,1.9,0.6,-1,-3.1,-3.2,-2.1,-0.8,0.7,0.1,-2.1,-1.1,0.8,-3.8,3.9,3.8
BQEYZ Autumn (treble filter)|4.3,3.6,2,0.3,-2.3,-3.1,-2.5,-1.5,-0.2,-0.6,-2.7,-1.8,0.1,-5,3.2,3.1
BQEYZ K1|-1.4,-1.7,-2.4,-3.4,-4.4,-3.3,-1.5,0.5,2.2,1.7,0.7,0.5,-0.8,6.1,1.1,0.2
BQEYZ KB100|1.9,1.3,-0.1,-1.8,-3.7,-3.6,-2.3,-0.7,1,0.5,-1.2,-1.9,-1.2,5.2,0,-0.7
BQEYZ Spring 1|1.2,0.9,-0.3,-2.1,-4.1,-4.1,-2.9,-0.9,1.5,2.2,0.9,3.4,3.7,5.7,-0.2,-1.5
BQEYZ Spring 1 (mesh mod)|1.3,0.8,-0.4,-2.1,-4.3,-4.4,-3.1,-1.2,1.1,1.9,-0.9,-1.3,0.7,5,1.2,2.4
BQEYZ Spring 2|0.4,0.3,-0.5,-1.7,-3.6,-3.5,-2.4,-0.8,1,0.9,-1.5,2.2,3.3,5.5,-2.7,-0.8
BQEYZ Summer|1,0.7,-0.4,-1.9,-3.6,-3.3,-1.8,0,1.8,1.6,-1,-0.3,0.4,5.1,-1.1,1.2
BQEYZ Topaz|1.2,0.8,-0.3,-1.9,-4,-4,-2.8,-1.3,0.2,-0.3,-2.2,-0.4,2.2,5.3,0.9,1.2
BQEYZ Wind|1.1,0.9,0.4,-0.6,-1.5,-1.3,-0.3,1,1.5,0.8,-0.5,-1.9,2.9,-0.8,1.4,0.5
BQEYZ Winter|2.3,1.8,0.6,-1,-2.9,-2.9,-2.1,-0.9,-0.1,-0.8,-3.2,1.1,3,0.6,1.5,0.6
Brainwavz B400|3.1,1.6,-0.6,-2.7,-5.5,-6.3,-5.4,-3.7,-1.1,-0.6,0,3.3,-0.4,3.4,4,3.7
Brainwavz HM5|4.5,3.9,2.1,0.2,-2.1,-1.1,4,1.1,-0.7,-1.3,-1.6,-2.6,-3.4,-3.2,1.4,0.5
Brainwavz Koel|4.3,4.3,3.3,0.4,-3,-4.5,-4.3,-3.3,-1.6,-1.3,-0.7,1.5,-0.1,4.3,-4.2,-1.8
Brainwavz M2|4.9,3.3,0.9,-1.3,-3.9,-4.8,-4.1,-3.3,-2.4,-3.7,-4.3,1.7,4.7,2.7,3,1.9
Brainwavz M5|4.8,4.8,4.8,4.8,4,1.7,0.2,-0.4,0.6,0.4,0.4,-1.2,-8.4,-7.8,-4.9,-1.1
Brainwavz R3|3.1,2.5,0.6,-1.3,-4,-5.3,-4.9,-4.4,-2.8,-2.2,-0.9,2.7,5.7,5.5,4.3,1.7
Brainwavz S0|-1.9,-2.3,-3.4,-4.7,-6.1,-5.4,-3.5,-1.5,1.5,3.6,5.2,4.4,2.2,-3.2,6.2,5.8
Brainwavz S1|-1.7,-1.9,-2.7,-3.8,-4.7,-3.5,-1.3,0.9,4,5.5,5.7,5.3,0.4,-2.3,4.1,0.6
Brainwavz S5|-2,-2.1,-2.7,-3.6,-4.2,-2.7,-0.7,1.4,4.3,5,7.3,7.3,1.4,-3.5,0,0.5
BÖHM B-66|1.2,1.5,1,0.1,-1.7,-2.5,-1.8,1.9,1.7,-1.6,-6,-2.6,-0.2,3.5,-2,2.2
Caeden Linea No 10 (wired, active)|-1.9,-2.7,-4.2,-4.9,-4.8,-1.9,0.5,2,1.5,-0.2,0.2,4.3,7.2,3,2.9,2.3
Caeden Linea No 10 (wired, passive)|-1.7,-2.5,-3.8,-4.2,-4.7,-2.4,-1,0,0,-1.1,0.1,5.2,6.8,2.7,3.6,4
California Headphone Company Lorado|5.9,2.4,-0.8,-2.9,-4.5,-4.8,-1.9,-1.5,-0.8,-2.2,-2.7,1.4,3.6,3,0.6,-2.5
California Headphone Company Silverado|-2.8,-1.3,-2.9,-5.8,-5.7,-4.3,-2.9,0.8,4.5,4.8,2,6.6,6.7,6.4,-0.7,0.3
Cambridge Audio Melomania 1|1.3,1.1,0.1,-1.5,-3.4,-3.3,-2.3,-0.2,2.1,1.2,1,1.6,5.3,6.5,0.1,-5.2
Cambridge Audio Melomania 1+|-2.3,-2.2,-2.6,-3.5,-4.3,-3.2,-1.2,1.5,4.3,2,2.3,2.5,6.3,7.3,1.4,-1.7
Campfire Audio Andromeda|1.8,1.1,-0.3,-2.1,-4.2,-4.7,-4.3,-2.8,-0.9,-0.4,-0.4,4.3,5.3,2.6,2.8,2
Campfire Audio Andromeda (pre-2020)|1.1,0.4,-0.9,-2.6,-4.9,-5.5,-4.7,-3,-0.6,0.5,0.4,5.6,5.7,4.6,1.9,1.3
Campfire Audio Andromeda 2020|1.5,0.9,-0.3,-2,-4.3,-4.8,-4,-2.5,-0.2,0.8,0.1,3.7,5.6,4.8,2.8,-0.5
Campfire Audio Andromeda Gold|1.2,0.4,-0.6,-2.1,-4.1,-4.1,-2.9,-0.9,1.6,1.3,-3.4,4.5,5.7,1.8,0.2,0.4
Campfire Audio Andromeda SS|2.3,1.2,-0.5,-2.4,-4.9,-5.8,-5.3,-4.2,-2.3,-0.8,0.2,4.4,5.2,5.2,2.5,1.8
Campfire Audio Ara|3,2.3,1,-0.8,-3.3,-4.1,-3.6,-2.3,-0.6,-1.4,-5.4,4.9,2.8,0.1,0,0
Campfire Audio Atlas|-3.1,-3.1,-3.7,-4.6,-5.6,-4.1,-1.9,0.7,3.3,3.7,3,5.4,4.8,0,1,3.5
Campfire Audio Bonneville|-1.7,-1.5,-1.8,-2.7,-3.6,-2.4,-0.8,0.8,1.9,1.5,-0.6,7.3,6.3,5.7,-3,-5.6
Campfire Audio Cascade|0.4,-1.6,-4.7,-6.1,-7,-4,1.7,1.4,1.8,0.6,2.3,2.1,2.9,0.1,1.4,3.3
Campfire Audio Comet|3,2.5,1.1,-0.7,-3.2,-4.3,-4.4,-3.2,-1.6,-0.9,0.6,1.6,-0.9,4.3,-1.8,1.2
Campfire Audio Dorado|-2.5,-2.7,-3.6,-5,-6.6,-5.9,-4.1,-2.1,0.2,0.3,0.6,6.4,6.4,6.4,3.5,5.1
Campfire Audio Dorado 2020|-2.2,-2.4,-3.1,-4.4,-5.7,-4.7,-2.7,-0.1,3.9,2.9,0.8,6.4,6.2,1.8,1.6,2.7
Campfire Audio Equinox|-2.4,-2.8,-3.6,-4.9,-6.5,-5.4,-3.4,-0.7,2.9,4.6,4.5,2.6,4.2,5.7,0.5,1.5
Campfire Audio Holocene|2.7,2.3,0.8,-1,-3.6,-4.8,-4.6,-3.7,-2.3,-1.7,0.1,5.8,5,5.8,0.7,-3.9
Campfire Audio Honeydew|-4.7,-4.5,-4.8,-5.4,-6.2,-4.7,-3,-1,0.8,1.5,4,7.1,7.1,1.8,2.7,5.4
Campfire Audio IO|2.2,1.5,0.2,-1.6,-3.8,-4.4,-3.5,-1.9,-0.1,-1.2,-6.7,6.1,5.1,6.1,0.3,-2.1
Campfire Audio Mammoth|-0.8,-1.1,-2,-3.6,-5.6,-5.5,-3.5,-0.5,2.3,1.4,-0.9,1.1,5.7,-2.9,5.4,5.6
Campfire Audio Onyx|-5.3,-5.4,-6,-7,-8.2,-6.6,-4.3,-1.1,2.9,6.7,4.9,6.3,6.4,6,4.3,4.1
Campfire Audio Orbit|-0.8,-1.2,-2.2,-3.2,-5.9,-5.9,-5.1,-3.4,-0.7,1.9,6.5,6.6,6.6,1.8,-0.6,2.3
Campfire Audio Orion|2,1.5,0.1,-1.7,-4.3,-5.3,-4.6,-3.4,-1.4,-1.1,-2.1,0.3,5,-1.8,4.4,5
Campfire Audio Pathfinder|-0.9,-0.7,-0.8,-1.6,-2.8,-2,-0.9,0.2,1.8,2,-1.4,6.4,4.6,2.4,-1.5,-3.5
Campfire Audio Polaris|-1.7,-1.3,-1,-1.6,-2.3,-0.9,0.6,2,2.9,0.9,-2.1,0,2.7,1.1,0.1,3.9
Campfire Audio Polaris (V1)|-1.7,-1.4,-1.2,-1.7,-2.4,-1,0.6,2,3,1.1,-2.1,0.1,2.8,1.3,0.1,4
Campfire Audio Polaris V2|-4.7,-4.6,-4.8,-5.6,-6.3,-4.5,-2.3,0.4,4,3.9,3,3.3,3.8,5.1,1.1,5.3
Campfire Audio Satsuma|1.9,1.4,0.1,-1.6,-3.9,-4.6,-3.9,-2.3,0.1,1.2,2,2.4,-0.9,-0.1,0.2,4.5
Campfire Audio Solaris (pre-2020)|1.4,1.1,0.1,-1.3,-3.3,-3.7,-3,-1.8,-0.7,-0.1,-0.4,1.4,5.1,6,2,-0.6
Campfire Audio Solaris 2020|1.1,0.9,0,-1.4,-3.2,-3.6,-2.9,-1.5,0.5,1,-3,4.4,5.9,5.2,0.5,-1.2
Campfire Audio Solaris SE|1.7,1.5,0.6,-0.8,-3,-3.6,-3,-2.1,-1.1,-0.7,-3.7,3.5,4.2,6,2.7,-0.2
Campfire Audio Solstice|0.2,-0.3,-1.4,-3.1,-5.4,-5.9,-5,-3.2,-0.3,0,0.1,5.7,5.8,4.4,2.6,3.9
Campfire Audio Supermoon|-1.8,-1.1,-0.5,-0.8,-1,0.3,1.5,2.6,2.8,0.8,-1.2,1.5,5.3,2.4,1,-1.4
Campfire Audio Vega (foam eartips)|-2.7,-2.6,-3.1,-4.2,-5.2,-3.8,-1.9,0.3,2.3,2.2,2.2,6.8,6.8,3.8,-3.2,-0.1
Campfire Audio Vega (pre-2020)|-3.4,-3.4,-3.8,-4.8,-5.8,-4.5,-2.7,-0.7,1.1,1.1,1.4,6.8,6.8,6.5,-0.8,5.1
Campfire Audio Vega 2020|-4.2,-4.1,-4.5,-5.4,-6.1,-4.8,-2.6,-0.1,2.6,2.9,-0.6,5,6.3,3.6,3,6.5
Canal Works CW-U77|1.2,0.7,-0.5,-2,-4,-4.2,-3.5,-3.1,-2.1,-1.4,-1.3,2.8,4.9,1.7,1.5,5
Canal Works CW-U91|2.2,1.1,-0.7,-2.6,-5.2,-6.1,-5.2,-3.5,-0.8,1.1,2.8,5.3,4.6,0.1,1.4,3.8
Canpur JF1|-0.3,-0.6,-1.5,-2.5,-3.9,-2.7,-1.1,0.9,2.6,1.4,-2.7,-0.6,-0.7,6.7,5,-1.7
Cardas A8|-1.2,-0.8,-0.7,-1.4,-2.6,-2.7,-2.2,-1.9,-2.1,-2.9,-2.1,5.6,5.2,-1.2,5.7,6
Cardas EM5813|-1.2,-1.5,-2.6,-4.1,-6.1,-6.1,-4.9,-3.4,-0.8,2.1,3.6,0.3,2.4,4.3,6.7,6.7
CAT Apollo|-1.6,-1.8,-2.3,-3,-3.2,-2.8,-0.8,2.1,2.3,1.7,0.8,0.3,1.9,0.5,-1.6,1.5
Cat Ear Audio Meow|2.4,1.7,0.3,-1.3,-3.3,-3.3,-2.2,-0.9,0.2,-0.7,-2.5,-6.9,-5.1,3.6,5,5
Cat Ear Audio Mia|-2.5,-2.4,-2.6,-3.5,-4.4,-3.3,-1.6,0.2,1.8,1,-2,-0.4,3.4,2.5,6.3,6.3
Cayin Fantasy|4.3,3.4,2,0.4,-1.6,-1.6,-0.4,1.1,2.7,1.8,0,-2.9,-0.4,-6.4,-2.6,-0.9
Cayin YB04|4.9,4.1,2.7,0.9,-1.5,-2.4,-2,-1.1,-0.2,-1.5,-5.3,3.4,0.3,1.8,0,-1.2
CCA A10|3.8,3.2,2.1,0.8,-0.7,0,1.4,2.4,1.4,-3.4,-5.4,-6.5,-0.8,-8,0,2.4
CCA C10|-0.3,-0.6,-1.2,-2.3,-3.6,-2.6,-0.9,0.9,2.5,1.9,-1.3,-1.4,2.5,0,1.6,2.4
CCA C12|0.6,0.4,-0.4,-1.5,-2.8,-1.8,-0.1,2.1,3.7,2,-0.1,-2.2,2.7,-1.6,2.9,-0.6
CCA C16|0.4,0.1,-0.7,-1.8,-3.1,-2,0,2.1,3,-1.2,-5.6,-3.2,1.8,2.3,0.6,3.6
CCA CA10|-3.8,-3.4,-3.5,-3.7,-3.7,-1.3,1.4,3.5,3.7,0.5,-1.9,0.6,3.8,-3.8,3.5,6.4
CCA CRA|-1.8,-1.1,-0.6,-1,-1.5,-0.4,0.7,1.8,2.7,2,0.5,2.5,3,0.2,0.6,-1.1
CCA CRA+|-0.8,-1,-1.7,-2.6,-3.6,-2.3,-0.7,1.3,2.9,2.2,1.9,2.9,4.1,1.8,1.7,-3.4
CCA CSN|-0.2,-0.5,-1.2,-2.3,-3.5,-2.6,-1.2,0.4,1.5,0.6,-2.7,1.4,4.9,-1.2,0.3,2.8
CCA CST|0.8,0.6,-0.2,-1.3,-2.6,-1.7,-0.1,1.8,3.7,3.5,2,-1,1.8,-2.9,-0.7,0.1
CCA CXS|0.1,-0.6,-1.2,-2.1,-3,-2.1,-0.8,0.5,1.3,0,-1.6,-2.6,0,0.6,0.7,2.2
CCA DUO|2.3,2.4,2.1,1.4,-0.2,0,0.6,1.1,1,0,-0.1,-0.5,1.3,1.3,-1.6,-5.4
CCA FLA|-2.9,-2.7,-2.6,-3.1,-3.4,-1.9,-0.2,1.4,2.5,1.6,1.7,-0.2,-0.5,4.6,2.5,0.3
CCA NRA|2.4,1.5,-0.1,-1.9,-4.4,-4.5,-2.7,0,2,-0.6,-2.3,1,5,-1.7,0.8,1.2
CCA Pianist|0.8,1.6,2.5,1.8,1.2,1.8,2.6,2.9,2.1,-0.6,-1.9,-1.5,-0.5,-2,-2.5,-4.7
CCA PLA13|-2.9,-2.3,-1.9,-2.3,-2.5,-1,0.9,2.6,4.1,3.4,1.3,-0.3,4.4,4,4.4,-1.8
CCA Polaris (off-off-off-off)|1.9,1.9,1.1,-0.2,-1.8,-1.8,-1,0,0.6,0,-0.1,0.3,0.1,0.4,-1.8,-3.2
CCA Polaris (off-off-on-off)|-0.3,0.3,0.4,-0.2,-1.2,-0.9,-0.2,0.6,1.2,0.4,0.5,0.8,0.7,0.9,-1.3,-2.8
CCA Polaris (off-on-off-off)|0.6,1.1,0.9,0,-1.3,-1.3,-0.6,0.2,0.9,0.1,0.2,0.6,0.5,0.7,-1.5,-3
CCA Polaris (on-off-off-off)|1.3,1.6,1.1,0,-1.6,-1.6,-0.8,0.1,0.7,0,0.1,0.4,0.3,0.6,-1.6,-3.1
CCA Rhapsody (off-off-off-off)|0.1,-0.7,-2,-3,-3.6,-1,1.4,3.2,3.5,1.7,1.1,1,1.1,1,1.1,-1.7
CCA Rhapsody (off-off-on-off)|1.1,0.3,-0.9,-1.9,-2.3,-0.1,1.7,2.6,2.7,0.9,0.3,0.3,0.3,0,0.2,-2.4
CCA Rhapsody (off-off-on-on)|2.3,1.5,0.4,-0.6,-0.9,1,1.8,1.9,1.6,-0.1,-0.8,-0.6,-0.6,-1,-0.7,-3.2
CCA Rhapsody (off-on-on-off)|-1,-1,-1.1,-1.5,-1.2,1.2,2.4,3,3,1.2,0.6,0.6,0.5,0.1,0.4,-2.1
CCA Rhapsody (on-off-off-off)|-0.6,-0.9,-1.6,-2.6,-3.1,-0.7,1.6,3.3,3.6,1.8,1.1,1,1,0.8,1,-1.7
CCA Rhapsody (on-off-on-off)|0.5,0.1,-0.5,-1.4,-1.9,0.1,1.7,2.6,2.7,1,0.3,0.3,0.2,-0.1,0.2,-2.4
CCA Rhapsody (on-on-off-off)|-2,-2.1,-2.3,-2.7,-2.6,0.4,2.5,3.8,3.9,2.1,1.4,1.3,1.3,1,1.2,-1.5
CCA Rhapsody (on-on-on-off)|-1,-1,-1.2,-1.5,-1.2,1.2,2.4,3,3,1.2,0.6,0.6,0.5,0.1,0.4,-2.1
CCZ Emerald|0.8,-0.8,-2.4,-3.9,-5.2,-4,-1.9,0.5,3.1,1.9,1.8,-0.1,1.1,-1.6,3.1,2.5
CCZ Melody|-1.7,-2,-2.9,-3.8,-5.1,-3.4,-1.5,1.1,3.4,2.3,1.6,-0.2,1.9,1.9,3.6,2.2
CCZ Plume|-1.5,-2.1,-3.1,-4.3,-5.5,-4,-1.9,0.7,2.7,2.9,2.5,1.8,4.2,-0.4,1.8,0.8
CCZ Warrior|0.2,-0.6,-1.9,-3.4,-5.1,-4.3,-2.8,-0.7,1.2,1.2,0,3.1,5.5,3.6,3.3,1.5
CHIKYU-SEKAI 16 Cosmos|2.1,1.2,-0.2,-1.8,-3.8,-4,-3.2,-1.9,-0.4,-0.3,-1.4,-1.5,1.1,5.8,5.8,1.4""",
"""Clariar i430|2.5,2,1.1,-0.6,-2.3,-2,-1,0,0.7,0,-1.2,-0.6,0,2.6,0.4,-2.4
Clariar i640|-0.7,-0.1,0.2,-0.4,-1.3,-0.9,0,1.2,2.1,0.9,-1.4,-1,0.8,3.5,1.9,0.3
Clear Tune CT-200|3.9,3.9,3.9,2.2,-1.4,-3.1,-3.1,-2.5,-1.7,-2.2,-3.2,-1.7,-1.5,-1.9,-0.7,3.9
Clear Tune CT-300P|0,-0.4,-1.6,-3.4,-5.8,-6.6,-5.8,-4,-0.8,3.3,5.3,3.9,3.6,-0.2,0.9,5.3
Clear Tune CT-6E|2.5,1.9,0.6,-1.2,-3.8,-4.8,-4.2,-2.7,0.8,2.6,-5.2,-2.7,-1.6,3.4,4.7,4.7
Clear Tune CT500-E|2.2,1.7,0.4,-1.5,-4,-4.8,-4,-2.1,1.5,2.8,-4.7,-2.2,-1,-1.3,4.6,4.7
Cleer Next|2.3,1.7,0.6,-0.9,-3,-3.5,-2,-0.3,1,1.2,0.3,-1.9,0,-2.4,3.4,3.8
CMF by Nothing Buds Pro (ANC on, balanced EQ)|-3.3,-4.3,-3.7,-2.2,1.9,1,1.3,0.9,1.7,-0.1,0.1,0.7,1.1,1.7,-0.8,1.6
Co-Donguri Brass|0.8,0.1,-1,-2.4,-3.7,-2.7,-0.9,1.4,3.7,3.8,2.8,-2.8,0.4,-2.6,1.7,-0.2
Co-Donguri Shizuku|-0.5,-0.5,-1.3,-2.3,-3.3,-2.7,-0.6,1.7,3.9,3.9,2.8,-3.7,-0.5,-5.3,3,0.8
Colorfly Quintet|2.6,2.3,1.6,0.4,-1.3,-1.4,-0.6,0.3,0.3,-1.8,-5.2,-2,-0.3,1.9,0.5,1.4
Comradz NW-STUDIO|6.3,6.3,6.3,6.3,6.3,6.3,6.3,2.3,4.2,1.1,-8.2,-12.8,-8.1,-9.3,-12.7,-5.5
Comradz NW-STUDIO PRO|3.9,3.9,3.9,3.2,-0.3,-2.6,-2.3,-1.1,2.6,-2.3,-12.8,-3.6,-0.9,-3.7,0.3,3.9
Cooler Master MH630|5.5,2.6,-0.4,-1.8,-2.4,1.8,6,1.8,3.8,4.4,3.4,-4.5,-1.9,-1.7,-6.5,-7.3
Cooler Master MH751|4.5,2.2,0.4,-1.5,-2.1,-1.1,-0.4,0.5,-0.1,-0.7,-0.5,-1,2.4,4.2,-1.8,-2.6
Cooler Master MH752|0.5,1.6,0.2,-2.6,-3.2,0.2,1,1.2,0.6,-1,-0.2,0,3.4,4.1,3,-1.2
Corsair HS35|-0.2,-1.3,-3.1,-4.1,-5.2,-3,1.8,2.7,3.2,7.2,7.2,0.9,1.2,-3.2,-2.7,-0.6
Corsair HS50|1,-0.2,-1.8,-2.9,-3.6,-0.3,2.7,2.1,-0.1,1.2,2.5,2,2.4,-0.5,-4.3,-3.6
Corsair HS60|0.8,-0.2,-1.8,-2.9,-3.4,0.4,3.7,2.5,0.2,1.7,3.3,1,4.4,-1.1,-3.5,-4.5
Corsair HS60 HAPTIC|0.8,-1.4,-3.1,-4.1,-4.5,-3.8,-2.7,0.7,0.2,2.7,6.1,1.6,3.2,3,-3.9,-2.2
Corsair HS60 PRO SURROUND|-1,-2.5,-4.4,-5.8,-6.4,-4.5,-1.5,2.4,3.1,4.1,6.5,5.6,4,2.1,-1.4,0.3
Corsair HS70 Bluetooth|1.9,-0.9,-3.3,-4.7,-5,-1,2.3,3.8,4.8,3.5,5.6,-0.3,1,-1.3,-6.8,-4.3
Corsair HS70 Pro|-0.5,-2.4,-4.5,-5,-4.3,-0.9,-0.4,-0.1,2.6,4.7,3.9,-1.6,3.4,0.7,-3.6,0.4
Corsair HS70 Wireless|1.4,0.4,-1.6,-2.7,-2.1,0.5,1,1.3,2.8,2.7,2.9,0.4,1.8,-0.6,-4.6,-6.3
Corsair HS75 XB WIRELESS|-0.1,-3.4,-6.3,-6.8,-6.2,-4.3,-2.5,-0.7,1.7,2.1,4.4,1.1,4.9,5.7,-0.7,2.1
Corsair HS80 MAX WIRELESS|3.2,1.7,-1.1,-2.7,-5,-5.7,-4.6,-1.3,-1.8,-2.7,0.2,3,4.9,4.8,-1.3,1.8
Corsair HS80 RGB WIRELESS|3.9,2,-1.1,-2.8,-5.2,-6,-4.6,-1.9,-0.8,-3.2,0.2,2.6,4.8,2.6,1.1,3.2
Corsair Virtuoso RGB Wireless SE|2.5,1.9,-0.8,-2.8,-5.4,-4.8,2,4.7,-0.1,-1.5,-1.5,-0.3,1,0.7,-0.4,0.9
Corsair VIRTUOSO RGB Wireless XT|4.6,4.4,1.1,-1,-3.8,-4.4,0.5,2.6,-1.1,-2.2,-4.5,-4.1,0.1,1,0.9,1.6
Corsair Void Elite|4.5,2.4,-1.2,-3.2,-6.1,-6.4,-4,0.8,3.2,-1,0.5,0.7,1.1,-0.8,-1,3.5
Corsair Void PRO RGB|3.8,-0.9,-4.1,-4,-2.9,-2.9,0,0.9,-0.6,-5.5,-1,2.8,3.4,-0.2,-1.1,3.8
Cosmic Ears CE6B|-1.4,-1.6,-2.5,-3.8,-5.3,-4.8,-3.6,-1.9,-0.2,-0.8,-0.5,3.1,5.5,4.7,4.6,3.8
Cougar Immersa|3.8,1,-2.7,-5.5,-7.2,-5.9,-3.6,0.3,2.2,4.1,5.6,2.9,1.7,2.1,-1.1,-1.1
Cowin Apex Elite|5.2,3.1,0.2,-2.3,-5.4,-6.1,-4.5,-1.6,2.5,2.3,5.8,5.9,5.9,-2.8,-5.1,-2.5
Cowin E7|1.3,1.3,0,-1.3,-3.5,-5.2,-4.6,-3.6,-3.4,-2.8,6.3,7.3,7.3,2.6,1.7,1.5
Cowin E7 (wired, ANC off)|6,2.4,-0.2,-4.6,-8.7,-8.1,-3,0.3,2.7,2,5.8,6.1,6.1,-1.9,-1.5,-1.6
Cowin E7 Pro|6.1,1.2,-1.9,0,-0.1,1,4.8,4.3,2.9,-2.8,-7,1.9,5.3,-1.6,-5.3,-6.4
Cowin E8|3.1,0.5,-2.4,-4.1,-5.6,-4.3,-2.1,-1.6,-2.2,-3.1,4.7,5.4,5.4,-2.7,-1.1,3.4
Cowin SE7|4.6,4.6,1.7,-1.4,-5.3,-7.7,-7.2,-5.2,-3.5,-2.5,-1.8,3.9,4.1,4.6,2.9,0.6
Cozoy Hera C103|-2.3,-1.2,-0.7,-1.2,-2.4,-2.4,-1.9,-1.3,-0.7,-1.5,-2.5,2.2,5.9,0.1,3.5,5.3
Cozoy Trio|2,1.2,-0.3,-2.1,-4.6,-4.9,-3.2,-0.1,3.4,-0.1,-3.5,1.9,0.6,2.4,0,3.4
Craft Ears Argentum|-1.4,-1.1,-1.1,-1.9,-2.8,-1.7,-0.3,1.2,2.1,1,-1,2.3,1.7,4.7,5.8,-1.9
Craft Ears Aurum|-1,0.5,1.8,1.5,0.5,0,0,-0.2,-0.3,-0.8,0.3,1.1,2.5,5.8,4.7,-2.3
Craft Ears Craft SIX|1,1.3,1.5,0.9,0,-0.1,0,-0.1,0.1,-0.6,-2.1,1.3,-0.8,5.4,1.4,-2.1
Craft Ears Craft SIX (CIEM)|-0.1,0.2,0.4,-0.1,-0.9,-0.8,-0.6,-0.4,-0.1,-0.7,-2.2,1.5,-0.4,6.4,4.9,-2.3
Craft Ears Craft TWO|0.1,-0.3,-1.4,-3,-4.9,-5,-4,-2.6,-0.9,-0.8,-0.2,2.2,3.9,0.8,5.5,4.7
Craft Ears Cuprum|2,1.6,0.5,-1.1,-3.1,-3,-1.8,-0.3,1.3,0.9,-0.4,2,3.1,1.2,4.1,-2
Creative Aurvana|1.8,0.6,-0.7,-1.8,-2.7,-2.3,-0.6,0.1,-0.8,-0.7,1.3,2.4,0.9,1.6,2.4,-0.6
Creative Aurvana Gold|-3.4,-2.9,-2.9,-3.4,-4.5,-4.8,-4.3,-3.9,2.2,-0.1,3.9,6.8,6.9,1.6,3.2,5.8
Creative Aurvana In Ear 3|4.1,4.1,1.9,-0.8,-3.8,-5.2,-4.6,-3.9,-2.2,-2,-1.2,1.1,1.4,0.1,0.5,4.1
Creative Aurvana Live!|1.8,0.8,-0.5,-1.6,-2.6,-1.5,0.5,1.3,-1.7,-1.7,-0.1,2.4,1.9,2.2,1.6,-1.3
Creative Aurvana Live! (Brainwavz PU leather earpads)|5.3,5.3,1.6,-0.7,-3.1,-1.8,2.1,1,-1.9,0.7,0.5,1.1,3.8,-2.2,-5.4,-2.7
Creative Aurvana Live! (Brainwavz sheepskin earpads)|2.6,0.8,-1.6,-3,-4.6,-3.4,1.3,6.3,0.8,0.5,1.5,1.8,4.5,-0.8,-3.2,-2.6
Creative Aurvana Live! (Brainwavz small oval perforated earpads)|1.4,0.5,-0.3,-0.9,-2.2,-1.4,1.3,4.1,1.5,0.6,1.9,2.8,2.3,0.4,-2.4,-1.7
Creative Aurvana Live! 2|-1.3,-1.6,-2.6,-4.7,-6.3,-6.1,-3.7,0.1,3.9,3.1,5.4,6.4,6.5,4.5,4.3,0.7
Creative Aurvana Trio|-0.6,-1,-2.1,-3.6,-5.5,-5,-3.3,-1.1,1.7,2.9,2.6,1.5,5.1,1.7,0.3,1.5
Creative Aurvana Trio LS|-3.2,-3.3,-3.9,-4.9,-5.7,-4.4,-2.1,1,3.4,4.4,5.2,4.2,3.1,-1.4,0,2.5
Creative Fatal1ty|-2,-3.5,-4.7,-5,-5.5,-4.1,-1,0.1,-1.7,-1.8,0.7,5.9,3,6.9,5.6,1.5
Creative Outlier Air|-0.4,-1,-2,-3.1,-4.1,-3,-1,1.5,3.7,3.1,3.3,3.6,0.7,3.5,-3.8,-1.4
Creative Outlier Air V2|-0.2,-0.1,-0.5,-1.5,-2.5,-1.5,0.1,2.6,5.3,3.2,3.8,4.6,-1.4,-0.5,-5.1,-2.6
Creative Outlier Pro|-0.3,-1,-1.3,-1.9,-3.1,-3.9,-3.7,-2.3,0.6,1.1,2.5,-0.8,-3.7,4.7,5.7,5.2
Creative Sensemore Air|5.2,4.3,1,-1.7,-5,-5.7,-4.2,-1.6,1.3,1.9,3.2,3.5,2.3,5.1,-5.9,-7.2
Creative Sound Blaster EVO ZxR|2.6,-0.4,-3.2,-4.7,-7,-8,-6.8,-2.4,3.9,2.7,5.7,5.7,5.5,3.9,-3,-1.8
Creative Sound BlasterX H5|5,5,2.1,-1.1,-4.2,-5,-3.5,-1.6,1.5,4.6,4.4,-2.3,-2.1,-0.9,-4.1,-1.5
Creative Sound Blasterx H6 (analog input)|2.6,1.8,0.8,-1.3,-3.2,-2.1,-0.2,2.8,0.3,-2.9,2.2,3.6,-2,-5.9,-0.4,0.1
Creative SXFI Air|5.2,4.5,2.3,0.5,-2.1,-1.7,-1.6,0.1,-1.6,-2.1,3.7,2.4,-3.8,-4,-5.3,-3
Creative Zen Hybrid (ANC off)|0.3,1.7,1.4,1,-2.8,-2.3,0.3,1.4,2.7,2.2,-0.9,-3.8,-0.9,-0.2,-0.1,0.7
Creative Zen Hybrid (ANC on)|0.2,3.3,1.1,-2.2,-2.3,1.9,2.9,3.1,3.2,1.7,-1.8,-4.8,-2.5,-2.6,-0.8,0.7
Creative Zen Hybrid (passive)|-1,-0.9,-2.3,-1.6,-5.2,-0.7,-1.3,-2.2,-3.1,-2.3,1.2,6.1,6.5,5.2,2.1,1.4
Creative Zen Hybrid Pro (ANC off)|-1.4,-2.2,-3.1,-2.9,-5.2,-1.2,1,1,5,0.6,-1.2,0.9,3.5,3.1,0.4,1.5
Creative Zen Hybrid Pro (ANC on)|0.9,0.4,-0.9,-2.1,-3.6,-1.6,-0.3,-0.7,2.8,-2.3,-1.7,1.8,4.9,2.6,0,1.1
Creative Zen Hybrid Pro SXFI|-2.7,-3.8,-3.7,-3,-3.5,-1.8,1,1.2,5.8,1.3,-3.4,-0.3,4.3,3.7,-1.1,0.4
Crystal Piston|-2,-2.1,-3.1,-4,-4.8,-3.4,-1.4,0.7,3.6,4.3,5.8,4.4,0.8,-1.3,0.4,2.8
Custom Art Ei.3|0.9,0.3,-1,-2.6,-4.7,-5,-4.3,-3.4,-2.4,0.1,0.1,4.7,4.5,0.9,3.6,5
Custom Art FIBAE 1|2.5,2.2,1.2,-0.5,-2.9,-3.7,-3.2,-2.2,-0.7,-0.5,-1.6,-5.2,-0.9,-0.1,4.3,4.5
Custom Art FIBAE 2|1.4,0.8,-0.6,-2.3,-4.6,-5,-4,-2.3,-0.3,-0.6,-4.3,1.7,1.1,5.4,3.1,2.9
Custom Art FIBAE 3|2.2,1.7,0.6,-1.3,-3.6,-4.4,-3.6,-2.3,-0.1,1.6,3.1,-2.3,1.7,3.4,5.7,-1.1
Custom Art FIBAE 4|0.8,-0.2,-1.5,-3.2,-5.3,-5.7,-4.5,-2.5,0.5,2.1,2.8,0.7,6.3,1.2,6.3,5.5
Custom Art FIBAE 7|1.7,1.1,0,-1.4,-3.2,-3.2,-2.2,-0.9,0.6,-0.1,-2.2,-2.5,-1.1,2.8,5.7,5.2
Custom Art FIBAE 7 Unlimited|1.2,0.9,-0.1,-1.5,-3,-2.8,-1.7,-0.5,0.9,1.1,1.5,3.2,2.7,1.8,3.1,-1.1
Custom Art FIBAE Black|0.6,-0.1,-1.4,-3,-5.4,-6.2,-5.5,-3.7,-0.4,0,-1.2,1.3,4.2,5.4,4.9,4.7
Custom Art FIBAE ME|-1.3,-1.8,-3,-4.6,-6.4,-6.1,-4.3,-1.7,1.9,3.1,0,5.1,3.4,7.1,7.1,2.8
Custom Art Harmony 8.2|2.2,1.1,-0.7,-2.5,-4.5,-4.3,-3,-1.6,-0.3,0.4,-3.5,-2.9,5.5,5.4,5.5,5.4
Custom Art Pro330v2|2,1.3,-0.3,-2.3,-5,-6.4,-6.2,-5.1,-2.2,0.7,-0.6,3.8,0.1,2,4.5,4.6
CVJ Konoka (off-off)|-0.5,0,0.2,-0.4,-1.1,-0.7,0.3,1.1,1.2,-0.6,-3.5,-3.1,2.6,-3.1,3,3.4
CVJ Konoka (off-on)|0.5,1.1,1.2,-5,-12.1,2.2,1.8,2.3,2.2,0.4,-2.6,-2.2,3.6,-2.3,3.7,4
CVJ Konoka (on-off)|0.3,0.5,0.7,0.1,-0.5,-0.1,0.8,1.5,1.7,-0.1,-3,-2.8,3.2,-4.2,-0.4,-1.8
CVJ Konoka (on-on)|0.9,1.3,3.6,-3.1,-6.6,2.2,1.7,2.4,2.3,0.4,-2.6,-1.6,3.4,-3.9,-0.1,-1.6
CVJ Mei (off-off)|-2.9,-2.8,-3.1,-3.9,-4.7,-3.8,-2.6,-1.6,-1.4,-2.9,-3.2,4.3,1.6,7,7,7
CVJ Mei (off-on)|-1.2,-1.1,-1.4,-2.2,-3.1,-2.1,-0.9,0.1,0.2,-1.3,-1.7,5.3,2,2.5,-1.4,0.9
CVJ Mei (on-off)|-1.7,-1.6,-1.9,-2.8,-3.6,-2.7,-1.5,-0.4,-0.3,-1.8,-2.1,5.4,2.9,7.2,6.7,0.4
CVJ Mei (on-on)|-0.5,-0.4,-0.7,-1.6,-2.4,-1.6,-0.4,0.7,0.8,-0.8,-1.1,5.8,2.6,2.4,-0.8,-1.5
CVJ TXS (off-off)|-1,-1.1,-1.6,-2.6,-3.5,-2.5,-1.1,0.4,1.3,0.3,-1,-1,0.6,0,1.8,3.7
CVJ TXS (off-on)|-0.5,-0.7,-1.2,-2.2,-3.2,-2.2,-0.8,0.7,1.5,0.5,-1,-1.1,0.1,-0.7,0.7,2.3
CVJ TXS (on-on)|-0.9,-1,-1.5,-2.5,-3.5,-2.6,-1.2,0.2,1.1,0.1,-1.1,-0.7,0.7,0.1,1.9,3.7
Cyberdrive Forte Classic Bass|-4,-3.8,-4,-4.7,-4.9,-3.1,-0.7,1.6,4.4,5.4,7.3,7.9,5.1,1.7,-0.9,1.4
Cyberdrive Forte Classic Soprano|-4.6,-3.5,-2.5,-2.5,-2.2,-0.3,1.7,3.5,5.3,5.6,6.2,5.8,-4,-4.1,0,0.6
Cyberdrive Forte Impact Bass|-3.3,-3,-3.2,-3.8,-4.2,-2.9,-1.2,0.5,2.8,4.7,6.7,7.7,5.7,1.8,-1.4,0.7
Cypher Labs Astru IEM|4.4,3.5,1.4,-0.7,-3.1,-4.1,-3.3,-2.5,-0.5,-0.6,-1.2,-1.1,-1.8,-0.1,1.1,2.6
Cypher Labs Astru IEM (bass boost filter)|2.9,2.3,0.7,-1.1,-3.4,-4.2,-3.4,-2.3,-0.3,-0.2,-0.7,-0.5,-1.1,0.9,2,3.4
Cypherus Audio Campfred|4.1,1.8,-0.5,-2.2,-4.1,-2.9,-3.7,-3.2,-3,-3.9,-5.7,-0.7,4.9,-1.1,4.9,4.9
Daiso S2 earphones|2.9,2.3,0.9,-1,-4.1,-6.1,-7.1,-8.8,-11.8,-4.3,2.7,6,6,5.8,5.9,6
Dali IO-4|4.8,2.3,-1.5,-2.2,-5.4,-5.2,-3.5,-2.1,-0.9,0.5,2.2,1.8,1.6,4.9,2.4,-1.2
Dali IO-6|-0.6,-0.9,-5.4,-5.4,-2.5,-2.1,-1.4,-0.7,0.5,1.8,2.3,4.4,1.3,2.7,4.4,2.8
Dan Clark Audio Aeon 2 Closed|0.6,0.3,-0.5,-2.8,-1.9,-1.7,-1.6,-0.7,-0.3,-0.9,1.1,3.9,6.8,2.2,3.4,0.8
Dan Clark Audio Aeon 2 Closed (perforated earpads)|2.2,1.7,-1.2,-3.2,-0.3,0.6,1,0.6,1.8,1.3,0.6,0.8,0.9,-0.4,0.7,-1
Dan Clark Audio Aeon 2 Noire|0.8,0.4,-1.2,-2.6,-1.4,-0.9,-0.6,0.1,0.3,-0.5,1.2,2.8,5.4,-1.2,3.6,1
Dan Clark Audio Aeon 2 Open|1.3,1,-0.7,-3,-4.5,-3.3,-1.1,1.1,1.7,2.8,3.3,2.9,5.3,1,0.9,-0.4
Dan Clark Audio Aeon Closed RT|0.4,-0.3,-1.2,-2.2,1.7,-2.5,-1.9,-0.6,-1.1,-1,1.1,5,4.7,0,3.5,1.3
Dan Clark Audio Aeon Flow Closed|1.2,1.2,0.9,0,-0.1,-1.5,-1.7,-1.7,-1.3,-1,1,3.7,3.3,0.6,1.5,0.2
Dan Clark Audio Aeon Flow Closed (black filter)|3.2,3.7,1.5,0.6,-0.9,-3.7,-3.7,-3.6,-3.3,-0.9,-0.4,3,2.2,1.9,0.7,0.5
Dan Clark Audio Aeon Flow Closed (filters)|1.3,1,1.6,0.8,0.7,-1.5,-1,-1.6,-1.5,-0.3,0.6,3.1,-0.6,1.8,-2.1,-1.2
Dan Clark Audio Aeon Flow Closed (white filter with one notch)|2.7,3,1.1,0,-1.3,-3.8,-3.5,-3.5,-3.2,-0.6,-0.3,2.8,1.4,3,1.8,1.5
Dan Clark Audio Aeon Flow Open|1.5,0.6,-2.8,-4,-5.7,-5.6,-3.2,-1.7,-1.2,0.1,3.2,5.6,6.5,2.5,4.8,3.5
Dan Clark Audio Aeon Flow Open (white filter with one notch)|2.6,1.7,-0.6,-3,-4.5,-5.1,-3.7,-2.5,-0.9,0.5,0.1,4.1,1,2.6,0.6,1.5
Dan Clark Audio Aeon Open|1.1,0.3,-2.2,-4.1,-5.5,-5,-2.9,-1.1,-0.5,0.9,3.4,6.2,6.6,2.2,3.6,2.1
Dan Clark Audio Aeon X Closed|0.9,0.6,-0.7,-1.5,-0.7,-1.1,-1.1,-0.6,0,1.9,1.9,3.3,0.5,-0.1,1.4,-0.6
Dan Clark Audio Aeon X Open|3.3,0.8,-0.4,-2.9,-4.6,-3.8,-1.5,0.1,0.7,1.9,2.5,2.3,3.3,0.5,1.3,-0.3
Dan Clark Audio Alpha Dog (2014)|0,0.1,1,-0.3,-2.2,-5.4,-4.4,-4.4,-0.6,-1.3,1.6,2.6,0.5,2.4,2.2,5.2
Dan Clark Audio Alpha Prime|0.1,-0.3,-1,-1.3,-2.5,-2.7,-2.4,-2.5,-1.7,-1.6,1.3,4.4,3.6,-0.5,0,3
Dan Clark Audio Ether 2|2.6,2,0.6,-1,-3.3,-4,-2.5,-1.5,-1.6,-2.2,-1.2,1.5,5.2,1.8,4.6,4.9
Dan Clark Audio Ether C|4.7,4.8,3.7,2.8,2.1,-2.1,-1.7,-1.4,-1.4,-2.4,-1.6,-2.6,-4.8,-2.3,0.2,0.2
Dan Clark Audio Ether C (1 black filter)|2,2.9,2.1,0.2,0.7,-2.7,-2.3,-1.1,-2.5,-2.7,-1.3,-1.1,-3.8,0.6,1.2,2.3
Dan Clark Audio Ether C (2 black filters)|1.7,2.6,1.5,-0.2,0.3,-3.2,-2.6,-2,-2.5,-2.8,-1.9,-0.7,-3.5,1.2,1.5,3.9
Dan Clark Audio Ether C Flow|3.2,-0.3,-0.9,-1.7,-0.6,-3.5,-2.6,-0.9,-4.2,-2.2,-1.6,1.5,4.7,4.8,1.6,1
Dan Clark Audio Ether C Flow 1.1|0.7,1.3,1.1,-1.1,0.2,-1.6,-1.7,-1.3,-1.4,-1.1,1.8,4.5,1.1,1,1.4,0.7
Dan Clark Audio Ether CX|3.3,3.7,2.4,0.9,-0.6,-2.4,-2.3,-2.6,-2.4,-3.8,-2.6,0.1,1.7,0.2,1.1,1.8
Dan Clark Audio Ether Flow|4.4,0.7,-1.8,-2.8,-4.4,-4.8,-2.5,1.2,-0.6,0.7,0.9,-0.2,4.3,3,-2.5,-0.4
Dan Clark Audio Ether Flow 1.1|2.4,2,0,-2.2,-4.5,-4.9,-3.1,-1.6,0.6,1.1,4.5,4.4,1.4,1,0.2,1.7
Dan Clark Audio EXPANSE|-0.6,-0.2,-0.5,-2.2,-2.4,-0.1,0.7,0.8,0.6,0.6,1.1,0.1,-0.6,-0.3,2.7,1.9
Dan Clark Audio Mad Dog|4.8,4.8,2.3,-3,-2.4,-2.8,-2,-3.8,-3.2,-3,-0.4,4.2,1.7,3.5,-2.1,-0.9
Dan Clark Audio Mad Dog (2014)|1.7,0.8,-0.3,-1.7,-4.9,-5.8,-4.5,-1.6,-1.5,-1.7,0.8,4.9,4.3,2.8,0.3,4.3
Dan Clark Audio Stealth|-0.4,0,0.4,-1,-0.2,-1,-0.3,0.2,-0.5,-0.8,-0.4,0,1.3,-0.5,2.7,3.1
Dan Clark Audio Voce|4.7,3.9,1.5,0.4,-1.4,-2.9,-2.8,-3.7,-4.4,-8.6,-1.8,0.5,0.4,-2.3,3.5,3
Dark Project HS4 Wireless (breathable earpads, passive)|0.5,-0.4,-0.2,0,-0.8,0.6,3.7,1.5,-1.5,-2.6,-1.7,0,4.3,-0.1,-2.9,-2.1
Dark Project HS4 Wireless (breathable earpads, wireless)|1.8,1.4,1.1,0.7,-0.5,4.2,6.8,4.3,-3.8,-5.2,-1.2,4.1,6.8,-0.5,-7.6,-8.4
Dark Project HS4 Wireless (leather earpads, passive)|0,-1.1,-1.5,-1.9,-3.5,-2.4,2.4,2.3,-0.9,-1.5,-0.6,1.6,5.6,1.8,-1.5,-0.4
Dark Project HS4 Wireless (leather earpads, wireless)|0.6,0,-0.5,-1.6,-3.4,1.6,7,5.7,-2.5,-3.6,0.3,5.8,7,1,-6.1,-6.8
Debauche Bacchus Monroe|-1,-1.1,-1.7,-2.8,-4,-3.1,-1.4,0.7,3.1,2.8,0.6,3.3,2.5,6,0.6,0.4
Delta Air complimentary earphones|5.7,5.7,5.7,5.7,1.7,-6.5,-14.3,-19.3,-16.3,-4.5,5.8,5.4,3.8,3.4,5.7,5.7
Denon AH-C260R|-5.5,-4.8,-4.1,-4.3,-4.3,-2.2,0.1,2.5,4.7,5.2,5.8,5,1.5,1.6,2,4.3
Denon AH-C351K|1.8,1.4,0.2,-1.7,-4.3,-5,-5,-3.5,-1.4,0.7,3.8,6.2,6.2,3.8,0,-0.2
Denon AH-C360|-1.9,-2.1,-3,-4.3,-5.5,-4.5,-2.6,-0.4,2.7,4,4.9,6.3,0.7,-2.4,1.7,4.6
Denon AH-C452K|-3.2,-2.5,-1.9,-2,-2.1,-0.8,1.2,3.1,4.5,3.7,3.6,0.5,-0.4,-2.7,-1.3,2.9
Denon AH-C551|-0.2,-0.8,-1.8,-3,-3.9,-2.5,-0.2,2.1,4.7,4.3,3.5,-1.2,-1.5,-2.7,-0.2,2.8
Denon AH-C551K|-1.7,-1.5,-2.1,-3,-3.3,-2.1,0.3,3.1,5.6,5.6,4.4,-1.2,-1,-3.1,-0.5,3.6
Denon AH-C560R|-0.1,-0.7,-1.8,-3.3,-4.8,-4,-2.3,-0.3,2.2,4,4.7,3.6,-0.9,-2.6,-0.1,2.2
Denon AH-C710|-3.8,-3.5,-3.5,-4.1,-4.4,-2.5,-0.4,1.5,3.9,4.3,5.1,2.1,1,-2.9,0.7,4.5
Denon AH-C751K|-0.4,-0.7,-0.7,-1.3,-1.9,-0.7,1.2,3.2,4.6,4.1,3.1,-1.1,-2.1,-3.5,-1.8,2
Denon AH-D1001|5.2,4.1,2.1,0.3,-1.4,-1.3,0.5,0.8,-2.4,-2.7,0.5,1.2,-3.7,-1.3,-2,-3.4
Denon AH-D1001K Silver|5.2,3.9,1.5,-0.3,-1.2,-1.3,0,0.7,-2.3,-3.3,-0.1,2.5,-3.3,-2.8,-1.2,-1.4
Denon AH-D1100|-2.3,-4.4,-6.3,-6.9,-8,-7.5,-0.1,5.6,2.3,3.6,5.2,2.2,2.3,5.4,1,0.9
Denon AH-D2000|5.2,4.2,3.4,2.6,1.1,0,0.1,-1.8,-2.5,-2.9,-0.6,0.8,-2.7,-0.4,-2.8,-3.4
Denon AH-D2000 (2012)|1.5,1.4,0.8,-0.3,-1.7,-2.3,-1.3,-1.2,1.1,0.1,2.4,2.5,-0.8,2.3,-0.1,-2.1
Denon AH-D2000 (JMoney earpads)|4,3.4,2.9,2,0.6,0.1,0.6,-0.5,-1,-1,1,0.4,-4.6,-4,-2.2,-2.3
Denon AH-D310R|2.6,1.1,0.1,-1.8,-3.8,-5.6,-3.2,-1.1,0.9,-3.6,2,4.5,5.9,0.4,1.3,0.8
Denon AH-D400|2.2,0.5,-1.2,-2.4,-4.8,-5.9,-3.8,0.6,-1.2,-4.5,-1.5,-1.3,0.6,4,5.4,5.4
Denon AH-D5000|-1.2,-1.5,-1.3,-1.8,-2.5,-1.9,0,1.5,1.1,1.1,0.7,4,6.1,-2.5,1.2,1.2
Denon AH-D5000 (JMoney earpads)|3.6,2.9,2.6,1.6,0.5,-0.1,0.9,2.2,0.5,0.7,0.8,-0.3,-4.6,-4.8,-3.2,-4.2
Denon AH-D5200|0.6,0,-0.4,-1.4,-3,-3,0.1,0.5,-0.9,0.6,0,2.8,1.4,-2.3,1.8,1.7
Denon AH-D600|-0.9,-0.6,-2,-2.9,-3.8,2.6,1.5,1.2,1.1,0.9,0.1,0.5,2.2,6,2.8,-0.3
Denon AH-D7000|3.4,1.5,0.6,0.6,0,0.7,2.8,-0.1,-3,-2.1,0.9,2.7,-1.3,-1.1,-2.7,-3.2
Denon AH-D7000 (2012)|1.2,0.9,0.6,-0.5,-1.9,-2.2,-0.8,0.1,0.2,0.4,1.8,3.5,-2,1.3,-0.1,-1.3
Denon AH-D7100|-2,-1.2,-2.1,-4,-4.2,2.7,6.7,4.1,1.4,-0.3,-1.4,3.1,2.8,5.7,2,-1.2
Denon AH-D7200|0.6,0.4,0.3,-0.4,-1.6,-1.4,0.2,-0.9,-2.8,-2.5,1.6,5,3.2,-0.9,1.6,0.8
Denon AH-D9200|1,0.8,0.6,-0.8,-2.7,-2.8,-0.3,0.6,-1.2,-1.7,2,5.4,1.4,-2.4,1.6,-1.3
Denon AH-NC732K|0.5,0.9,1.4,0.9,0.6,-0.2,0,-0.8,-2.2,-6.3,-3.7,4.4,0.9,4.4,0.8,0.1
Denon AHGC20 Globe Cruiser|-6.4,-6.4,-5.5,-4.4,-3,-0.8,-0.8,-2.2,1.6,2.3,7.9,4.3,1.7,2.1,3.6,7.9
Denon PerL Pro|-1.1,-0.3,0.5,0.7,1.2,0.1,-1.5,-1.3,0.5,0.2,1.4,6.7,3.2,0.7,-4.8,-5.6
Dethonray Tender I|4.4,3.8,2.4,0.4,-2.5,-4.5,-4.8,-3.7,0.7,-1.8,-4.9,-1,1.2,5.7,5.7,1.9
Devialet Gemini|-4.4,-1.9,-0.2,-0.1,-0.2,1,1.1,0.8,1.5,2.1,2,2.2,-0.1,0.6,-1.7,-2
Diskin DH3|-3.5,-1.8,-1,-1.5,-2.9,-3.3,-1.9,2.1,1.7,-2.1,-4.4,0.8,2.1,5.4,-0.6,3.1
Dita Audio Answer|1.3,0.6,-0.3,-1.8,-3.7,-4.4,-3.8,-2.6,-0.6,0.9,2.6,5.8,3.5,-1.5,0.5,1.4
Dita Audio Brass|1.6,1.1,-0.1,-1.8,-3.8,-3.8,-2.5,-0.3,2.3,3.4,4.3,5.8,1.6,-4.1,-1,1.4
Dita Audio Dream|-0.9,0.1,0.7,-0.1,-1.5,-1.8,-1.1,-0.1,1.7,1.9,1.9,3.9,1.5,-1.5,0.5,0.5
Dita Audio Dream XLS|4.7,4.7,4.4,1.9,-1.4,-3.4,-3.7,-3.6,-3,-2.3,-0.9,3.8,1.1,-4.9,1.1,1.6
Dita Audio Fealty|1.3,0.6,-0.7,-2.2,-4,-4.1,-3.3,-2.2,-0.6,-0.1,1.6,5.5,3.6,-1.1,0.5,2.1
Dita Audio Fidelity|0,0.6,0.8,0.1,-1.4,-1.6,-0.7,0.7,2.4,2.3,2,2.4,-0.8,-4.6,-0.6,0.1
Dita Audio Perpetua|4.5,3.3,1.3,-0.8,-3.6,-4.8,-4.6,-4.1,-3.4,-3.4,-2.7,2.7,4.3,3.7,0.1,2.1
Dita Audio Project 71|-2,-1.7,-1.8,-2.4,-3.7,-3.7,-2.9,-1.7,-0.1,1,1.4,3.8,2.3,1.3,6.5,6.5
Dita Audio Sakura 71|0.6,1.2,1.2,0.5,-0.7,-1.7,-1,0.1,1.6,1.7,0.7,-0.6,-2.2,-3.1,4.3,3.3
Dita Audio Truth|0.9,0.6,-0.1,-1.4,-3.3,-4,-3.1,-1.9,0.3,1.6,2.9,6,3.4,-2.1,0.3,0.9
Dolby Dimension|3.8,1.6,0.1,-0.9,-2,-2.8,-2.2,-1.8,-2,-0.1,0.5,0.2,0.1,5.8,1.8,-3.9
Dual Driver|0.2,-0.5,-1.9,-3.4,-5.2,-4.7,-3.2,-1.1,1,1.2,-0.1,0.7,2.2,4.4,6.3,6.3
DubsLabs Bedphones|5,5,5,5,-0.4,-6.6,-7.4,-7.8,-7.8,-8.7,-6.9,1.9,5,3.7,5,5
DUNU DK2001|1,0.5,-0.6,-2.2,-4.4,-4.5,-3.1,-1,1.3,1.6,-0.8,-2.6,3.4,0.5,0.6,3.9
DUNU DK3001 Pro|1.1,0.6,-0.4,-1.8,-3.6,-3.7,-2.9,-1.8,-0.8,-1,-2.2,1.8,3.3,0.5,1.2,4.8
DUNU DK4001|2,1.8,1.3,0.1,-1.4,-1.7,-1.4,-1.2,-1.3,-2.7,-3.3,4.4,5.5,3.2,-1.7,1.6
DUNU DM380|-3.2,-2.6,-1.7,-1.9,-2.4,-1,0.9,2.8,4.3,5.7,2.6,-2.8,1.4,0.6,1.8,-0.9
DUNU DN1000|3.2,2.6,1.1,-0.7,-3.4,-4.7,-4.4,-3.3,-1.5,-0.7,-0.1,1.9,3.6,-0.8,-2.5,2.3
DUNU DN2000|0.7,1.2,0.7,-0.8,-3.2,-4.5,-4.4,-3.9,-2.5,-1.1,1,5,2.7,2.3,0.8,1.6
DUNU DN2000J|2.9,1.9,0.3,-1.2,-2.8,-2.6,-1.4,-0.4,1.3,1.5,1.9,2.9,2.2,-2.9,-4.2,-1.5
DUNU DN2002|-0.7,-1,-1.9,-3.2,-4.8,-4.7,-3.8,-2.7,-1.1,0.3,1.4,6.1,3.8,1.4,4.1,2.3
DUNU DN900|-1.2,-0.5,-0.6,-1.6,-3.4,-4.2,-4.1,-3.9,-3,-2.7,-1.9,3.2,6.2,6.2,6.2,5.6
DUNU EST 112|1.5,1.1,0,-1.3,-2.9,-2.8,-1.9,-1.1,-0.3,-0.8,-1.8,-2.3,3.1,-0.8,1.4,3.8
DUNU Falcon Pro (Atmospheric Immersion filter)|2.5,1.3,-0.5,-2.4,-5,-5.8,-5,-3.3,-0.9,0.5,-0.5,0.5,3.6,2.1,5.2,5.2
DUNU Falcon Pro (Reference filter)|2.2,1.1,-0.7,-2.6,-5,-5.5,-4.4,-2.5,0.2,1.8,1.3,1.6,2.3,1,3.4,3
DUNU Falcon Pro (Transparency filter)|3,1.8,0.1,-1.9,-4.3,-5.1,-4.2,-2.5,-0.1,1.3,0.6,-0.5,1.5,-0.5,3,3.6
DUNU Falcon Ultra (blue nozzle)|-1.4,-1.5,-1.8,-2.5,-3.5,-2.1,-0.7,0.8,2,1.4,0.9,1.5,0.8,2.1,-0.1,-0.6
DUNU Falcon Ultra (brass nozzle)|0.2,-0.3,-1,-2.1,-3.2,-2.5,-1.3,-0.3,0.3,-1.2,-1.8,-0.5,1,2.7,2.3,0.5
DUNU Falcon Ultra (gold nozzle)|-1.2,-1.4,-1.9,-2.8,-3.7,-2.5,-1.2,0.1,1,-0.3,-1.7,2.3,2.8,4.2,2.1,-0.1
DUNU Kima|1.2,0.8,0,-1,-2.2,-1.8,-0.9,0,0.6,-0.6,-2.9,-2.4,0.1,0,5.6,4.4
DUNU Kima Classic|-1.1,-0.4,0,-0.5,-1.1,-0.3,0.9,2,2.6,1.4,0.2,-2.3,0.7,-3.3,2,-0.7
DUNU Luna|3.1,2.2,0.6,-1.3,-3.5,-4.2,-3.5,-2.4,-0.9,-0.5,0.4,-0.2,-2.4,-2.6,4.4,2
DUNU SA3|3,2.3,0.9,-0.8,-3.6,-4.3,-3.8,-2.6,-1.2,-1.6,-4.9,0.8,-2.1,3.9,4.1,4.5
DUNU SA6 (atmospheric Immersion mode)|-0.7,-0.5,-0.5,-1,-1.8,-1.1,-0.4,-0.2,-0.3,-1.1,-2.1,0.6,3.9,2.5,3.1,2
DUNU SA6 (default mode)|0.3,0.4,0.4,-0.3,-1.2,-0.8,-0.4,-0.3,-0.7,-1.6,-2.6,0,3.3,1.8,2.6,1.5
DUNU SA6 MK2|-1.6,-1.4,-1.6,-2.3,-2.9,-1.7,-0.6,0.2,0.1,-1.3,-0.6,2.8,4.4,6.4,5.9,-0.7
DUNU SA6 MK2 (Atmospheric Immersion mode)|-2.2,-2,-2.1,-2.7,-3.2,-1.8,-0.6,0.2,0.4,-0.2,1.1,5.6,5.7,7,4.6,-1.8
DUNU SA6 MK2 (default mode)|-0.9,-0.7,-1,-1.6,-2.3,-1.3,-0.4,0,-0.2,-1,0.2,4.7,4.8,6.2,3.7,-2.7
DUNU SA6 Ultra (atmospheric immersion mode)|-0.2,-0.2,-0.2,-0.9,-1.5,-0.8,0,0.4,0.1,-0.9,-1.2,2.3,3.3,4.8,3.4,0.5
DUNU SA6 Ultra (default mode)|0.7,0.7,0.6,-0.2,-1,-0.5,0,0.1,-0.3,-1.5,-1.8,1.6,2.7,4.4,2.8,-0.1
DUNU Talos|-1.7,-0.6,0.2,0.1,-1,-0.5,0.2,0.6,0.6,-0.8,-2.2,0,5,6.4,0.8,-1.7
DUNU Talos (BA off)|2.3,2.6,2.5,1.6,0.1,-0.3,0,0.3,0.5,-0.8,-2.6,-2.8,2.4,4.7,0,-1.3
DUNU Talos (BA on)|2.7,3.1,2.9,2.1,0.6,0.2,0.5,0.8,1,-0.2,-2,-2.1,4.1,6,-2.7,-5.1
DUNU Titan 1|4.7,4.3,3.1,1.1,-1.7,-2.9,-2.6,-0.7,1.6,2.2,-1.1,-0.3,-2,-2.1,-2,-1.2
DUNU Titan 3|4,4,4,2.4,-0.6,-2.7,-2.8,-3,-1.4,-1.3,-3.2,-5.4,-2.2,-8.1,2.9,4
DUNU Titan 5|-0.3,-0.4,-1,-2,-2.9,-2,-0.6,0.8,2.1,1.3,0.9,-0.2,1.6,-1.2,0.6,1.6
DUNU Titan S|2.5,2.1,1.5,0.4,-1,-1.2,-0.5,0.3,0.9,-0.1,-2.2,-2.7,-2.2,2.1,3.1,-0.7
DUNU Vulkan|-0.7,0.1,0.6,0,-1.2,-1.6,-1.3,-0.6,-0.1,-0.6,-1.3,1.3,2.4,4.7,3.2,2.4
DUNU Vulkan (gray eartips)|-1.4,-0.6,-0.1,-0.7,-1.7,-1.7,-1,-0.3,0.6,0,0.6,3.9,3.3,4.5,2,0.6
DUNU Vulkan (red eartips)|-1.6,-0.8,-0.2,-0.8,-1.8,-1.7,-1.1,-0.2,0.6,0,0.6,3.5,3.3,3.9,0.6,0.3
DUNU Zen|0.9,0.5,-0.4,-1.4,-2.6,-2.1,-1.1,-0.2,0.5,-0.9,-3.1,-4.2,-1.4,2.3,1.5,4.8
DUNU Zen Pro|1.6,0.6,-0.5,-1.8,-3.3,-2.9,-1.9,-1,-0.4,-1.4,-3.2,-3.5,-0.2,2.8,4,2.5
Dynamic Motion DM200H|0.5,0,-0.8,-1.9,-2.9,-1.8,-0.2,1.6,3.1,2.5,0.6,-1.5,2.1,-0.8,1.6,-0.1
Dyson Zone|2,1.9,2.3,1.1,0.4,-1,-0.8,-0.5,-0.6,-0.2,-0.6,-0.6,2.6,2.9,0,-3.5
E-Mu Purpleheart|-0.1,-1.2,-2.3,-3.1,-4.1,-3.5,-1.5,-0.5,0.8,1.4,2.3,4.3,0.4,3.2,2.6,0.8
E-Mu Teak|0.6,-0.4,-1,-1.1,-2,-1.3,0.5,0.6,-2.2,-1.6,0.2,2.9,2.6,-2.1,2,1.1
E-Mu Teak (2016)|-0.8,-1.4,-1.5,-2.2,-3.2,-2.4,-0.7,0.3,0.3,0.6,2.6,4.5,0.8,1,1.9,0.3
E-Mu Teak (mahogany)|-0.1,-0.4,-0.7,-1.5,-2.6,-2.5,-0.9,0.4,0.5,0.8,2.2,4.1,0.5,0.9,2,-0.7
E-Mu Teak (rosewood)|0.2,-0.3,-0.8,-1.6,-2.8,-2.7,-1.1,0.1,0.9,0.3,1.9,4.2,1.7,1.3,1.9,-1.4
E-Mu Walnut|1.7,0.8,-0.7,-1.6,-3.2,-3.1,-1,-0.1,-1.6,-1.5,0.5,3,4.1,2.6,2.8,-0.4
EarFun Air Pro|-1.6,-1.4,-1.7,-1.9,-0.6,2.3,2.6,2.5,4.6,1.8,1.4,1.9,-0.2,-0.8,-1.5,-0.6
EarFun Air Pro 2 (2021)|-2.6,-1.6,-1.1,-1,0.5,1.8,1.5,1.5,3.9,3.8,3.5,2.2,-0.7,-0.3,-1.7,-2.6
EarFun Air Pro 3|-5.9,-5.3,-4.5,-4,-2.8,-0.9,0,0.7,2.1,4.4,3.2,1.9,2.3,-0.5,3.3,4.7
EarFun Air Pro 3 (ANC noise cancelling, default EQ)|-4.3,-3,-1.1,0.3,2.1,4,2.9,2,2.5,1.6,-0.7,-2.3,-0.8,-1.3,0.1,-0.1
EarFun Air Pro 3 (ANC normal, default EQ)|-3.6,-2.9,-1.5,0.6,0.9,2.6,2.7,2.7,2.9,1.9,-0.5,-3,-0.2,-0.5,0.5,-0.2
EarFun EH100 (black ring)|0.9,0.6,-0.4,-1.6,-3.1,-2.3,-0.7,1.5,3.1,3.2,2.7,0.6,-0.9,-4.3,-1.8,-2.9
EarFun EH100 (red ring)|-2.5,-2.3,-2.3,-2.8,-3.6,-1.7,0.3,2.6,4.3,4.4,3.9,1.7,0.1,-3,-0.6,-1.7
EarFun Free|-5.6,-4.1,-2.9,-3.1,-3.8,-3.1,-2.1,-0.8,1,0.7,-0.1,0.3,3.7,6.8,6.8,6.4
EarFun Free Pro (Oluv Edition)|0.5,1.7,3,1.4,-1.1,-2,-1.5,-0.7,0.3,-0.4,-0.8,-1.2,-0.3,3.1,5.6,-1.3
EarFun Free Pro (Oluv Edition, ANC off)|0.3,1.3,1.7,0.8,-1.4,-2.6,-2,-1.1,0.2,-0.5,-0.6,-0.1,0.1,3.6,5.5,-0.5
EarFun Free Pro (Oluv Edition, ANC on)|0.3,1.4,1.7,0.7,-1.4,-2.6,-2.1,-1.2,0.2,-0.5,-0.7,-0.1,0.3,3.4,5.3,-0.5
EarFun Free Pro 3 (ANC off, default EQ)|-1.5,-2.7,-3.4,-3.6,-2.4,-1.1,-2.1,-1.6,0.5,0.2,0.3,3.7,6.7,6.7,-1,-1.6
EarFun Wave|0.4,-0.1,-3.8,-4.9,-3.7,-1,3.6,3.2,-0.3,0.1,-0.9,-1.8,-1.1,5.6,-2.8,-1.1
EarSonics Blade|-2.1,-2.3,-2.8,-3.8,-4.5,-2.8,-1.1,-0.6,-1.6,-3.1,-2,4.5,6.3,4.2,4.7,3.9
EarSonics EM10|-1.1,-1.1,-1.8,-3,-3.8,-1.6,-0.1,-0.2,-1.1,-3.5,-3.3,6.6,4.2,6.6,5.5,5.8
EarSonics EM32|-3.8,-4,-4,-4.3,-3.6,1.2,1.9,0.4,-0.6,-2.2,-2.1,5,2.9,3.5,5.4,4.6
EarSonics EM4|-0.3,-0.6,-1.4,-2.7,-4.5,-4.6,-3.6,-2.1,-0.2,-0.5,-1.9,1.9,5.6,6.9,6.9,6.6
EarSonics EM6|0.7,0.6,-0.3,-1.7,-3.7,-4.2,-3.6,-2.3,-0.7,-1.1,-2.5,2,4.2,6.2,6.2,4.8
EarSonics ES2|4.6,4.2,1,-1.8,-5.3,-6.9,-6.4,-5.1,-3.4,-3.3,-4.9,3.4,4.5,4.6,4.6,4.1
EarSonics ES3|-2.6,-2.5,-2.4,-2.7,-2,-0.2,-0.6,-1.2,-1.2,-2.4,-2.5,4.8,2.1,4.6,2.6,2.9
EarSonics ES5|-1.5,-1.7,-2.3,-3.1,-3.5,-1.3,1,2.2,0.1,-4.3,-3.4,5.4,4.5,6.2,3.3,4.6
EarSonics Grace|-0.2,-1,-2,-3.2,-4,-2,-0.6,-0.7,-1.3,-3.3,-3.7,5.9,4.1,6.2,4,5.6
EarSonics Onyx|-3.5,-3.1,-2.9,-3.1,-3.4,-0.8,1.7,3.4,1.9,-2.2,-4.1,5.2,2.9,4.7,1.8,1.2
EarSonics Purple (standard)|3.9,3.1,1.7,-0.1,-2.6,-3.5,-2.8,-1.5,-0.1,-2.8,-4.2,2.5,2.2,5.6,3.4,-0.6
EarSonics S-EM9|1.3,0.1,-1.1,-2.4,-3.5,-3.1,-2.9,-3.3,-4.5,-5.5,-1.3,6.4,6.3,6.4,6.5,4.6
EarSonics Stark|-2,-2.1,-2.6,-3.3,-3.9,-1.8,0.1,-0.1,-1,-2.2,-3.2,6.3,5.1,6.8,6,4.3
EarSonics Velvet (standard)|-2,-2.3,-2.2,-2.4,-0.8,4.2,2.2,-0.5,-1.8,-3.4,-3.7,3.9,2.1,2.7,2.7,0.7
EarSonics Velvet (tight)|-1.1,-1.4,-1,-0.9,1.2,4.5,1,-1.6,-2.7,-4.1,-4.5,3.2,1.5,2.1,2.1,0
EarSonics Velvet (warm)|-3.2,-3.5,-3.6,-4.1,-3.4,1.5,3.3,1,-0.5,-2.1,-2.6,5.1,3.1,3.5,4,2
Eartech Dual|0.9,0.3,-1.1,-2.9,-5.3,-5.8,-4.8,-3.2,-0.8,-0.1,-0.1,3.3,5,4.9,1.3,2.6
Eartech Quad|1.5,0.4,-1.5,-3.7,-6.3,-6.9,-5.7,-3.7,-0.7,1.1,2,4,5.4,3.9,1.4,2.8
Eartech Triple|1.8,0.9,-0.7,-2.6,-5.1,-5.8,-4.9,-3.3,-1.2,-0.4,0.3,3.8,4.9,4.2,0.6,1.5
EarWerkz Legend Omega|0.7,-0.2,-1.5,-3.2,-5.4,-5.9,-5,-3.4,-1.4,-1.5,-4,3.8,5.5,5.4,5.4,5.4
ECCI YST-02|2.6,2.5,1.6,0.2,-1.6,-1.5,-0.7,0.2,0.8,-0.2,-1.8,-1.9,-4.1,-1,-1.8,0.3
Echobox Finder X1 (black filter)|-2.5,-1.8,-1.3,-1.5,-1.4,0.2,2,3.5,5.2,4.9,5,3.7,-2.4,-2.6,-3.5,-2.1
Echobox Finder X1 (red filter)|-2.4,-1.7,-1.2,-1.4,-1.4,0.2,2.1,3.5,5.1,4.7,4.7,3.6,-3.4,-1.7,-2.7,-2.7
Echobox Finder X1 (white filter)|-2.7,-2.1,-1.6,-1.8,-1.8,-0.2,1.6,3.1,4.8,4.3,4.5,3.9,-1.9,-1.7,-1.5,-2.2
Edifier NeoBuds Pro|-0.8,-1.5,-3.2,-2.5,-2,-1.5,-1.2,-0.8,0.4,-0.3,0.9,3.4,6.6,3.5,3.9,-1.8
Edifier NeoBuds Pro 2 (ANC high, classic EQ)|-1.4,-2.2,-3.5,-2.3,-1.4,0.5,1.2,1.4,1.8,0.3,0.6,3.2,5.8,1.1,2.5,-3.2
Edifier NeoBuds Pro 2 (ANC off, classic EQ)|-2.8,-3,-3.3,-2.7,-3.1,-0.4,0.5,1.6,2.1,0.7,0.9,4.3,7,1.9,3.4,-2.6
Edifier P180|4.7,4.7,4.7,1.6,-1.2,-0.4,0.1,1.8,3,2.9,-3.9,-7.3,-1.2,-1.9,-4.3,-2.1
Edifier TWS1|-1.5,-1.4,-1.7,-2.2,-2.2,1.3,2,1.4,1.6,1.1,0.6,4,3.4,0,-1.3,2.2
Edifier TWS5|-0.9,-0.8,-1,-1.8,-1.6,1.2,0,0,1.3,1,0.3,5.1,5.8,2.1,-1.5,-0.9
Edifier TWS6|-2.1,-2.2,-2.4,-3.2,-3.3,-1.4,-0.7,-0.8,-0.6,-0.7,-0.5,3.3,2.6,3.3,0.4,5.2
Edifier W820NB (ANC off)|-2.8,-4.6,-3.4,-4.8,-4.6,0.6,2.4,2.2,2.4,1.1,2.4,-1.9,2.3,2.6,1.2,4.1
Edifier W820NB (ANC on)|-0.5,-0.2,-1.9,-3.4,-4.4,-0.5,1.1,0.6,1.5,-0.3,1.3,-2.2,2.5,2.5,0.2,3.1
Edifier W820NB (passive)|-0.9,-0.7,0.3,-2.3,-3.4,-0.1,0.6,0.9,1.5,-0.2,1.1,-4.6,2.3,0.8,-1.3,0.7
Edifier W820NB Plus|-0.5,-0.5,0.1,0.5,0,2.2,3.8,1.4,1,-0.1,0.8,-2.1,0.7,-0.4,-1.9,-0.5
Edifier WH700NB|-2.2,-4,-5.9,-3.6,-2.2,2.9,3.8,4.3,4.1,3.8,4.8,-1,4.9,0.1,-0.6,-1.6
Edifier WH950NB (ANC off, customized EQ flat)|-4.5,-3.6,-2.4,-1.7,-4.8,-2.1,-0.9,-0.7,0.3,1.2,2.8,2.1,4.6,3.2,3.9,2.7
Edifier WH950NB (ANC on, classic EQ)|-1.2,-0.3,0.2,-0.4,-1.6,-1.6,0.4,1,-0.3,-1.4,0.9,-1.2,-0.1,-1.4,1.9,2.7
Edifier WH950NB (ANC on, classic mode)|-3.1,-1.4,-1,-1.7,-2.7,-3,-1.3,0.6,0.8,1.6,2.8,1.4,1,-3.7,3.2,4.2
Edifier WH950NB (ANC on, customized EQ flat)|-2.4,-1.5,-1,-1.6,-2.8,-2.8,-0.7,0,-1.1,-1.6,1.4,1.2,3.2,2,3.3,2.9
Edifier WH950NB (ANC on, dynamic mode)|-4.8,-3.3,-2.5,-2.5,-2.1,-1.3,0.1,1.7,1.9,2.3,3.9,2.2,2,-2.6,2.9,3.1
Edifier WH950NB (passive)|-3.6,-3.3,-4.9,-8.7,-9.4,-5.5,-2.5,-1.1,1.4,5.1,7.6,7.3,7.6,3.7,3,3.8
Edifier X3 Lite|-2.6,-2.6,-2,-1.3,1.3,3.9,3,3.1,3.1,2.9,2.8,1.6,-0.9,-1,-3,-2.4
Effect Audio Axiom|-0.9,-1.4,-2.5,-3.9,-5.7,-5,-3.2,-0.7,2.6,4.5,2.1,4,6.7,0.6,3.3,1.4
Effect Audio King Arthur|0.6,-0.4,-1.8,-3.7,-6,-6.5,-5.5,-3.9,-1.8,-0.9,0.1,6.4,6.4,6.4,6.4,5.5
Effect Audio Symphony|-1.3,-1.9,-3,-4.7,-6.7,-7,-5.7,-3.4,0.1,2.9,5.5,6.2,6.3,5.4,0.9,1.4
Effect Audio x Elysian Gaea|0,0.2,0.2,-0.2,-0.3,1.1,1.5,1.4,2.1,1.7,-1.9,1.2,0.7,0,1,-3.8
EKSA E900 Pro|-1.8,-1.8,-3.3,-5.3,-8.3,-9.9,-6.5,-0.6,-0.9,0.8,2.1,6.7,6.7,6.7,3.9,4.9
Elysian Annihilator (2021)|-0.2,-0.2,-0.4,-1,-1.8,-0.2,1.3,1.5,2.4,2.2,0,0.8,1.8,3.2,2.6,-2.4
Elysian Annihilator (2023, Dan)|-2.4,-1.9,-1.7,-1.9,-1.9,0.7,2.1,2.8,3.4,2.8,2.7,2.4,1.7,4,1,-4.9
Elysian Annihilator (2023, Free)|-2.4,-1.9,-1.4,-1.5,-1.3,1.3,2.7,3.5,4.2,3.1,1.4,2,1.4,3.7,1.4,-6.1
Elysian Annihilator (pre-2021)|1.7,1.3,0.6,-0.4,-1.5,-0.5,0.7,1.8,2.9,2.1,-0.9,0.6,1.3,1.4,0.7,-4.4
Elysian Artemis|0.5,-0.4,-1.8,-3.6,-5.8,-6.2,-5.1,-3.2,-0.6,0.7,1,1.9,5.3,3.1,3.6,5.3
Elysian Artemis V2|1.4,0.8,-0.5,-2.1,-4.1,-4,-2.7,-0.8,1.7,1.3,-2.1,1.5,3.1,1,1.3,-1.1
Elysian Cora V2|-0.1,-0.2,-0.5,-1.2,-1.9,-0.3,1.4,2.6,3.1,0.1,-3.7,-0.4,0.6,3.8,3.7,-3.1
Elysian Diva (half bass)|-2.2,-0.6,1.4,1.5,0.8,0.3,0.3,0.5,0.7,-0.7,-2.3,-1,1.2,1.5,3.2,0
Elysian Diva (max bass)|-4.8,-3.1,-0.7,0,0.2,0.7,1,1.4,1.7,0.4,-1.2,0,2.3,2.6,4.1,1.3
Elysian Diva (min bass)|2.2,2.3,2,0.8,-0.9,-1.3,-1.1,-0.5,-0.2,-1.4,-3,-1.4,0,0.2,2.3,-0.5
Elysian Eros|-0.1,-1.2,-2.5,-4.3,-6.4,-6.8,-5.7,-4,-1.3,-0.2,0.4,3.6,5.5,5.5,5.5,5.5
Elysian Hades|2.4,1.6,0.1,-1.7,-4.2,-4.9,-4,-2.5,-0.1,1,1.6,3.1,3.5,-0.3,1.4,1.5
Elysian Hades V2|3.2,2.9,2.1,0.9,-0.7,-0.8,0,1.2,2.7,2.4,-1.9,-0.3,-2.4,1.8,0.4,-6.4
Elysian Hades V2 (music)|1.3,1,0.4,-0.7,-1.9,-1.3,0,1.5,3.3,3.1,-0.8,0.9,-1.4,2.9,1.7,-5
Elysian Hades V2 (stage)|-0.8,-1,-1.7,-2.6,-3.5,-2.4,-0.6,1.5,3.8,4,0.7,2.6,0.1,4.8,3.5,-3.2
Elysian Minerva|0.5,-0.5,-1.8,-3.6,-5.7,-5.7,-4.5,-2.6,-1,-2.3,0.4,4.4,5.8,5.4,3,1.5
Elysian Poseidon|0.7,0,-0.7,-1.9,-3,-1.9,-0.5,1.3,2.8,1.3,-1,2.1,4.5,3.3,1.6,-3.3
Elysian Terminator|0.8,0.1,-1.1,-2.6,-4.4,-4.3,-3.1,-1.4,0.6,0.6,-0.5,0.5,4.5,5.7,3.3,1.6
Elysian Terminator V2|-0.7,-0.9,-1.3,-2.2,-3.1,-1.9,-0.2,1.6,3.4,3.1,1.1,1.3,0.7,3.6,3.3,-2.6
Elysian X|-0.7,0.1,0.8,0.4,-0.4,0.2,1.1,1.7,2.3,1.3,-0.9,0.8,1,2.2,3.7,-1.6
Emotiva Airmotiv GR1|1.8,0.1,-0.9,-1.4,-2.9,-3.9,-3.9,-2.2,-1.8,0.7,2.3,1.5,3.9,1.7,1.7,0.4
Empire Ears Bravado|-6.6,-5.4,-4.2,-4.2,-4,-2,0,2.2,4.5,3.6,1.5,1.3,6.2,-0.4,6.9,6.3
Empire Ears Bravado Mk2|-4.8,-3.8,-3,-3.1,-3,-0.9,1.2,3.4,5.1,3.3,0.9,1.2,0.8,0.1,4,3.5
Empire Ears ESR|1.1,0.5,-1,-2.7,-4.9,-5.2,-4.1,-2.2,0.3,0.8,0.3,4.8,5.4,5,-2.6,0.1
Empire Ears ESR Mk2|2,1.5,0.1,-1.6,-3.9,-4.2,-3.3,-1.7,-0.1,-0.7,-1.3,0.9,1.1,0.6,0.4,1.6
Empire Ears EVR|2.9,2.2,0.7,-1.2,-3.9,-5,-4.6,-3.6,-2.2,-2.5,-3.4,3.3,4.6,4.6,-0.4,2.1
Empire Ears EVR Mk2|4.7,3.9,2.4,0.6,-2,-3.4,-3.3,-2.6,-1.7,-3,-6.3,3.2,4.7,4.8,-1.2,-1.7
Empire Ears Hero|-1.3,-0.5,0.1,-0.2,-0.8,-0.1,1.3,2.3,2.6,0.7,-2.3,0.3,4.1,0.1,-1.7,0.8
Empire Ears Legend EVO|-2.8,-2.3,-2.2,-2.6,-2.9,-1.5,-0.3,3.9,4,2.9,-1.3,-0.4,1.9,1.7,3.8,2
Empire Ears Legend X|-3.7,-3.2,-2.8,-3.3,-3.6,-2,-0.1,1.8,3.8,2.5,0,2.1,5.9,6.8,4,1.9
Empire Ears Legend X SE|-3.7,-3,-2.6,-3,-3.2,-1.3,0.7,2.7,4.6,2.5,0.4,1.1,3.3,4.7,2.7,-0.7
Empire Ears Nemesis|-3.6,-2.7,-1.9,-2.2,-2.3,-0.6,1.2,2.9,4.9,3.2,1,3,7.2,6.7,-1.4,-2
Empire Ears Odin|0,0.4,0.5,-0.3,-1.2,-0.6,0.1,0.7,0.7,-1.7,-4.7,-2.2,2.7,4.3,4,1.3""",
"""Empire Ears Phantom|0.2,-0.7,-2.1,-4,-6.2,-6.6,-5.5,-3.7,-1.2,-0.1,1.2,4.9,5.3,4.2,2.9,5.3
Empire Ears Phantom X|0.8,-0.2,-1.8,-3.4,-5.6,-6,-5.1,-3.3,-1.1,-0.5,-0.8,2.3,3.4,3,5.2,5.2
Empire Ears Raven|-1.9,-1.3,-1,-1.6,-2.1,-1.1,0.1,2,3,2.2,0.6,4,7.2,3.5,0.4,-1.9
Empire Ears Valkyrie|-2.6,-1.1,0.2,0.4,0.4,2,3.5,5,5.4,3.1,-1.7,-0.7,0.2,-3.6,-2.8,-2.9
Empire Ears Valkyrie MKII|-3.5,-2.6,-1.7,-1.8,-1.9,-0.1,1.7,3.5,4.9,2.5,-1.6,-1,-1.8,-0.9,2,3
Empire Ears Vantage|-5,-4.2,-3.4,-3.6,-3.7,-1.9,-0.1,2,3.6,2,-0.2,2.3,5.7,0.3,5.5,5.3
Empire Ears Wraith|3,2.2,0.7,-1,-3.4,-4,-3.3,-2.2,-1.2,-3.2,-7.1,-0.7,0.4,2.5,4.5,4.1
Empire Ears Zeus|3.5,2.4,0.7,-1.1,-3.5,-4.3,-3.7,-2.5,-1.2,-2.7,-3.3,1.9,4.9,4.2,-2.6,0.4
Empire Ears Zeus-XIV|4,3.1,1.5,-0.3,-2.7,-3.5,-2.7,-1.5,-0.4,-2.7,-3,0.1,5.1,4.5,-2.9,-1.9
Empire Ears Zeus-XR (R mode)|4.7,3.7,1.4,-0.7,-3.5,-4.3,-3.5,-2.2,-1.1,-3.2,-3.1,0.1,4.7,3.5,-1.4,1.7
Empire Ears Zeus-XR (XIV mode)|4.7,3.6,1.5,-0.8,-3.5,-4.4,-3.7,-2.4,-1.1,-3.2,-3,0.2,4.7,3.6,-1.1,1.8
ENACFIRE E60|-1.9,-2.1,-2.8,-3.7,-4.5,-3.5,-1.6,0.5,2.6,0.7,-0.7,-1.4,3.3,5,1.9,5.9
ENIGMAcoustics Dharma Production 2015|4.2,2.3,0.1,-1,-1.9,-1.2,0.9,1.2,0.7,-0.6,-0.4,0.9,2.2,-2.8,0.8,-0.3
Enigmatic Audio Paradox|2.1,1.5,0,-2.1,-5.5,-6.4,-3.8,-2,-1,-3.1,-0.4,3.4,-0.5,4.7,2.2,2.7
EPOS GTW 270 Hybrid|0,-0.4,-1.2,-2.2,-3.7,-3.3,-1.5,1,2.4,0.9,2.3,7.1,7.1,7.1,-3.5,-6.1
EPOS Sennheiser GSP 600|3.4,2.8,3.3,3.8,4.4,-0.6,-1.8,-0.9,-1.1,-1.2,-1.1,-4.2,-1.7,-2.3,-1.2,1.2
EPZ 530|1.3,1,-0.1,-1.4,-2.8,-2,-0.1,1.9,3.6,2.1,-0.6,0.5,2.2,-1.1,-1.8,-4.8
EPZ G10|-1.1,-0.2,0.4,-1.7,-1.1,-0.9,-0.1,0.7,1,-0.1,-0.8,-0.1,-0.4,1.2,0.9,-0.5
EPZ K5|0.8,0.4,-0.3,-1.2,-2.1,-1.1,0.2,1.3,1.2,-1.5,-0.6,-0.1,-0.6,-3,-0.3,1.5
EPZ Q1|-2.3,-2.2,-2.4,-3.2,-4,-2.7,-1.4,-0.2,0.7,0.1,-0.5,1.8,3.2,6.4,6.7,3.1
EPZ Q5|-0.8,-0.1,0.4,0,-0.7,0.2,1,1.8,2.3,1.3,0.2,0,1.1,-0.9,0,-1.5
ES Lab ES-1a|5.5,4.6,2.9,1.8,-0.2,-1.7,-1.7,-1.4,-3.1,-4.9,-2.4,1.1,-0.8,-1.9,4.2,-1.1
Eskuche 33 1 3 B|2.7,-0.8,-2.2,-2.7,-3.9,-5.5,-4.9,-6.1,-5.6,-2.5,2.1,1.9,1.3,1.7,5,5
ETA Mini Closed|0.2,0.1,0,-0.5,-2.5,-3.8,-2.3,-0.3,0.5,-1.5,-0.4,4.1,2.5,3.5,6.8,2
ETA Mini Semi-Closed|0.9,0.9,0.8,-0.1,-2.1,-3.6,-2.6,-0.9,0,-2,-1.1,3.9,2.6,2.4,6.6,1.8
ETA Mini Semi-Closed (with ring dampeners installed)|0.7,0.6,0.2,-0.3,-2.4,-4,-2.8,-0.9,-0.2,-2.1,-1.1,3.7,2.5,4,6.4,2.7
Eternal Melody EM6W|0.6,-0.1,-1.7,-3.5,-5.9,-6.2,-5,-2.9,0,1.2,0.2,2.8,4.9,2.6,2.9,5.3
Etymotic EK5|4.3,4.3,3.8,1.9,-0.5,-1.8,-1.7,-2.1,-1.9,-4,-5.1,-4,-3.3,-0.1,2.1,2.9
Etymotic ER2SE|4.5,4.5,3.9,2,-0.7,-2.1,-2.1,-1.8,-1.3,-2.9,-5.4,-4.8,-1.3,0.6,3.7,1.6
Etymotic ER2XR|0.6,1.1,1,0.1,-1.5,-1.7,-1.1,-0.4,0.3,-1,-3.4,-2.9,0.8,2.8,5.7,2.9
Etymotic ER2XR (brown filter)|0.2,1.3,1.7,0.9,-0.5,-0.7,-0.2,0.6,1.2,-0.1,-1.7,-2.1,2.9,1.8,0.1,-3.3
Etymotic ER2XR (green filter)|-0.3,0.7,1.2,0.4,-1,-1.2,-0.7,0.2,0.9,-0.3,-1.3,0,2.6,2.3,1.3,-3.4
Etymotic ER2XR (white filter)|0.5,1.6,2,1.2,-0.2,-0.5,0,0.8,1.4,-0.1,-2,-3.5,2.9,1.5,-0.2,-2.8
Etymotic ER3SE|4.3,4.2,2.6,0.7,-1.9,-3.2,-3.2,-2.9,-2.4,-3.7,-5.1,-4.2,-1.9,0.9,4.3,4.3
Etymotic ER3SE 75 ohm|3.6,2.9,1.9,0.2,-1.9,-2.7,-2.3,-1.6,-1,-2.1,-4.5,-4.3,-1.5,1,3.9,1.2
Etymotic ER3XR|2,1.4,0.2,-1.5,-3.6,-4,-3.3,-2.4,-1.3,-2,-3.7,-2,0.1,2.8,4.8,4.8
Etymotic ER4B|4.6,4.6,4.6,4.2,1.3,-0.4,-0.6,-0.4,0,-1.9,-5.2,-6.6,-3.3,-1.9,-0.2,-1.7
Etymotic ER4P|4.3,4.3,2.7,0.7,-2.2,-3.6,-3.6,-3.2,-2.5,-3.3,-5.3,-4.1,-1.8,2,4.3,4.3
Etymotic ER4S|4.5,4.5,4.5,2.5,-0.4,-1.9,-2,-1.7,-1.3,-2.5,-4.9,-5.2,-2.1,1.2,2.6,-0.4
Etymotic ER4S (CIEM)|4.8,4.8,4.8,2.9,-0.1,-1.7,-1.8,-1.6,-1.3,-2.9,-6.2,-5.8,-1.5,-0.8,4.8,-1.2
Etymotic ER4SR|4.3,4.3,4.2,2.2,-0.6,-2,-2,-1.9,-1.7,-3.4,-5.1,-5.1,-2.7,-0.4,3.9,1.3
Etymotic ER4XR|3.7,3.3,2.2,0.6,-1.7,-2.4,-2.1,-1.5,-1.1,-2.7,-4.1,-3.5,-1.3,0.9,3.6,0.9
Etymotic ER4XR (2-flange eartips)|3.8,3.3,1.9,0.1,-2.2,-2.9,-2.4,-1.6,-0.8,-1.8,-2.8,-1.2,0.9,0.3,-0.9,-2.7
Etymotic ER4XR (3-flange eartips)|3.6,3.1,1.8,0,-2.2,-3,-2.4,-1.6,-0.8,-1.8,-2.7,-1.1,0.8,0.6,-0.4,-2.8
Etymotic ER4XR (foam eartips)|4.3,3.7,2.3,0.5,-1.9,-2.6,-2.2,-1.4,-0.7,-1.8,-3,-1.3,0.1,-0.4,-2.2,-2.5
Etymotic ER6i|4,4,3.4,1.5,-1.1,-2.6,-2.8,-2.8,-2.7,-3.9,-4,-3.8,-3.4,-0.5,2.5,4
Etymotic ERX|3.3,2.8,2.1,1.3,-0.6,-0.7,0.6,0.5,-1.1,-2.8,-3.8,-3.6,-1.6,-2,-0.7,0.2
Etymotic ERX (brown filter)|2.4,2.1,1.2,-0.1,-1.9,-2.3,-1.8,-1.1,-0.5,-1.9,-3.5,-1.1,1.4,1.8,0.9,-2.1
Etymotic ERX (green filter)|1.9,1.7,0.8,-0.5,-2.3,-2.7,-2.2,-1.6,-0.9,-2.1,-3.4,-0.4,1.8,2.4,1.3,-1.5
Etymotic ETY Kids|4.5,4.5,4.4,2.8,0.2,-0.9,-0.9,-0.6,-0.2,-2,-3.8,-4.6,-3.4,0.6,-3,-2.3
Etymotic EVO|0.8,1.4,1.3,0.4,-1.3,-1.8,-1.4,-0.8,-0.2,-1.6,-3.4,-2.3,-0.2,1.4,2.4,1.4
Etymotic Evo (2-flange eartips)|0.3,0.9,0.8,0,-1.7,-1.9,-1.4,-0.6,0,-1.3,-2.7,-1,1.3,1.8,1.7,-0.5
Etymotic Evo (3-flange eartips)|-0.2,0.2,0.3,-0.6,-2.1,-2.3,-1.7,-0.7,0.1,-1.1,-2.4,-1.3,0.9,1.9,2.4,1.1
Etymotic hf2|4.2,4.2,2.7,0.6,-2,-3.4,-3.4,-3,-3.2,-4.3,-4.4,-3.1,-1.1,2.6,3.9,2
Etymotic hf3|4.3,4.3,2.7,0.9,-2,-3.4,-3.5,-3.2,-3,-4.5,-4.9,-3.6,-1.3,2.5,4.3,2.7
Etymotic hf5|4.2,4.2,2.9,0.9,-2,-3.4,-3.4,-3.2,-2.8,-4,-4.5,-3.2,-2,1.3,4.2,2.7
Etymotic mc3|4,4,3.2,1.3,-1.1,-2.6,-2.7,-3,-3.1,-5.2,-6.2,-3,-0.9,1.7,2.5,3.6
Etymotic mc5|4.1,4.1,3.3,1.2,-1.5,-2.9,-2.9,-2.9,-3.4,-5.5,-7.3,-2.2,-1.2,1.8,3.8,4.1
Etymotic Mk5|4.1,4.1,4,2.3,-0.1,-1.6,-1.5,-1.8,-1.8,-3.9,-5.7,-5.9,-5.8,1.7,2.5,3
Etymotic mk5 Isolator|4.6,4.6,3.9,2.1,-0.4,-1.6,-1.5,-1.2,-0.8,-2.7,-3.7,-3.7,-2.6,2.7,-2.6,-3.5
EVO|1.6,1.5,0.2,-1.6,-4,-4.5,-4,-2.9,-1.2,-1.3,-1.3,3.5,5.2,0.5,2.1,0.3
FAAEAL Hibiscus|3,2.6,1.5,0.2,-1.6,-1.7,-1.1,-0.4,0,-1.5,-4.7,-3.7,-2.4,0.4,5.7,4.9
FAAEAL Iris|4.7,4.7,4.6,0.3,-3.2,-3,-2.4,-2,-1.4,-0.2,-2.4,-6.5,-2.2,1,4,-1.4
FAAEAL Snow Lotus|4.1,4.1,4.1,1.4,-6.1,-5.2,-4.4,-3,-2,-2.4,-2.6,-0.6,3.3,-1.7,0.4,3.9
Fatfreq 20.6|2.4,1.4,-0.1,-1.9,-4.3,-4.8,-3.9,-2.4,-0.6,-0.9,-3.7,3.3,5.9,5.9,3.2,0.3
Fatfreq 20.6 (bass switch on)|0.8,-0.1,-1.4,-3.1,-5.1,-5.2,-3.9,-2,0.3,0.2,-2.6,4.1,6.4,6.4,4.2,1.2
Fatfreq 20.8|-0.7,-1.3,-2.3,-3.6,-5.1,-4.8,-3.5,-1.9,-0.2,0.1,0,4.2,4.7,3,1.9,5.6
Fatfreq Eden|1.7,1,-0.2,-2,-4.3,-5,-4.3,-2.8,-0.6,0.3,-0.8,1.5,3.2,5,1.1,3.4
Fatfreq Hayabusa|2.3,2.5,1.9,0.9,-0.7,-1.3,-1,-0.5,0.3,-0.5,-1.5,1.7,-1.1,2.8,1.4,-0.6
Fatfreq Hayabusa (bass)|-0.8,-0.6,-0.8,-1.6,-2.5,-1.8,-0.9,0.3,1.4,1,0.1,3.4,0.8,4.8,3.4,1.3
Fatfreq Maestro|-4.7,-3.1,-1.2,-1.1,-1,0.2,1.1,1.7,2.1,1.4,0.3,1.4,2.5,5.7,2.2,2.1
Fatfreq Maestro Mini|-4.8,-3.2,-1.5,-1.1,-0.8,1,1.9,2.2,2.9,2.8,2.4,2.6,0,4.4,1.9,-0.5
Fatfreq Maestro SE|-6.8,-4.6,-2.4,-1.6,-0.8,1.2,2.3,3,3.5,2.5,1.2,0.9,3.1,4.2,2.7,1.9
Fatfreq Musician|4.2,3.2,0.9,-1.1,-3.8,-5.1,-5,-3.9,-2,-1.3,-1.3,-1,-0.4,0.5,4.2,4.2
Fatfreq Musician Plus|-0.5,-0.9,-2.1,-3.7,-5.7,-5.8,-4.5,-2.3,0.8,2.4,2.9,3.3,5.9,5.9,6.7,3.4
Fatfreq Musician Plus V2|-6.5,-5.4,-3.9,-3.7,-3.2,-1.5,-0.2,0.5,1.7,1,-0.5,0.5,2.7,4.9,6.9,6.9
Fatfreq Musician Pro|0.5,-0.2,-1.5,-3.3,-5.6,-6.4,-5.6,-4.1,-1.6,0.1,1.7,3.8,5.1,3.3,1,5
Fatfreq Musician Pro SE|-5.7,-3.9,-2.1,-1.9,-1.9,-0.9,-0.1,0.5,0.9,0.1,-1.1,-0.4,0.4,5,6.3,6.5
Fatfreq Reference|3.4,2.4,0.9,-0.9,-3.4,-4.6,-4.3,-3.4,-1.8,-1.5,-2,-2.6,0.7,0.4,4.4,4.4
Fatfreq Reference Plus|1.7,1.2,-0.3,-2,-4.5,-5.4,-4.9,-3.6,-1.7,-1.5,-2.8,-0.3,5.5,3.9,5.5,5.5
Fatfreq Reference Plus V2|2.6,1.7,0.4,-1.6,-4.1,-5.2,-4.7,-3.5,-1.7,-1,-1.3,-0.9,1.9,3.2,4.9,4.9
Fatfreq Reference Pro|4.2,3.3,1.9,0,-2.6,-4.2,-4.3,-4,-2.9,-1.7,-0.8,2.1,3.8,1.2,-1,0
Fatfreq Reference Pro SE|2.9,2.2,0.8,-1,-3.4,-4.2,-3.5,-2.2,-0.3,-0.5,-3.4,-1.6,-1.2,0.1,3.7,4.3
Fatfreq Sakura|1.5,1.6,1.3,0.3,-1.4,-2,-1.7,-1.1,-0.6,-1.6,-3.6,-0.4,2.5,3.9,3.4,1.8
Fatfreq Sakura (bass switch on)|0,0.2,0,-0.9,-2.1,-2.1,-1.4,-0.8,0,-0.9,-2.9,0.3,3.3,4.7,4.3,2.7
Fatfreq Scarlet Mini|-7.3,-5.4,-3.8,-3.3,-2.5,0.5,2.7,4.4,5.5,5.3,6.1,6,5.2,3.5,2.2,-1.8
Fatfreq Tsuru|3.6,3.1,1.8,-0.1,-2.7,-4.2,-4.2,-3.8,-2.6,-1.8,-1.6,2.4,2.7,2.2,-1.6,-0.1
FAudio Chorus|0.5,-0.2,-1.2,-2.8,-4.8,-5,-3.8,-1.8,0.7,1.6,1.4,3.3,1.5,1.2,4.4,3.4
FAudio Dark Sky|-1.4,-1.4,-1.5,-2.2,-2.8,-0.9,0.3,0.2,1.1,1.3,0,0.3,5,0.9,1.7,2.2
FAudio Mezzo (off-off-off)|-0.3,-1,-1.9,-3.1,-4.2,-2.8,-0.3,2.8,3.8,3.6,0.9,1.4,4.1,0.3,2.1,0.2
FAudio Mezzo (off-off-on)|-1.1,-1.8,-2.8,-3.8,-4.9,-3.5,-1,2.2,3.5,3.5,1.4,2.6,5.5,1.7,3.2,0.9
FAudio Mezzo (off-on-off)|0.8,0.1,-0.9,-2,-3.1,-1.7,0.7,3.8,4.7,3.9,-0.2,-0.5,0,-3.1,0.9,0
FAudio Mezzo (off-on-on)|-0.2,-0.9,-1.7,-2.8,-3.9,-2.4,0,3.2,4.3,3.9,0.4,0.6,1.2,-1.8,2.1,1.1
FAudio Mezzo (on-off-off)|-0.1,-0.7,-1.6,-2.8,-3.9,-2.4,-0.1,3,4,3.8,1.1,1.5,3.7,-0.1,0.1,-1.1
FAudio Mezzo (on-on-off)|1,0.2,-0.6,-1.8,-2.8,-1.4,1,4,4.9,4.1,-0.2,-0.5,-0.3,-3.4,-0.8,-1
FAudio Mezzo (on-on-on)|0.1,-0.6,-1.6,-2.6,-3.7,-2.2,0.2,3.3,4.5,4.2,0.5,0.8,1.1,-2.1,0.4,0
FAudio Minor|1.7,1,-0.3,-1.8,-3.5,-2.9,-1.5,-1.3,-0.7,-0.3,-0.8,0.6,3.6,-2.4,1.5,2.5
Fearless Audio ACME 8|-2.3,-3.1,-4.5,-6.2,-8,-5,-1.1,1.3,3.5,-1.1,-1.7,6.7,6.5,6.7,5.4,4.9
Fearless Audio Barcelona|-3,-2.5,-2.2,-2.9,-4,-3.6,-2.6,-1.2,0.4,0.6,0,0,4.9,-0.2,6.1,6.2
Fearless Audio Crystal Ball|0.3,-0.6,-1.9,-3.6,-5.6,-5.9,-4.7,-2.9,-0.6,-0.6,-2,4.8,4.4,5.5,3,5
Fearless Audio Dawn|-1.2,-1.2,-1,-1.2,-1.3,-1.2,-1,-0.3,0.7,0.5,-0.5,0.7,3.3,1.5,3,2
Fearless Audio HyperS 12|2.7,2.2,1.2,0,-1.6,-1.7,-1.1,0,1.4,-0.6,-4.8,-2.4,-0.6,6.2,5.7,-1.9
Fearless Audio La Hire|2.2,1.3,0.1,-1.7,-4.1,-4.8,-4.1,-2.7,-0.8,-0.8,-1.4,1.4,0.1,-0.7,4.9,4.9
Fearless Audio Lancelot|-0.4,-0.9,-1.8,-3.2,-4.9,-4.6,-3.3,-1.6,0.4,0.6,-0.1,1.5,1.8,5.3,3.9,4.7
Fearless Audio Provence|-2.8,-2.2,-1.9,-2.5,-3.6,-3.3,-2.3,-0.6,1,1,0.4,0.2,2.8,1.7,5.2,6.1
Fearless Audio Roland|0.3,1.4,1.4,0,-2.6,-3.9,-3.9,-3.2,-2,-1.9,-2.3,-0.4,1.5,5.2,5.7,5.7
Fearless Audio S10 Genie|2.8,2.1,0.9,-0.4,-2.2,-2.2,-1.4,-0.1,1.3,-1,-4.9,-1.6,-1.6,5.1,3.1,-0.4
Fearless Audio S4|1.8,0.6,-1.1,-2.7,-4.6,-4.4,-3.2,-1.4,1,1.3,0.5,0.8,-0.1,-4.8,2.3,4.7
Fearless Audio S5H|-0.6,-1.1,-2.2,-3.4,-5,-4.7,-3.3,-1.3,1.3,2,1.7,1.9,0.1,-2.6,3.7,5.4
Fearless Audio S5T|2,0.6,-1.1,-2.9,-5.4,-5.9,-4.8,-2.8,0.2,-0.2,-3.6,3.4,4.9,5.9,4.1,0.7
Fearless Audio S6Pro|4.7,4.7,3.7,1.8,-0.8,-2,-1.7,-0.8,0.6,0.5,-1.8,-1.5,-2.9,-2.6,-0.4,-0.7
Fearless Audio S6Rui|-0.2,-0.8,-1.7,-3,-4.6,-4.3,-2.8,-0.9,1.4,1.3,-0.8,1.1,-1.6,0.2,4.6,5.2
Fearless Audio S8F|0.5,0,-1,-2.3,-3.9,-3.8,-2.4,-0.4,2.1,2.5,1.4,2.7,-0.4,0.3,1.4,1.9
Fearless Audio S8Pro|0.8,0.4,-0.5,-1.9,-3.5,-2.9,-1.4,0.9,3.4,3.9,4,3.6,-1,-2.1,-2.8,-1.5
Fearless Audio S8Z|2,1.4,0.3,-1.2,-3.1,-3.1,-2,-0.3,1.5,1,-1.2,1.7,0.9,0.8,1.2,3.3
Fearless Audio Shangri-la|0.2,0.6,0.7,-0.1,-1.7,-1.8,-0.9,0.3,1.2,1,-0.3,1,2.7,0.3,2.9,2.7
Fearless Audio Start|-1.3,-1.6,-2.5,-3.8,-5.4,-4.5,-2.7,-0.2,2.5,2.1,-2.3,0,0.7,5.1,6,6
Fearless Audio Tequila|2.3,2.3,1.4,-0.2,-2.8,-4.1,-3.9,-3.3,-2.3,-2.1,-1.9,1.3,2.7,1.8,3,5.2
Fender FXA6|-1.7,-1.5,-2.1,-3.3,-4.8,-4.4,-2.9,-1.5,0.5,0.4,-0.7,1.1,1.4,6.2,6.2,6.2
Fender FXA9|0.9,0.2,-1.2,-3.2,-5.7,-6.7,-6,-4.6,-2.9,-2.4,-0.2,5.4,1.2,5.8,5.8,5.8
Fender Ten 5|-0.4,-0.9,-1.8,-2.9,-4,-3,-1.6,-0.2,0.7,-1.5,-4,1.2,-1,1.7,4.1,5.3
Fender Thirteen 6|3.6,2.8,1.5,-0.6,-3.5,-5.3,-5.5,-5.4,-5.7,-4.5,-4,1.3,4.7,4.7,4.7,4.7
Fengru EMX500|4.6,2.4,-0.6,-2.4,-4.5,-3.6,-4.7,-4.3,-4.1,-4.2,-2.6,1.9,4.6,1.5,2.1,4.6
Fengru HE-200|-6.3,-5.8,-5.4,-5.6,-5.8,-3.6,-1.4,1.3,4.2,5,4.5,3.4,2.2,-1.7,7.4,7.4
Fidue A31s|-3.9,-4.1,-4.4,-5.1,-5.5,-3.9,-1.8,0,2.2,2.4,2.2,0.4,3.2,1.7,7.9,7.9
Fidue A63|0.7,-0.6,-2.5,-4.3,-6.2,-5.7,-4.2,-2.4,-0.2,1,0.5,1.5,3.4,5.6,4.7,5
Fidue A65|3,1.3,-1,-3.1,-5.2,-5.2,-3.8,-2.2,-0.1,0.9,-0.7,0.4,2.4,4.2,2.2,2.7
Fidue A71|-6.7,-6.3,-6.1,-6.5,-6.8,-4.8,-2.4,0.1,2.5,2.9,1.6,2.6,7.7,7.7,6.9,7.3
Fidue A81|1.7,0.9,-0.4,-1.9,-3.4,-2.7,-1,0.6,2.7,2.9,2.4,-0.9,0.9,-6.6,-1,-1.1
Fidue A83|1.2,0.6,-0.6,-2,-3.4,-2.9,-1.3,0.3,2.2,0.7,-3.1,-0.4,3.6,6.2,-3.5,-3.1
Fidue A91 Sirius|3.3,2.4,0.7,-1.3,-4,-5.1,-4.1,-2.5,-0.1,-2.1,-6.4,-0.3,1.1,4.3,3.6,4.4
Fidue A91 Sirius (CIEM)|4.6,4.2,2.5,0.6,-1.8,-3.1,-3,-2.4,-2,-3.6,-4.9,0.1,-0.1,1.6,2.3,0.3
FIIL Key|-5.3,-8.3,-12.1,-11.4,-5.7,4,-0.8,-2,0,1.2,2.6,1.8,6.2,5,7.5,7.3
FIIL T1 Lite TWS (bass EQ)|-6.3,-5.8,-4.6,-3.6,-4.9,-1.8,0.8,3.4,5.7,5.7,5.9,3.1,3.7,5.4,1.3,0.6
FIIL T1 Lite TWS (default EQ)|-3.3,-2.4,-1.4,-0.5,-2.5,-0.9,1,3.1,4.7,4.3,3.8,-0.1,2,3.6,-0.5,-1
FIIL T1 Lite TWS (treble EQ)|-2.2,-1.2,0.2,-2.3,-1.7,-0.1,1.9,4,5.7,5,4.3,0.5,0.2,1.3,-3.1,-3.4
FIIL T1X|-2.2,-1.3,-0.5,-0.7,-1.2,-0.3,1.4,3.6,5.5,2.5,2.1,-1.5,3.3,5.6,-2.5,-4.7
FiiO EH3|0,1.8,-2.5,-4.6,-7.6,-6.1,-2,1.3,3.2,0.9,3.4,3.5,6.7,-1.4,2.2,4.6
FiiO F3|-0.7,-1.5,-2.6,-3.4,-4.2,-3.5,-2.1,-0.3,1.2,0.8,0.2,4.8,5.5,6.1,1.4,2
FiiO F5|0.8,0.5,-0.6,-2.3,-4.2,-4.1,-2.9,-0.9,1.2,1.4,0,-1.7,2.5,-2.7,1.2,5.1
FiiO F9|2.1,1.6,0.3,-1.3,-2.8,-2.3,-1,0.8,2.6,1.3,-1.9,1.7,4.9,-0.9,-4.4,-1.1
FiiO F9 Pro|0.9,0.6,-0.2,-1.4,-2.6,-1.7,-0.2,1.4,2.9,1.8,-2.4,0.3,3.5,2.6,-3.6,-2.3
FiiO FA1|2.9,2,0.6,-1.1,-3.5,-4.3,-3.7,-2.4,-0.7,-0.5,-1.1,-1.4,1.2,0.5,0.7,3.6
FiiO FA7|0.5,-0.6,-2,-3.7,-5.9,-6.3,-5.2,-3.2,-0.3,1.3,2.3,4.7,5.5,5.6,4.3,3.4
FiiO FA7S|0.6,0.1,-0.8,-2.4,-4.4,-5.1,-4.4,-3,-0.9,0.3,1.1,5.7,3.1,2.5,4.9,3.8
FiiO FA9|1.5,1.3,0,-1.6,-3.2,-3.4,-2.4,-1.5,-1.1,-1.5,-0.8,3.8,4.6,4.6,3.2,0.2
FiiO FD1|2.6,1.8,0.1,-1.6,-3.6,-3.5,-2.3,-0.4,1.6,2.1,-0.2,-2.2,-3.1,-0.6,1.1,0
FiiO FD11|-1.8,-1.9,-2.2,-3,-3.6,-2.3,-0.6,1.3,2.7,1.8,0.4,1.7,4.6,0.1,3.6,2
FiiO FD3 (bass eartips)|-2,-2.2,-2.8,-3.7,-4.6,-3.3,-1.6,0.3,1.9,2,1,1,4.5,-1.7,2.8,4.4
FiiO FD3 (black nozzle)|-1.7,-1.8,-2.4,-3.3,-4.5,-3,-1.3,0.6,2.2,2.3,1.6,0.4,3.8,-2.2,0.7,3.2
FiiO FD3 (foam eartips)|-1.8,-2,-2.6,-3.5,-4.4,-3.2,-1.5,0.3,1.8,1.7,0.3,1.5,5,-1.9,1.3,3.5
FiiO FD3 (stock nozzle)|-1.8,-2,-2.7,-3.6,-4.5,-3.4,-1.6,0.2,1.8,1.6,0.7,1.3,5.2,-1.5,2.2,3.6
FiiO FD3 (vocal eartips)|-2.1,-2.2,-2.7,-3.3,-4.8,-3.4,-1.6,0.2,1.8,1.9,0.9,1.4,4.7,-1.6,2.6,4.1
FiiO FD5|0,-0.3,-1.1,-2.2,-3.5,-2.7,-1.3,0.2,1.7,1.7,0.6,-0.7,2.7,-1.6,1.9,3.4
FiiO FD5 (narrow nozzle)|-2.2,-2.1,-2,-2.7,-3.6,-2.5,-1,0.5,1.7,1.5,0.5,1.3,5.2,-0.4,3.7,3.5
FiiO FD5 (narrow nozzle, 3-flange eartips)|-1.5,-1.8,-2.5,-3.6,-4.7,-3.7,-2.1,-0.4,0.9,0,-2.1,5.6,6.2,1.5,1.1,1.5
FiiO FD5 (stock nozzle)|-1.1,-1.4,-1.9,-3,-4.2,-2.8,-1.2,0.7,2.4,2.2,1.6,1.2,4.3,-1.3,0,1
FiiO FD7|1,0.1,-1,-2.4,-3.9,-3.5,-2.5,-1.2,-0.1,0,-1.2,-0.3,3.6,-1,4.1,5.3
FiiO FD7 (balanced nozzle)|0.3,-0.3,-1.3,-2.6,-4.3,-3.8,-2.7,-1.4,-0.2,-0.1,-0.5,2,4.7,-0.8,2.4,2.9
FiiO FD7 (balanced)|0.3,-0.3,-1.3,-2.6,-4.3,-3.8,-2.7,-1.4,-0.2,-0.1,-0.5,2,4.7,-0.8,2.4,2.9
FiiO FD7 (bass nozzle, 3-flange eartips)|0.2,-0.4,-1.5,-2.9,-4.6,-4.3,-3.4,-2.3,-1.3,-1.7,-2.8,5,5.7,2.5,2.6,1.8
FiiO FD7 (treble nozzle)|0.4,-0.2,-1.3,-2.4,-4.1,-3.7,-2.5,-1.2,0.1,0.3,0.1,1.7,4,-1.3,1.2,2.4
FiiO FDX|0.8,0.3,-0.5,-1.8,-3.1,-3,-2.1,-1,0,-0.3,-0.7,0.4,3.4,-1.3,2.7,3.6
FiiO FF1|4.4,4.4,4.4,0.7,-3,-3.3,-2.7,-2.6,-1.8,-0.3,0.3,-3.1,-3.6,-3,3.7,3.9
FiiO FF1 (black)|4.3,4.3,4.3,4.3,1.3,0.2,1.4,-0.7,-1,-1,-3,-8,-4.4,-4,-1.7,1
FiiO FF1 (foam)|4.5,4.5,4.5,1.8,-2.5,-2.8,-1.5,-1.6,-0.8,1,2.4,-4.3,-4.1,-3.3,-0.6,0.5
FiiO FF1 (no foam)|4.8,4.8,4.8,4.8,3.5,2.6,3.8,1.9,2.1,2.4,0,-11.3,-8.3,-5.9,-7,-4.4
FiiO FF1 (white)|4.5,4.5,4.5,4.5,2,0.8,1.9,0.2,-0.1,-0.3,-2.8,-7.8,-5.5,-5.7,-2.2,0
FiiO FH1|-1.6,-1.6,-2.2,-3.3,-4.4,-3.2,-1.4,0.6,2.5,1.1,-1.3,1.4,4.6,4.8,2.2,1.7
FiiO FH11|-2,-2.1,-2.7,-3.5,-4.7,-3.2,-1.2,1.2,3.2,1.7,-1.2,5.3,5.2,4,-0.5,-1.7
FiiO FH15 (balanced nozzle)|-3.6,-3,-2.8,-3.4,-4.2,-3.7,-2.7,-1.6,-0.2,0,-0.5,1.6,4.3,4.1,6.6,6.6
FiiO FH15 (bass nozzle)|-4.3,-3.6,-3.3,-3.8,-4.6,-4,-3.1,-1.8,-0.2,0.2,-0.1,2,6.4,5.3,6.7,6.7
FiiO FH15 (treble nozzle)|-3.3,-2.7,-2.5,-3,-3.9,-3.3,-2.4,-1.3,0,-0.2,-0.7,1.3,2.7,3.1,6.4,6.5
FiiO FH1s|2.1,1,-0.7,-2.4,-4.5,-4.3,-2.8,-0.7,1.9,1.7,0,-1,-1.7,0.1,1.5,0.5
FiiO FH3|-1.9,-1,-0.6,-1,-1.8,-1.3,-0.2,1.2,0.2,-2.5,0.5,0.9,4,3,0.9,-0.1
FiiO FH5|-1.9,-1.1,-0.9,-1.4,-2.2,-1.4,-0.6,0,0.3,-1,0,2.1,6.6,4.4,2.2,2.3
FiiO FH5s|1.6,1.6,1.1,0.1,-1.2,-0.7,0.4,1.4,1.8,-0.2,-4.6,0,1.3,0.3,0.7,0.7
FiiO FH5s (off-off-off)|0.6,0.7,0.3,-0.7,-1.8,-1,0.2,1.5,2.1,0.3,-3.1,2.4,1.6,-0.3,-1.5,-2
FiiO FH5s (off-off-on)|2,2.1,1.6,0.6,-0.7,-0.1,1,1.9,2.3,0.3,-4.1,0.5,1.8,0.5,-1,-2.1
FiiO FH5s (off-on-off)|1.5,1.5,1.1,0,-1.3,-0.7,0.5,1.6,2.2,0.4,-4.1,-0.7,1.5,0.4,0.8,0.5
FiiO FH5s (off-on-on)|0.1,0.6,0.9,0.6,-0.4,0.8,2.2,3.4,3.9,1.8,-1.8,1.5,2.1,0,-2.9,-5
FiiO FH5s (on-off-off)|0.7,0.8,0.3,-0.7,-2,-1.4,-0.3,0.6,0.9,-1.1,-5.4,0,2.2,1.1,2.5,3.5
FiiO FH5s (on-off-on)|-0.7,-0.1,0.2,0.1,-1.2,0,1.2,2.3,2.5,0.4,-3.1,2.2,3.2,1,-0.8,-1.9
FiiO FH5s (on-on-off)|-1.2,-0.6,-0.3,-0.6,-1.6,-0.5,0.9,2,2.4,0.3,-3.2,1.1,3.1,0.8,0.3,0.5
FiiO FH5s (on-on-on)|1.1,1.2,0.7,-0.4,-1.5,-1,0.1,1.2,1.7,-0.1,-4.3,0.2,3.4,1.2,0.6,0.4
FiiO FH7|1.8,1.6,0.6,-0.9,-2.7,-2.3,-1.4,-0.7,-2.3,-2.2,-2.2,2.1,6,5.9,0.8,-2
FiiO FH7 (bass nozzle)|1.7,1.4,0.5,-1,-2.8,-2.5,-1.4,-0.5,-0.5,-0.9,0.6,0.9,4.8,5.9,-0.6,-3
FiiO FH7 (reference nozzle)|1.8,1.5,0.6,-0.9,-2.6,-2.4,-1.3,-0.6,-0.8,-0.7,0.9,1.2,5.3,6,-1.7,-4.1
FiiO FH7 (treble nozzle)|2.1,1.9,0.8,-0.6,-2.4,-2.1,-1,-0.1,-0.4,-0.8,0.9,1.4,6.1,4.3,-2.7,-4.9
FiiO FH7s (balanced nozzle)|0,-0.3,-1.4,-2.9,-4.6,-3.8,-2.1,0.1,2.5,2.5,2.1,2.6,2.6,4.3,1.3,-1.6
FiiO FH7s (bass nozzle)|-0.2,-0.5,-1.6,-3.1,-4.7,-4,-2.2,-0.1,2.3,2.2,1.7,2.2,2.4,4.6,2.2,-0.4
FiiO FH7s (treble nozzle)|0.4,0.1,-0.9,-2.3,-3.7,-3.3,-1.5,0.7,3,2.9,1.9,2.6,2.8,4,0.1,-3.2
FiiO FH9 (black filter)|2,1.5,0.2,-1.3,-3.5,-3.8,-2.7,-1.2,0.5,0.9,0.2,-1.2,1.3,2.1,4.4,2.4
FiiO FH9 (green filter)|2.1,1.8,0.6,-1.1,-3.3,-3.6,-2.3,-0.8,0.6,1,0.1,-1.1,1.2,1.9,3.4,0.8
FiiO FH9 (red filter)|1.4,1,-0.3,-1.8,-4,-4.2,-3.1,-1.7,0,0.5,-0.4,-2,0.5,3.5,5.4,5.4
FiiO FHE Eclipse|-2.7,-2.1,-1.8,-2.1,-2.6,-1.5,-0.1,1.1,1.6,1.1,-0.1,0.2,3,6,4.2,3.6
FiiO FT3 (pleather earpads)|2.6,2,0.8,-0.3,-1.8,-2.5,-1.8,-1.1,0.8,-0.3,-0.4,-1.3,2.6,-3.3,0.1,1.1
FiiO FT3 (suede earpads)|0.6,1,-1,-1.9,-2.8,-2.1,0,2.4,2.9,-0.3,-0.6,-1.3,3.6,-1.2,0.1,0.6
FiiO FW5|-1.4,-1.3,-1.7,-2.7,-3.8,-3.2,-1.9,0.3,2.7,2.8,2.5,1.4,3.2,-1.3,1.7,-2.6
FiiO FX15 (off)|-1.7,-1.6,-1.9,-2.6,-3.6,-2.8,-1.6,-0.6,0.7,1.7,2.4,-2.9,-1.1,0,2.4,5.6
FiiO FX15 (on)|-0.7,-0.6,-0.8,-1.6,-2.6,-1.8,-0.7,0.4,1.7,2.7,3.4,-1.8,-0.2,1,-0.1,-1.4
FiiO x JadeAudio JD3|-3.4,-3,-2.9,-3.3,-3.7,-2.6,-0.8,0.9,1.8,0.7,-0.9,-0.1,5.9,2.4,5.7,6.4
Final Audio A3000|1.3,0.8,-0.1,-1.2,-2.5,-2.2,-1.1,0,1.2,1.1,0.7,4.2,3.3,1.6,-1.5,-2.7
Final Audio A4000|0.4,0.6,0.5,-0.2,-1.1,-0.8,0.3,1.4,2.8,2.5,2,1.6,-1.1,-1,-1.3,-0.8
Final Audio A5000|-2.9,-2,-1.4,-1.7,-2.2,-1,0.2,1.7,3.2,2.5,3.9,5.5,1.1,0,-1.3,-1.2
Final Audio A8000|0.1,0.4,0.3,-0.3,-1.4,-1,-0.1,0.9,1.5,0.3,-1.6,-2.3,2,-3.4,-1.9,2
Final Audio Adagio III|-3.2,-2.8,-2.7,-3.2,-3.4,-1.1,0.9,3.7,6.5,6.9,6.8,6.6,-1.1,-0.9,-4.7,-1.9
Final Audio B1|2.4,0.9,-1.3,-3.2,-5.1,-4.6,-2.7,-0.5,2.1,2.7,1.7,1.5,2.1,-0.2,-2.2,0.7
Final Audio B2|2.9,2.3,0.8,-0.9,-3.4,-4.1,-3.6,-2.4,-0.6,-0.5,-0.9,-2.3,-2.3,-2.3,4.5,4.6
Final Audio B3|3.9,2.2,0,-2.1,-4.7,-5.3,-4.4,-3,-0.8,-0.1,0.1,1.6,1.7,2.7,0,0.2
Final Audio B3 (Final Audio TWS eartips)|3.1,1.6,-0.6,-2.6,-5.4,-5.9,-5.1,-3.4,-1,0.3,2.3,4.9,3.7,5.1,0.9,-2.8
Final Audio B3 (Final Audio Type E eartips)|3.3,1.7,-0.3,-2.2,-4.9,-5.7,-4.8,-3,-0.7,0.5,2.3,4.8,2.1,2.9,-1.1,-1.7
Final Audio D8000|2.7,1.2,-0.8,-1.8,-3.2,-3.1,-2.1,-0.2,-0.7,-2.3,-1.8,5.7,4,0.8,0,-1.5
Final Audio D8000 Pro Edition|4.1,3,1.7,0.4,-1.5,-2.2,-1.2,0.7,-1.2,-2.8,-0.9,5.5,1.9,-1,-1.7,-3.4
Final Audio E1000|4.2,2.8,0.8,-1.1,-3.4,-3.6,-3.5,-2.1,-0.5,0.2,1.4,1.8,0.8,-0.5,-0.4,1
Final Audio E1000 (Final Audio Type E eartips)|4.6,3.9,1.4,-0.8,-3.7,-4.6,-4,-2.7,-0.9,-0.2,4.6,3.9,-0.1,-2.5,-1.7,0.2
Final Audio E2000|2.9,1.1,-1.3,-3.4,-5.7,-5.5,-4.1,-2.1,0.3,1.3,0.3,1.3,3.6,3.5,2.7,2.9
Final Audio E3000|1.3,-0.2,-2,-3.5,-4.8,-4.3,-2.5,-0.5,1.3,1,2.3,2.6,2.5,3.2,-0.2,0
Final Audio E4000|-0.3,-1.4,-2.9,-4.3,-5.6,-4.6,-3,-1.1,1.1,1.7,0.6,1.2,2.8,4.7,5.1,4.2
Final Audio E500|4.7,3.4,1.2,-0.9,-3.5,-4.2,-3.5,-2.6,-1,-1,-0.4,0.8,-0.4,0.5,0,0.4
Final Audio E5000|-2.8,-3,-3.1,-3.5,-3.9,-3,-1.2,0.5,2,1.4,1.9,2.3,1.3,5.1,2.2,2.3
Final Audio EVA2020|0.1,-1.3,-2.9,-4.4,-5.7,-4,-3.1,-1,1.5,0.9,1.1,2.4,3.8,3.5,2.7,3.9
Final Audio F3100|4.3,4.3,3.9,1.2,-2.1,-3.7,-4,-3.1,-1.8,-1.6,0,0.3,-3.8,4.3,-3.4,-1.7
Final Audio F4100|4.4,3.8,1.3,-1,-4,-5.5,-5.2,-4.1,-2.1,-1.3,-0.9,0,-0.5,4.4,1.9,3.2
Final Audio F7200|4.6,4.6,2.5,-0.1,-3.3,-4.7,-4.5,-3.6,-2.1,-1.9,-0.4,0.7,-3.8,4.6,0.9,0.1
Final Audio FI-DO6SS ANT20|-0.7,-1.3,-2.3,-3.7,-5.6,-5.6,-4.3,-2.3,0.3,1.6,2.1,1.1,5.2,4.3,2.5,5.8
Final Audio Heaven 4|1.3,0.9,-0.5,-2.3,-4.7,-5.5,-4.7,-3.2,-0.9,-0.2,0.2,1.3,1.8,3,4.7,5
Final Audio Heaven 7|4.1,4.1,1.7,-0.6,-3.7,-5.3,-4.8,-3.7,-1.7,-1,-0.6,-0.3,-1.5,4.1,-0.3,4.1
Final Audio Heaven 8|0.2,-0.7,-2.2,-3.9,-6.1,-6.4,-5.3,-3.5,-0.9,0.3,1.1,2.6,2.3,5.4,4.7,5.4
Final Audio Heaven A|4.3,3.5,1.7,-0.2,-2.7,-3.8,-3.3,-2.6,-1.2,-1.1,-0.3,-1.5,0.4,2.6,-1.5,1.1
Final Audio Heaven II|4.5,4,2.6,0.7,-1.8,-3.3,-3.2,-3.1,-2.1,-2.5,-2.3,-3.3,0.9,4.2,0.1,-0.8
Final Audio Heaven III|-3.4,-3.7,-4.5,-5.5,-6.6,-4.7,-1.6,2.6,7.3,7.3,7.3,7.3,-0.5,-0.7,-2.5,3
Final Audio Heaven IV|1.2,0.9,-0.4,-2.1,-4.4,-5.6,-4.6,-3.1,-1.3,-0.8,0.7,1.4,1.4,3.8,3.5,4.9
Final Audio Heaven S|1,0.4,-1,-2.5,-4.7,-5.3,-4.2,-2.3,0,1,1.4,1.2,2.5,2.5,2.6,5.2
Final Audio Heaven V|1.7,1.3,0,-1.7,-3.9,-4.7,-3.6,-2.2,-0.4,-0.3,0.7,-0.1,1.5,2.2,0.6,4.4
Final Audio Lab 1|1.9,0.5,-1.3,-3.2,-5.4,-5.8,-4.8,-3,-0.6,0.4,0.8,0.1,3.4,2.8,2.2,4.9
Final Audio Lab 1 Fit 1|2.8,2.4,0.8,-1.2,-4.2,-6.3,-7.3,-9,-10,-5.1,0.6,6.6,6.6,4.2,6.6,6.6
Final Audio Lab 1 Fit 2|5,5,4.5,0.1,-5.2,-6.6,-6.1,-5.5,-5.2,-4.6,-4.5,4.6,5,1.5,5,5
Final Audio Make 2|3,1.3,-1.3,-3.4,-5.7,-5.5,-4,-1.9,0.7,1.2,2.3,2.3,0.5,0.4,-0.8,2
Final Audio Pandora Hope 4|-1.8,-1,-2.8,-0.7,-2.7,-3.2,-1.6,-1.3,-1.3,-0.3,1.5,2.4,2.1,2.8,3.9,1.3
Final Audio Sonorous III|4.4,4.4,4.4,3,-2.3,-6.2,-4.5,-3.1,-4.5,-3.8,0.4,0.6,4,0.8,-1.4,-1.9
Final Audio Sonorous VIII|5.8,4.7,-3.9,-4.4,-5.8,-6.1,-5.4,-5.6,-4.2,-1.3,2.7,5.8,2.9,3.5,5.8,3.7
Final Audio Sonorous X|5.2,5.2,-0.6,-2.7,-5.3,-6.6,-6,-6,-5.1,-2.5,1.4,5.2,1.9,4.1,5.1,1.1
Final Audio UX3000|-1.9,-1.7,0.3,-1.8,-3.9,0.4,1.9,0.8,1.3,-0.1,-1.3,1,3.5,-0.2,-1.8,0.3
Final Audio UX3000 (Bluetooth, ANC on)|-0.8,-2.1,-3.4,-4,-3.3,-2.1,-0.4,-0.2,-0.9,-1.1,-0.4,2.1,1.8,-0.3,3.5,5
Final Audio VR2000|0.9,0.7,0.2,-0.7,-1.9,-1.5,-0.5,0.4,1.5,0.8,2.2,5.5,1.6,-0.5,-2.8,-4
Final Audio VR3000|1.7,1.4,0.7,-0.5,-1.9,-2.1,-1.3,-0.5,0.7,0.4,2,5.1,2.3,-0.4,-4.1,-4
Final Audio ZE3000|3.6,2.7,1.1,-0.6,-2.8,-2.7,-1.7,0,1.8,1.6,1.1,-0.7,3,2.6,-1.4,-4.1
Final Audio ZE8000|-0.6,-1,-2,-3.4,-5.4,-5.3,-4,-2.4,-0.4,0.6,3.3,7,7,7,2.5,-3.1
Fir Audio e12|-2.1,-1.8,-1.8,-2.4,-2.9,-1.2,0.6,2.7,4.3,3.5,2,0.4,4.5,-2.2,-2.7,0.5
Fir Audio Krypton 5|0.9,0.4,-0.8,-2.5,-4.4,-3.8,-1.8,0.5,2.2,1.1,-0.5,6,4.9,6,1.2,-2.5
Fir Audio M2|2,1.4,0.1,-1.7,-3.8,-3.4,-1.3,2.7,3.8,2.1,-0.6,-2.9,-2.6,-7.2,-0.4,4.6
Fir Audio M3|1.5,1.7,1.5,0.9,0.3,1.7,3,3.8,2.1,-2,-3.1,0.4,1.3,-2.7,-2.2,-2.9
Fir Audio M4|1.5,1.3,0.8,-0.2,-1.2,-0.4,1,2.5,3.5,0.9,-1.9,0.5,0.6,-4.2,-0.6,-1.8
Fir Audio M5|1,0.7,-0.2,-1.5,-3,-2.8,-1.7,-0.3,1.6,1.8,-0.9,2,3.1,-0.5,1.9,1.3
Fir Audio M5 (CIEM)|0.6,1.1,1.1,0.2,-1.6,-2.2,-2.2,-1.9,-0.9,-0.3,-3.5,-1.7,0.1,-0.5,5.7,5.7
Fir Audio M5 S2|0.6,0.8,0.5,-0.5,-2.1,-2.6,-2.4,-1.6,0.1,0.5,-1.3,1.6,2.1,1.4,6.3,3.1
Fir Audio Neon 4|0,-0.4,-1.5,-3.1,-4.8,-4.3,-2.9,-1,0.9,0.5,0.4,6.2,5.5,6.4,4.5,0.2
Fir Audio Radon 6|-1.7,-1.7,-1.8,-2.4,-2.7,-2.6,-2.1,-0.6,2.2,3.4,5,4,4.4,1.6,0.3,-1.3
Fir Audio VxV|1.5,1.1,0,-1.5,-3.3,-3.1,-1.9,-0.5,-0.3,0.6,3.5,0.5,6,1.7,-1.4,-2.1
Fir Audio Xenon 6|0.6,0.3,-1,-2.7,-5.1,-5.7,-4.3,-1.3,2.7,4.3,5,1.1,5.5,3.5,2.9,-0.4
Fir Audio Xenon 6 (CIEM)|-0.3,-1.1,-2.5,-4.2,-6.3,-5.3,-3,-0.4,2.7,4.1,2.6,1.8,3.3,6.3,4.6,2.8
First Harmonic IEB6|5.2,4,1.2,-1,-3.1,-3,-1.5,0,2.1,1.8,1.7,1.8,-3,-2.4,-1.7,-1.6
Fischer Audio FA-003|2.6,1.3,0.6,-2.1,-4.5,-0.3,2.6,-1.2,-0.7,0.5,2.1,0.8,-2.2,-2.8,3.6,2.8
Fischer Audio FA-011 LE|1.4,-1,-2.2,-2.6,-3.1,-3.6,-3,-3.2,-1.4,-0.9,2.9,5.4,2.8,4,0.3,0.4
Fischer Audio Oldskool RPM 33 1-3 (Zeppelin & Co Limited Edition)|5,3.4,0.9,0,-1,-2,-2,-3.6,-5.8,-2.1,0.2,-0.5,3.6,4,-0.7,3.1
Fischer Audio Omega Spark|4.3,4.3,4.3,3.2,0.5,-0.8,-0.9,-0.3,0.7,0.7,1,1.2,-7.7,-5.8,-6.1,0.1
Fischer Audio SBA-03|4.3,3.7,2,0.1,-2.4,-3.8,-3.5,-3,-1.9,-1.9,-1.3,-3.9,3.8,-2.4,-1.6,4.2
Fischer Audio Tandem|5.2,4.9,1.9,-0.8,-4,-5.8,-5.6,-5.3,-4.1,-3.9,-1,3.9,3.2,2.5,5.2,2.3
Fit Open Earbuds S50|4.8,4.8,4.8,4.8,4.8,3.2,1.2,-2.2,-5,-5,-4.6,-7.2,-2.1,-5,-3.1,-2.7
Fit SE S30|4.2,4.2,4.2,4.2,4.2,2.6,0.8,-1.7,-3.1,-2.7,-3.2,-9.3,-6.1,-6.1,-4.4,4.2
Fitbit Flyer|3.7,-4.9,-9.3,-5.8,-1.8,-0.7,-0.8,1.1,2.9,0.2,0.6,0.3,4.1,5,5.1,3
FitEar Air 2|2.2,1.2,-0.5,-2.1,-4,-3.4,-1.9,0,1.9,1.5,-1.4,1,4.7,3.3,0.5,-1.8
FitEar Aya|0.6,0.1,-1.2,-2.9,-5.2,-5.6,-4.6,-2.9,-0.7,0,0.4,2.6,0.4,4.6,4.2,5.4
FitEar EST|2.3,1.6,0.3,-1.5,-3.9,-4.7,-4,-2.9,-1.1,-0.9,-0.6,2.2,1.4,-2.3,1.7,4.5
FitEar F111|-2,-2.1,-3,-4.4,-6.2,-6.1,-4.7,-2.6,0.2,2,3.7,5.5,6,3.7,4.4,6
FitEar Fitear|-0.6,-0.9,-2.1,-3.7,-5.9,-6.3,-5.4,-3.7,-1.3,0,0.7,3.5,3.6,5.7,5.7,5.7
FitEar MH334|1.6,0.7,-0.9,-2.7,-5.3,-6,-5.1,-3.7,-1.5,-0.9,-1.5,1.6,3.9,3.1,4.1,4.9
FitEar MH334SR (CIEM)|1.4,0.2,-1.3,-3.1,-5.4,-6,-5.1,-3.4,-1.1,-0.4,-0.9,2.3,4.6,4.4,1.4,4.9
FitEar MH335DW|0,-0.6,-1.9,-3.7,-5.8,-6,-4.7,-2.7,0,1.2,1.5,4,5.7,2.4,3.1,5.2
FitEar Monet 17|-0.4,-1.1,-2.3,-3.9,-5.9,-5.5,-3.9,-1.3,2.1,4.4,5.9,3.6,4.4,-1.6,-1,1.5
FitEar Parterre|-2,-2.3,-3.4,-4.8,-6.7,-6.5,-5,-2.8,0.3,2.2,3.6,5.1,5.8,5.2,3.7,5.4
FitEar Private 222|1.8,1.3,0.1,-1.7,-4,-4.4,-3.4,-1.7,0.5,1.1,0.3,0.3,1.6,-3,2.1,4.9
FitEar Private 223|4.7,4.7,3.4,1.7,-0.7,-1.8,-1.5,-0.7,0.4,-0.6,-3.3,-0.8,1.2,-4.9,-2.8,-1.2
FitEar Private 333|3.3,2.7,1.3,-0.5,-2.9,-3.8,-3,-1.7,0.1,0.2,-0.4,-0.5,1.3,-3.5,-1.9,2.7
FitEar Private C435|3.4,1.8,-0.1,-2.1,-4.6,-5.6,-5.1,-3.9,-2.6,-3.2,-6.8,4.8,3.1,5,5,5
FitEar TG334|0.9,0.3,-1.1,-3,-5.3,-5.8,-4.9,-3.4,-1.2,-0.7,-0.7,3.3,4.1,5.1,3,5.1
FitEar Titan|2.5,1.3,-0.4,-2.1,-4.1,-3.8,-2.3,-0.4,1.6,1.6,0,0,4.5,3.9,2.1,-2.1
Flare Audio Reference R1|5.7,5.7,-0.4,-3.6,-6.2,-7.5,-7,-8.4,-8,0.4,0.6,3.4,5.6,5.7,5.7,5.1
FLC Technology DD31|0.8,-0.1,-1.6,-3.4,-5.3,-4.7,-3,-0.3,3.2,5.1,6.6,6.6,4.1,-0.9,-1.1,-3.1
FLC Technology FLC8 (gray LF, grey ULF, black HF)|0.6,0.8,0.5,-0.4,-1.7,-1.9,-1.4,-1.2,-0.6,-2.2,0.8,3.2,6.5,6.5,-2.3,-4.9
FLC Technology FLC8 (gray LF, grey ULF, blue HF)|-2.2,-2,-2.3,-3.3,-4.6,-4.8,-3.9,-3.3,-2.3,-3.1,0.4,5.4,7,7,6.7,3.1
FLC Technology FLC8 (light, grey LF, grey ULF, blue HF)|5.3,5.3,3.7,1.6,-1.2,-2.6,-2.4,-2.2,-1.8,-3.8,-1.6,1.3,5.3,5.3,-4.9,-6.8
FLC Technology FLC8 (pop-rap, black LF, grey ULF, black HF)|-0.6,-0.5,-0.7,-1.6,-2.5,-2.3,-1.5,-1.1,-0.1,-0.9,2,4.5,6.8,6.8,-2.2,-4.5
FLC Technology FLC8 (strings-piano, clear LF, clear LF, green HF)|5.3,5.3,4,1.9,-0.7,-2.2,-1.9,-1.8,-1.4,-3.4,-0.3,1.4,5.3,4.7,-7.1,-7.9
FLC Technology FLC8 (vocals, clear LF, clear ULF, yellow HF)|5.1,5.1,3.4,1.4,-1.3,-2.9,-2.6,-2.4,-1.6,-3,-6.7,-1,2.4,5.1,0.2,-4.3
FLC Technology FLC8D (gold filter)|-2.1,-1.1,-0.6,-0.9,-1.4,-0.8,0.2,1.2,1.6,-0.6,-1.4,3.7,2,7,1,-2.5
FLC Technology FLC8D (gunmetal filter)|-2.1,-1.1,-0.6,-0.9,-1.5,-0.8,0.1,0.9,1,-0.3,3.7,4.4,4.2,7.2,-2.5,-4.5
FLC Technology FLC8P (gold, black, gunmetal)|-2.5,-2.5,-2.8,-3.5,-4.2,-2.6,-1.5,0.4,1.9,1.3,3.2,6.7,6.1,6.5,-2.1,-2.9
FLC Technology FLC8P (gold, black, red)|-4,-3.5,-3.2,-3.6,-4,-2.4,-1,0.7,2,1.2,4,7.2,6.8,7,-1.7,-2.5
FLC Technology FLC8P (gold, black, transparent)|4.8,4.8,3.4,1.4,-1.4,-2.3,-1.9,-1,-0.2,-1.9,-2.9,3.3,-0.2,3.1,-5.5,-6.5
FLC Technology FLC8P (gold, gray, gunmetal)|-0.4,0,0,-0.6,-1.7,-1.3,-0.7,0.2,0.9,-0.7,0.3,5.5,2.9,5.1,-3.7,-4.6
FLC Technology FLC8P (gold, white, gunmetal)|1.4,1.8,1.8,0.8,-0.7,-0.9,-0.7,0.1,0.4,-1.5,-1.4,4.3,0.8,4.1,-4.7,-5.6
FLC Technology FLC8P (green, gray, gunmetal)|-0.5,0,0.1,-0.6,-1.7,-1.4,-0.7,0.1,0.6,-0.6,4.8,5.7,4.5,3.2,-6.3,-6.1
FLC Technology FLC8P (gunmetal, black, gunmetal)|-2.9,-2.9,-3.3,-4,-4.6,-3.3,-2,-0.1,1.3,1.6,6.7,7.1,7.1,7,-2.2,-3.1
FLC Technology FLC8P (gunmetal, gray, gunmetal)|-0.9,-0.6,-0.4,-1.1,-2.2,-1.8,-1.2,-0.3,0.1,-0.9,4,5.8,4.2,5.6,-4.1,-4.8
FLC Technology FLC8P (silver, gray, gunmetal)|-0.7,-0.3,-0.3,-1,-2.1,-1.8,-1,-0.1,0.7,-0.8,-4.4,5.2,1.3,6.1,1.3,-0.1
Flicker Ear Flow|2.7,1.3,-0.8,-2.8,-5,-4.5,-1.6,0.9,-0.3,0.2,0.5,0.4,0.5,-2.9,0,2.2
Flicker Ear Norma|0.9,-0.2,-1.8,-3.7,-6,-6.6,-5.8,-4.3,-2,-0.8,1,3.2,3.2,4.3,4.6,5.2
FlipEars Aurora|-0.2,-0.5,-1.4,-2.7,-4,-3.1,-1.5,0.2,1.2,0.2,-2.6,2.3,4.1,5.5,2.5,2.5
Floaudio CALLA|-3.2,-2.8,-2.6,-3,-3.3,-1.7,0.1,1.7,3,1.6,-1.5,2.8,6,0.9,0.7,0.4
Focal Bathys|-0.3,-0.7,-1,-1.3,-1.5,-2.1,-2.8,-1.8,2.6,-0.2,-0.1,2.4,2.8,2.5,0.5,1.5
Focal Bathys (USB)|-1.2,-1.4,-1.5,-1.6,-1.9,-2.5,-3.2,-3.2,0.9,-1.3,1.1,2.7,5.9,6.6,2.8,2.2
Focal Bathys (wired)|-1,-1.2,-1.3,-1.4,-1.7,-2.4,-3.1,-2.9,1.1,-1.2,1.3,2.6,5.8,6.6,2.1,1.3
Focal Bathys (wireless)|-1.4,-1.6,-1.7,-1.8,-2.1,-2.8,-3.4,-3.1,1.3,-1.6,0.1,1.3,3.9,6.3,2.6,1.3
Focal Celestee|0.4,0.5,-1.3,-2.6,-1.3,0.6,0.3,-0.6,-1.4,-2.1,-1.8,-0.1,3,3.3,3.3,2.3
Focal Clear|3.7,2.8,1.2,0,-1.7,-2.4,-1.6,-1.2,-1.7,-3.6,-2.8,0.2,-0.6,2.9,3.1,1.9
Focal Clear (Focal Clear Mg earpads)|3.3,1.9,0,-1.4,-3.1,-3.7,-2.7,-2.3,-2.9,-4.7,-2.4,1.1,1.3,5.1,3.7,3.9
Focal Clear Mg|2.8,1.7,0.1,-1,-2.6,-3.1,-2.1,-1.6,-2.1,-3.7,-2.3,0.4,1.4,5.9,5.9,3
Focal Clear MG Professional|5.5,5.3,2.1,0.5,-2,-2.3,-1.2,-0.6,-0.9,-3,-3,-0.4,0.2,3.3,1.1,0.4
Focal Elear|3.3,2.2,0.5,-0.7,-2.4,-3.1,-2.2,-1.8,-1.8,-2.6,-2.2,-0.2,1.9,5.2,4,1.9
Focal Elear (Elex earpads)|4.2,2.7,0.9,-0.3,-2,-2.6,-1.7,-1.3,-1.7,-3.3,-4.4,0,0.3,3.8,3.7,1.9
Focal Elear (Utopia earpads)|4.1,2.9,1.1,-0.1,-1.7,-2.3,-1.3,-0.9,-1.6,-3.8,-3.8,0.5,-2,1.5,3.1,2.9
Focal Elegia|2,1.8,-0.1,-0.8,-0.2,-2.5,-2.6,-2.4,-2.8,-2.9,-3,1.1,4.9,1.8,3.4,3.4
Focal Elex|3.9,2.7,1,-0.2,-1.8,-2.4,-1.4,-1,-1.5,-3.3,-2.9,0.6,-0.6,0.3,2.8,3.3
Focal Listen|1.1,1.4,3.4,4.5,-0.2,-4.4,-3.5,-3.2,-2.3,-2.3,-1.4,1.1,2.3,1.1,1.2,0.7
Focal Listen Professional|5.2,5.1,5.2,5.1,1.5,-3.5,-5.4,-4.5,-2.9,-2.1,-1.6,2.5,4.4,-3.1,-1.1,-3.1
Focal Radiance|0.9,0.7,-1.2,-2.7,-2.7,-0.3,0,-0.2,-0.3,-0.9,-0.3,0.4,2.2,3.3,3.1,1.7
Focal Sphear|1.9,0.8,-0.9,-2.6,-4.8,-4.6,-3.1,-1.2,1.1,1.5,0.2,0.3,2.2,-1,-0.1,3.8
Focal Spirit Classic|2.1,1.5,0.8,-0.4,-1.6,-2.6,-2.8,-3.5,-2.2,-1.1,1.5,3.1,1.2,2.3,1.7,-0.3
Focal Spirit One|-1.9,-1.9,-1.7,-4.4,-5.2,-2.9,-0.7,-1.7,-1.1,-0.2,1.6,4.9,4.3,-0.2,4.2,3.7
Focal Spirit One S|-1.3,-1.7,-2.1,-3.9,-4.9,-2.1,1.2,-1.2,-0.2,0.4,3.1,5,0,1.4,3.5,1.4""",
"""Focal Spirit Professional|-1.1,-1.4,-1.8,-2.6,-3.1,-2.2,-0.6,-1.6,-0.6,-0.2,1.2,2.6,-3,3.3,4.3,2.4
Focal Stellia|0.8,0.7,-3,-4.9,-4,-0.1,0.5,0,-0.6,-1.5,-1.6,-0.4,1.9,2.9,3.5,5.5
Focal Utopia|3.7,2.5,0.8,-0.5,-2.2,-2.7,-1.7,-1.1,-1.3,-2.9,-0.9,-1.1,1.3,0.9,3.7,2.8
Focal Utopia (Dekoni fenestrated Sheepskin Earpads)|1.7,0.6,-0.9,-2,-3.5,-3.6,-2.4,-1.7,-2,-2.4,1.3,-0.2,4.8,1.6,6,4.1
Focal Utopia (Dekoni sheepskin Earpads)|1.3,0.3,-0.9,-2.1,-3.6,-3.7,-2.5,-1.9,-1.3,-2.9,0.7,-0.3,5.2,2.3,6.2,4.9
Focal Utopia (Elear earpads)|1.2,0.4,-1.2,-2.2,-3.4,-3.4,-1.6,-0.6,0.7,0.6,1.7,-0.8,2.2,4.7,2.3,1.8
Focal Utopia 2022|3.5,2.4,0.7,-0.6,-2.5,-3.2,-2.2,-1.5,-1.6,-3.4,-1.3,-0.1,1.4,2.9,4.5,3.7
Focus Audio EDM|-6.3,-5.9,-5.2,-5.6,-6.4,-4.7,-2.3,0.6,4.3,6.2,2.1,5.7,7.2,0.5,2.8,5.3
Focusrite HP60 (Scarlett Studio)|5.5,3.6,0.7,1.1,-1,-1.3,0.4,-2.6,-1.7,-2.1,-0.1,0.9,3.8,-5.7,-0.8,0.8
Fonge X2 Pro|-3.8,-3.4,-3.5,-4,-4.3,-2.6,-0.3,2.3,5,4.1,4,1.7,3.6,-3,1.5,2.3
Fostex T-X0|5,3.1,1.1,-1.4,-4.7,-6.1,-2.9,-0.1,1.2,-2.2,1.7,2.2,0.4,-0.2,1.5,2.3
Fostex T20RP Mk3|5,3,-3.4,-5.5,-5.2,-3.8,-1.8,1,1.5,-1.4,1.8,2.3,-2.9,1.5,-1,-0.5
Fostex T40RP Mk3|0.2,-1.1,-2.3,-2.7,-3.6,-2.6,-1.5,-0.5,-1.9,-0.4,3.6,3.7,2.2,3.4,0.9,0.6
Fostex T50|4.4,4.4,4.4,4.4,-0.6,-2,-2.4,-3.7,-4.3,-4.6,-3.4,-3,-3.2,2.1,2.3,1.4
Fostex T50RP|4.3,4.3,4.3,1.4,-2.8,-5.2,-4,-4.6,-4.8,-5.3,-4.6,1.1,0,3.5,3.3,1.5
Fostex T50RP (sample 1)|4.2,4.2,4.2,1.5,-2.6,-5.1,-3.9,-4.6,-4.8,-5.8,-5,0.7,0.2,2.9,3.7,2.2
Fostex T50RP (sample 2)|4.4,4.4,4.4,1.3,-3,-5.3,-4.1,-4.7,-4.7,-4.8,-4.2,1.5,0,4.1,2.9,0.7
Fostex T50RP Mk2|1.6,0.2,-0.6,-1.3,-3.8,-4.8,-3.8,-4.2,-2.1,-6.3,-4.2,3.3,2.6,5.2,4.7,2.8
Fostex T50RP Mk3|4.2,1.7,-0.2,-1,-1.6,-0.3,-1.7,-0.2,-1.2,-1.7,2.2,3.9,0.1,-0.6,-4.3,-3.2
Fostex TH-X00|1.2,1.6,1.5,1.6,0.5,0.3,1.1,0.2,-1.7,-1.7,-0.1,0.5,3.7,-1.1,0.5,-1.4
Fostex TH-X00 (ebony)|2.6,2.4,2.1,1.1,-0.3,-1,-0.2,-0.3,-1.9,-2.3,-1.5,-2.1,2.2,-0.3,-0.6,0.9
Fostex TH-X00 (mahogany)|1.4,1,1,0.5,-0.6,-0.1,1.2,0.7,-3.8,-3.6,-3.1,-1.5,5.8,1.8,-1.3,-0.2
Fostex TH-X00 (mahogany) (sample 1)|0.6,0,-0.8,-1.4,-2.3,-1.3,0.1,0.7,1.5,-0.4,0.4,2.1,2.2,1.3,0.4,-1.1
Fostex TH-X00 (mahogany) (serial number 1927)|0.4,0.3,-0.1,-0.9,-2.1,-1.7,-0.3,0.3,0.1,-0.4,0.5,2.7,1.8,1.6,1.4,-1.1
Fostex TH-X00 (purpleheart)|1,0,-0.4,-0.9,-2,-1.9,-1,1,1.5,-0.5,-0.8,-0.9,6.4,-0.5,-0.1,-0.8
Fostex TH-X00 (ZMF Universe perforated lambskin earpads)|0,1.1,2.1,3.2,2.7,2.7,3.7,2.9,-0.2,-1.5,1.8,-0.6,1.9,-5.9,0.5,-0.5
Fostex TH-X00 (ZMF Universe perforated suede earpads)|0.5,1.7,2.6,3.8,2.9,2.7,3.8,2.7,-0.8,-1.6,1.3,-1.1,2,-6.3,0.1,-1.2
Fostex TH-X00 Mahogany|0.5,0.1,-0.5,-1.2,-2.2,-1.6,-0.1,0.5,0.8,-0.4,0.4,2.4,2.4,1.4,0.9,-1.1
Fostex TH500 (worn earpads)|5,5,1.4,-2.6,-5.8,-5,-2.7,-2.9,-2.1,-3.2,1,3.5,5,0.5,-0.1,0.5
Fostex TH500RP|4.6,4.6,4,-1.1,-6,-6.2,-4.7,-4.2,-3.7,-4.1,1.5,3.1,4.6,0.9,2.6,1.1
Fostex TH600|-1.6,-2.1,-2.9,-3.4,-3.9,-2.3,0.9,4.5,1.7,1,3.2,6.7,3.3,-0.7,0.1,1.7
Fostex TH610|2.4,1.6,0.5,-1,-2.9,-3.3,-1.7,-0.7,-0.4,-0.1,1.5,5.8,2.2,-2.5,1.4,1.6
Fostex TH610 (Dekoni fenestrated Sheepskin Earpads)|4,3.3,2.4,1.1,-0.2,-0.2,0.8,0.4,0.3,-1.1,0.4,1.3,-1.8,-7.7,2,-1
Fostex TH616|1.9,0.6,-0.5,-1.5,-2.6,-2.2,-0.7,0.3,0.9,3,5.2,4.3,-0.5,-2.4,0.5,-1.1
Fostex TH7|4.8,3.7,1,-0.8,-2.6,-3.2,-2.8,-3.3,-3.1,-2.1,-0.4,3.5,1,-2.3,0.3,0.8
Fostex TH900|-1.9,-2.2,-2.7,-3.4,-3.9,-2.3,1,4.2,3,1.7,3.8,7.3,3.6,-3.2,1.1,1.7
Fostex TH900mk2|-1.1,-1.6,-1.5,-1.8,-2.1,-0.7,1.7,3.2,0.9,0.4,2.8,6,2,-4,1,1
Fostex TH900mk2 (TH610 earpads)|-0.1,-0.8,-1.7,-2.5,-3.8,-3.9,-2,-0.7,1.2,1.1,2.6,5.1,0.9,4.7,2.2,-0.1
Fostex TH909|-0.2,-0.4,-0.6,-1.1,-2.1,-2.7,-2.5,-1.6,1.6,3.3,3.4,5.3,0.3,-1.1,2.9,1.9
Fostex TM2|1.4,1.5,1.3,0.6,-0.4,0,0.7,1.6,2.3,1,-1.6,-4.6,3.4,4.3,-2.4,-3.9
Fostex TR-80|4.4,2.5,-0.7,-3.7,-6.3,-3.3,-0.2,0.7,0.5,0.1,0.3,0.5,1.3,0.6,1.6,1
Fujisan Telos|4.9,3.7,1.1,-1.2,-4.2,-5.4,-4.4,-2.8,-0.4,-1,-1,-0.5,4.3,0.4,1.4,1.1
Future Sonics G10|-3.6,-3.5,-3.7,-4.3,-5,-3.6,-1.5,0.7,2.5,3,-1.2,1.1,0.6,3.9,6.8,6.8
Future Sonics MG5HX|-4.2,-3.9,-3.6,-4,-4.4,-2.5,-0.6,1.6,3.6,1.9,-4.8,3.9,2.9,-1.5,5.4,5.3
Geek Wold GK10|2.1,0.7,-1.2,-3.1,-5.3,-5.1,-3.5,-1.1,2,3.3,1.4,2,3.5,0.5,2,-0.6
Geek Wold GK3|-2.7,-2.7,-2.9,-3.6,-4.2,-2.9,-1.2,0.6,2.2,1.4,0.1,-4.9,-6.4,5,7.3,7.3
Geek Wold GK80 (microphone cable)|-5,-4,-3.1,-3.1,-3,-1.3,0.5,2.1,3.2,1.2,-2.3,-4,-1.8,6.9,6.2,6.9
Geek Wold GK80 (normal cable)|-3.3,-2.3,-1.5,-1.6,-1.5,0.6,2.8,4.9,6.7,4.2,-0.5,-2.1,0.2,0.4,-2.9,-3
Geekfly GF10|-3,-2.6,-2.3,-2.6,-2.7,-1,0.9,2.8,4.2,3.1,1.5,-0.6,2.4,-1.2,2.3,0.5
Geekfly GF8S|-4.9,-3.9,-2.9,-2.9,-2.8,-0.8,1.2,3.1,4.6,3.1,1.3,-0.3,2.1,1.6,5.7,1
Genesis Radon 800 (farbic earpads)|5.4,4.1,-0.7,-2.8,-4.5,-4,-2.7,-2.5,-1,0,0.3,1.6,5.5,3.2,-3,-1.5
Genesis Radon 800 (leather earpads)|2.9,3.2,-3.6,-5.2,-6.3,-2.7,-2.3,-2,0,2.9,2.9,2.2,5.9,2.4,-3.1,-3
GK G1|4,2.6,0.5,-1.8,-4.6,-6.1,-5.8,-4.1,-1.1,0,-0.2,-0.1,-0.9,1,4.7,4.7
Gold Planar GL12 (filter)|4.4,3.4,1.6,-0.4,-3.2,-4.9,-4.9,-4.4,-3.4,-2.4,-0.9,4,3,5.7,5.7,2.5
Gold Planar GL12 (no filter)|4.9,3.9,2,0,-2.9,-4.5,-4.6,-4,-3,-2.1,-0.4,4.9,0.4,4.8,5.4,0.9
Gold Planar GL20|4.7,4.2,2.9,1.1,-1.8,-3.7,-4.4,-5.4,-8.4,-5.5,-4.7,5.5,5.5,5.5,4.2,-0.8
Gold Planar GL2000|4.5,3.8,1.6,0.3,-1.6,-2.8,-1.8,-1.6,-3.6,-4.3,-0.9,1.9,4,-1.9,3.7,0.1
Gold Planar GL2000 (double-sided, leather earpads)|4.2,3.5,1.1,0.1,-1.9,-3.5,-2.5,-2.6,-4,-4,0.3,3.5,4.6,-1.4,3.2,0.4
Gold Planar GL2000 (double-sided, perforated hybrid earpads)|5.2,4.5,1.3,0.2,-1.6,-2.4,-1.1,-0.4,-3.2,-3.9,-1.1,2,2.9,-1.4,2.4,-1.2
Gold Planar GL2000 (single-sided, leather earpads)|4.4,3.2,1.2,0.2,-1.7,-3.4,-3.5,-2.8,-4.2,-6,-1.7,1.8,3,-1.4,5.3,5.3
Gold Planar GL2000 (single-sided, perforated hybrid earpads)|4.9,3.7,1.5,0.5,-1.6,-3.5,-3.5,-1.2,-2.4,-6,-0.9,1.9,0.6,-1.1,5.3,5.2
Gold Planar GL850|3.3,1.8,0.4,-0.7,-2.6,-3.7,-2.9,-2.4,-4.7,-4,-1.4,2.8,5.6,3.2,3.9,1.7
Google Pixel Buds (2017)|4.8,4.8,2.7,-2,-5.6,-3.7,-2.6,-1.6,0.7,3.8,2.1,-1.3,1.2,-1.6,-2.3,-2.5
Google Pixel Buds (2020)|2.9,2.6,2,1,-0.1,-0.4,-1.9,-1.3,-2.1,-3.1,-1.2,-1.8,-1.5,0,-1.9,-1
Google Pixel Buds A-Series|2.6,2.9,2.4,2.2,3.3,1.5,-1.2,-1.6,0.1,-0.8,-0.2,-0.7,-2.1,-1.8,-3.8,-5.7
Google Pixel Buds Pro|-2.8,-2.7,0.3,0.6,-0.3,-0.6,-0.5,0.1,0.6,-0.2,-1.6,-1,-2.1,0.9,4.1,5.4
Google Pixel USB-C Earbuds|4.2,4.2,2.1,-2.2,-5.7,-5.6,-5.5,-4.9,-2.1,0.3,-1.3,-1.1,4.1,-1.6,3.7,4.2
Grado GR10|4.2,4,2.4,0.5,-2.1,-3.5,-3.6,-3.3,-2.4,-2.4,-1.3,-0.8,2.1,1.3,-2.4,1.9
Grado GR8|1.8,1.5,-0.1,-1.8,-4.4,-5.3,-4.8,-3.7,-1.8,-0.9,0.1,2.4,2.4,2.2,4.6,5.2
Grado GS1000|5.6,5,-0.7,-3.1,-2.4,0,1.3,2.1,2.8,2.5,3.4,2.6,-2.6,-5.7,-6.8,-4
Grado GS3000e|4.5,4.5,4.5,0.4,-3.6,-0.6,-0.1,1.2,2.1,1.2,0.4,-3.5,-2.4,-2.9,-5.7,-2.1
Grado GT220|-1.5,-1.9,-2.3,-2.5,-2.5,-1,0.6,2.2,3.3,2.2,0.8,-2.9,2,-3.8,1.4,2
Grado GW100|1.2,0.4,-0.6,-1.1,-3.9,-3.4,-1.5,0.2,2.1,3,1.3,-0.8,6,0.4,0.3,-0.5
Grado Hemp|4.8,4.2,1,-0.4,-2,-2.9,-1.9,-1.2,-1,-1.4,-1.7,-3.6,1.9,2.3,0,-1.6
Grado HF1|4.8,4.3,0.4,-0.9,-1,-0.3,0.9,0.8,1.1,0.4,-1.1,-4.1,-2.4,-1.5,-3.2,-1.3
Grado HF1 Prototype|5,5,2.1,0.3,-0.1,0.4,1.7,1.6,1.8,0.9,-0.6,-1.5,-2.5,-6.2,-6.4,-3.3
Grado HF2|4.8,4.8,0.8,-1,-1.1,-0.4,0.5,0.8,1.1,0.3,-1.8,-4.3,-3.2,-2.3,-3.5,-0.3
Grado iGE|-2.3,-2.2,-2.2,-2.9,-3.8,-2.9,-2.1,-1.1,-0.7,-1.8,-2.7,4.9,6.2,6.2,3.2,4.7
Grado iGi|1.6,0.8,-0.4,-2,-3.6,-3.4,-2.6,-1.8,-1.8,-3.5,-2.1,5.8,5.8,5.8,0.2,-0.4
Grado iGrado White|4.6,4.6,4.6,2.7,-0.9,-2.4,-2.5,-2.7,-2.7,-2.8,-2.9,-0.3,-1.4,3.7,-0.2,-1.4
Grado PS1000|5.3,5.2,-0.2,-2,-1.4,0.4,2,2,2.3,1.5,1.8,1.5,-1.8,-5.3,-7.8,-3.8
Grado PS2000e|4.7,4.7,4.7,0.9,-3.2,-1.1,0.2,1.5,2.2,1.4,-1,-3.1,-2.5,-3.2,-4.7,-4.1
Grado PS500|4.9,1.7,-3.1,-4.8,-4.5,-3,-1.2,-0.6,-0.1,-1.3,-2.6,-1.3,-1.1,4.1,1.3,1.6
Grado PS500e|4.8,2.6,-2.4,-4.1,-4.1,-2.9,-1.1,-0.4,-0.1,-0.5,-1.3,-2.8,0.1,0,1.3,3.2
Grado RS1|5.1,5.1,3.2,0.6,-0.3,0.2,1.7,1.7,2.3,2,-0.4,-2.1,-1.7,-5.5,-5,-2.1
Grado RS1e|4.6,4.6,4.6,0.1,-4.4,-1.9,-0.6,0.7,1.8,1.2,0.3,-7.4,-4,-3,0.1,-1
Grado RS1e (bowl earpads)|4.5,4.5,2.1,-2.1,-3.9,-2.5,-1.9,-1.4,-1.1,-2.3,-3.2,-4.4,-0.3,2,3.3,4.3
Grado RS1e (flat earpads)|4.9,4.9,-0.5,-3.9,-4.5,-3.9,-3,-2.8,-2.6,-3.9,-2.4,-0.4,3.1,4.4,4.9,4.4
Grado RS1e (S earpads)|4.8,1.5,-1.9,-3.7,-5.4,-5.2,-4,-3.2,-2,-2,-2.7,-0.5,3,4.2,5.3,5.3
Grado RS1e (yellow earpads)|5.3,2.8,-1.4,-3.3,-4.9,-4.5,-3.7,-2.9,-2,-2.4,-3.2,-1.1,2.5,4.7,5.3,5.3
Grado RS2|4.9,4.9,4.4,1.8,0.5,0.4,1.6,1.7,2.1,1.5,-0.3,-2.5,-2.4,-6,-6.9,-3.3
Grado SR125e|4.7,4.6,1.2,-0.3,-1.5,-1.5,-0.9,-0.7,-0.6,-1.1,-1.9,-4.3,0.3,1.3,-0.4,-0.2
Grado SR125i|4.7,3.6,0.6,-0.6,-1.4,-0.9,-0.2,-0.1,0.6,0.1,-1.2,-4.3,-3.9,-0.2,-1.6,0.1
Grado SR225|5.2,5.2,5.1,3,1.5,1,2.1,2.4,2.3,1.3,0.2,-1.5,-6.8,-6.9,-5.6,-6.4
Grado SR225e|5.1,5.1,3.2,2.2,1,0.9,1.4,1.5,1.4,0.8,-0.3,-5.7,0.3,-2.9,-7.4,-6.9
Grado SR225i|4.8,4.8,1.8,0.3,-0.5,-0.2,0.8,0.8,1.3,0.5,-0.9,-3.4,-5,-1.4,-5.1,-2.1
Grado SR225i (comfy earpads)|4.7,3.7,0.7,-0.7,-1.6,-1.5,-0.7,-0.3,0.1,-0.5,-1.7,-4.8,-3.9,0,-0.4,0.9
Grado SR225i (G earpads)|5,5,1.6,-0.1,-0.9,-0.3,1.1,1.2,1.8,1.4,1.8,0.7,-5.4,-5.7,-7.9,-3.4
Grado SR225i (goo bowl earpads)|4.9,4.9,1.8,0.3,-0.8,-0.4,0.6,0.7,1.3,0.4,-0.7,-2.6,-4.3,-1.5,-4.9,-2.7
Grado SR225i (reverse bowl earpads)|4.8,4.7,0.9,-0.7,-1.7,-1.6,-0.4,-0.3,0.4,0,-1,-2.3,-3,0.6,-3.3,-1.6
Grado SR225i (small flat earpads)|4.7,4.3,-0.1,-1.4,-2.9,-3.3,-2.4,-2.1,-1.3,-1.4,-0.9,-1.1,-0.7,3.4,-0.3,0.6
Grado SR225i (TTVJ flat earpads)|4.7,3.2,-0.7,-2,-3.2,-3.2,-2.5,-2.2,-1.3,-1.4,-0.6,-0.2,-0.1,3.7,-0.6,0.4
Grado SR325|5.3,5.3,3.6,2.3,1.6,1.2,2.5,1.6,1.9,0.9,-0.9,-3,-2.4,-6.8,-6.4,-5.3
Grado SR325e|4.6,4.6,1.3,-0.7,-2.2,-2.2,-0.8,0.1,0.1,-0.5,-1.4,-4.3,-2.2,-1.7,0.3,1.6
Grado SR325i|4.9,4.9,1.9,0.1,-0.8,-0.4,0.6,0.8,1.3,0.7,-0.7,-2.9,-2.4,-2.4,-4.9,-2.9
Grado SR60|5.2,4.6,2.6,1.4,0.8,-0.5,0.6,1.4,1.4,0.5,-0.3,-3.1,-0.9,-5.9,-3.3,-2.5
Grado SR60e|4.8,4.4,1.7,0.7,-0.2,-0.4,-0.2,0.3,0.3,-0.4,-1.9,-4.4,1,-3,-0.9,-0.4
Grado SR60i|4.5,3,0.1,-1,-2,-1.8,-0.8,-0.6,0.1,-0.5,-1.6,-4.1,-3.7,0.2,0.1,2.6
Grado SR80|4.9,4.9,4.9,3.4,1.1,0.8,1.7,2.2,1.9,0.9,-0.2,-2.7,-7.6,-5.8,-7.2,-4.3
Grado SR80e|4.9,4.3,1.2,0.1,-0.9,-0.9,-0.4,0.2,0.2,-0.4,-0.7,-4,-0.4,-3.5,-2,0.1
Grado The Hemp Headphone|4.5,4.5,1.2,-0.4,-2.1,-3.1,-2.6,-1.9,-1.7,-2.3,-2.4,-3.9,1.3,1.7,1,0.5
Grell Audio TWS 1|-2.2,-2.8,-3.2,-3.3,-3.1,-4.1,-3.6,0.8,6.4,5.3,2.6,-0.5,4.3,-0.6,0.9,1.2
GS Audio GD11|1.9,1.4,-0.1,-1.8,-4.4,-5.3,-5.2,-4.1,-3,-3,-2.9,4.7,5,5.2,5.5,4.8
GS Audio GD3A|-2.4,-1.4,-1.2,-1.9,-3.2,-3,-1.8,-0.3,1.8,1.6,2.3,2.1,3,-1.7,0.7,2.2
GS Audio GD3C|-3.6,-2.5,-2,-2.6,-3.8,-3.4,-2.3,-0.6,1.2,1.2,1.7,2,2.2,-1.3,4.1,6
GS Audio GT12X|-1.7,-2,-2.8,-4,-5.3,-5.3,-3.7,-2.1,-0.3,-0.2,1,5.6,7.2,7.2,7.2,1.1
GS Audio SE11|0.8,1.2,0.7,-0.8,-2.8,-3,-2.1,-0.7,0.5,0.5,0.2,2.8,5,2.7,-0.1,-2.8
GS Audio SE12|-0.6,-0.7,-1.3,-2.4,-3.4,-2.4,-1.1,0.2,0.9,-0.2,0.3,1.4,2.9,0.4,-0.4,3.1
GS Audio ST1|1.1,0.8,-0.1,-1.5,-3.3,-3.9,-3.4,-1.9,-0.2,0,0.7,-0.1,2.5,-1.7,2.8,3.8
GuideRay GR-I|-1.1,-1.5,-2.1,-2.4,-2.4,-2.3,0.6,1.7,2.5,0.2,-0.6,1.4,2,-3.4,-1.5,4.3
HAKUGEI Sea-elf|-5,-4.1,-3.3,-3.3,-3.3,-1.5,0.3,2.2,3.6,2.8,1.3,-2.8,-2,4,5.4,4.9
Harman Kardon FLY ANC|-3.7,-2.9,-3.3,-4.3,-2.9,-2,1.1,2.3,1.8,1.3,0.9,3.9,-1.8,3.3,3.4,5.4
Harman Kardon NI|-2.4,-1.8,-1.6,-1.9,-2.2,-0.7,0.8,1.9,3,2.6,1.6,-4.1,0.3,-6.5,5.8,5.4
HarmonicDyne Devil|2,1.9,1.2,0.1,-1.3,-1.4,-0.5,0.6,1.2,0,-1.7,-2.8,2.4,-4.8,-4,1.5
HarmonicDyne G200 (lambskin earpads)|2,1,-0.5,-1.8,-3.8,-4.3,-3.8,-3.1,-3.2,1.7,6.6,1.5,5.5,2.3,4.4,1.7
HarmonicDyne G200 (velour earpads)|2.3,1.6,0.4,-0.9,-3,-4,-4,-3.4,-3.9,-3.1,4,2,5.9,2.7,5.2,1.1
HarmonicDyne Helios|5.4,3.6,0.4,-2,-3.4,-3.2,-2.1,-0.6,1.8,1.3,3.1,0.2,-1.1,-1.2,-0.6,-0.9
HarmonicDyne P.D.1|-3,-2.4,-2,-2.3,-2.7,-1.3,0,1.3,2.2,1.5,1.9,-3.7,-2.6,6.3,6.4,5.3
HarmonicDyne Poseidon|5.1,5.1,0.2,-2.9,-5.8,-7,-5,-2.8,-1.4,1.7,4.5,1.8,4.2,1.2,0.3,1.1
HarmonicDyne Zeus|4.1,1.6,-0.9,-3.1,-5.2,-5.8,-4,-1.7,-1.1,1.8,3.1,1.1,2.6,1.9,2.4,2.3
HarmonicDyne Zeus Elite|0.4,-0.2,-0.7,-2.4,-4.1,-3.2,-1.5,-0.3,0.7,0.3,0.7,-1.7,4.1,5.9,1.8,0.9
Havi B3 Pro1|5.2,4.4,2.4,0.4,-2.2,-3.9,-4.4,-5.1,-4.8,-3.5,-0.6,1.8,-0.9,5.2,4.7,-1.9
Haylou GT1|-5.5,-5.3,-5.2,-5.5,-5.7,-3.6,-1.3,1.2,4,4.8,6,8.3,5.2,8.3,1.6,-2.4
Haylou GT2|-3.5,-2.6,-1.7,-1.8,-2.4,-2,-1.4,-0.2,1.2,1.2,1.6,2.4,0.5,6.1,1.3,-2
Haylou Moripods|-2.1,-5.1,-6.9,-7,-5.8,-3.6,-2.6,-2,1.1,3.5,5.3,1.8,4.1,2,2.6,6
Haylou PurFree Buds|6.6,3,-3.8,-5.9,-7.9,-8.6,-8.8,-9.5,-10.2,-5.2,3.9,6.6,6.6,6.6,6.6,6.6
Haylou S35|5.6,5.6,5.6,5.6,5.6,5.1,5.6,-1.9,-5.4,-5.3,-2.5,0.5,-2,-4.1,-4.1,-6.5
Haylou S35 ANC|-0.9,-4.3,-1.6,-1.6,-0.9,1.9,3.1,2.1,0.9,0.5,-1.3,-3,-0.7,3.1,0.7,-3
Haylou W1 ANC|-3.5,-2.5,-1.1,-0.6,0.5,1.8,2.2,1.8,1.3,1.6,0.7,-0.6,2.4,3.2,2.8,-4.5
Heartfield Acoustic Deer|-2,-2.1,-2.6,-3.4,-4.4,-3.6,-2.5,-0.9,1.2,1.5,-0.1,0.6,4.4,-0.7,4.3,6
Heavys H1H (AUX power off)|-1.6,-0.4,-4.3,-7,-7,-3,1.6,4.2,3,4.1,3.7,-2.3,-3.2,5.2,6.8,4.9
Heavys H1H (Hell Blocker off)|-4.3,-4.2,-5.4,-6.2,-5,-0.4,1.3,1.3,2.7,5.2,5.7,0.2,1.2,7.6,5,0.3
Heavys H1H (Hell Blocker on)|-0.1,-1.8,-4.5,-3.4,-2.9,-0.4,-0.9,0.3,1.5,3.4,5,-0.5,0.7,7.1,4.4,-0.5
HEDD HEDDphone|4,2.2,0.7,-0.3,-2.4,-3.9,-2.1,-0.2,-4.2,-2.1,0.2,1.5,-0.1,0.7,5.5,0.4
Heir Audio Tzar 350|3.4,2.8,1.3,-0.4,-2.9,-3.9,-3.6,-2.5,-0.8,0,0.7,4,5.2,1.7,-2.7,-2.5
Hessian TWS7|-5.4,-4.4,-3,-2.6,-2,0.3,2.2,4.2,6.6,3.6,2.6,-0.7,3.7,4.4,-2,-0.1
HiBy Crystal 6|2.8,1.9,0.7,-1,-3.3,-3.8,-2.8,-1,1.6,3,3.2,-0.9,-0.6,-0.2,-0.6,2.1
HiBy Crystal 6 II|2.9,2.1,0.5,-1.3,-3.8,-3.8,-2.7,-0.5,2.3,3.7,5,1.6,1.3,-1.4,-1.3,-4.9
HiBy Seeds II|-3.1,-2.9,-3,-3.7,-4.1,-2.3,-0.1,2.3,4.5,4.6,3.9,0.4,-0.1,-2.8,2.2,1.6
HiBy WH3|-2.8,-2.8,-3.4,-4.5,-5.6,-4.5,-2.6,-0.3,2.3,2.4,1.5,2.2,0.6,5.8,7,4
HiBy XOE|-0.5,-1.4,-2.5,-3.8,-4.9,-3.3,-1.2,1.2,4.2,5.2,6.2,6.9,2.3,-0.4,-1.3,-3.3
Hidition NT6|3,2.6,1.4,-0.4,-2.8,-3.6,-3.1,-2.1,-1,-1.3,-1.8,-0.4,2.7,1.2,-0.6,-0.8
Hidition NT6 Pro|1.1,1,0.4,-1,-2.9,-3.3,-2.4,-0.4,2.1,0.2,-1.6,0,3.2,1,-2.7,-0.6
Hidition NT6 Uni|3.2,2.6,1.7,0.1,-1.9,-2.8,-2.3,-1.6,-1,-1.9,-2.6,-1.3,1.4,-0.6,-2.9,0.2
Hidition NT8|2.6,2.9,2.6,1.4,-0.7,-1.6,-1.7,-1.6,-1.9,-4.2,-4.7,0.3,4.3,5,0.2,-0.7
Hidition Viento|4.7,4.4,3.6,2,-0.4,-1.7,-1.5,-1,0,-0.2,-1.5,-1.5,-0.3,1.3,-0.4,-1.9
Hidition Viento (bass)|1.3,1.9,2.2,1.3,-0.4,-1.3,-1,-0.5,0.6,0.3,-0.8,-0.4,0.6,2.3,1.4,-0.5
Hidition Viento (bass, mids)|1.3,1.6,1.5,0.3,-1.7,-2.6,-2.2,-1.2,0.4,1.4,0,0.7,2.3,3.5,1.9,0.5
Hidition Viento (mids)|3.4,3,1.9,0.3,-2.1,-3.3,-2.9,-2,-0.3,0.4,-0.5,0.1,1.6,3.2,1.6,0.1
Hidition Viento-A (CIEM)|3.9,3.7,3.1,1.5,-0.7,-2,-1.8,-1.3,-0.1,-0.9,-2,-1.7,-1.1,1.2,1.9,-0.9
Hidition Viento-B (CIEM)|1.2,1.8,2,0.9,-0.9,-1.8,-1.7,-1,0.3,0,-0.5,-0.1,0.8,2.7,2.4,-0.2
Hidition Viento-D (CIEM)|0.2,0.5,0.1,-1.1,-3.1,-3.9,-3.5,-2.3,-0.1,0.7,0.5,-0.2,1.9,4.7,3,2.5
Hidition Violet|-1.8,-1.5,-1,-1.1,-0.8,-0.5,-0.6,-0.5,0.1,0.2,0.7,3.4,4.6,4.3,0.9,0.2
Hidition Waltz|0.8,0,-0.9,-2.1,-3.3,-2.5,-1.2,0,0.8,-0.4,-2.5,-0.2,2.8,0.6,2.1,2.7
Hidizs MD4 (off-off)|3.8,2.7,0.9,-1.1,-3.7,-4.3,-3,-0.1,2.8,2.7,4.1,-4.1,1.3,-1,0.8,-2.5
Hidizs MD4 (off-on)|4.1,2.9,1,-1.1,-3.8,-4.5,-3.6,-1.5,0.8,1,3.1,-4.4,1.8,-2.4,2.7,-0.2
Hidizs MD4 (on-off)|2.9,2,0.2,-1.8,-4.4,-5.2,-4.3,-2.1,1.2,2.4,5.6,-2.6,2.8,0.5,2.2,-1.2
Hidizs MD4 (on-on)|3.5,2.3,0.3,-1.7,-4.4,-5.4,-4.7,-2.9,-0.2,0.8,4.2,-3.5,2.8,-1.3,3.5,0.8
Hidizs Mermaid MM2 (balanced filter)|-0.3,-0.3,-0.7,-1.5,-2.3,-1.3,-0.1,1.1,1.9,1,0.1,2.1,4.4,1.9,0.3,-1.9
Hidizs Mermaid MM2 (bass filter)|-0.9,-1.9,-3.4,-4.8,-6.3,-5.2,-3.3,-0.8,1.8,2.5,2.9,0.8,-0.6,4.4,3.4,4.5
Hidizs Mermaid MM2 (reference filter)|-0.9,-1.3,-2,-2.7,-3.5,-2.1,-0.4,1.3,2.6,1.5,0,-3.8,-3.2,4.4,1.8,2.8
Hidizs Mermaid MM2 (treble filter)|-0.7,-0.8,-1.2,-1.9,-2.6,-1.3,0.2,1.6,2.5,1,-0.8,-5.1,-4.1,4.3,1.2,2.3
Hidizs Mermaid MS1|-1.1,-1.8,-2.7,-3.9,-5,-3.5,-1.5,1,3.2,2.6,2.3,2.7,2.6,1.6,-1,-0.9
Hidizs Mermaid MS4|-0.8,-0.8,-1.2,-2,-2.8,-1.5,0.2,2,3.5,1.7,-1.8,-2.6,-1.4,2.7,0.7,1.1
Hidizs MP145|-1.3,-1.1,-1,-1.5,-2.1,-0.9,0.3,1.4,2.1,1.1,0.4,2.7,4.3,3.8,1.8,-3.1
Hidizs MP145 (red nozzle)|-2.3,-2,-1.8,-2.3,-2.6,-1.2,0.4,1.9,2.8,2.2,2.2,4.5,5.2,3.3,2.4,-1.8
Hidizs MP145 (rosegold nozzle)|-1.5,-1.2,-1.2,-1.6,-2.1,-0.8,0.7,2,2.7,1.7,0.8,2.4,4.3,2.9,2.1,-2.2
Hidizs MP145 (silver nozzle)|-1.3,-1.1,-0.9,-1.4,-1.7,-0.4,1.1,2.5,3.3,2.3,1.5,1.6,3.7,1.5,1.6,-2.4
Hidizs MS2|3,2.3,0.9,-0.8,-2.8,-2.8,-1.7,-0.1,1.7,0.4,-1.3,0.3,1.2,2.9,-0.8,-5.2
Hidizs MS3 (red nozzle)|0.2,-0.1,-0.8,-1.8,-3,-1.8,-0.2,1.5,3.1,2,1.4,4.6,1.8,1.3,-4.3,-4.4
Hidizs MS3 (rosegold nozzle)|2.2,1.8,0.7,-0.6,-2.1,-1.4,-0.2,1.5,2.8,1.2,-1,1.1,0.8,0.4,-3.6,-4.8
Hidizs MS3 (silver nozzle)|2.6,2.1,1,-0.3,-1.9,-1.3,-0.1,1.5,2.8,1.1,-1.5,0.1,0.5,0.1,-3.3,-4.7
Hidizs MS5|-0.6,-0.6,-1.2,-2.3,-3.4,-2.3,-0.8,0.7,0.5,-0.1,2.1,5.4,2.3,0.4,-2,0
Hidizs MS5 (red nozzle)|-2.3,-2.3,-2.8,-3.8,-4.8,-3.5,-1.8,0,1.1,1.1,2.9,4.5,3.7,1.1,0.9,3.4
Hidizs MS5 (rosegold nozzle)|-1.9,-1.9,-2.4,-3.4,-4.4,-3.3,-1.6,-0.1,0.8,1,2.9,4.5,4,0.4,0.4,3.1
Hidizs MS5 (silver nozzle)|-1.6,-1.5,-2.2,-3.2,-4.3,-3.2,-1.6,-0.1,0.9,1.1,3.1,4.6,4.1,-1,-0.2,3.1
HiFi Boy OS V3|-1,-1.4,-2.3,-3.7,-5.6,-5.6,-4.3,-2,0.7,0.9,0.9,3.3,3.9,2.4,1.5,5.4
HIFIMAN Ananda|3.9,3.4,1.5,0.3,-1.5,-2.6,-1.5,-2.1,-1.3,0,3.4,-0.1,-2.4,-0.8,-0.1,-0.1
HIFIMAN Ananda (V1)|5.4,3.4,1.5,0.1,-1.3,-2.4,-1.2,-1.4,-1,0.5,5.4,1.3,-2.6,-0.9,0,-2.7
HIFIMAN Ananda BT|5,2.8,0.9,0.6,-0.9,-0.8,-0.4,0.1,-0.9,0.1,5,-0.7,-3.7,-0.3,-5,-3.2
HIFIMAN Ananda Nano|3.8,3.3,1.5,0.6,1.1,-1,-0.8,-0.4,1.3,0.3,2.9,0.3,-3,-2.1,-0.5,-3.2
HIFIMAN Arya|3.9,3.4,1.9,0.8,-1.5,-2.3,-1.6,-2.5,-0.2,0.6,3.2,-0.9,-1.1,-3.3,-1.2,-0.7
HIFIMAN Arya Organic|4,3.2,1.5,0.5,-1.6,0.2,-0.8,-1,-0.9,0.2,3.6,-0.4,-0.7,-2.8,-0.6,-2.9
HIFIMAN Arya Stealth Magnet Version|3.8,2.9,1.4,0.3,-1.3,-2.2,-1.5,-1.1,-0.4,-0.1,3.6,0.5,-1.4,-4.1,-0.2,-0.8
HIFIMAN Bolt|-5.6,-4.1,-2.9,-2.7,-2.5,-0.9,1.2,3.2,5,5.2,4.7,0.5,0.4,-6.8,3.2,2.8
HIFIMAN Deva (wired)|4.5,2.6,0.3,-0.5,-2,-3.2,-2.6,-1.6,-2.4,-2.1,1.2,0.4,-0.4,-1.5,1.6,1.8
HIFIMAN Deva (wireless)|4.8,4.8,3.6,1.4,-1.4,-3.4,-3.2,-1.6,-3,-3.1,0.6,-0.4,-1.1,-1.6,0.6,-0.3
HIFIMAN Deva Pro|4.8,2.8,0.2,-0.7,-2,-2.9,-2.6,-1.8,-2.6,-4.2,-0.6,0.3,-0.7,-0.1,1.7,1.1
HIFIMAN Deva Pro-wired|4.8,2.9,0.6,-0.1,-1.7,-3,-2.6,-2.1,-3,-2.9,-1.2,-1.5,0.9,1.2,0.7,0.7
HIFIMAN Edition S (closed back)|4.6,3.3,2.6,1.2,0.2,0,1.7,2.3,3.4,-2.8,-2.8,-1.5,-7.8,-3.8,2.3,-2
HIFIMAN Edition S (open back)|4.1,2.3,1.1,-0.6,-2.1,-2.7,-0.9,0,1.1,0.8,1.2,-0.5,-3.7,-3,3.7,-0.6
HIFIMAN Edition X|2.4,1.7,-0.3,-0.6,-2.1,-3.2,-2.8,-1.9,-3,-3.4,1.2,-0.7,-1.4,0.8,1.2,4.5
HIFIMAN Edition X V2|3.1,2.8,0.6,-0.2,-2.3,-4.7,-1.8,-3.4,-1.4,-0.5,2,0.3,-1,1.1,0.9,2.2
HIFIMAN Edition XS|3.4,2.5,1.4,0.5,-1.1,-1.3,-1.5,-0.2,0.3,0.5,3.2,-0.2,-1,-0.3,-0.5,-2.7
HIFIMAN Edition XX|2.5,1.1,0.1,-1,-3,-4.9,-3.3,-4.2,-2.6,-0.5,4.4,0.1,-0.6,-2,2,3.8
HIFIMAN HE-300|0.9,0,-0.5,-1.3,-1.5,-0.6,2.2,4.6,6.5,-0.5,-4.5,-1.7,-7.9,-3.7,5,1.9
HIFIMAN HE-300 Rev 2|4.8,1.4,-1.3,-2.6,-2.6,-2.4,0.4,1.7,3.2,0.5,-0.7,-1.4,-5.7,-4.7,0.4,1.1
HIFIMAN HE-4|5.2,4.5,3.3,2.2,0.4,-1.7,0.5,-0.2,3.1,2.1,4.5,3.6,-3.7,-2.8,-5,-8
HIFIMAN HE-400|4.1,3.9,2.8,1.3,-0.7,-1.5,-1.9,0.8,-3.5,-4.1,3.4,6.1,2.9,0.9,-3.6,-7.3
HIFIMAN HE-5|4.2,3.6,2.4,1.4,-0.5,-3.1,-2.1,-3.2,2.2,2.7,5.1,1.5,-2.5,-1.4,-1.6,-2.6
HIFIMAN HE-500|3.7,3.3,2.1,1.3,-0.3,-1.5,-1.1,-1.4,-0.5,-1,2.2,2.5,-2.2,0.2,-2.1,-2.9
HIFIMAN HE-500 (FocusPad-A)|3.1,2.3,1.2,0.2,-1.5,-2.9,-2.8,-3.2,-2.5,-3,1.9,0.9,0.5,3,3,0.6
HIFIMAN HE-5LE|3.6,3.8,3.1,2.3,-1,-3,-2.5,-2.6,1.4,0.7,5.8,5.4,0.2,-1.8,-4.6,-5.4
HIFIMAN HE-6 (6-screw, HIFIMAN FocusPad earpads)|3.7,3.1,2.2,1.2,-0.5,-1.8,-1.5,-1.8,-1.5,0.6,3.1,-1.4,-2.9,0,-0.9,-1
HIFIMAN HE-6 (6-screw, pleather earpads)|4.2,3.5,2.2,1.1,-0.7,-1.5,-1.1,-1.8,-2.5,-2.4,1,-0.6,-0.6,0.1,-1.1,-1.6
HIFIMAN HE-R9|-1.3,-3.4,-3.8,-4.5,-5.7,-6.2,0.1,2.6,-0.1,2,6.6,6.5,0.9,3.9,1.1,1.8
HIFIMAN HE-X4|5.4,3.8,1.5,0.4,-1.4,-2.6,-2.4,-1.5,-2.1,-1.7,2.3,1.4,0.3,-0.3,-1.1,-1.7
HIFIMAN HE1000|4.1,3.3,2,1,-0.7,-1.8,-2.5,-0.8,-0.9,-1.1,3.6,4.5,-1.2,-4.9,-3.7,-2.4
HIFIMAN HE1000 V1|5.9,-1.7,-2.2,0.1,0.1,-0.3,0.3,0.9,0.3,-0.8,2.4,0.3,-0.9,-3.7,0.1,1.2
HIFIMAN HE1000 V2|4.1,3.5,2.3,1.1,-0.6,-1.9,-0.5,-0.7,0.6,0.4,1.7,-2.3,-2.4,-3.5,-2,-0.7
HIFIMAN HE1000se|4.6,3.1,1.9,1.1,-0.6,-1.1,-1,0,0.5,-0.1,3.2,0.3,-3,-3.7,-1.5,-3
HIFIMAN HE1000se (Dekoni fenestrated sheepskin earpads)|2.1,2.2,1.7,1.1,-0.4,-1.2,-1,-0.7,-1.4,-2.2,1.6,0.2,-0.5,1.1,-1,-1.2
HIFIMAN HE350|5.6,4.4,2.3,1.1,-0.9,-1.2,-0.1,-0.3,-1.2,-0.7,1.6,3.6,-0.9,-7.5,-1.9,-3.2
HIFIMAN HE35X|0.8,-0.9,-1.6,-3.5,-6.1,-6.5,-4.1,-0.8,2.8,4.7,5.7,-0.6,-3.7,-6.8,5.7,5.7
HIFIMAN HE400i|4.9,4.9,2.9,1.5,-0.1,-1.2,-1.1,0.6,-1.1,-0.5,0.2,-0.8,-1.3,0.2,-4.4,-3.5
HIFIMAN HE400i (2016)|5.2,5.2,1.7,0.4,-1.3,-2.4,-2.1,-1.8,-1.1,-1.8,1.6,-0.4,-2,1.7,-0.7,-1.6
HIFIMAN HE400i (2020)|5.2,3.4,1.3,0.2,-1.6,-2.7,-2.7,-0.9,-1.5,-1.2,1.9,1.5,-0.2,2.1,-2,-1.7
HIFIMAN HE400S|4.6,4.5,1.8,0.4,-1.4,-2.4,-2.4,-1.6,-2,-2.9,-0.8,0.8,-2.8,0.8,0.7,2.6
HIFIMAN HE400se|4.5,3.3,1.6,0.7,-1,-2.5,-2,-1.8,-2.8,-1.5,2.5,1.6,-0.1,-1.5,-1.2,-1.5
HIFIMAN HE400se (non-stealth magnet)|5.1,4.8,2.7,1.4,-0.5,-1.9,-1.8,-1.3,-2.3,-1.3,0.9,-0.5,-0.7,0.8,-2.8,-3.6
HIFIMAN HE400se (stealth magnet)|4.4,3.4,2.3,1.3,-0.8,-2.8,-2.6,-2.2,-2,-1.2,1.1,-0.6,-2.3,1.1,-1.7,-1.8
HIFIMAN HE4XX|5.1,3.9,1.9,0.9,-1,-2.2,-1.4,-1,-1.3,-2.4,1.8,-0.5,-0.8,2.5,0.2,-1.6
HIFIMAN HE560|3.8,3.1,1.8,0.7,-1.4,-2.5,-1.7,-2.2,-1.2,-0.9,3,2.1,-3.8,-2.2,-0.1,-0.2
HIFIMAN HE560 (V1)|3.5,2.9,1.7,0.6,-1.4,-3.1,-2,-2.7,-1,-2.1,2.6,2.9,-2.3,-1,1.1,-0.2
HIFIMAN HE560 2014|3.9,3.6,2.4,1.4,-0.5,-2.3,-1.9,-1,-0.6,-0.8,1.8,2,-5.2,-2.9,-1.8,-0.3
HIFIMAN HE5se|5.5,5,2.1,1,-0.8,-2.1,-1.9,-2.1,-2.1,-1.2,5.2,1.2,-5.1,-1.1,-1.1,-2.1
HIFIMAN HE5XX|5.4,3.6,1.2,-0.4,-1.8,-2.9,-2.3,-1.6,-1.7,-1.7,0.8,1.4,1.5,-1.6,1.4,0.6
HIFIMAN HE6|3.9,3.1,2.2,1.2,-0.5,-1.4,-1,-1.3,-1.8,-2.6,0.6,2.1,-1.5,-4.3,1.2,-1
HIFIMAN HE6 (6-screw, velour earpads)|4.1,3.2,2.2,1.1,-0.6,-1.1,-0.8,-1.2,-1.3,-2.3,-0.1,1.9,-1.2,-4.3,0.2,-1.1
HIFIMAN HE6se|4,3.4,2.2,1,-1,-2.7,-2.3,-2.3,-2.3,-0.8,4.4,0.9,-3.9,-0.8,0.4,0.4
HIFIMAN HE6se V2|4,3.4,2.3,1.3,-0.6,-2,-1.7,-1.7,-1.7,-0.9,2.9,0.2,-3.5,-1.7,1.3,0.7
HIFIMAN Jade II|4.9,3.4,1.6,-0.3,-1.6,-2.3,-1.8,-2.3,-1.8,-1.8,0.8,-1.1,-4.7,0.4,2,0.9
HIFIMAN RE2000|3,2.3,1,-0.6,-2.7,-2.6,-1.3,0.6,3.1,3.1,0,-0.6,-0.8,-3.7,1.3,-1.4
HIFIMAN RE2000 Pro|0.7,0,-1.1,-2.5,-4.2,-3.6,-1.8,0.7,3.9,4.9,1.5,1.8,0.4,-2.2,1.9,0.3
HIFIMAN RE262|3.8,3.3,1.8,0,-2.8,-4.5,-4.9,-5.1,-3.9,-5.2,-8.5,2.1,4.3,4.7,4.7,2.9
HIFIMAN RE272|4.6,4.2,3,1,-1.6,-3.3,-3.6,-3.5,-1.6,-3.3,-7.2,-0.3,1.8,3.7,2.9,-0.6
HIFIMAN RE300|0.1,-0.3,-1.7,-3.3,-5.3,-5.7,-4.6,-3.3,-1.4,-2.6,-2.9,6.7,6.7,6.7,3.4,5.2
HIFIMAN RE400|4.1,3.5,2.1,0.2,-2.3,-3.1,-2.6,-1.6,-0.4,-1.1,-3,-2.8,4,2.4,0,1.6
HIFIMAN RE600|2.8,2.4,1,-0.7,-3,-3.8,-3.4,-3.1,-2.5,-4.1,-6.4,0.2,5.2,4,5.2,4.9
HIFIMAN RE600S|3.7,3.3,1.8,-0.2,-3,-4.7,-5.1,-5.7,-6.2,-4.4,-1.2,0.8,4.4,3.6,3.3,4.4
HIFIMAN RE800|4.6,4.6,4.6,4.3,1,-2.2,-3.1,-2.2,0.4,1.4,0.9,-1.3,-2.9,-3.4,-3.3,0
HIFIMAN Shangri-La|5.3,5,3,2,0.1,-1.2,-1.1,-1,-0.6,2,4.5,3.1,-4.5,-5,-3.9,-5.6
HIFIMAN Shangri-La Jr|4.8,4.2,2.1,1,-0.8,-2,-1.8,-1.6,-1.7,-0.4,0.8,0,-2.8,-2.3,-0.3,-0.7
HIFIMAN Sundara|5.1,3.7,1.6,0.6,-1.1,-2.3,-2,-1.3,-2.7,-1.9,0.8,0.6,-2.7,-3.7,0.2,0
HIFIMAN Sundara (2018)|4.7,4.2,2.2,1.4,-0.1,-1.6,-1.4,-0.7,-1.5,0.4,2.4,1.3,-3.8,-3.9,-1.9,-1.5
HIFIMAN Sundara (Dekoni sheepskin Earpads)|4.8,4.2,2.9,1.8,-0.2,-1.9,-1.6,-0.6,-2.3,-4.2,-1.2,0.9,0.7,0.4,1.9,-0.9
HIFIMAN Sundara (post-2020 earpads)|4.1,2.9,1.7,0.8,-0.8,-2.1,-1.7,-1,-1.9,-1.8,-0.1,0.9,-0.1,-0.1,0.7,-1
HIFIMAN Sundara (pre-2020 earpads)|3.8,3.2,1.9,0.8,-0.9,-2.2,-1.8,-1.1,-2.4,-1.1,1.8,0.7,-2.6,-2.7,0.3,-0.9
HIFIMAN Sundara Closed-Back|1.5,0.9,-0.5,-1.5,-2.8,-3.1,-3,2.9,0.5,-3.2,-1.5,2.6,6.1,1.3,1.9,2
HIFIMAN Sundara Silver|4.5,3.6,2.1,1,-0.9,-2.7,-1.6,0,-1.8,-1.9,0,0.1,-2.3,-0.6,-0.9,-3
HIFIMAN Susvara|3.8,2.8,1.8,0.7,-1.3,-2.7,-2,-2.1,-1.7,-0.7,2.7,1.3,-1.7,-1.9,-0.9,-0.8
HIFIMAN Svanar (ANC Hifi)|1.9,0.7,-1.1,-2.7,-4.3,-3.6,-2,0.3,2.4,2.6,2.5,1.6,1.7,-2.7,-3.4,-0.2
HIFIMAN Svanar (ANC)|1,1.6,0,-1.8,-3.2,-2.2,-0.9,-0.4,0.9,2.4,3.4,2.1,-1.1,-2.9,-1.7,1.1
HIFIMAN TWS600|4.6,4.6,4.3,2.5,0,-1.7,-2,-2.1,-2.8,-6.3,-13.8,-5.3,-3.1,4.5,4.6,3.7
Hisenior Mega 5 EST|-0.1,0.1,0.1,-0.4,-0.8,-0.6,-0.7,-0.7,-0.2,-0.3,1,2.1,0.2,2.2,1.2,-1.7
Hisenior Mega5P|0.4,0.7,0.4,-0.5,-1.6,-1.3,-0.8,-0.5,-0.7,-2.4,-2.7,1.5,2.1,4,1.7,0.3
Hisenior Okavango (off-off)|0,-0.2,-1.1,-2.3,-3.6,-3.1,-1.9,-0.4,0.6,-0.2,0,2,0.4,4.2,0.8,1.7
Hisenior Okavango (off-on)|1,0.8,0,-1.2,-2.6,-2.1,-0.8,0.7,1.7,0.8,1,3.1,1.8,5.9,-2.9,-5.8
Hisenior Okavango (on-off)|-1.3,-1.4,-2.3,-3.5,-4.7,-3.9,-2.5,-0.6,0.9,0.1,1.3,3.4,1.4,5.4,2.3,3.3
Hisenior Okavango (on-on)|-0.3,-0.4,-1.2,-2.4,-3.7,-2.9,-1.6,0.2,1.7,0.7,1.8,4.4,2.6,6.6,-1.4,-4
Hisenior T2|-1.1,-1.2,-1.8,-3.1,-5.3,-5.5,-4.2,-2.6,-0.5,0,1.3,4.9,4.7,4.1,2,2.9
Hisenior T2U|1.2,0.8,-0.5,-2.1,-4.4,-5,-4.1,-2.7,-0.9,-0.6,0.5,3.6,4,2.2,-0.4,2.8
Hisenior T4 (off-off)|1.5,1.3,0.2,-1.4,-3.4,-3.5,-2.4,-1.1,-0.2,-1,-0.9,1.7,1.2,0.2,0.5,1.2
Hisenior T4 (off-on)|2.2,2,0.9,-0.6,-2.7,-2.8,-1.7,-0.4,0.5,-0.3,-0.2,2.4,2,0.9,0.3,-2
Hisenior T4 (on-off)|0.7,0.6,-0.4,-2,-3.9,-3.7,-2.5,-0.9,0.2,-0.5,-0.3,2.1,1.3,0.3,0.7,2
Hisenior T4 (on-on)|1.6,1.4,0.3,-1.3,-3.2,-3.1,-1.8,-0.2,0.9,0.3,0.5,3.2,2.3,1.3,1,-1.8
HiSoundAudio BA-100|3.4,2.9,1.5,-0.3,-2.9,-4.1,-3.9,-3.5,-2.3,-2.4,-1.6,2.5,-4.1,-3.8,3.5,4.5
HiSoundAudio Crystal|2.4,2,0.7,-0.8,-2.6,-2.7,-1.5,-0.2,1.6,1.2,-0.2,4,0,1.9,1.2,-0.6
HiSoundAudio Golden Crystal|-1.8,-1.5,-1.5,-2.1,-2.5,-1.4,0.1,1.4,3.1,2.5,2.8,6.7,3.4,5.1,-2.1,-2.1
HiSoundAudio Hiphone HP4|-4.2,-4.3,-4.8,-5.8,-6.6,-5.2,-3.1,-0.7,2.4,3.7,7.3,7.7,7.5,6.3,3.9,3.1
HiSoundAudio Wooduo|-5.8,-5.5,-5.5,-6.1,-6.2,-4.3,-1.8,0.7,3.7,5.7,7,6.9,2.9,0.4,5.7,6.2
Horluchs HL4300|1.4,0.8,-0.4,-2.1,-4.5,-5.3,-4.6,-3.2,-0.8,0,-2.2,3,2.6,4.1,1.5,5
House of Marley Exodus Second Pair|4.4,3.5,1.8,-0.1,-2.2,-3.4,-2.5,-2.2,-2.9,-2.9,0.4,1.1,-4.2,0,0.7,4.4
House of Marley Legend|1.1,0.4,-1,-2.9,-5.1,-6,-5.2,-4.3,-2.2,-2.1,-1.7,0.9,5.6,5.4,5.6,5.6
House of Marley Liberate XLBT Wired|-0.6,-1.2,-2.2,-3.1,-4.8,-6.7,-5.8,-2.9,0.7,-3.3,-0.8,2.9,2.4,5.8,5.8,5.8
House of Marley Smile Jamaica|-4.4,-4.7,-5.8,-7.1,-8.4,-7.6,-5.5,-2.9,1.5,4.4,2.9,7.6,7.6,2.3,7.6,7.6
House of Marley Stir It Up|-2,-2.1,-3.6,-4.8,-5.1,-3.7,-2.8,-5.6,-4.4,-1.1,3.7,6.6,5.1,1.7,6.6,6.6
Howard Leight Sync|5.9,5.9,5.9,3.8,-3.6,-3.3,0.5,1.7,3.7,5.9,4,-3,3.5,1.4,-8.8,-10.2
Huawei Free Buds Pro 3 (ANC max)|-1.5,1.7,3.8,3.1,3.1,1.7,1.6,0.6,0.1,0.5,-0.7,-1.4,0.4,1,2.3,-2.7
Huawei Freebuds 4i|-0.8,-1,-1.3,-1.4,-3.2,-1.7,-0.3,0,0.7,0.7,0.5,3.8,0.4,1.7,2.5,3.1
Huawei Freebuds 5i (ANC off)|0.1,0.4,0.5,-0.6,-2.1,-1.4,-1,-1.1,-0.7,-0.6,0.6,0.2,-0.5,1.6,2.9,2
Huawei Freebuds 5i (ANC off, awareness)|0.2,0.8,0.9,-0.3,-2,-1.4,-1.1,-1.3,-0.9,-0.7,0.5,0.3,0,1.2,2.9,2.3
Huawei Freebuds 5i (ANC off, bass boost)|-2,-3,-4.3,-5.3,-5.5,-3.2,-1.7,-0.4,1.3,2.5,4.3,4.2,1.9,2.8,4.4,3.6
Huawei Freebuds 5i (ANC off, treble boost)|1.6,2,2.3,1.5,0.7,2.2,2.5,1.4,-0.4,-3.5,-1.4,1,-1.7,-2.2,-0.7,-1.9
Huawei Freebuds 5i (ANC on)|1.4,1.9,2,0.9,-0.9,-0.6,-1,-2.5,-2.5,-1.4,0.4,0.3,-0.6,0.4,2.2,1.9
Huawei Freebuds 5i (ANC on, bass boost)|-0.8,-1.6,-2.8,-3.8,-4.2,-2.5,-1.8,-1.9,-0.4,1.6,4,4.4,1.8,1.5,3.6,3.6
Huawei Freebuds 5i (ANC on, treble boost)|2.9,3.5,3.7,2.9,1.9,3,2.4,-0.1,-2.1,-4.3,-1.7,1.2,-1.8,-3.4,-1.5,-1.9
Huawei Freebuds Pro|2.2,-0.3,-2.5,-3.6,-4.2,-3.4,-2.1,-0.8,0.5,0.3,0.5,0.9,1.5,0.7,2.5,2.8
Huawei Freebuds Pro 2|-0.9,-0.3,1.5,2.7,2.8,0.5,0.6,0.7,0,-1.7,-1.4,-0.4,-0.1,-0.9,2.6,-0.2
HUM Dolores|3.2,2.5,1.3,-0.4,-2.7,-3.6,-3,-1.9,-0.7,-2,-4,-1.6,1.4,-5.7,2.9,4.2
HUM Hong Kong Reference|4,3.3,1.8,-0.1,-2.7,-4.2,-4.1,-3.6,-2.6,-3,-3.7,-2.4,-0.2,3.3,3.6,4
HUM Hong Kong TT|1.9,1,-0.1,-1.9,-4,-4.4,-3.5,-1.7,0.4,0.6,-1.2,-0.4,-0.1,-5.6,4.7,4.7
HUM Pristine Reference|2.3,1.6,0.1,-1.7,-4,-4.4,-3.4,-1.7,0.3,0,-3.2,-1.8,0,-2.5,4.8,4.8
HYLA CE-5|-1.2,-0.9,-0.7,-1.4,-1.8,-0.7,0.6,1.8,2.3,0.5,-2.3,-1,0.4,-0.6,2,1.1
HYLA Nerva X|-0.4,-0.7,-1.5,-2.8,-4.4,-4.5,-3.7,-2.4,-0.8,-0.7,-1.4,3.1,6.1,6.1,6.2,4.8
HYLA Sarda|1.4,1.4,0.7,-0.4,-1.6,-0.6,0.8,2.2,2.8,0.7,-2.9,-2.3,-0.3,-1.8,0,-1.7
HYLA TE-5B|-2.5,-2.1,-1.8,-2.3,-2.8,-1.3,0.1,1.4,2.1,0.8,-1.7,-0.8,1.6,0.9,2.9,2.9
HYLA TE-5T|0,0.5,0.8,0.3,0,1.3,2.6,3.5,3.2,0,-4.6,-3.8,-2.2,-3.4,-1.3,0
HyperX Cloud Alpha|2.4,1.5,-0.1,-1.6,-3.5,-3.8,-2.2,0.2,3.1,2,1.3,0.7,3.2,2.9,-0.3,-1.6
HyperX Cloud Alpha S|0.9,-0.1,-0.8,-1.7,-3.3,-2.7,-1.1,0.2,1.8,2.8,2.6,-0.9,2.4,4.4,-2.5,-1.9
HyperX Cloud Alpha Wireless|-2.1,-1.8,-2.5,-2.1,-1.2,0.3,1.6,1.3,3,4.9,4.8,-1.4,4.8,3.9,-6.5,-4.5
HyperX Cloud Core|1.4,0.8,-0.3,-1.6,-2.8,0.1,2.2,2.3,1.9,2.5,1.9,-0.6,4.4,-0.2,-5.1,-4.7
HyperX Cloud Flight|2,0.8,-0.4,-1.1,-1.3,1.6,5,3.5,-0.7,-0.1,0.6,0.9,0.2,-2.2,-5.8,-5
HyperX Cloud Flight S|0,-0.5,-1.7,-2.3,-1.4,3.4,5.9,2.5,-0.3,-0.3,-1,-3,4.1,3.5,-3.3,-3.8
HyperX Cloud II|2,0.7,-1.1,-2.6,-5,-1.8,1.8,2,1.9,1.6,0.5,-0.5,6.3,0.9,-3,-2.5
HyperX Cloud II Wireless|-0.1,-0.3,-0.8,-1.5,-1.8,-0.6,2.7,2.4,3.6,2.8,2,-0.4,2.2,1,-3.3,-4.8
HyperX Cloud III|5,3.7,2.1,2.3,2.6,0.3,-1,-1.5,-3,-2.8,-3.4,-0.1,1.5,0.7,-4.6,-2.7
HyperX Cloud III Wireless|4.3,2.6,2.3,2.9,2,-0.2,-1.1,-1.8,-3.3,-2.5,-2.4,-1,1.1,0.4,-4.2,-2
HyperX Cloud Mix|0.2,-0.3,-1.4,-2.1,-3,-3.4,-1.1,2.4,1.3,-0.4,-1.4,2.4,6.2,6.4,0.5,-2.5
HyperX Cloud Revolver|0.9,0,-1.7,-2.2,-2.4,-0.9,1.4,2,2,-0.3,-2.8,-1.7,3.7,3.1,0.2,0.4
HyperX Cloud Revolver + 7.1|3.2,0.8,-2.1,-3.1,-3.9,-2.9,-0.6,1.5,1,0.4,-0.4,0,5.7,2.8,-1,-2.4
HyperX Cloud Stinger|-1.1,-1.6,-1.9,-2.5,-2.7,1.4,6.9,6.7,1.9,2.3,-1,-2.7,1.8,4.8,-1.3,-1.9
HyperX Cloud Stinger 2|2.2,0.5,-1.4,-2.9,-4.3,-3,0.5,4.9,1.4,0.2,1,0.2,2.9,2.8,-3.1,-1.3
HyperX Cloud Stinger S|2,-0.3,-1.8,-2.1,-2.1,1.4,6.2,5.2,1.4,1.8,-2.9,0.5,3.1,0,-4.1,-4
HZSOUND Heart Mirror|4.5,4,2.5,0.7,-1.9,-3,-2.5,-1.6,-0.3,-1,-4,-1.8,1.4,-3.1,-1.1,-0.9
HZSOUND Heart Mirror Pro|0.6,0.3,-0.2,-1.3,-2.6,-2,-0.9,0.2,1.3,0.3,-1.3,-1,0.1,0.1,5.5,3.1
HZSOUND Heart Mirror Zero|4.1,3.4,1.9,0.2,-2.4,-3,-2.3,-1,0.7,-1.2,-2.4,0.1,1.9,-1.9,-3.7,-3.4
HZSOUND HZ-EP001|-0.9,-0.8,-1.2,-2.4,-4.1,-5,-4.8,-3.2,-1.6,-0.8,1.4,4.2,-1.3,5.2,6.3,4.1
HZSOUND HZ3|2.6,1.9,0.5,-1.2,-3.4,-4,-3.2,-2.5,-0.7,0.2,2.4,3,0.5,-4.3,-0.4,1.4
I-INTO i8|-3.4,-3.1,-3.1,-3.8,-4.6,-3.9,-3,-2,-0.1,1.5,2,3.8,6.8,1.3,6.8,6.8
I-Mego Throne|-3.1,-3.9,-4.9,-5.5,-6.8,-4.7,-2.4,-5.1,-3.6,-1.3,2.7,6.8,5.9,5.3,7.1,7.1
IKKO OH1|-0.7,-0.7,-1.1,-1.9,-2.7,-1.8,-0.5,0.7,1.7,1.1,-0.5,-2.3,-1,-2.2,2.3,4.4""",
"""IKKO OH10|-0.7,-0.6,-0.7,-1.5,-2.2,-1.1,0.3,2,3.2,2,0.3,-1.1,0,1.8,3.4,0.1
IKKO OH1S|4.9,4.9,4.5,2.3,-0.7,-2.3,-2.1,-1.4,0.1,0,-2.8,-2.8,-0.4,-0.5,-2.2,-1.6
IKKO OH2|4.8,3.2,1.2,-0.9,-3.5,-4.4,-3.7,-2.4,-0.6,-0.5,-2.7,-2.8,0.2,1.7,4.9,4.8
IKKO OH5|4.8,3.3,0.9,-1.4,-3.9,-4.7,-4,-2.5,-0.5,-0.3,-0.8,-0.8,0.3,-0.9,4.4,2.3
IKKO OH7|-3.5,-3.2,-3.3,-3.8,-4.3,-2.4,-0.5,1.4,2.1,1.2,-0.8,0.7,4,-0.1,6.6,6.6
IMR Acoustics Aten (silver nozzle, blue filter)|-0.5,-1.4,-2.6,-3.9,-5.3,-4.4,-2.4,0.2,3.1,4.5,4.9,2.1,4.2,3.1,1.2,-0.1
IMR Acoustics R1 (black)|-4.3,-3.6,-3.2,-3.5,-3.6,-1.5,0.6,3.1,5.6,6.1,5.3,0.3,0.6,-1.4,4.8,2
IMR Acoustics R1 (blue)|5.2,5.2,4.1,2,-0.8,-2.7,-2.5,-1.2,1.3,2.3,1.7,-2.1,-3.6,-5.6,1.2,-1.8
IMR Acoustics R1 (orange)|2.5,1.7,0.6,-1.3,-3.6,-4.9,-3.9,-2,1.1,2.5,2.8,3.6,0.8,-4.5,2.9,2.4
IMR Acoustics R1 (pink)|2.3,1.7,0.7,-1,-3.1,-3.4,-2.1,-0.1,2.6,3.3,2.5,-1.3,-0.4,-2.6,3.2,0
IMR Acoustics R1 (yellow)|-4.6,-4.1,-3.5,-3.6,-3.8,-1.7,0.5,2.9,5.5,5.9,5.1,0.9,1.5,-0.8,5.4,2.3
IMR Acoustics R2 Aten (black nozzle, black filter)|-3.7,-3.5,-3.9,-4.7,-5.4,-3.8,-1.7,1,4.1,5.6,6.2,5.9,5.9,4.2,2.3,0.8
IMR Acoustics R2 Aten (black nozzle, blue filter)|-3.5,-3.4,-3.8,-4.5,-5.3,-3.7,-1.5,1.2,4.2,5.9,6.4,5.5,4.9,3.5,2.2,0.7
IMR Acoustics R2 Aten (black nozzle, gold filter)|-4.5,-4.4,-4.8,-5.5,-6.4,-4.6,-2.5,0.2,3.2,4.7,5,8,8,6.5,3.8,1.5
IMR Acoustics R2 Aten (black nozzle, green filter)|-3.9,-3.9,-4.2,-5.1,-5.9,-4.2,-2.1,0.6,3.7,5.2,5.5,5.9,7.7,5.1,2.9,1
IMR Acoustics R2 Aten (black nozzle, purple filter)|-3.7,-3.7,-4.1,-4.8,-5.6,-4,-1.8,0.9,3.9,5.4,5.9,5.6,6.7,4.4,2.5,0.9
IMR Acoustics R2 Aten (black nozzle, red filter)|-3.9,-3.8,-4,-4.9,-5.7,-4.1,-1.9,0.9,3.9,5.5,5.9,5.8,6.8,4.5,2.8,1
IMR Acoustics R2 Aten (gold nozzle, black filter)|-7.7,-7.3,-7.1,-7.5,-7.8,-5.4,-2.9,0.5,4.6,7.8,8.9,8.9,8.9,1.4,5.2,8.1
IMR Acoustics R2 Aten (gold nozzle, blue filter)|-7.8,-7.4,-7.3,-7.8,-8,-5.8,-3.2,0.1,4.3,7.5,8.8,8.8,8.8,2.7,6.3,8.7
IMR Acoustics R2 Aten (green nozzle, black filter)|5.2,5.2,5.2,5.2,3.6,-1.5,-4.9,-6.7,-2.6,0.2,0.9,1.2,0.9,-0.6,-2.5,-4.1
IMR Acoustics R2 Aten (purple nozzle, black filter)|3.1,2.1,0.8,-1.2,-3.6,-4.7,-4.1,-2.1,1.1,3,3.5,3.4,3.2,1.4,-0.2,-1.7
IMR Acoustics R2 Aten (purple nozzle, purple filter)|3.6,2.4,1.1,-0.9,-3.4,-5,-4.4,-2.5,0.7,2.7,3.3,3.4,3.5,2.1,-0.3,-2.3
IMR Acoustics R2 Aten (red nozzle, black filter)|-2.4,-2.6,-3.2,-4.3,-5.1,-4.2,-2.1,0.5,3.6,5.2,5.6,5.3,5.8,3.8,1.9,0.2
IMR Acoustics R2 Aten (red nozzle, red filter)|-2.6,-2.6,-3.4,-4.4,-5.5,-4.2,-2.2,0.4,3.4,5.1,5.5,5.5,6.3,4.4,2.1,0.1
IMR Acoustics RAH (black nozzle, black filter)|-3.8,-3.4,-3.4,-3.8,-4.3,-2.6,-0.8,1,2.2,1.8,3.1,4.1,7.3,3.8,2.7,2.1
IMR Acoustics RAH (black nozzle, silver filter)|0.8,0.3,-0.7,-2.2,-3.9,-3.7,-2.3,-0.8,0.5,0.2,1.7,2.5,6.1,2.3,1,0.1
IMR Acoustics RAH (blue nozzle, black filter)|-3.5,-3.2,-3.2,-3.8,-4.4,-2.7,-0.9,1,2.2,1.7,3,4,7.2,3.7,2.4,1.8
IMR Acoustics RAH (gold nozzle, black filter)|-8.7,-8.1,-7.5,-7.8,-7.7,-5.1,-2.2,1.3,5.3,7.6,9.1,9.1,9.1,0.4,2.9,7.3
IMR Acoustics RAH (green nozzle, black filter)|5,5,5,5,4.3,-1.6,-5.6,-6.8,-3.8,-3.1,-1.3,-0.4,4.5,-0.7,-1.6,-2.5
IMR Acoustics RAH (purle nozzle, black filter)|3.7,2.6,1.4,-0.6,-3,-4.3,-3.6,-2.2,-0.8,-0.9,0.6,1.5,5.4,1.3,0,-0.8
IMR Acoustics RAH (red nozzle, black filter)|-2.6,-2.5,-2.8,-3.6,-4.3,-3,-1.2,0.6,1.9,1.5,2.9,3.7,7,3.5,2.2,1.7
IMR Acoustics RAH (red nozzle, blue filter)|-2.3,-2.2,-2.6,-3.3,-4.1,-2.7,-0.9,0.7,2,1.5,3.2,2.5,7,2.7,2,1.1
IMR Acoustics RAH (red nozzle, gold filter)|-2.8,-2.8,-3,-3.8,-4.6,-3.1,-1.4,0.4,1.6,1.3,2.8,5.4,7.2,4.1,2.3,1.5
IMR Acoustics RAH (red nozzle, green filter)|-2.8,-2.6,-2.9,-3.7,-4.5,-3,-1.3,0.5,1.8,1.4,3.3,4,7,3.6,2.4,1.7
IMR Acoustics RAH (red nozzle, no filter)|-2.1,-2.1,-2.4,-3.2,-4,-2.6,-0.8,0.9,2.3,1.9,3.5,3,6.1,2,1.4,0.7
IMR Acoustics RAH (red nozzle, purple filter)|-2.6,-2.6,-2.9,-3.7,-4.5,-3.1,-1.3,0.4,1.7,1.4,3,4,7,3.5,2.3,1.6
IMR Acoustics RAH (red nozzle, red filter)|-2.6,-2.5,-2.9,-3.7,-4.4,-3,-1.3,0.5,1.8,1.3,3,3.9,7.1,3.2,2.2,1.7
IMR Acoustics RAH (red nozzle, silver filter)|0.8,0.2,-0.8,-2.3,-4,-3.7,-2.3,-0.8,0.6,0.4,1.9,2.8,6.2,2.1,0.9,0.3
IMR Acoustics RAH (silver nozzle, black filter)|0.8,0.3,-0.7,-2.3,-4.1,-3.8,-2.5,-0.8,0.5,0.3,2.3,2.4,6.1,2.2,1.1,0.2
IMR Acoustics Semper (Pink nozzle with pink filter)|-0.2,-0.9,-1.9,-3.4,-5,-4.7,-2.9,-0.6,1.9,2.3,0.5,5.9,6.6,5.7,0.7,1.4
IMR Acoustics Zenith (black)|3.8,3.6,2.5,0.7,-1.3,-1.8,-1,0.5,2,1.2,-1,-6.9,-5.6,-3.5,5.6,1.1
IMR Acoustics Zenith (blue)|1.3,1.1,0.2,-1.2,-3.1,-3,-1.8,-0.4,1,0.1,-1.6,0.2,-1.9,2.5,6.2,2.2
IMR Acoustics Zenith (gold)|-3.6,-2.6,-1.8,-1.9,-2,-0.3,1.4,3.1,4.4,3.4,1.2,-3.9,-2.6,-0.8,7.5,4.1
IMR Acoustics Zenith (orange)|4.7,4.7,3.4,1.4,-1.7,-3.9,-3.9,-2.6,-0.4,-0.3,-1.2,-1.5,-5.1,-0.3,4.7,3.4
IMR Acoustics Zenith (pink)|5.2,5.2,4.9,3,0.2,-1.7,-2.1,-1.2,0.7,0.2,-1.8,-7.1,-5.5,-4.4,5.1,0.8
INAIR M360|-0.7,-0.6,-1.2,-2.5,-4.3,-4.3,-3.1,-1.3,1,1.9,2,1.9,2.5,6.4,5.8,3.9
InEar ProPhile-8|0.5,-0.3,-1.4,-2.2,-2.8,-2,-0.8,0,0.9,0.4,-0.4,2.7,2.5,2.4,1.4,3.6
InEar ProPhile-8 (bass)|1,0.4,-0.7,-2.1,-3.6,-2.8,-1.6,-0.8,0,-0.6,-1.4,2,2.1,1.8,2,5
InEar ProPhile-8 (bass, treble)|1.3,0.8,-0.3,-1.7,-3.2,-2.3,-1.1,-0.3,0.5,-0.2,-1,2.4,2.3,1.8,0.9,3.1
InEar ProPhile-8 (treble)|4.4,3.7,2.3,0.6,-1.7,-2.6,-2.4,-1.9,-1.1,-1.7,-2.4,1.1,1.1,0.8,-0.3,1.8
InEar StageDiver SD2|3.2,2.6,1.2,-0.6,-3.1,-4.2,-3.8,-2.6,-0.9,-0.7,-2.1,0.9,5.6,2.4,4.4,1.5
InEar StageDiver SD3|0.2,-0.2,-1.6,-3.4,-5.8,-6.2,-4.7,-2.3,0.5,1,-0.7,1.5,6.5,6.1,6.5,6.5
InEar StageDiver SD4|1.4,0.8,-0.4,-2.3,-4.7,-5.5,-4.8,-3.2,-0.7,1.1,2.7,5.8,5.8,1.8,-0.2,2.6
InEar StageDiver SD5|1,0.7,-0.4,-2,-3.9,-3.8,-2.2,-0.3,1.6,0.4,-2.2,1.4,4,5.6,-0.7,1
InEar StageDriver 3|0.6,0.1,-1.1,-3,-5.3,-5.8,-4.2,-2.3,-0.1,0.1,-0.3,1,6,5.2,6.5,3.6
Insignia Digital Wireless Headphones|0.1,-0.7,-1.8,-2.5,-2.3,-3.7,-3,1.2,2.1,-2.7,-3.9,2.7,7,7,0.6,-4
Intime Miyabi|2.3,1.9,0.9,-0.5,-2.5,-2.7,-1.8,-0.5,0.1,-1.1,-2,0.5,2.4,4.2,-0.4,-3.6
Intime Sho DD|-3.9,-3.8,-3.1,-4.2,-4.7,-2.4,0.3,2.8,3.3,2.4,3.6,3.6,3.4,1.5,2.3,0.4
Intime Sora|-1.7,-0.6,0.2,-0.2,-1.1,-0.8,-0.3,0.2,0.6,-0.1,-0.1,1.8,0.6,0.1,5.5,3.6
Intime Sora 2|1.4,0.5,-1,-2.7,-5.2,-5.7,-3.8,-0.3,2.9,2.2,0.1,3.1,3.7,5.3,-1,-1.6
Intime Sora Light|-3.8,-2.8,-2.1,-2.2,-2.5,-1.8,-0.5,0.5,1.2,1.1,1.7,3.2,4.4,4.3,4.2,-0.7
Intime Todoroki GO|-5,-4.4,-4,-4.3,-3.7,-1.9,0.9,3.6,4.3,1.9,0.5,3.9,5.7,7.8,1.9,-1.2
Intime Yo|2.5,1.7,0.2,-1.5,-3.8,-4.1,-2.6,-0.5,0.6,-1,-1.7,0.8,3.7,5.5,-1.4,-4.4
ISN AUDIO D10|-6.4,-5,-3.5,-2.9,-2.9,-0.8,1.1,2.9,4.2,3.1,1.5,-0.9,-0.2,3,5.6,4.8
ISN AUDIO EST50|-1.5,-0.9,-0.9,-1.6,-3,-3.4,-3.1,-1.8,0.3,0.5,1.8,3.3,4.8,2.9,1.8,0.6
ISN AUDIO H40|-2.4,-2.3,-2.6,-3.4,-4.1,-2.7,-0.9,0.8,1.4,-0.7,1,3.3,3.8,6.2,-0.1,0.7
ISN AUDIO H50|1.4,1,0,-1.6,-3.8,-4.4,-3.9,-2.4,0.4,1.8,3.8,4.8,2.2,0.4,1.8,0.6
ISN AUDIO Neo 1|-0.1,-0.3,-0.8,-1.7,-2.7,-1.5,0,1.4,2,0.8,-0.4,-0.2,0.7,-2,3.8,1.9
ISN AUDIO Neo 5|-0.5,-0.3,-0.8,-1.5,-2.2,-0.7,0,0.2,0.4,-0.5,-0.3,1.8,2.2,2.9,3.5,0.2
ISN AUDIO Rambo II|5.8,4.2,1,-1.9,-6.1,-9.3,-10.6,-12.3,-10.2,-4.2,3.1,5.8,5.8,5.8,5.8,5.8
Itsfit Lab Fusion|0.9,1,0.3,-1.2,-3.4,-4.4,-3.7,-2.4,-0.7,0.1,0,-0.3,5.7,5.5,4.2,0
Itsfit Lab R3|2.7,1.8,0.4,-1.5,-4,-5.3,-5.1,-4.2,-2.5,-1.9,-2.1,2.7,4,2.6,2.6,0.8
Itsfit Lab R3 (CIEM)|2.8,1.8,0.2,-1.7,-4.3,-5.6,-5.4,-4.6,-2.9,-2,-2.2,1.6,4.1,2.8,5,1.8
Ivery IS-1|-3.9,-4.2,-5.2,-6.3,-7.3,-6.3,-4.1,-1.6,2.5,6.2,8.3,8.3,8.3,8.3,2.2,0.9
Jabra Elite 10|0.6,1,1.7,2.5,4,-0.1,-0.6,0.7,2.1,1,1.3,2.7,2.2,-0.6,-4.6,-6.8
Jabra Elite 25e|-5.8,-5.5,-5.2,-5.4,-5.4,-3.6,-1,2.1,5,3.9,4.2,4.7,6.9,5.8,5.4,4.2
Jabra Elite 3|-1.2,-1.6,-1.8,-1.9,-0.6,1.3,1.2,2.1,3.8,1.9,2.4,3.2,0.5,3.4,-3.1,-5
Jabra Elite 3 Active|-3.1,-2.7,-2.3,-2.1,-0.9,0.9,0.8,1,1.4,0.1,0.3,1.3,0.7,3,-0.2,0.7
Jabra Elite 4|-0.2,-0.1,-0.2,-0.4,0.3,2,1.4,2.1,2.1,-0.4,-0.2,2,0.5,1.8,-2.2,-4.4
Jabra Elite 4 Active|2,0.7,-0.7,-1.6,-1.2,0,-0.3,0,1.1,-0.9,-1.3,1.2,0.2,2.3,-3.2,-2.7
Jabra Elite 45e|1.2,-0.2,-2,-3.3,-5.2,-4.9,-3.8,-2,0.7,-0.5,0.2,5.3,6.4,6.1,4.9,1
Jabra Elite 5|4.7,4.7,3.1,-0.5,-2.6,0,0.5,2.5,0.7,-1.6,-1.7,-0.3,-0.3,0.3,-6.3,-4.4
Jabra Elite 65e|-1.5,-1.2,-0.7,-0.8,-0.5,0.5,1.1,2.1,3.7,4.3,2.9,4.5,3.2,4.2,-4.4,-7.7
Jabra Elite 65t|2.4,0.9,-0.3,-1.4,-2.7,-2.3,-1.2,0.6,2.5,0.7,0.5,0.9,2.6,1,-3.7,-5.9
Jabra Elite 7 Active|-0.2,0.2,-1.4,-0.5,-0.1,0.5,1.3,2.6,4.2,2.2,1.5,0.7,1.2,-0.2,-0.8,-2.7
Jabra Elite 7 Pro|-0.1,0,-1.2,0.2,0.2,1.3,3.6,3.4,2.2,0.3,-0.5,-1.1,0.9,-0.4,-2.6,-1.9
Jabra Elite 75t|-6.5,-6.9,-3.6,0.7,6,5.5,3.4,2.9,2.5,0.1,-1.4,-0.2,3.7,2.4,-0.2,-2.6
Jabra Elite 8 Active|-0.8,-0.7,-1,-0.7,0.1,1,1.1,2,2.3,1.8,1.1,3.5,2.7,3.7,-1.5,-3.2
Jabra Elite 85h|-0.9,-0.8,1.9,0.2,0.2,1.4,1.2,0.9,1,-0.2,3.5,2.5,1.8,0.5,-0.6,-3.4
Jabra Elite 85t|-3.4,-3.1,-2.1,-1.3,-0.6,-1,0,0.5,1.9,0.2,-0.6,1.6,3.4,2.2,1.8,-0.1
Jabra Elite 85t (ANC Off)|-4.1,-3.9,-3.3,-3,-2.8,-3,-1.6,0,0.6,-0.8,0.1,1.5,4.2,3.2,5.8,6
Jabra Elite 85t (ANC On)|-3.7,-3.3,-2.1,-1.3,-0.6,-1.1,0.1,0.2,-0.3,-1.8,-0.8,1.2,3.5,1.1,3.4,4.8
Jabra Elite Active 45e|5.5,5.5,5.5,5.5,-0.4,-3.6,-3,-1.3,-0.5,1.6,4.7,-3,0.2,0.9,-4.2,-8.7
Jabra Elite Active 65t|0.1,-1.3,-2.4,-3.7,-5.1,-4.6,-3.3,-1.6,0.5,0.8,0,1.1,3.2,2.1,-0.2,4.5
Jabra Elite Active 75t|-5.6,-6.2,-2.9,1.5,7,6.1,4.4,3.9,4.3,0.3,1.2,2.8,4.4,2.3,-4.2,-7.2
Jabra Elite Sport|-3.4,-3.5,-3.9,-4.8,-5.4,-3,0.5,1.7,2.8,2.6,4.9,3.7,6.7,7.5,-0.5,-3.5
Jabra Evolve 65t|0.7,-0.4,-1.2,-2,-3.1,-2.4,-1,0.9,2.8,1.9,1.5,3.2,4.3,2.4,-2.6,-6.2
Jabra Evolve 75e|-3,-2.5,-1.6,-1.4,-1,0.7,1.7,2.2,3.7,6.8,4,3.4,3.4,2.4,-4.5,-7.1
Jabra Evolve2 65|2.7,1.2,-0.3,-1.1,-3,-3.5,0.8,4.9,4.1,2.9,2.8,1,1,1.6,-2.8,-4.1
Jabra Evolve2 85|5.7,2,-0.7,-0.9,-2.4,0.3,2.3,1.6,3.3,2.3,0.8,-3.3,-2.2,1.7,-4,-4.5
Jabra Move (wired)|-2.6,-0.7,-1.4,-3.2,-4.2,-1.6,-2.5,-3,-1.8,-0.6,1.1,4.6,3.5,5.1,5.7,6.2
Jabra Steel|4.1,4.1,4.1,4,-3.2,-7.9,-6.2,-1.4,3.5,4.1,2.3,-1,-3.8,-9.3,-3.1,4.1
Jabra Talk 45|5.6,5.6,5.6,5.6,5.6,1.4,-2.1,-3.9,-4.3,-3.8,-4.4,-4.6,2.2,-2.4,-7.1,-7.4
Jade Audio EA1|1,0.6,-0.5,-1.7,-3.1,-2.1,-0.5,1.5,3.3,2.7,1.7,-0.1,-0.6,-1.9,-2.6,-3.8
Jade Audio EA3|0.1,0,-0.5,-1.5,-2.7,-1.9,-0.5,0.7,1.3,-1,-3.6,5,1.3,5.8,-4.5,-4.1
Jaybird Freedom 2|-4.5,-4.6,-4.6,-4.8,-4.4,-2,0.9,4.1,6.9,5.1,5,1.3,7.5,7.6,-2.5,-3.6
Jaybird Freedom F5 (2016)|-1.3,-1.2,-1.5,-2.2,-2.8,-2.4,-0.7,1.3,3.1,1.6,0.2,-2.1,2.6,6.1,-2.4,-1.6
Jaybird Run|-1.7,-1.6,-1.8,-2.5,-3.2,-2.2,-0.4,1.9,3.5,1,0.5,-1.6,4.6,5.5,-4,1.1
Jaybird Run XT|-2.4,-2.2,-2.5,-3.3,-3.8,-2.7,-0.7,1.5,3.5,1.6,0.6,-1.2,4.6,6.3,-2.9,1.5
Jaybird Tarah|0.4,0.4,0.1,-0.6,-2.7,-1.2,0.6,2.7,4.2,1.1,0.5,-2,3.2,3.3,-3.6,-2.3
Jaybird Tarah Pro|0.1,0.1,-0.4,-1.4,-2.7,-2,-0.1,2.3,3.7,1.5,1,-0.7,3.8,5.1,-2,-3.2
Jaybird Vista|-4.9,-6.7,-6.4,-5.3,-4,-1.5,0.3,2.1,3.4,2.6,0.8,1,7.2,7.3,0.6,1.6
Jaybird Vista 2|0,0.4,0.4,-0.1,-0.9,-0.2,1.1,2.7,4,-0.2,-0.7,-2.3,4.4,5.2,-5.7,-4.3
Jaybird X2|1,1,0.3,-0.7,-1.8,-1.3,0.2,2.3,3.6,0.8,1.3,2.2,3.2,4.6,-2.7,-6.9
Jaybird X3|-2,-1.8,-2,-2.8,-3.4,-2.5,-0.4,2,3.3,0.8,1,-0.6,4.1,6.1,-4,0.4
Jaybird X4|-0.7,-0.4,-0.2,-0.3,-2.8,-0.8,1.1,3.1,4.3,1.3,0.9,-1.4,3.7,4.8,-1,-3.4
JAYS a-JAYS FIVE|0.8,0.3,-0.5,-1.4,-2.2,-1.3,0.3,1.5,3,2.2,1.5,-3.4,0.6,0,0,1.4
JAYS d-JAYS|4.2,3.4,1.8,-0.2,-3.1,-4.5,-4.6,-4.1,-3.4,-3.8,-3.4,-2.5,0.9,0,4.5,4.5
JAYS j-JAYS|-1.3,-1.4,-2.5,-4,-5.8,-5.1,-3.8,-1.7,0.2,-0.1,-1.1,4.2,5.4,-1.8,5.9,5.9
JAYS q-JAYS|3.8,3.5,2.1,0.4,-2.1,-3.4,-3.1,-2.9,-1.8,-1.9,-0.5,2.4,5.5,4.6,-2.2,-5
JAYS u-JAYS|1.6,0.9,-0.4,-0.7,-1.8,-1.5,1.4,-0.3,-1.5,-2.7,-1,1.9,-0.6,3.1,0.3,2.7
JAYS v-JAYS|5.3,5.3,1.2,0.7,1.2,2.5,0.7,2.2,2.8,1.6,-1.5,-4.8,-10.9,-3.7,0.4,-1.1
JBL CLUB 700BT|-2.5,-2.9,-2.3,-1.8,0.2,-0.3,1.3,3.2,2.6,2.1,2.2,2,1.7,4.5,-0.6,-1.6
JBL CLUB 950NC|1.9,-0.7,-2.1,-2.1,-0.8,-0.4,1.2,0.9,1.3,-0.3,-0.6,-1.5,1.4,-2.4,-2.2,1.4
JBL CLUB ONE|-0.4,-0.8,-1.5,-1.1,1.1,2.9,5,2.6,1.8,0.9,1.9,-1.3,2.4,-1.8,-3.2,-1.5
JBL CLUB PRO Plus TWS|-0.7,-0.8,0.8,3.5,1.7,1.8,1.9,2.1,4.1,0.4,0.1,1.5,1.5,1.4,-3.1,-5.7
JBL Duet NC (ANC Off)|-3.4,-3.2,0.2,-0.2,-2.2,4.7,5.4,2.1,-1.6,-2.3,-0.4,2.7,-1.5,1.4,1.4,0.7
JBL Duet NC (ANC On)|-3.1,-2.1,-1.8,-2,-1.5,1.7,4.2,5.2,3.1,-2,-2.2,2.1,2,1.3,0.8,0.8
JBL E25BT|-1.7,-2.1,-2.7,-3.6,-4.3,-2.4,-0.2,2.5,5.8,4.2,5.1,5.5,2.5,2.3,-1.4,-1.1
JBL E45BT|0.3,0.1,-1.5,-1.9,-2,0.4,5.9,6.5,2.4,-0.4,0.7,0.3,-3.8,1.1,-2.1,-1.1
JBL E55BT|-1.5,-1.4,-2.1,-3.4,-4,3.2,6.3,2.7,-0.7,-0.9,1.8,-0.1,1.3,3.5,-1.8,-1.5
JBL E65BTNC|-3.2,-2.6,-2,-2.3,-2.6,1.4,3,2.9,2.2,-1.1,0.7,4,-1.5,2.5,-1.1,0.8
JBL Endurance Dive|-1.1,-1.4,-1.2,-0.7,1,3.4,3.1,2.8,3,1.4,1.9,2.9,0.6,-1.3,-1.2,-3
JBL Endurance Peak|-3.2,-2.7,-1.8,-1.3,-0.3,2.9,2.6,2.9,3.3,1.1,2,1.3,0.4,0.5,0.8,0.5
JBL Endurance Peak 3|-10.2,-7.6,-4.3,-2.9,-1,2.5,4.1,5.1,5.7,3.5,3.3,3.2,3.3,0,2.7,4.8
JBL Endurance Peak II|-5.6,-4.4,-2.7,-1.7,-0.6,2.4,3.1,3.4,4.3,1,1.2,0.3,0.9,0.1,1.1,5.4
JBL Endurance Sprint|-2.4,-2.1,-1.2,-0.5,1.2,4.1,3.9,3.8,3.8,1.9,3,3.6,2.2,0.2,-1.6,-4.8
JBL Everest 110|-1.4,-1.4,-2,-3.1,-4.1,-3,-1.5,0.9,3.4,4,4,5.7,2.4,1.4,-0.4,-1.1
JBL Everest 310|-1.1,-0.8,-1.2,-0.2,1.7,4.3,3.6,0.5,-1.2,-1,0.5,1.2,0.1,6.2,-0.7,-3.9
JBL Everest 710|3.3,3.6,2.5,1,1.4,2.6,2.6,-0.1,-1.8,-2.9,-3.3,0.1,2.4,1.2,-5,-5.9
JBL Everest Elite 700|1.1,1.2,1.5,1.5,0.8,0,1.3,0.6,0.6,0,-1.3,-2.6,0.4,3,-4.8,-5.2
JBL Everest Elite 700 (wired, active)|-1.9,-0.8,1.2,0.5,0.3,-1.2,0.2,-0.2,-0.3,-1.1,-1.6,-4.2,-2.7,5.3,-0.2,1.4
JBL Everest Elite 700 (wired, passive)|2.1,2.3,2.8,1.5,0.2,-2.7,-1.8,-4.4,-4,-4.4,-3.2,-2.3,0.2,5.1,2.1,0.2
JBL Everest Elite 700 (wireless)|-1.3,-0.3,0.9,0.9,-0.1,-1.1,0.1,-0.3,-0.3,-1.3,-1.5,-4.6,-2.8,4.8,-0.7,1.7
JBL Free 2018|-2.1,-1.9,-1.8,-2,-1.4,0,0.6,1.9,3.5,1.8,1.9,2.3,3,5.7,0.1,-3.1
JBL Free X|-3.3,-2.6,-2,-1.8,-1.5,0.4,0.8,1.8,2.9,2.4,2.2,-0.2,3.2,5.7,2.3,-2
JBL J55i|-2.5,-2.9,-4.1,-5,-6.9,-7.5,-4.9,-1.4,0.3,0.2,-0.4,4.7,7,7,6.3,6.9
JBL J88i|-0.1,-1.2,-2.8,-3.9,-6.3,-7.6,-4.7,2.5,-2,-3.9,-2.2,6.1,6.1,3.4,6.1,5.7
JBL JR300BT|3.8,2,0.5,-0.6,-2.4,-2.2,-0.8,1.5,1.7,-1.4,-1.7,-1.3,-0.8,4.2,-4,-0.8
JBL Live 200BT|-1.3,-1.6,-1.4,-1.3,-0.5,2.2,1.2,1.8,3.5,2.6,0.9,0.4,1.3,-0.4,-1.3,0.2
JBL Live 300TWS|-2.6,-3,-2.9,-2.7,-2.3,-1,-0.5,0.5,2.1,2.1,0.8,-0.4,0.6,1.3,3.9,4.8
JBL Live 400BT|-1,-1.1,-1.5,0.5,4.3,2.7,-0.4,-0.2,0.2,0.5,1.7,1,-2.5,-1.9,-0.6,-1
JBL Live 460NC|-1.4,-1.1,0.6,0.9,-0.3,1,1.1,0,1.9,2.8,1.2,-1,-2.5,2.4,-3.2,0.1
JBL Live 500BT|-1.8,-2.1,-2.9,-1.9,0.8,5.3,1.9,0.6,0.8,1.9,1,-1.8,-4.5,0.4,-1.2,0.5
JBL Live 650BTNC|-2.6,-1.5,-1.6,-1.5,0.8,1.9,2.8,2.7,2.7,-0.4,-0.7,2.5,-2,2.1,-0.1,0.5
JBL Live 660NC|-1.1,-2.5,-2.4,-0.8,3.1,1.1,1,1,1.6,0.7,1.6,1.9,-0.5,2.7,-0.7,-2.5
JBL Live Free NC+ TWS|0,-0.5,-0.7,-0.8,0.1,0.5,-0.1,0.6,2.6,0.2,1.4,2.5,0.3,2.5,-1.3,-3.5
JBL Live Pro 2 TWS|-2.2,-1.6,-0.7,-0.4,0.2,1.5,2.2,2.9,2.9,1.1,0.3,0.5,-0.8,-2.1,0,1.6
JBL Live Pro+ TWS|-2.3,-1.7,-1,-1.3,-1.7,-1,0.1,2.1,5.8,4.1,2.9,0.6,0.5,2.4,0.9,-3.6
JBL Quantum 100|3.8,3.2,1.9,-1.8,-4.5,-4.9,-2.2,2.8,0.1,3,3.8,1,4.7,2,-6,-3.5
JBL Quantum 400|-0.7,-1.7,-2.6,-3.2,-2.3,0.4,2.2,1.5,0.2,1.1,1.2,-0.7,3.6,5.8,-3.8,-3.6
JBL Quantum 800|-1.2,-1.6,-2.9,-3.6,-3.3,-0.2,1.8,2.5,1.7,1.9,2.6,0.1,4.8,3.7,-4.9,-4.6
JBL Quantum ONE|0.2,0.6,0.8,0.9,1.6,0.1,1.1,0.8,0.5,3.2,1.4,-5.2,-1.6,-0.9,-3.8,-2.6
JBL Reflect Flow|-3.2,-2.6,-1.9,-1.5,0,1.4,1.5,2.2,3.6,1.8,0.9,0.4,2.1,4.5,-0.3,-2.1
JBL Reflect Mini 2|-0.7,-0.4,-0.7,-1.3,-1.9,-0.8,0.9,2.9,3.9,1.8,1.6,-1.3,3.8,5,-2.2,-3.7
JBL Synchros E50BT|-1.1,-1.5,-1.7,-1.8,-2.2,1.8,6.3,5.6,1.2,-1,-3,-1.9,2.3,-0.3,-3.6,-3.4
JBL T200A|-3.2,-3.5,-4.3,-5.2,-5.9,-5.1,-2.8,0.2,4.1,6.5,5.5,6.5,2.7,-2,1.4,6.5
JBL T450BT|2.5,0.1,-2.6,-4.9,-6.3,-4.1,-1.2,-0.7,-2.3,-2.2,-0.1,0.9,-1.5,0.6,4.9,4.9
JBL Tour One|0.9,0.3,-0.9,-1.9,-1.9,-1.9,-1,1.1,1.9,2.2,3,1.2,5,2.3,-3,-1.3
JBL Tour One M2 (ANC on)|-1.2,-1.2,-1.9,-3.9,0.2,0.6,1,1,1.4,1.7,2.5,-0.6,3.3,4.6,-0.8,-1.1
JBL Tour One M2 (ANC on, studio EQ)|0.8,0.7,-0.4,-3,0.5,0.2,0.3,0.5,1.1,1.3,2.1,-1.2,2.3,3.8,-1.6,-1.8
JBL Tour One M2 (passive)|-2.2,-2.7,-5.8,-7.4,-10.3,-3.7,2.7,3.9,3.1,3.3,4.5,2.3,6.1,5.5,0.7,2.3
JBL Tour Pro 2|0,-0.9,-1.5,-1.8,-1.3,-0.5,-0.5,-0.2,0.5,0,-0.4,-0.5,1.5,2.4,-0.3,0.1
JBL Tour Pro+ TWS|0.5,-0.5,-0.7,-0.5,0.1,1.5,1.4,3.4,3.7,0.3,-0.4,0.1,-0.1,1.5,-3.3,-4.1
JBL Tune 110|-5.2,-4.6,-4.1,-4.4,-4.6,-2.6,-0.4,1.8,4,4.9,6.3,7.2,2.2,-1.5,3.6,2.7
JBL TUNE 120TWS|4.5,3.6,1.1,0.3,1.7,2.2,1.5,1.5,2.2,-0.3,0.6,-3.1,-0.8,1.3,-4.1,-7.5
JBL Tune 125TWS|-0.8,-0.5,-1.5,-1,0,2.8,2.8,3.5,4.8,1.2,1.6,-1,2.9,3.8,-1.4,-5.1
JBL Tune 130NC TWS|-0.8,-0.9,-0.8,1,2.5,2,1.3,0.7,1,-1,-0.4,0.4,0.4,1.4,2.2,-2.1
JBL TUNE 220TWS|4.7,4.7,4.7,4.7,4.5,2.7,0.5,-1.9,-2.4,-3.2,-6.5,-4.1,-1,-0.5,-4.3,-2.8
JBL Tune 225TWS|4.7,4.7,4.7,4.7,4.7,3,1,-1.6,-2.4,-2.8,-6.4,-4.3,-1.8,-2.1,-4.1,-2.3
JBL Tune 230NC TWS|-3.7,-3,-1.4,-1.4,-0.3,1.7,2.3,2.7,4,1.6,1.1,1.6,3,3.2,0.3,-1.9
JBL Tune 510BT|-1.8,-3.2,-3.6,-2.4,0.5,-1,1,2.5,2.1,2.2,4.2,3,-0.5,-1.5,-1.9,-0.6
JBL Tune 660NC|-2.1,-2.7,-2.9,-2.8,-0.9,1.4,2.1,1.2,0.8,1.4,2.2,1.6,3.4,6,-4.9,-2.7
JBL Tune 720BT (passive)|4.8,4.8,4.8,4.5,1.2,-0.8,-3,-4.7,-4,-2.7,-0.6,-1.4,0.2,2.7,-2.7,-2.6
JBL Tune 720BT (wireless)|5.5,5.5,2.2,1.1,2,-0.4,-2.7,-3.7,-2.4,0.9,0.6,-3.1,-0.8,4.7,-1.4,-4
JBL TUNE 750BTNC|-1.2,-2,-1.8,-1,0.9,2,2.5,2.4,1.2,0.7,2.9,1.2,2.6,4,-2,-5.1
JBL Tune 760NC|-0.9,-1.7,-2.4,-2.2,1,1.3,2,2.8,3.9,3.1,4.6,-0.6,-0.1,2.7,-2.5,-5.7
JBL Tune 770NC (ANC on)|3.8,1.3,-0.8,0.8,2.7,2.1,1.2,-0.4,-0.2,-0.9,-1.2,-3.8,-1.6,3.1,-2.2,-3.8
JBL Tune 770NC (ANC on, bass EQ)|2.4,-1.3,-4.6,-3,-0.2,0.2,0.8,0.1,0.5,0.1,0.4,-1.8,-0.1,4.9,0.8,-1.6
JBL Tune 770NC (passive)|4.9,4.9,4.9,4.9,1.7,0.4,-2,-4.4,-4.5,-2.8,-1,-1.8,0.1,2.4,-2.8,-2.9
JBL Tune Flex|-2.1,-1.8,-0.9,-0.2,1.3,1.4,2,2.1,2,0.2,-1,-0.2,2.7,2.1,-1.9,-2.9
JBL Vibe 100TWS|-2.3,-1.6,-0.6,-0.3,0.7,1.7,1.9,2.4,3.2,0,0.6,1.2,2.3,3.7,-0.3,-2.6
JBL Vibe Beam|0.9,0.7,1.1,1.2,1.4,1.8,2.1,2.7,2.2,-0.2,-0.3,-1.7,0.6,-1.8,-2.4,-1.3
JCally AT1|5,5,3.7,1.3,-2.2,-4.8,-6,-4.8,0,3.6,5,4.9,0.2,-4.2,-4.6,-3.1
JH Audio 13v2 (2 o'clock)|0.2,0,-1.2,-2.7,-4.3,-4.1,-2.8,-0.9,1.4,1.9,1.9,1.3,6.6,6.4,0,1.6
JH Audio 13v2 (max)|-2.3,-2.6,-3.3,-4.4,-5.4,-4.4,-2.5,-0.2,2.4,2.8,2.8,2,7.1,7.2,1.8,3.5
JH Audio 13v2 (min)|4,2.9,1.7,-0.3,-2.6,-3.3,-2.6,-1.3,0.3,0.6,0.8,0.6,5.1,3.2,-3.2,-1.5
JH Audio 16v2|3.1,1.9,-0.1,-2.2,-4.8,-5.4,-4.8,-3.4,-1.5,-1.1,1.3,3.1,4.6,5.2,-0.3,1.5
JH Audio 16v2 (2 o'clock)|-0.3,-1,-2.5,-4.2,-6.4,-6.7,-5.6,-3.9,-1.3,0.3,2.5,5.4,6.1,6.1,3.8,4.8
JH Audio 16v2 (max)|-2.9,-3.3,-4.6,-6.1,-8,-7.8,-6.2,-3.9,-0.7,1.7,4.9,7.1,7.1,7.1,6.9,7.1
JH Audio 16v2 (min)|2.2,1.3,-0.4,-2.4,-4.8,-5.4,-4.7,-3.3,-1.3,-0.3,1.2,3.1,5,5.4,0.6,1.2
JH Audio Angie (2 o'clock)|2.5,1.7,0.1,-1.6,-3.9,-4.4,-4,-3.2,-2.3,-4.6,-4.5,3.4,5.4,5.4,3.6,4.4
JH Audio Angie (max)|-2.1,-2.3,-3.2,-4.3,-5.4,-4.5,-3,-1.3,0.3,-2,-2.3,4.9,6.8,6.8,6.2,6.8
JH Audio Angie (min)|4.9,3.7,1.6,-0.5,-3.3,-4.4,-4.3,-3.8,-3.1,-5.4,-5.2,3.4,4.8,4.9,2.1,3.3
JH Audio Billie Jean|-0.2,-0.5,-1.6,-3.1,-5.2,-5.3,-4,-2.2,-0.1,-0.6,-2.5,3.4,5.6,4.6,3,4.8
JH Audio Contour XO (half bass)|3.1,2.4,1.1,-0.6,-2.9,-4,-3.5,-2.5,-0.9,0,1.7,5.7,4.3,4,-1.9,-2.3
JH Audio Contour XO (max bass)|-3.8,-3.4,-3.2,-3.6,-4.2,-3.3,-1.9,-0.2,1.7,2.5,3.6,7.5,7.2,7.1,1.6,1.2
JH Audio Contour XO (min bass)|5.1,3.8,1.9,-0.1,-2.9,-4.1,-3.9,-2.9,-1.3,-0.4,1.3,5.4,3.9,3.5,-2.8,-3.2
JH Audio Diana|0.8,-0.2,-1.9,-3.9,-6.8,-7.8,-6.7,-4.8,-1.8,0.2,3.5,5.8,5.8,5,2.9,1.7
JH Audio JH13v2 (half bass)|0.8,0.4,-0.5,-2,-3.5,-3.1,-1.9,-0.4,1.3,1.5,1.4,0.3,6.3,5.2,-1.1,0.8
JH Audio Jolene (half bass)|5.6,5.6,5.6,4.8,1.4,-2,-3,-3.5,-4.9,-6.8,-1.6,5.6,5.4,4.5,-1.3,-4.3
JH Audio Jolene (max bass)|-1.9,-1.6,-1.6,-2,-2.5,-1.6,-0.8,-0.2,-0.8,-2.8,2.1,7.6,7.6,7.5,2.9,0.1
JH Audio Jolene (min bass)|5.6,5.6,5.6,5.6,1.8,-2.2,-3.1,-3.7,-5.1,-7,-1.8,5.6,5.4,4.5,-1.5,-4.5
JH Audio Layla (2 o'clock)|0.3,-0.2,-1.2,-2.5,-4.2,-4.4,-3.9,-3,-1.9,-2.1,0.8,6.2,6.2,6.2,1.6,2
JH Audio Layla (half bass)|0.3,-0.2,-1.2,-2.5,-4.2,-4.4,-3.9,-3,-1.9,-2.1,0.8,6.2,6.2,6.2,1.6,2
JH Audio Layla (max)|-3.2,-3.4,-4,-4.8,-5.6,-4.6,-3.1,-1.6,-0.1,-0.4,2.3,7.2,7.2,7.2,3.9,4.6
JH Audio Layla (min)|3.1,1.9,0.5,-1.3,-3.6,-4.7,-4.6,-4.1,-3.1,-3.3,-0.2,5.4,5.4,5.4,0.1,0.7
JH Audio Layla AION (2 o'clock)|-1.3,-1.5,-2.2,-3.2,-4.4,-4,-3.1,-2.1,-1.3,-1.9,0.4,6.4,6.5,6.5,3.6,4.9
JH Audio Layla AION (half bass)|-1.3,-1.5,-2.2,-3.2,-4.4,-4,-3.1,-2.1,-1.3,-1.9,0.4,6.4,6.5,6.5,3.6,4.9
JH Audio Layla AION (max bass)|-3.5,-3.6,-4,-4.8,-5.5,-4.3,-2.6,-1.1,0,-0.7,1.4,7.1,7.1,7.1,5.1,6.3
JH Audio Layla AION (min bass)|3.7,2.8,1.1,-0.6,-3.1,-4.2,-4.2,-3.9,-3.6,-3.9,-1.5,5.1,5.1,5.2,0.8,1.8
JH Audio Lola (2 o'clock)|0.9,1,0.9,-0.1,-1.9,-2.9,-2.6,-2,-1.4,-1.4,4,5.5,6.1,5.9,-3.3,-1.8
JH Audio Lola (max)|-4.5,-4.3,-4.3,-4.8,-5.1,-3.6,-1.8,0.1,1.5,1.4,5.6,7.7,7.7,7.7,0.8,2.3
JH Audio Lola (min)|5.1,5.1,4.7,2.6,-1.1,-3.5,-3.8,-3.4,-2.9,-2.9,3.6,4.6,5,3.6,-5.6,-4.2
JH Audio Roxanne (2 o'clock)|2.3,1.1,-0.7,-2.6,-5.1,-5.9,-5.3,-4,-1.7,-0.4,1.2,5.4,5.5,5.5,-0.2,2.9
JH Audio Roxanne (max)|-1.1,-1.7,-3.2,-5,-7.2,-7.5,-6.3,-4.5,-2.1,-0.4,2.8,6.4,6.4,6.4,4.4,5.6
JH Audio Roxanne (mid)|4.6,3.3,1,-1.2,-4.2,-5.7,-5.6,-5,-3.8,-2.8,0.7,5,5,5,-1.8,-0.6
JH Audio Roxanne (min)|4.9,4.3,1.9,-0.5,-3.6,-5.3,-5.3,-5,-3.9,-2.9,0.5,4.9,4.9,4.9,-2.7,-1.6
JH Audio Sharona|-0.3,-0.2,-0.4,-1.2,-2,-1.3,-0.5,0,0.1,0.8,3,6.1,6.6,4.6,-3.7,-4.1
JH Audio Sheena|0.6,0.2,-0.8,-2.2,-3.7,-2.7,-1,0.8,1.5,-0.4,-0.1,6.5,6.5,1.5,-2.9,-2.1
JH Audio TriFi 10th Anniversary|-1.4,-2.1,-3.4,-5.1,-7,-6.7,-5.2,-2.7,0.5,2.4,3.2,6.3,6.2,6.3,2.2,1.9
JLab Audio GO Air POP|-1.3,-2.2,-3,-3.7,-3,1,2.8,4.1,5.9,2.2,2.2,1.9,1.9,4.9,-3.8,-5.3
JLab Audio JBuds Air|-5.6,-4.3,-3.2,-3.2,-2.9,-0.4,2.5,4.2,5,3.6,4.7,7.2,1.5,2.5,-1.4,-2
JLab Audio JBuds Air ANC|1.5,0.4,-2,-2.5,-3.1,-0.2,2.3,4,3.2,1.8,1.8,2,-1.8,-4.5,-1.8,-1.6
JLab Audio JBuds Air Executive|-3.8,-3.6,-3.5,-3.6,-2.9,-0.6,2.3,5.7,8.1,6.9,7.4,7.4,-1.7,-0.7,-4.2,-3.7
Jomo Audio Flamenco|2.9,1.9,0.3,-1.6,-4.1,-4.9,-4,-2.3,0.8,2.3,1.2,0.1,0.4,1,1.2,1.6
Jomo Audio Flamenco (bass on, treble on)|2.4,1.4,-0.1,-2,-4.3,-4.8,-3.7,-1.7,1.6,3.4,2.1,0.7,1.2,-2.9,-0.6,3.2
Jomo Audio Flamenco (bass)|1.8,0.9,-0.7,-2.6,-5,-5.5,-4.5,-2.4,1.2,3.6,3.2,0.7,0.3,1.7,2.7,2.8
Jomo Audio Flamenco (treble)|4.8,4.4,2.8,0.8,-1.9,-2.7,-2.1,-0.5,2.1,2.6,0.4,-1.7,-1.7,-5.3,-3.1,0.4
Jomo Audio Haka|-0.3,-0.7,-1.9,-3.4,-5.6,-6.1,-4.7,-2.6,0.3,1.8,2.9,3.4,3,0.7,4.4,5.5
Jomo Audio Jazz|1,0.5,-1,-2.6,-4.9,-5.3,-4.2,-2.5,-0.5,-1.5,-4.7,3,4.2,-1,5.3,5.3
Jomo Audio P3|-3,-2.4,-1.7,-1.4,-0.6,-0.3,1.9,2.8,2.4,1,-1.5,-2.4,0.7,-0.9,0.2,2.8
Jomo Audio Quatre|-2,-2.3,-3.1,-4.5,-6.2,-6,-4.8,-2.9,-0.3,1.6,3.8,5.5,6.1,-0.4,4.4,6.1
Jomo Audio Quatre (black)|-1.4,-0.9,-1.1,-2.3,-4,-4.3,-3.5,-2.1,0,0.8,0.2,3.1,5.3,-1.7,2.7,5.7
Jomo Audio Quatre (no filter)|-0.8,-1.1,-2.3,-4,-6,-6.2,-5,-3.3,-0.7,0.5,2.3,5,5.7,-0.6,3.8,5.7
Jomo Audio Quatre (white)|-1.9,-1.8,-2.1,-3.2,-4.7,-4.6,-3.6,-2,0.3,1.1,0.9,3.7,5.9,-1.3,3.2,5.9
Jomo Audio Salsa|2.4,1.7,0.2,-1.7,-4.2,-4.9,-4.2,-2.5,0,-1.7,-3.6,2.8,4.7,2,-0.8,4.5
Jomo Audio Samba|1.7,1,-0.3,-2,-4.1,-4.1,-3,-1.2,0.8,1.1,0.6,2.4,3.7,3.1,-0.7,2.4
Jomo Audio Samba (CIEM)|2.9,1.8,0,-1.7,-4,-4.6,-3.7,-2.2,-0.2,0.5,0,1,3.8,2.8,0.7,-0.7
Jomo Audio Tango|-1,-1.6,-2.7,-4.2,-5.9,-5.3,-3.5,-0.6,3,4.2,3.5,3.1,2.1,-2.7,2.1,5.4
Jomo Audio Trinity|-0.6,-0.5,-0.7,-1.4,-1.9,-0.2,1.8,3.7,4.8,2.7,-2.1,-1.6,-3.8,-3,-2.8,1.2
Jomo Audio Trinity Brass|-2.1,-2.2,-2.6,-3.5,-4.3,-2.7,-0.7,1.5,3.4,2.9,1.3,3,1.7,3.1,3.4,2.3
Joyodio Shine (off-off-off-off)|0.3,0.3,-0.2,-1.3,-2.6,-2.1,-1.1,0.3,1.5,-0.6,-2,-1.7,0.9,3.8,6.4,1.5
Joyodio Shine (off-off-off-on)|3.6,3.4,2.5,1.1,-0.9,-1.3,-0.8,-0.2,0.2,-2.5,-4.3,-3.3,-1.5,1.7,5.2,-0.7
Joyodio Shine (off-off-on-on)|4,3.8,2.8,1.4,-0.5,-0.9,-0.4,0.3,0.6,-2.1,-3.9,-2.8,-1,2.5,4.6,-3.3
Joyodio Shine (off-on-on-on)|4.6,4.4,3.6,2.1,0.1,-0.4,0,0.5,0.4,-2.6,-4.6,-3.6,-2.6,1.8,3.7,-3.3
Joyodio Shine (on-off-off-off)|-2,-1.5,-1.2,-1.7,-2.3,-1.5,-0.3,1,2.2,0.1,-1.3,-1.1,1.5,4.5,7,2.1
Joyodio Shine (on-off-off-on)|0.1,0.6,0.8,0.3,-0.5,-0.2,0.4,1,1.2,-1.5,-3.3,-2.3,-0.5,2.6,6.3,0.2
Joyodio Shine (on-off-on-off)|-1.6,-1.1,-0.8,-1.3,-2,-1.2,-0.1,1.3,2.4,0.4,-1.1,-0.9,1.7,5,7.1,0
Joyodio Shine (on-off-on-on)|0.4,0.9,1.2,0.7,-0.2,0.1,0.8,1.4,1.6,-1.2,-3,-1.9,-0.1,3.4,5.4,-2.5
Joyodio Shine (on-on-off-off)|-1.5,-1,-0.7,-1.1,-1.8,-1.1,-0.1,1,1.9,0,-1.5,-1.6,0.3,4,6.6,1.7
Joyodio Shine (on-on-on-off)|-1.1,-0.6,-0.3,-0.8,-1.5,-0.8,0.2,1.3,2.2,0.3,-1.2,-1.5,0.5,4.5,6.3,-0.2
Joyodio Shine (on-on-on-on)|1.1,1.6,1.9,1.3,0.4,0.7,1.2,1.6,1.5,-1.6,-3.7,-2.7,-1.7,2.7,4.6,-2.4
JQ 4U Pro|0,0.2,0,-0.9,-1.9,-1.6,-0.9,-0.3,0,-1.1,-2.8,1.5,3.2,3.3,3.7,1.8
JQ Audio 4U|-1.5,-1.1,-1.4,-2.3,-3.1,-2.1,-0.7,0.5,1.1,-0.4,-3.1,4,4.8,0,1,-0.1
JQ Hua Jiang|0.5,0.8,0.7,-0.2,-1.5,-1.6,-1.2,-0.5,0.2,-0.5,-2.6,-0.3,1.6,5,2,-0.7
Just Ear Club Sound|3.3,2,-0.4,-2.9,-5.7,-4.5,-2.5,-0.8,0.6,0,-2.4,-4.7,2.8,-1.1,3.2,4.8
Juzear 41T|-0.1,0.8,1.2,0.5,-0.8,-1.6,-1.2,-0.7,-0.6,-1.5,-0.9,0.8,3,2.3,2.7,-0.1
Juzear 51T|-0.5,-0.1,-0.1,-0.6,-1.2,0,1.4,2.4,1.5,-0.4,-0.6,2.9,3.4,1.8,1.2,-3.9
Juzear Clear|-3,-2.4,-2.1,-2.6,-2.9,-1.4,0.6,2.6,4.4,3.1,3.4,3.4,1.4,-3.3,0,0.6
JVC FD01 (modded)|0.4,0.4,-0.1,-1.2,-2.8,-2.9,-2.4,-1.6,-0.8,-1.2,-1.6,0.8,0.1,2,2.4,3.3
JVC HA-FD01|1.5,1.4,1.1,0.1,-1.4,-1.5,-0.8,0,0.7,0.9,0.3,-0.5,-3.2,-2,2.5,1.7
JVC HA-FD01 (brass nozzle)|2,2.4,2.2,1.2,-0.4,-1.1,-0.9,-0.5,-0.1,-0.2,-0.8,-1.7,-3.7,-0.6,3.1,-1.1
JVC HA-FD01 (steel nozzle)|2,2.3,2.1,1.1,-0.4,-1.1,-0.9,-0.5,-0.1,-0.3,-0.8,-1.6,-3.5,-0.5,2.8,-1.2
JVC HA-FD01 (titanium nozzle)|2.1,2.4,2.2,1.1,-0.4,-1.2,-1,-0.4,-0.1,-0.3,-0.7,-1.7,-3.7,-0.6,3,-0.9
JVC HA-FD02|1.2,1,0.4,-0.7,-1.9,-1.4,-0.5,0.4,1,0.2,-1.1,-2.5,-3.7,-1.1,5.7,3.4
JVC HA-FDX1|2.2,2.1,1.2,0,-1.4,-1.1,-0.3,0.4,0.9,-0.2,-1.2,-0.4,-2.2,-0.7,2.2,-0.5
JVC HA-FDX1 (blue)|3.8,3.7,3,1.6,-0.6,-1.7,-1.6,-1.4,-1.1,-1.9,-1.8,-1.2,-3.4,-1.1,4,0.5
JVC HA-FDX1 (green)|3.8,3.8,3,1.6,-0.7,-1.7,-1.7,-1.5,-1.4,-2.2,-2.3,-1.8,-3.3,-1.3,4.6,1.5
JVC HA-FDX1 (white)|4.5,4.5,3.7,2.3,0.1,-0.9,-0.9,-0.6,-0.4,-1.1,-1.3,-2.4,-5.6,-3.3,3.3,-0.3
JVC HA-FR15|-4.2,-3.6,-3.2,-3.4,-3.5,-0.6,0.4,3.3,6,6.9,7.7,6.9,1.2,-0.5,-2,-0.2
JVC HA-FR301|-1.7,-2.6,-3.8,-5.2,-6.4,-5.1,-2.6,0.4,5,6.8,6.8,5.6,-1.8,-3.2,3.2,2.8
JVC HA-FW01|-1.6,-2.3,-3.4,-4.8,-6.3,-5.3,-3.5,-1,2.1,3.3,2.5,1.4,1.1,-0.3,5.6,5.7
JVC HA-FW02|1.6,0.3,-1.5,-3.2,-5.2,-4.8,-3.2,-1.1,1.3,2.4,2.5,-0.4,-0.9,-2.3,4.2,5.2
JVC HA-FW03|4.2,3.1,1.4,-0.6,-3.1,-3.7,-2.9,-1.6,0.1,0.3,-0.9,-1.9,-1.5,-0.6,3,3.7
JVC HA-FW10000|4.2,3.6,2.5,0.4,-2.1,-2.2,-0.1,2.4,1.6,-1,-3.6,-4.1,-3.6,-3.2,-0.5,-0.5
JVC HA-FW1000T|-0.7,-1.7,-2.3,-2.4,-2.7,-0.9,0.3,1.4,1.5,0.4,-0.2,-3.4,-5.3,3.9,4.8,3.8
JVC HA-FW1500|0.1,-0.4,-1.4,-2.6,-4.1,-3.4,-1.9,0,2.2,3,0.5,0.4,-0.4,-2.7,4.9,5.7
JVC HA-FW1800|-2.3,-2.2,-2.6,-3.3,-4.4,-3.6,-1.6,0.4,2.8,2.5,2,0.8,-0.8,-3,4.2,6
JVC HA-FWX1|-1.4,-1.8,-2.7,-3.8,-4.9,-3.7,-1.9,0.1,2,2.6,3.3,5.9,2,-0.8,0.9,1.9
JVC HA-FX6|-6.2,-5.5,-4.8,-4.9,-4.7,-2.9,-0.1,2.9,6.1,7.8,7.8,7.7,1.5,-1.9,0.9,5.4
JVC HA-FX7 Gumy|-2.9,-3.4,-4.3,-5.4,-6.2,-3.9,-2.4,0.9,4.7,4.9,5.2,5.7,2.7,1.5,-0.7,3.7
JVC HA-FX99X-B|-2,-2.3,-2.9,-3.9,-4.7,-2.9,-1.2,-0.5,1.5,0.5,1.9,2.3,-1.3,1.2,5.6,5.1
JVC HA-FXT90|2.4,0.9,-1.3,-3.2,-4.9,-4.5,-3,-1.3,1.1,1.3,4.1,1.7,0.6,-3.2,-0.4,2.2
JVC HA-S500-Z|0.7,-0.9,-2.1,-2.8,-4.4,-5.2,-4.4,1,-1.3,-2.9,-0.1,5.7,3.1,3.3,3.7,3.4
JVC XX Elation HA FR100X|2.4,0.7,-1.4,-3.6,-6,-6.7,-5.5,-3.7,-0.4,1.9,4.5,2.2,-0.4,-3.9,5.2,5.2
KBEAR Believe|4.6,3.5,1.3,-0.8,-3.1,-3.6,-2.5,-0.8,0.9,1.3,0.1,-1.1,-1.4,0.8,-3.5,-2.3
KBEAR Diamond|-3.4,-2.9,-2.5,-2.4,-3.1,-1.4,0.5,2.6,4.3,3.9,3.8,2.7,-0.5,1.1,3.4,0.6
KBEar F1|1.8,1.2,-0.1,-1.4,-2.3,-4.5,-3.7,-1.1,-0.5,-0.1,0.6,-0.2,-0.6,-1.1,1.9,5
KBEAR KB04|-0.2,-0.2,-0.7,-1.6,-2.5,-1.4,0.3,2.2,3.6,3.3,1.8,-2.1,1.7,-4.1,-3.9,-1.2
KBEAR KS1|1.2,-0.6,-2.2,-3.8,-5.2,-4,-2.1,0.3,2.7,2,1.4,0.5,2.8,-2.4,0.4,0.7
KBEAR KS2|-3.1,-2.7,-2.6,-3,-3.3,-1.4,0.8,3.1,5.1,3.8,1.8,1.9,1.9,6.1,-3.3,-3.5
KBEAR Lark|3.2,2.8,1.6,0,-2.1,-2.2,-1,0.8,2.7,2.3,1.3,0.1,-1.8,-2.5,-6.5,-4.5
KBEAR Neon|3.4,2.6,1.3,-0.6,-3.2,-4.5,-4.4,-4,-2.8,-2.8,-3.1,-1,0.6,4.2,3.2,4.2
KBEAR Qinglong|0,-0.2,-0.9,-2.1,-3.4,-2.7,-1.3,0.3,1.5,0.3,-1.7,-4.5,-0.3,0.7,5.6,5.2
KBEAR Robin|-3.7,-3.7,-3.9,-4.6,-5.2,-3.6,-1.5,0.9,3.1,2.4,0.7,1,3.2,2.6,2.8,5.4
KBEAR Rosefinch|-5.2,-4.7,-4.5,-5,-5,-3.2,-1,1.4,3.6,3.7,4,4.1,2.8,5.9,0.4,1.1
KBEAR Stellar|1.7,0.2,-2,-4.1,-6.7,-7.5,-7,-5.7,-4.1,0.1,5.5,6.7,6.7,6.7,3.2,2.1
KBEAR Storm|-2,-2.4,-2.6,-3.2,-3.6,-1.9,0.3,2.8,4,3.4,2.3,-2.2,-2.9,2.3,1.5,1
KEF M200|-3.7,-2.7,-2.1,-2.3,-2.2,-0.8,1.1,2.4,3.6,2.3,-0.3,3,2.3,7.4,3.4,-1
KEF M500|-0.3,-0.5,-1.5,-1.9,-4.3,-3.9,-0.5,-1.9,-1.3,1.3,3.1,1.2,-3.2,4.3,2.7,3.5
Kefine Klanar|-1.8,-1.1,-0.7,-1,-1.4,-0.2,1.2,2.5,2.6,0.4,-1.8,1.3,5.6,3.7,-1.1,-2.4
Kennerton Jimo|-3.1,-3.6,-4.6,-5.9,-7.5,-6.5,-4.6,-1.7,2.2,6.2,6.9,6.9,6.9,4.8,4.1,6.2
Kennerton Magister|2.4,0.9,1.2,-2.8,-6,-2.5,5.7,0.2,-0.5,-0.1,2.5,1.8,1.5,-6.1,5.6,2.9
Kennerton Odin Bog Oak|5.1,5.1,2.2,0.1,-2.5,-4,-3.7,-3.2,-2.2,-4.3,2.6,0.6,-0.1,0.2,3.4,1.4
Kennerton Thror Bog Oak|5.2,5,1.3,-0.3,-2.6,-4.2,-3.3,-1.7,-3.5,-1,2.9,-1.6,-0.7,0.4,3.1,0.5
KFA2 SONAR-04|-0.8,-0.6,-2.6,-2.8,-4.8,-4.7,-6.4,1.4,4.1,7.3,7.3,6.1,7.3,-1.6,-4.4,-1.5
KiiBOOM Allure|-1.5,-1.4,-1.4,-1.9,-2.7,-1.8,-0.4,0.7,1.3,0.1,-1.8,-0.8,2.6,1.8,2,1
KiiBOOM Evoke|-1.4,-1,-0.7,-1.2,-1.7,-0.9,-0.2,0.4,0.5,-0.6,-0.7,-0.2,0.6,2.9,2.9,0.6
Kinera Baldr|1.1,0.4,0.1,-0.5,-1.1,0.2,1.8,2.8,2.9,-1,-5.9,-3.3,-1.8,-3.7,2.5,4
Kinera BD005|-6.7,-5.7,-4.8,-4.9,-4.8,-2.6,-0.4,1.7,3.9,2.9,1.9,3,3.3,5.6,8,8
Kinera BD005 Pro|1.4,1.3,0.7,-0.3,-1.7,-1.7,-0.9,0,1.2,0.5,-3.2,0.6,0.9,-0.3,-0.9,1.3
Kinera Celest Gumiho|-0.6,-0.5,-1,-1.8,-2.7,-1.7,-0.5,0.7,1.4,-0.6,-3.5,1,3,6.2,-1.9,-1.3
Kinera Celest Pandamon|1.8,1.5,0.3,-1.2,-3.3,-3.2,-2.5,-1.4,-0.4,-1,-2.8,-2.2,1.2,2.3,1.7,2
Kinera Celest Phoenixcall|-0.2,-0.8,-1.6,-2.5,-3.2,-1.3,1.2,3.4,4.4,1.3,-3.1,2.9,0.8,0.9,-0.7,-2.3
Kinera Celest Wyvern|-2.4,-1.8,-1.4,-1.8,-2.7,-2,-1.4,-0.6,-0.3,-1.8,-2.3,0,2.5,6.5,6.6,4.6
Kinera Celest Wyvern Pro|-0.3,0.4,0.8,0.3,-0.6,-0.2,0.3,0.9,0.9,-0.6,-1.8,-1.6,-0.6,2.6,3.4,0.4
Kinera Freya|4.2,2.5,0.4,-1.2,-2.8,-1.7,0.1,2.4,2.9,0.4,-4.7,-2.2,-4.3,-4.5,-2.2,0.9
Kinera Freya 2.0|1,0.9,0.3,-0.9,-2.3,-1.8,-0.5,0.7,1.1,-1.1,-1.5,-0.1,-0.6,-1.9,-1,2.4
Kinera H3|1.8,1.7,1.3,0.8,0.2,1.2,3.6,3.7,2.5,-1.5,-3.6,1.8,1.8,-6.9,-8.4,-1.9
Kinera Hodur|-0.1,-0.6,-1.7,-3,-4.4,-3.7,-2,0,2,1.9,-1,0.8,5.2,0.5,2.5,2.3""",
"""Kinera IDUN|3.1,2.5,1.4,-0.3,-2.6,-3,-2,-0.2,0.8,-2.9,-3.3,1,0.4,2.8,0.1,0.6
Kinera IDUN Deluxe|3.1,2,0.4,-1.3,-3.6,-4,-3.3,-2.2,-1.9,-3.1,-0.5,2.7,1.8,3.1,0.8,3.1
Kinera Imperial Loki|-0.1,0.7,1.3,0.9,0.6,1.8,2.8,3.3,2.3,-1.3,-3.5,0.7,1.4,3.7,-1.2,-4.6
Kinera Imperial Loki Emerald|-1.9,-1.1,-0.4,-0.5,-0.9,0.9,2.2,3.2,3,0,-1.8,2.6,2.4,5.2,3.3,-3
Kinera Imperial Nanna|1.6,2.2,2.4,1.7,0.1,0.1,0.4,1.5,1.5,-0.4,-1.7,-0.3,1.7,0.6,-2.2,-6.2
Kinera Imperial Odin|1.4,0.9,-0.3,-1.7,-3.5,-3,-1.3,0.9,2.8,0.7,-4.1,0.4,5,3.9,-1.1,-0.7
Kinera Imperial URD|-1.9,-1,-0.5,-1.1,-2.2,-2.1,-1.4,-0.6,0.1,-0.3,0.9,0.9,2.4,1.6,1.4,0.9
Kinera Nanna|0.4,1.1,1.5,1,-0.1,-0.2,0.5,1.2,1.8,1.2,-0.9,2.1,4.8,-2.5,0.6,-3.2
Kinera Nanna 2.0|-1.8,-1.3,-0.9,-1.4,-2.1,-1.4,-0.1,0.9,1.9,0.1,-2.2,2.7,4.3,5.1,3.3,-0.9
Kinera Norn|0.1,0.2,-0.1,-0.9,-1.8,-0.5,1.3,3.9,5.1,2.1,-4.2,-1.5,-1.6,-1.4,1.7,0.7
Kinera Odin|1.7,1,-0.3,-2,-4,-3.9,-2.6,-0.7,0.7,-2,-5.2,1,5.1,3.7,1.8,3.8
Kinera SEED|4.3,4.3,1.4,-1.7,-5,-5.7,-4.3,-2.3,-0.1,-0.8,-2.9,2.5,-3.6,-2.7,4.3,4.3
Kinera SIF|0.5,0,-1.2,-2.6,-4.4,-3.9,-1.9,1,5,4.4,2,2.1,3.7,-1,-2.5,-3
Kinera Skuld|2.8,2,0.8,-0.9,-3.2,-3.9,-3.2,-2.1,-1.2,-2.8,-3.4,0.6,1.6,3.5,4.8,3.4
Kinera Tyr|-5.3,-4.2,-3,-2.8,-2.3,-0.5,1.4,3.2,4.3,4,3.4,-2.7,1.2,1.1,0.9,5.4
Kingsound KS-H04|5.4,2.7,1.1,0.2,-1.3,-2.2,-2,-2.7,-3.7,-5.4,-2.1,3,2.2,2,3.9,-1.1
Kitsound Mini|-8.2,-7.7,-7.4,-7.7,-7.8,-5.5,-2.9,0.1,4.1,7.3,8.5,8.5,8.5,5.9,3.6,7
Kiwi Ears Cadenza|-1.3,-1,-0.9,-1.5,-2.2,-1.7,-0.6,0.4,1.3,0.6,0.3,1.3,0.4,3.1,1.5,-0.8
Kiwi Ears Dolce|0.1,0,-0.7,-1.7,-2.9,-2,-0.3,1.6,3.3,2.7,2.4,1.9,0.4,3.1,0.9,-4
Kiwi Ears Melody|0.3,0,-0.9,-1.9,-2.9,-1.7,0.1,2,3.6,1.7,1.5,2.4,4.2,4.5,1.3,-4.7
Kiwi Ears Orchestra|2.1,1.9,1.3,0.1,-1.5,-2.1,-1.8,-1.1,-0.1,-0.5,-1.4,0.4,3.2,4.2,-0.1,-1.2
Kiwi Ears Orchestra Lite|0.1,-0.1,-0.8,-1.6,-2.2,-0.9,-0.6,-0.6,-0.3,-1.1,-1.3,0.6,4.7,2.8,1.3,0.2
Kiwi Ears Quartet (off-off)|-1,-1.3,-2.2,-3.4,-4.6,-2.9,-0.4,0.9,1.8,2.1,2.9,3.3,3.7,2.7,-0.1,-1.2
Kiwi Ears Quartet (off-on)|-0.4,-0.7,-1.6,-2.8,-4,-2.6,-0.1,1.1,1.6,1.1,0,1.6,2.7,1.8,1.2,0
Kiwi Ears Quartet (on-off)|-2.2,-2.6,-3.4,-4.6,-5.8,-4.1,-1.6,-0.2,0.9,1.3,2.9,4.9,5.7,4.8,2.2,0.9
Kiwi Ears Quartet (on-on)|-1.7,-2,-2.9,-4.1,-5.3,-3.6,-1.2,0,0.9,0.7,0.6,3.2,4.8,4,3.3,1.9
Kiwi Ears Quintet|-1.4,-0.7,-0.3,-0.7,-1.2,-0.9,-0.2,0.3,1.1,1,1.1,2.3,2,1.3,0.9,-1.4
KLH Ultimate One|-0.1,-0.7,-1.1,-2.7,-4.7,-4.8,-1,3.3,4.9,6.6,4.1,-1.9,0.5,2.4,0.4,0.3
Klipsch Heritage HP-3|1.2,0.4,-1.2,-2.7,-4.1,-4,-2.5,-0.9,3.8,3,0.7,6.6,5.2,-1.2,-0.2,-1.7
Klipsch Image One|4.9,4.9,3,0.6,-1.7,-2.1,-1.1,-0.5,1.3,-0.9,-1,-3.6,-4.3,2.8,-1.4,-1.2
Klipsch Image One B|-3.6,-4,-4.5,-4.7,-5.2,-4.4,-1.1,3.7,6.5,3.8,2.2,-1.6,-2.1,3.8,6.9,5.4
Klipsch Image S2|-1.7,-1.5,-1.8,-2.5,-3.1,-1.6,-0.1,1.7,2.6,1.2,1.9,-1.7,3.4,-0.4,2.6,5.7
Klipsch Image S4|-1.5,-1.8,-2.9,-4.2,-5.6,-4.6,-2.7,-0.3,2.4,3.2,4,3.9,4.4,3.3,6.7,3.3
Klipsch Image S4i|-1.7,-1.8,-2.7,-4.1,-5.6,-4.7,-2.6,0.1,2.8,3.3,4.1,4.9,4.4,3.5,3.1,2.4
Klipsch Mode M40|-0.9,-2,-2.4,-2.6,-3.4,-3.4,-2.4,-2.6,-2.5,-4.4,-7.3,2.9,2.3,6.6,6.6,6.6
Klipsch R6 II|-4,-3.9,-4.4,-5.4,-6.3,-4.8,-2.8,-0.2,2.4,2.8,3.3,2.9,6.2,3.9,4.7,5
Klipsch Reference On Ear|-0.9,-1.3,-2,-2.6,-3.9,-4.7,-3.4,-3.3,-0.8,2.6,5.2,5.3,0,5.7,0.7,2.6
Klipsch Reference ONE|-1.5,-2.2,-3.6,-4.1,-5.5,-4.5,-1.7,1.8,4.2,1,0.7,-0.8,-0.5,4.6,5.1,4.9
Klipsch S4|-2.1,-2,-2.5,-3.5,-4.5,-4.4,-2.2,0.5,3.5,5.1,6.1,6.1,6.1,4.5,0.8,0.5
Klipsch T5 II True Wireless ANC|-0.6,-1.1,-1.8,-2.7,-3.4,-2.3,-0.3,2.3,4.8,2.3,1.8,0.5,0.2,-2.4,1.4,-0.2
Klipsch T5 II True Wireless Sport|-2,-0.7,0.2,0,-0.8,-0.4,0.6,1.7,2.6,-2,-3.7,-0.8,5.8,5.2,-2.8,1.7
Klipsch T5 True Wireless|-3.3,-2.2,-1.3,-1.7,-2.4,-1.9,-1,-0.2,0.5,-0.8,-4.3,-2.7,6.3,6.4,4,4.3
Klipsch X-10i|0.8,0.4,-0.7,-2.4,-4.5,-4.9,-4,-2.7,-1.2,-1,0,0.6,2.4,5.4,2.8,3.9
Klipsch X10|0.4,0.1,-1.2,-2.8,-4.7,-5.2,-4.3,-3.4,-1.6,-1,0.5,1.4,2.9,5.3,2.9,4.8
Klipsch X12i|0.5,0,-1.2,-2.9,-4.9,-5.3,-4.5,-3.4,-1.2,-0.5,1.1,2.5,1.8,5.9,4.5,1.2
Klipsch X20i|0.9,0.5,-0.8,-2.3,-4.3,-4.8,-3.9,-3,-1,-0.6,0.4,0.4,4.6,4.7,3.3,-0.1
Klipsch X6i|2.4,1.8,0.4,-1.4,-3.6,-4.4,-3.8,-3.1,-1.5,-1.4,-0.5,0.3,1.9,1.7,1,2.2
Klipsch XR8i|-4.4,-4,-4,-4.4,-4.8,-3.7,-2.5,-1.4,0.2,0.2,1.3,1.9,3.7,6.6,6.7,6.7
Kojo Technology KJB-01 Keyagu|-0.2,-1.3,-2.9,-4.4,-6,-5,-2.5,0.7,4.6,5.6,4.9,1,-2.5,-3.1,3,5.7
Koss BT540i (wired)|1.3,-1,-3,-3.7,-5.4,-4.8,-1.1,3.1,3.1,2.4,2.2,-1.4,-3.8,6.3,2.8,-2.3
Koss BT540i (wireless)|-3.1,-3.9,-4.3,-4.3,-4.4,-3.1,0.5,4.1,3.8,3.1,3.4,0.2,-2,5.6,4.3,0.9
Koss ESP950|4.8,4.7,4,1.6,-0.4,-1.8,-1.3,-2,-2.5,-3.8,-3.3,0.9,-1.8,2.1,-2,-1.5
Koss ESP950 (sample 1)|4.9,4.9,4.2,1.2,-0.4,-1.7,-1.3,-1.8,-2.4,-3.7,-3.3,0.5,-2.1,1.8,-1.6,-1.2
Koss ESP950 (sample 2)|4.8,4.5,3.7,2,-0.4,-1.9,-1.4,-2.3,-2.6,-4,-3.2,1.7,-1.6,2.4,-2.3,-1.8
Koss ESP95X|4.4,4.2,1.7,0.6,-1,-2.1,-1.9,-2.7,-3.5,-5.7,-2.5,1,1.7,1,2.2,1.3
Koss ESP95X (Vesper earpads)|5,4.5,2.9,0.9,-1,-2.3,-2,-2.4,-2.4,-3.9,-1.6,-1.5,0.2,3.6,0.4,-2.1
Koss KDE250|5.1,5.1,5.1,5.1,3.5,1.3,1.9,2,1.7,-0.5,-4.4,-15.3,-5.3,2,-7.6,-6
Koss KEB90|-7,-5.5,-4.3,-4.1,-3.7,-1.3,0.6,2.9,5.1,5.7,6.3,8.4,7.3,-0.8,1.5,1.5
Koss KPH30i|5.1,5.1,0.8,-1.5,-3.5,-3.3,-1.8,-0.8,-0.3,-0.7,-1.9,-1.5,1.7,-3.4,0.4,0.9
Koss KPH30i (Grado G Cushion earpads)|5.7,5.4,1.8,-1.1,-3.1,-2.4,-0.1,1.4,2,2.2,2.2,1.8,-1.1,-5.4,-5.8,-5.1
Koss KPH30i (Grado GS1000 earpads)|5.7,5.4,0.4,-2,-3.4,-2.3,-0.1,1.3,2.2,1.9,1.5,0.4,1.1,-6.9,-3.3,-3.3
Koss KPH40|5.3,2.2,-1.5,-3.2,-4.7,-4,-2.2,-1,-0.3,-0.6,-1.6,-0.7,3.8,-2.2,2.3,4.1
Koss KPH7|4.7,4.7,4.7,4.7,1.3,-6.4,-5,-2.1,-1.3,-0.9,-1.2,-10.1,-1.6,-0.1,2.7,-0.5
Koss KSC75|5.1,5.1,5.1,2.5,-0.4,-1.1,0.2,0.9,1.1,0.3,-0.9,-4.2,-3.4,-4.6,-2.7,-1.9
Koss KSC75 (Venture Electronics earpads)|5.1,5.1,2.5,-0.1,-2.5,-2.6,-1.2,-0.3,0.1,-0.2,-1.6,-4.4,-1.4,-3.2,-1,-0.3
Koss KSC75X (Yaxi earpads)|5.4,5.4,3.3,0.3,-2.4,-2.5,-1,0.2,1.1,1,0.4,-1.2,-0.2,-2.8,-0.7,-3.2
Koss Porta Pro|4.1,0.6,-2.4,-3.8,-5.2,-4.5,-2.7,-1.4,-0.8,-1,-1.7,0.2,3.2,-2.2,4.4,4.3
Koss Porta Pro (Dekoni earpads)|5.3,3.2,-0.8,-2.8,-4.9,-5,-3.3,-2.2,-1.1,-1.1,-1.3,0,2.7,2,3.7,2.8
Koss Porta Pro (sample 1)|5.5,3.3,-1.1,-3.3,-4.8,-3.6,-1.7,-0.2,0.4,0.1,-0.5,-0.4,2,0.3,3.9,0
Koss Porta Pro (sample 2)|5.5,3.5,-1,-3.3,-4.6,-3.4,-1.8,-0.2,0.5,0.2,-0.2,-1.2,0.5,-1.8,5.2,2
Koss Porta Pro (Yaxi earpads)|5.5,2.9,-0.7,-2.6,-4.2,-3.4,-1.7,-0.2,0.9,1.2,1.2,1,1.3,-4.3,-0.1,0.3
Koss Porta Pro Aniv Ed|5.5,3.3,-1.1,-3.3,-4.8,-3.6,-1.7,-0.2,0.4,0.1,-0.5,-0.4,2,0.3,3.9,0
Koss Porta Pro KTC|4.7,1.4,-1.8,-3.4,-4.8,-3.7,-2.1,-0.7,0.1,0.1,-0.8,-1.1,1.3,-2,0.8,3
Koss Porta Pro Wireless|2.1,-0.2,-2.5,-3.7,-5,-4.2,-2.6,-1.2,-0.3,-0.1,-0.7,0.8,3.4,-0.9,2.4,4.9
Koss PRO DJ 100|5.1,5,2.9,1.8,-3.3,-5.4,-4,-5.4,-2.7,-1.1,1,-2.3,-2.4,4.7,3.2,-2.1
Koss PRO DJ100|3,3.6,2.5,-0.3,-2.5,-1.6,2.9,-1.3,-0.5,-0.3,0.1,-1.5,-2,-1.8,-0.1,0.8
Koss Pro4AA 1975|4.8,4.8,3.9,0.8,-3.2,-7.6,-8.7,-2.6,2.9,-0.6,-4,-4,-0.1,4.8,4.6,0.7
Koss Pro4AA 2014|4.8,4.8,4.8,4.8,4.8,-0.3,-3.9,-6.3,-3.4,-1.4,-4.7,-10.5,-1.1,-0.3,2.7,0.2
Koss Pro4AAA|2.5,0.9,-1,-3.5,-6.4,-6.8,-5.3,-2.2,1,2.4,3.6,-1.7,-1,4.3,3.9,3.1
Koss Pro4S|0.6,-0.8,-2.1,-2.4,-1.3,3.1,-1.2,-2.2,-2.3,-2.1,-1.6,-4.1,-4.6,5.7,5.5,4.5
Koss QZPro|6,2,-4.7,-6.5,-6.8,-6.2,-4.9,-4.8,-2.7,-5.4,0.6,6,6,6,3,2.3
Koss SP330|-1.6,-1.8,-2.1,-2.7,-2.5,-0.5,0.3,-1.5,0.2,1.1,3.3,2.9,-1.3,0,2.3,3.3
Koss SP540|-0.2,-1,-0.8,-2.4,-5.4,-3.5,-0.3,1,2.4,3.3,3.9,0.8,-0.9,-0.2,2.8,3.1
Koss SportaPro|5.2,4.7,0.6,-1.1,-2.3,-2.1,-0.7,0.4,1,0.5,0.2,-1.6,-5.4,-3.1,1.8,0
Koss Tony Bennett|3.6,1.4,-0.4,-0.8,-5.2,-6.3,-3.7,-5.8,-3.3,-1.4,2.2,1.1,-0.3,5,2.8,0.7
Koss UR 20|4.7,4.7,4.7,2.6,-0.5,-2.7,-6.2,-9.8,-3.3,2.1,4.7,2.3,-0.3,-1.6,-4.6,0.3
Koss UR 29|5.3,5.3,3.9,1.5,-0.9,-2.5,-2,-2.3,-4.1,-4.1,-2.3,-2.3,0,4.4,-0.5,-1.5
Koss UR20|2.1,1.7,0.5,-0.6,-2.8,-2.9,-3.3,-4.2,-2.3,0.8,5.3,5.2,3.9,-0.7,-6.4,-0.6
KRK SYSTEMS KNS 6400|5,5,5,2.5,-0.9,-2.3,-2.2,-2.1,-0.2,-0.7,-0.1,-3.5,-3.4,3.6,-0.2,-1.8
KRK SYSTEMS KNS 8400|4.9,4.9,4.2,-1.4,-4.3,-3.4,1.1,3.1,2.4,1.6,-0.2,-4.4,-5.7,1.4,-0.2,-0.9
Ks Earphone K64|4.6,4.6,4.6,4.6,2.5,2,1.3,3.8,1.7,-3.4,-10.4,-6.6,-2.7,-5.5,-3.5,-2.9
Kumitate Lab KL-Corona|2.8,1.3,-0.4,-2.1,-3.9,-4,-3.1,-1.9,-0.4,-0.5,-1.7,-1.5,1,2.1,0.6,4.5
Kumitate Lab KL-Focus (max bass)|-0.9,-0.9,-1.1,-1.8,-2.5,-1.3,-0.2,1,1.6,0.1,-2.3,1.7,2.9,-1,0.7,2.5
Kumitate Lab KL-Focus (min bass)|4.4,4.4,3.9,2.6,0.4,-0.6,-0.8,-0.7,-0.8,-2.8,-5.3,-1.8,0.2,-3.8,-2,-0.4
Kumitate Lab KL-Focus (setting 2)|2.5,2.5,2.1,1.1,-0.3,-0.4,-0.2,0.1,0.1,-1.8,-4.3,-0.8,0.9,-3.2,-1.4,0.3
Kumitate Lab KL-Focus (setting 3)|0.7,0.7,0.5,-0.3,-1.3,-0.7,0,0.6,0.8,-1,-3.5,0.2,1.8,-2,-0.4,1.3
Kumitate Lab KL-Kanon 6-Minus|4,4,4,4,1.5,-0.9,-1.3,-1.2,-0.7,-1.4,-3.6,-1.9,-4.1,-10.1,-1.5,4
Kumitate Lab KL-Kanon 6-Plus|0.3,0,-1.2,-2.8,-4.9,-4.9,-3.3,-0.9,2.7,5.3,4.6,3.2,0.3,-7.1,-0.1,5.3
Kumitate Lab KL-Meteo|-3.5,-3.4,-3.6,-3.9,-3.7,-0.5,2.2,3.2,1.6,-1.5,-3.1,0.5,1.9,3.4,3.2,6.1
Kumitate Lab KL-REF Type-S|1.4,1.4,0.9,-0.5,-2.4,-3,-2.8,-2.5,-1.7,-2.2,-3.2,0.6,2.7,-1.7,2,4.7
Kumitate Lab KL-REF Type-S (half bass)|-0.8,-0.6,-0.6,-1.6,-2.9,-2.7,-2.2,-1.8,-0.9,-1.2,-2.2,1.4,3.6,-0.7,3.6,5.3
Kumitate Lab KL-REF Type-S (max bass)|-5,-4.6,-4.3,-4.8,-5.1,-3.2,-1.2,-0.3,1.4,1.4,0.6,4,5.7,1.6,6,6.6
Kumitate Lab KL-Sanka|0.7,0.1,-1.3,-3.2,-5.6,-6.4,-5.7,-4.2,-2,-0.4,2.2,4.1,5.2,4.1,3.7,5.2
Kumitate Lab KL-Sanka B|-1.3,-1.6,-2.1,-2.9,-3.3,-1.1,0.3,1.6,3.2,3.5,2.6,2,5.2,1.5,-5.1,-4.8
Kumitate Lab KL-Sanka K|0.2,-0.1,-1.2,-2.8,-4.4,-3.1,-0.9,1.4,3.6,4.2,4.8,1.9,5.6,-0.6,-5.2,-4.8
Kumitate Lab KL-Sirius|3.8,2.8,1.5,-0.2,-2.5,-3.5,-3.1,-2.5,-1.9,-3.5,-3.8,0.7,-0.2,0.4,0.5,4.2
Kumitate Lab KL-Sirius (CIEM)|3.9,3,1.7,0,-2.4,-3.4,-3.3,-2.8,-2.4,-4,-4.5,0.3,0.6,2.6,1.3,1.7
Kumitate Lab Trio (half)|1.2,1.1,0.7,-0.5,-1.4,0.1,1.9,4.2,6.1,4.6,-2.6,-3.5,2.2,3,-6.7,-5.6
Kumitate Lab Trio (max)|2.8,2.7,2.2,1,0.2,1.5,3.4,5.7,6.4,6.1,-3.8,-5.8,0.2,0.9,-9.1,-8.5
Kumitate Lab Trio (min)|-1.4,-1.4,-1.9,-3,-4,-2.5,-0.6,1.6,3.4,2.1,-2.8,0.5,5,6.5,-1.4,0.4
KZ AS06|-1.7,-1.9,-2.7,-3.9,-5,-3.8,-1.7,0.9,3.7,4.6,4.7,0.7,3.3,-2.4,-1.2,1.3
KZ AS10|-1.7,-2,-2.5,-3.5,-4.5,-3.4,-1.5,0.7,3.2,3.8,3.7,0.3,-0.8,-3.6,0.6,3.4
KZ AS16|3.3,2.8,1.7,0.4,-1.1,-0.7,0.7,1.8,1.1,-3.5,-5.6,-4.5,1,0.7,0.4,-0.4
KZ ASF|-2.3,-2.5,-2.8,-3.7,-4.4,-2.6,-0.2,2.7,4.6,1.2,-4.8,-1.3,1.5,-2.6,6.3,6.3
KZ AST|-4.1,-4,-4.7,-5.3,-5.7,-3.7,-0.9,2.2,4.3,2.7,0,2.1,4.6,-2.2,6,6.6
KZ ASX|-1.1,-1.4,-2,-3.2,-4.2,-2.8,-0.5,2.2,4,0.7,-4.6,0.1,2.2,-2.8,6.2,6.2
KZ ATE|-0.6,-1.3,-2.3,-3.3,-4.2,-2.8,-0.8,1.6,3.8,4,3.5,1.6,0.3,-2.1,-1.1,-0.8
KZ BA10|1,0.7,-0.2,-1.4,-2.9,-2.3,-1.1,0.6,2.3,2,0.5,-2.9,1.2,-3.8,-0.3,0.9
KZ Castor (off-off-off-off)|-1.7,-2.8,-4.3,-5.4,-5.4,-0.5,3.5,4,3.9,3.2,2.5,1.7,1.2,-0.4,0,-1.4
KZ Castor (off-off-on-off)|-4.9,-5.5,-5.8,-5.8,-4.2,1.5,3.6,4,4.4,3.5,2.8,1.4,3.7,2.9,1.9,0.1
KZ Castor (off-off-on-on)|0,-1.1,-2.5,-3.5,-3.2,2.6,3.8,2.3,2.1,1.5,0.9,0.2,-0.4,-2.1,-1.7,-3
KZ Castor (on-n-off-off)|-3.5,-3.9,-4.7,-5.2,-4.6,0.8,3.9,4.2,4.2,3.4,2.7,1.9,1.4,-0.2,0.2,-1.1
KZ Castor (on-off-off-off)|-5.7,-5.9,-5.9,-5.9,-4.6,0.8,3.6,4.5,4.8,3.8,3,1.7,4.1,3.6,2.2,0.2
KZ Castor (on-off-on-off)|-5.2,-5.5,-5.5,-5.5,-3.9,1.5,3.5,3.8,4.3,3.4,2.6,1.3,3.8,3.5,2.1,-0.1
KZ Castor (on-on-off-off)|-6.3,-6.5,-6.2,-6,-4.2,1.3,3.9,4.5,4.9,3.9,3.1,1.8,4.2,3.8,2.4,0.4
KZ Castor (on-on-on-off)|-6.1,-6.2,-5.9,-5.5,-3.4,2.3,3.7,3.8,4.3,3.5,2.7,1.5,3.9,3.4,2.1,0.1
KZ Castor (on-on-on-on)|-1.7,-2.2,-2.7,-3.2,-2,4.1,4,2.5,2.4,1.9,1.2,0.6,0,-1.9,-1.3,-2.6
KZ Castor Improved Bass Version (off-off-off-on)|2,0.8,-0.7,-1.7,-1.7,2.1,2.4,1.4,1.2,0,-0.3,-0.8,-0.4,-1.6,-1.7,-2.8
KZ Castor Improved Bass Version (off-off-on-off)|1.1,-0.2,-1.7,-2.8,-3.1,0.8,2.7,2.4,2.2,1,0.7,-0.1,0.1,-1.4,-1.1,-2
KZ Castor Improved Bass Version (off-off-on-on)|2.3,1.2,-0.3,-1.3,-1.3,1.8,1.8,0.9,0.9,-0.1,-0.5,-0.9,-0.2,-1.4,-1.6,-3.3
KZ Castor Improved Bass Version (off-on-on-on)|0.6,0.1,-0.6,-1.2,-0.7,3,2.6,1.5,1.3,0.2,-0.2,-0.6,-0.2,-1.5,-1.6,-2.6
KZ Castor Improved Bass Version (on-off-off-off)|-0.4,-1.3,-2.4,-3.5,-3.8,0,2.8,3.3,3.1,1.9,1.4,0.5,0.7,-0.8,-0.5,-1.4
KZ Castor Improved Bass Version (on-on-off-off)|-1.3,-1.9,-2.7,-3.4,-3.3,0.7,3.3,3.5,3.2,2,1.6,0.7,0.8,-0.7,-0.4,-1.3
KZ Castor Improved Bass Version (on-on-on-on)|0.5,0,-0.7,-1.3,-0.7,3.1,2.6,1.5,1.3,0.2,-0.1,-0.6,-0.2,-1.4,-1.5,-2.7
KZ D-FI (off-off-off-off)|0.5,0.5,-0.1,-1.3,-2.8,-2.5,-1.5,-0.4,0.6,0,-0.7,-0.2,0,2.7,2.5,0.8
KZ D-FI (off-off-off-on)|2,1.8,0.9,-0.6,-2.5,-2.7,-1.9,-0.9,0,-0.7,-1.2,-0.4,-0.2,2.2,1.9,0.2
KZ D-FI (on-off-off-off)|0,0.3,-0.1,-1.2,-2.5,-2.3,-1.3,-0.2,0.8,0.1,-0.5,0,0.2,2.8,2.6,0.9
KZ D-FI (on-off-off-on)|1.1,1.5,1.2,0.4,-0.9,-1.1,-0.5,0.3,0.7,-0.2,-1.1,-1.7,-2.1,0,1.5,-1.5
KZ D-FI (on-off-on-off)|0.7,1.2,1.2,0.4,-0.9,-1,-0.4,0.4,0.8,-0.2,-1.1,-1.6,-2,0.1,1.4,-1.6
KZ D-FI (on-off-on-on)|0.5,0.9,1,0.3,-0.8,-0.9,-0.3,0.5,0.8,0,-1,-1.5,-2,0.1,1.7,-1.3
KZ D-FI (on-on-off-off)|-1,-0.4,-0.4,-1.1,-2.2,-1.9,-1,0,1,0.2,-0.3,0.2,0.3,3,2.9,1.1
KZ D-FI (on-on-off-on)|0.6,1,1.1,0.4,-0.8,-0.9,-0.4,0.4,0.8,-0.1,-1.1,-1.6,-2,0.1,1.5,-1.5
KZ D-FI (on-on-on-off)|-2,-1.3,-1,-1.5,-2.2,-1.5,-0.6,0.4,1.3,0.5,0,0.5,0.6,3.3,3.1,1.4
KZ D-FI (on-on-on-on)|-1.5,-0.9,-0.5,-1.1,-1.9,-1.4,-0.7,0.3,1.1,0.2,-0.3,0.5,0.6,2.9,2.6,1.1
KZ DQ6|1.1,0.8,0,-1.3,-2.8,-2.3,-1.1,0.4,1.4,0.5,-2.3,-0.6,4.8,-2.6,-2.6,3
KZ DQ6S|-2.7,-2.4,-2.1,-2.5,-3,-1.8,-0.5,0.7,1.7,0.7,-1.1,3.3,5.4,1.7,2.1,4.3
KZ E10|2.9,2.4,1.4,-0.6,-1.1,-0.8,-0.1,-0.1,0.8,0.1,-3.1,-6,-1.3,-1.1,3.2,1.4
KZ ED Special Edition|-3.8,-2.7,-1.8,-1.8,-1.8,0.2,2.1,4.3,6.3,6.3,4.8,2.6,-1.5,-3.5,-0.2,1
KZ ED16|-2.7,-2.3,-2.2,-2.8,-3.5,-2,-0.4,1.5,3.1,1.9,-1.6,2.8,2.3,7.1,1.2,-2.4
KZ ED9 (balanced filter)|-4.9,-4.5,-3.9,-3.8,-5,-2.6,-0.3,2.1,4.1,4.4,3.9,1,1.2,-3.1,6.7,6.3
KZ EDR1|-5.3,-4.4,-3.5,-3.4,-2.9,-1.1,1.1,3.4,5.3,5.1,4.3,0.4,2.7,-1.8,2.2,3.5
KZ EDSE|-3.9,-2.8,-1.9,-1.9,-1.8,0.6,2.2,4.5,5.8,6.3,6,4,-1.2,-0.7,-3.5,-2.2
KZ EDX Pro|-2,-1.8,-2,-2.7,-3.4,-1.9,-0.1,1.9,3.7,3,0.3,-0.4,4.7,-1.3,3.7,2.1
KZ EDX Ultra|-1.2,-1,-1.1,-1.8,-2.4,-1.2,0.5,2,3.1,1.7,0.6,2.5,3.7,3.1,2.9,-3.8
KZ EDXS|-1.6,-0.6,0.1,-0.3,-0.8,0,0.9,1.7,2.2,1,0.1,-1.5,-0.1,-2.2,2.3,4.8
KZ ES4|-2.8,-2.9,-3.1,-3.2,-3.1,-1.7,0.2,2.1,3.8,3.2,0.5,-2.5,-1.3,5.5,7.5,2.4
KZ ESX|-2.3,-2.1,-2.2,-2.9,-3.6,-2.3,-0.8,0.8,2,1.3,0.1,0.3,3.2,2.4,1.7,2.7
KZ Krila|0.9,0.9,0.7,0.1,-0.9,-0.6,0.1,0.7,1.2,-0.4,-1.2,-0.6,-0.5,1.7,-0.2,-3
KZ Lin Long|-3.2,-1.8,-0.8,-1,-1.5,-0.9,0.1,1,1.4,0,-0.8,0,2,1.4,2.6,2.5
KZ PR1|-0.4,-0.3,-0.4,-1,-1.8,-0.5,1.1,2.8,4.2,3.7,1.8,0.5,3.3,4.2,2.2,-5.1
KZ PR1 Pro|-0.4,0,0.1,-0.5,-1,0,1.2,2.5,3.4,2.2,1.6,2.1,5,1.8,0,-6.8
KZ PR2|-0.7,0.3,1,1,0,0.8,1.5,2.3,2.8,1.3,-0.4,-1,1.3,0,1.3,-2.7
KZ PR3|-0.1,0.7,1.3,0.9,0.2,0.8,1.7,2.6,3,1.4,0.1,-1,2.6,1.6,-1.9,-6.5
KZ S2|2.5,0.9,-0.9,-2.4,-4.1,-3.7,-2.2,0.1,2.8,3.3,2.1,1.7,-2.3,-4.1,-0.1,0.5
KZ S4|-5.4,-4,-2.8,-2.8,-2.6,-0.6,1.5,3.5,5.8,5.3,3.4,-0.3,0.2,-7.6,4.3,7.6
KZ SK10|-3.7,-3.7,-3.4,-2.7,-3.3,-1.4,-0.4,1.6,4.6,5.6,5.2,2.8,-0.1,-5.5,-1.6,3.3
KZ SKS|-3.7,-4.7,-4.6,-3.5,-2.8,-1.7,-0.4,1.6,4.5,3.5,3.7,0.7,-0.8,-5.4,3.4,5.4
KZ VXS Pro|-0.2,-0.8,-1.4,-3,0.4,1.2,1.3,1.5,2.4,1.7,1.3,-0.3,-0.2,0.1,-1.4,-3.6
KZ x Crinacle CRN|-0.9,-0.2,0.2,-0.5,-1.6,-1.7,-1.2,-0.6,0.1,-0.3,-1.3,1.1,0.8,1.8,1.3,3.9
KZ x HBB PR2|-1.6,-0.6,0.1,-0.1,-0.8,0.1,1.2,2.2,2.7,1.5,0.5,-0.6,1.6,-0.6,1.9,0.4
KZ x HBB PR2 (with black mesh)|-0.7,0.3,1,0.7,0.2,0.8,1.6,2.4,2.7,1.5,-0.1,-1.2,0.3,-1.3,1.2,-1.4
KZ x HBB PR2 (without black mesh)|0.3,1.4,2.2,1.9,1.1,1.4,2.1,2.7,2.9,1.6,0.5,-1.3,2.4,0.4,-0.8,-6.4
KZ ZAS|-3.4,-2.9,-2.5,-2.8,-3.1,-1.6,0,1.7,3.2,1.1,-1.6,-0.3,6.1,4.3,1.8,4.7
KZ ZAX|0,-0.2,-0.9,-2,-3.2,-2.4,-0.7,0.9,2.4,1.7,-0.7,-2.2,2.7,2.1,1.8,0
KZ ZES|-4.1,-3.2,-2.3,-2.3,-2.5,-1,0.5,1.7,2.4,1.2,0.5,6.3,6.8,0,-1.6,0.8
KZ ZEX|2.3,1.5,-0.2,-2,-4.4,-4.7,-3.1,-0.7,1.9,0.6,-2.8,2.5,5.2,-0.2,1.5,0.4
KZ ZEX Pro|-1.8,-0.5,0.4,0.2,-0.9,-0.7,-0.3,0.3,0.7,0.1,-0.2,2.6,1.4,1.7,-0.6,2.4
KZ ZS10|-0.3,-0.4,-0.8,-1.1,-3.3,-2.5,-0.8,1,2.1,0.4,-1.9,-1.9,1.2,0.2,1,4
KZ ZS10 Pro|0.2,0,-0.9,-2.1,-3.4,-2.6,-1,0.8,2.4,1.8,-0.7,-1.9,2.8,0.3,2.3,0.7
KZ ZS10 Pro X|-1.7,-1.4,-1.5,-2.1,-3,-1.8,-0.4,1.2,2.2,1.6,-0.1,0.2,1.2,4.3,0.1,-0.9
KZ ZS3|-2.8,-2.7,-3.2,-4.2,-5.2,-3.9,-1.9,0.5,3.5,4.4,3.7,2.5,3.6,-3,3.3,5
KZ ZS4|-4.6,-4.1,-3.6,-3.9,-3.8,-1.8,0.5,3.2,5.5,6.2,5.8,2.7,-0.7,3.2,0.3,-4.1
KZ ZS5v1|-0.2,-0.7,-1.6,-2.8,-4.2,-3.4,-1.9,0,1.7,1.4,-0.4,-2.6,4.1,6.5,3.1,0.1
KZ ZS5v2|0.3,-0.3,-1.3,-2.5,-3.6,-2.4,-0.5,1.7,3.8,3.5,0.9,1.7,4.2,7.4,-0.8,-8
KZ ZS6|0.1,-0.3,-1,-2.1,-3.4,-2.6,-1,0.9,2.7,2.5,1.9,0,0.6,7.2,1.5,-5.8
KZ ZS7|-2.6,-2.6,-2.9,-3.6,-4.5,-2.9,-1.1,0.9,2.5,1.6,-1.3,2,1.4,3.8,2.8,3.2
KZ ZSE|-4.2,-3.8,-3.7,-4,-3.9,-2.7,-0.3,2.1,4.4,4.6,4.8,1.7,1,-4.5,2.1,2.4
KZ ZSN|0,-0.4,-1.1,-2.1,-3.1,-2.2,-0.4,1.5,3,1.3,-1.1,-1.9,-2.4,3.9,-0.6,-0.3
KZ ZSN Pro|0.5,0.4,-0.2,-1.2,-2.3,-1.1,0.5,2.3,3.6,2.4,-0.1,-0.4,3.9,-3,-1.2,-3.6
KZ ZSN Pro X|0.7,0.5,-0.3,-1.4,-2.5,-1.5,0.3,2,3.6,2.9,0.4,-1.1,3.5,-2.6,-0.2,-2.6
KZ ZSR|0.5,-0.2,-1.2,-2.4,-3.7,-2.5,-0.9,1,2.6,2.6,0.3,4.4,5.4,5.6,-2.9,-6
KZ ZST|-0.2,-0.3,-0.7,-1.4,-1.8,-1.2,0.3,1.7,2.6,1.1,-1,-0.3,1.2,1.8,-0.7,-2.1
KZ ZSX|0.4,0.1,-0.6,-1.7,-3,-2,-0.4,1.3,2.7,1.8,-0.7,-2.2,2.6,-0.3,4.9,0.2
KZ ZVX|-2,-1.6,-1.4,-2,-2.6,-1.7,-0.4,0.9,2.1,1.2,0.6,1,1.8,1.9,-0.2,0.1
Lafitear LM1|0.8,0,-1.4,-2.7,-4.2,-3.5,-1.5,1.2,4.1,4.2,4.7,3.7,4.4,-0.3,-6.7,-7.4
Lafitear LM2|-1,-1.5,-2.6,-3.8,-4.7,-3.8,-1.8,0.9,3.7,4.3,4.8,2.6,3.6,0.4,1,-3.2
Lark Studio LSX|-1.1,-1.8,-2.7,-4.1,-5.7,-5.3,-3.7,-1.6,0.6,0.8,-0.3,3.9,6.1,3.3,3.2,6.1
Lear LCM BD4.2|4.1,4.1,3.3,1.7,-0.5,-1.3,-1.1,-0.8,-0.2,-1.2,-3.4,-5.1,-3.5,-4.3,0.9,4.1
Lear LUF 4C|2.5,2,0.8,-0.6,-1.4,0.8,2,1.8,1.6,0,-2.8,-4.6,-0.7,-0.6,-2.9,-3
Lear LUF-4F|2.2,1.4,0,-1.4,-2.1,0.3,1.3,1,0.9,-0.8,-3.1,-3.1,-2.5,1.9,-0.4,-1.5
Lear NS-U1 (all Off)|4.1,4.1,4.1,4.1,4.1,2.9,1.7,0.4,-1.4,-4.3,-9.1,-10.9,-10,-3,1.2,4
Lear NS-U1 (NS on)|-5.1,-4.2,-3.6,-3.8,-4.3,-3.6,-2.8,-1.3,7.3,7.5,1.4,-0.8,-4,0.3,7.5,7.5
Lenntek Pro Series|4.1,4.1,3.3,1.4,-1.1,-2.6,-2.6,-2.9,-2.6,-4.4,-6.3,-2.9,-1.6,0.7,2.7,4.1
Lenntek Sonix 3 Headset|4,4,4,2.9,0,-1.6,-1.9,-2,-2.4,-3.6,-4.5,-3.9,-2.4,-1.5,0.1,3.7
Lenovo Thinkplus XT98|-1.5,-3.3,-4.6,-5.3,-5,-1.2,0.7,2.6,3.2,1.8,0.9,-0.3,2.2,-0.6,0.6,5.1
LG Quadbeat HSS-F420|1.8,1.5,0.3,-1.4,-3.6,-3.9,-2.8,-1.6,0.5,1,2,2.1,1.5,3.6,1.7,-0.4
LG Tone Free|2,2.4,3,1.6,-0.6,-1.6,-4,-7.9,-4.2,-1.4,-0.4,-1.1,0.4,0.1,2.5,4.4
LG Tone Ultra (ANC off)|-3.1,-3,-2.8,-3,-2.5,0.1,0.3,0.5,0.9,0.7,2.8,1.1,0.7,-0.7,2.5,1.4
LG Tone Ultra (ANC on)|2.1,0.2,-1.5,-2.6,-3,-1,-0.9,-0.8,-0.3,-0.5,1.7,0,-0.5,-1.9,1.3,0.3
LH Labs Verb|-0.2,-0.1,-0.1,-0.9,-1.5,-0.3,1.4,3,4.6,4,2.8,-0.4,-4.3,-7,1.1,4.1
Libratone Track Air Plus|-4.7,-1.5,2,2.7,1,0.9,-1.4,-3.1,-1,-0.9,-1.3,0.9,2,2.4,4.4,3.1
Life Acoustics PreKickstarter|1.4,0.9,-0.6,-2.2,-4.5,-5.4,-4.8,-3.9,-1.9,-1.1,1.3,2.9,3.6,3.8,1.2,2.2
Light Harmonic Mera|-2.4,-2.5,-2.9,-3.6,-4.1,-2.6,-0.5,2,3.4,2,1.7,0.4,2.2,-4.4,0.3,5.9
Light Harmonic Stella|0.2,-0.1,-1.3,-2.8,-4.8,-4.5,-2.9,-0.7,1.7,1.4,0.2,6.4,5.1,6.8,1.4,-3.1
Lime Ears Aether|1.4,1.1,0.2,-1.4,-3.4,-4,-3.3,-2.2,-1.8,-2.7,-2.5,-0.1,4.3,1.7,0.9,4.5
Lime Ears Aether (bass)|-3.5,-3,-2.7,-3.3,-3.9,-2.8,-1.4,0.1,0.4,-0.9,-0.9,1.4,5.7,3.2,2.4,6
Lime Ears Aether R|4.7,3.3,1.2,-0.8,-3.2,-3.4,-2.2,-0.1,0.3,-1.9,-4,-3.5,3.7,4,-1.3,0.4
Lime Ears Aether R (bass)|2.2,1,-0.7,-2.5,-4.6,-4.4,-2.6,0.1,2,-0.3,-2.6,-2.4,4.4,5.3,-0.1,1.7
Lime Ears LE2|2.8,1.9,0.9,-1.1,-3.6,-4.6,-4.2,-3.1,-1.4,-1.2,-2.6,0.6,5.4,2.8,5.1,5.2
Lime Ears Model X (switch off)|4.6,4,2.7,0.8,-1.8,-3,-2.8,-1.9,-0.5,-0.5,-1.5,-0.4,0.7,-2.9,-2.6,-1
Lime Ears Model X (switch on)|1.2,0.7,-0.6,-2.3,-4.6,-5.4,-4.5,-2.9,-0.4,0.8,1,3.2,3.3,0.2,0.5,2.3
Lindy HF-100|4.4,4,0.4,-1.8,-3.8,-2.5,4.5,0.8,-0.9,-0.8,-1.1,-2.6,-2.5,-4.5,4.2,4.1
Linsoul BLD|5,4.9,1,-2.5,-6.9,-7.8,-9,-7.4,-5.8,-4.3,-0.6,5,5,3.9,5,5
Linsoul Lokahi|0,-0.3,-0.7,-1.8,-2.3,-0.1,0.9,1,1.5,1.6,0,0.1,4.3,-0.2,3.3,0.3
Linsoul x THOR Mjölnir MKII|-2.1,-1.5,-1.5,-2.3,-3.4,-2.8,-1.3,0.8,2.5,3.1,4.2,2.6,0.3,-2.6,1.2,0.8
Lioncast X55|-1,-2,-2.9,-3.2,-4.6,-0.2,-0.6,-1.1,-1,-0.1,4.3,4.3,7,-0.1,1.8,1.9
Little Dot GYFU|2.1,1.4,0.5,0.5,0,-1.1,-0.2,-1,-2.3,-0.7,1.9,-1.7,1.5,-5.2,0.9,2.1
Logitech G Pro Gaming Headset|0.3,-0.2,-0.9,-2.3,-4.7,-1.9,3.2,1.4,2.2,3.2,1.1,0.8,1.4,2.2,-2,-3.1
Logitech G Pro X|0.3,0.6,-2,-6.1,-7.8,-4.3,0.5,0,0,0.5,-2.3,0.2,5.8,3.4,4.6,5.4
Logitech G Pro X (3.5mm jack)|1.5,1.5,-2.1,-2.8,-6.4,-3.7,1.5,1.5,0.7,-0.5,-5.1,-4.1,4.8,5,0.8,4.1
Logitech G Pro X (USB)|-2.1,-1.9,-5.5,-5.3,-5.4,-0.7,6.6,6.5,4.9,4.1,-3.8,-3.4,5,4.5,-1.5,1
Logitech G PRO X 2 LIGHTSPEED|-0.5,-2.3,-2.9,-1.4,0.6,0.3,0.1,-0.2,0.2,-0.4,1.2,0.1,4.8,5.5,-2.6,-2
Logitech G Pro X Gaming Headset|2.6,1.9,-0.8,-2.6,-4.6,-3.3,-0.1,-0.2,-0.5,1,-2.2,-1.1,5.2,2.5,-0.8,1.2
Logitech G PRO X LIGHTSPEED|3.1,2.1,-0.5,-1.5,-1,0.7,1.2,0.9,1.5,0.6,-3.8,-5.3,-1,0.5,-3.1,-1.9
Logitech G333|-2.1,-2.7,-3.7,-5,-6.2,-4.5,-1.3,1.1,3.3,2.9,5.5,5,6.1,2.7,1.6,-0.9
Logitech G335|4.7,2.7,1.3,0,-0.7,1.9,4.7,2.9,-1,-1.6,-2.9,-7.2,-3.1,-3.1,-6.7,1.3
Logitech G430|-0.5,-2.2,-2.2,-2.8,-3.1,-2.1,1.1,2.6,0.4,0.2,0.7,2.3,5.9,-0.5,1.4,2.1
Logitech G431|-1.6,-1.7,1.2,-4.4,-7,-5.6,-3.6,0.2,1.3,2.4,7,7,7,1.4,1.2,1.1
Logitech G432|3.9,4,3.4,1.3,-1.2,-2.7,-0.6,-2.2,-2,-0.9,-1.2,2.3,3.3,-0.1,-2,-2.3
Logitech G433|-1.1,-2.3,-0.8,-1.8,-5.8,-2.1,3.8,1.9,-0.2,0.3,-0.6,2.2,4.1,6.5,0.2,-2.6
Logitech G435 LIGHTSPEED|3.1,0.3,-0.7,-1.6,-1.7,-2,-1.6,0.7,3.1,-0.2,0.3,-3.9,-0.9,1.2,1,-0.1
Logitech G533|0.3,0.4,0.1,-0.6,-1.4,-1.3,-1.1,1.5,3.2,1.9,5.4,2.4,1.2,1.6,-2.1,-3.6
Logitech G535 LIGHTSPEED|3.7,0.7,-1.4,-2.2,-1.9,-0.1,2.5,2.4,0,-0.2,-1.3,-2.2,0.9,-0.9,-0.3,-0.4
Logitech G635|0.2,-0.2,-0.5,-0.7,-2,-1.7,-0.3,1.2,1,1.4,3.1,0.4,1.8,-1.2,-2.4,0.1
Logitech G733 LIGHTSPEED|-0.4,0.1,-0.9,-1.2,-2.4,-0.8,-1.1,-0.3,2.1,-0.1,0.6,-0.4,6,2.7,-0.5,0
Logitech G735|-2.4,-3.3,-4,-2.9,-3.3,-2.7,0.6,2.3,2.3,1,2.9,0,7,2.2,-1.9,-0.6
Logitech G930|-0.9,-1.5,0.6,-2.1,-5.2,0.1,0.1,-0.5,-0.6,-0.8,-0.7,0.2,2.3,-2.3,2.8,5.8
Logitech G933|-2.5,-2.2,-3.2,-2.5,-2,1.5,4.2,4.4,2.9,2.4,2.8,3.2,-0.8,1.6,-1.8,-2.8
Logitech G933s|-0.7,-0.9,-0.5,-4.4,-6.8,-6.7,-3.6,1.2,2.3,-0.1,2.3,2.3,5.8,0,3.5,5.7
Logitech G935|0.6,-0.6,-2.7,-3.2,-2.6,-0.6,0.4,2.9,1.9,1.3,1.6,-1.2,2.3,-0.6,-1.8,0.4
Logitech UE 6000 passive|-2.6,-2.6,-2.6,-2.6,-3.6,-2.5,0,-0.6,-2.2,-2.6,0.5,5.7,3.5,3.3,4.4,3.9
Logitech UE 900|3.2,2.9,1.3,-0.4,-3,-4.7,-5,-5.8,-2.8,0,0.6,1.3,5.7,5.7,2.3,-1
Logitech UE 9000|-1.3,-1.9,-2.4,-2.6,-4.2,-3.1,-1.2,-0.7,-1,-2.3,-0.1,4.3,3.4,2.4,3.5,3.5
Logitech UE4000 2012|-2,-2.5,-3.7,-4.3,-5.4,-5.1,-1.1,-1.9,-3.7,-3.7,0.8,7,5.7,5.2,7.2,7.2
Logitech Zone Wired|1.9,-0.1,-2,-3.2,-4,-2.1,-0.1,2.2,2.5,1.3,0.8,0,0.2,0.3,0.7,-0.1
LucidSound LS31|2.8,-0.5,-3.3,-4.1,-5.2,-5.2,-3.6,0.1,1.5,-0.2,1.6,5.8,5.3,2.9,-2.4,-1.4
Ludos Clamor|2.7,1.7,0.1,-1.3,-3.2,-4.4,-4.4,-1.5,1.2,2.8,0.9,3.3,-2,-10.2,0.1,3.5
Lypertek Bevi|3.6,3.1,1.9,0.1,-2.4,-3.6,-3.5,-2.7,-1.6,-1.6,-1.8,0.8,-0.1,1.3,0.4,1
Lypertek PurePlay Z3 1.0|-2.3,-0.5,1,0.9,0.4,0.3,1.1,1.8,2.3,1,-0.2,-2.6,1.8,4.2,0.6,0
Lypertek PurePlay Z3 2.0|-0.9,0.1,0.6,0,-1.4,-1.6,-1.1,-0.1,0.8,0.2,-2,-2.8,2.2,5.7,2.3,1.2
Lypertek PurePlay Z7|-3.1,-2.9,-2.9,-3.4,-3.6,-1.4,0.8,2,0.7,-1.8,-3.3,-1.1,-1.4,6.1,6.2,6.2
Lypertek Tevi|-1.9,-0.5,0.4,-0.1,-1.1,-1.1,-0.6,0.1,0.9,0.1,-2.1,-3.1,0.5,4.1,2.9,0.8
LZ Hi-Fi Audio A2 Pro|-2.9,-2.6,-2.7,-3.3,-3.9,-2.4,-0.8,1,2.3,1.3,-1.8,-0.3,5.5,6.3,3.8,1.4
LZ Hi-Fi Audio A4 Pro (black nozzle, black vent)|2,1.6,0.7,-0.6,-2.1,-2.1,-1.3,-0.5,0,-1.5,-4.8,-2.4,-4,5,5,1.1
LZ Hi-Fi Audio A4 Pro (black nozzle, blue vent)|4.7,4.7,3.9,1.7,-1.4,-3,-2.7,-1.9,-1.1,-2.6,-5.8,-3.4,-5.2,4.2,4.2,0.1
LZ Hi-Fi Audio A4 Pro (black nozzle, red vent)|-0.2,-0.1,-0.3,-1,-2.1,-1.6,-0.7,0.2,0.7,-1.1,-4.1,-1.6,-2.9,5.8,5.7,1.8
LZ Hi-Fi Audio A4 Pro (blue nozzle, black vent)|2.3,2,1,-0.2,-1.8,-1.7,-1,-0.3,0.2,-1.6,-5.9,-3.1,-5,4.8,5.3,1.3
LZ Hi-Fi Audio A4 Pro (blue nozzle, blue vent)|4.5,4,2.9,1.6,-1.4,-2.5,-1.9,-0.9,-0.6,-2.5,-6.2,-1.8,-4.4,4,2.5,-2.7
LZ Hi-Fi Audio A4 Pro (blue nozzle, red vent)|-2.3,-1.5,-0.7,-0.7,-1.5,-0.3,0.8,1.6,1.5,-0.6,-4.7,0.5,-1.5,6.5,4.4,-0.8
LZ Hi-Fi Audio A4 Pro (gold nozzle, black vent)|-0.2,0,-0.1,-0.7,-1.7,-1.1,-0.2,0.5,0.5,-1.7,-6,0.4,-1.9,6.2,5.2,-1
LZ Hi-Fi Audio A4 Pro (gold nozzle, blue vent)|4.3,3.8,2.7,1.4,-1.6,-2.7,-2.1,-1.3,-0.9,-2.9,-7.1,-1,-3.6,4.9,3.9,-2.3
LZ Hi-Fi Audio A4 Pro (gold nozzle, red vent)|-2.5,-1.7,-0.8,-1,-1.6,-0.6,0.5,1.3,1.1,-1.2,-5.7,1.3,-0.5,6.9,5.9,-0.2
LZ Hi-Fi Audio A4 Pro (red nozzle, black vent)|-2.2,-2.1,-2.5,-3.3,-4.3,-3.7,-2.5,-1.2,0.6,1.2,1.9,3.4,1.1,6.4,3.4,1.3
LZ Hi-Fi Audio A4 Pro (red nozzle, blue vent)|-0.1,-0.1,-0.5,-1.5,-2.8,-2.8,-2.2,-1.6,-1,-0.5,1.7,3.1,1,6,-0.4,-3
LZ Hi-Fi Audio A4 Pro (red nozzle, red vent)|-6.3,-5.3,-4.2,-4.2,-4.6,-3.2,-1.5,0.2,2,3.1,4.9,6,4.3,7.7,2.1,-0.4
LZ Hi-Fi Audio A4 Pro (silver nozzle, black vent)|2.2,1.8,0.9,-0.3,-2,-2,-1.3,-0.5,-0.1,-2.1,-6.4,-1.9,-3.9,5.3,5.6,1.8
LZ Hi-Fi Audio A6 (black nozzle)|-1.1,0.3,1.5,1.7,1.3,2.4,3.4,4.1,3.8,1.2,-3,0.6,-0.8,4,-3.8,-7.5
LZ Hi-Fi Audio A6 (blue nozzle)|-0.7,0.7,1.9,2.2,1.6,2.8,3.7,4.3,3.9,1.2,-4.1,0.5,-2,4.2,-4,-7.5
LZ Hi-Fi Audio A6 (dark blue nozzle)|-1.7,-0.3,0.9,1.2,0.6,1.7,2.6,3,2.3,-1.7,-6.9,4.2,2.5,7.6,-0.8,-7.1
LZ Hi-Fi Audio A6 (grey nozzle)|-2.6,-1.4,-0.2,0.1,-0.5,0.6,1.6,2.3,2.1,-0.5,-5.1,1.6,0.9,7.4,2.8,-2.3
LZ Hi-Fi Audio A6 (pink nozzle)|-3.4,-1.9,-0.5,-0.1,-0.8,0.4,1.5,2.5,3.1,2.7,2.4,3.7,5.1,6.6,-2.5,-7.6
LZ Hi-Fi Audio A6 (purple nozzle)|-3.7,-2.3,-0.9,-0.5,-1.2,0,1.2,2.2,3,2.7,3.1,5,5.7,7.9,-2.1,-8.2
LZ Hi-Fi Audio A6 (red nozzle)|-3.4,-2,-0.6,-0.1,-0.8,0.4,1.7,2.7,3.5,3,2.6,3.1,5.1,5,-2.8,-6
LZ Hi-Fi Audio A6 (silver nozzle)|-1.8,-0.4,1,0.9,0.6,1.6,2.5,3,2.4,-0.8,-4.1,4.2,2.8,7.7,-1.4,-7.8
LZ Hi-Fi Audio A6 (yellow nozzle)|-0.9,0.5,1.8,2.1,1.4,2.4,3.4,4,3.5,0.4,-5.6,1.8,-0.7,6.1,-2.9,-7.8
LZ Hi-Fi Audio A6 Mini (black nozzle)|-3.8,-2.3,-0.8,-1,-0.9,0.1,1.1,1.9,1.9,-0.5,-4.1,-2.2,-3.3,6.6,6.6,2.2
LZ Hi-Fi Audio A6 Mini (blue nozzle)|-3.5,-2,-0.5,-0.7,-0.7,0.4,1.5,2.3,2.1,-0.4,-4.7,-3,-4.8,6.5,6.6,2
LZ Hi-Fi Audio A6 Mini (red nozzle)|-6.3,-4.8,-3.2,-2.8,-3.2,-1.9,-0.6,0.7,1.7,1.6,2.1,2.6,3.8,7.6,4.7,1
LZ Hi-Fi Audio A7 (monitor, black nozzle)|-0.3,0.2,0.4,-0.3,-1.1,-1.5,-0.9,-0.4,-0.2,-1.4,-3.5,0.7,1.7,5.9,1.2,0.3
LZ Hi-Fi Audio A7 (monitor, blue nozzle)|0.2,0.7,0.9,0.4,-1.3,-1.1,-0.7,-0.1,0,-1.5,-4.5,0,1.7,5.9,1.3,-0.2
LZ Hi-Fi Audio A7 (monitor, gold nozzle)|-0.7,-0.2,0,-0.2,-2.1,-2,-1.4,-0.9,-0.8,-1.9,-3.5,2.8,2.9,6,2,0.4
LZ Hi-Fi Audio A7 (monitor, red nozzle)|-1.7,-1.2,-0.9,-1.5,-2.9,-2.6,-2,-1.2,-0.4,-0.5,0.2,4.3,2.2,6.2,1.1,1.5
LZ Hi-Fi Audio A7 (monitor, silver nozzle)|0.2,0.7,0.9,0.3,-1.3,-1.1,-0.6,0.1,0.2,-1.2,-3.9,-0.8,1.1,5.8,1,0.2
LZ Hi-Fi Audio A7 (pop, black nozzle)|-1.7,-1,-0.3,-0.3,-1,0.3,1.5,2.4,2.5,0.5,-2.9,0.5,0.4,6.2,-0.5,-1.6
LZ Hi-Fi Audio A7 (pop, blue nozzle)|-1.3,-0.5,0.1,0.1,-0.7,0.6,1.7,2.7,2.7,0.4,-4,-0.5,0.3,6.4,-0.3,-2
LZ Hi-Fi Audio A7 (pop, gold nozzle)|-2.2,-1.4,-0.8,-0.8,-1.5,-0.2,1,2,2,0.1,-2.7,2.4,1.6,6.8,0.2,-1.7
LZ Hi-Fi Audio A7 (pop, red nozzle)|-3.1,-2.4,-1.7,-1.8,-1.9,-0.8,0.6,1.8,2.5,1.8,1.2,3.9,1,5.2,-0.4,-0.7
LZ Hi-Fi Audio A7 (pop, silver nozzle)|-1.3,-0.6,0,-0.1,-0.2,0.7,1.9,2.8,2.8,0.8,-3.3,-1.5,-0.5,5.7,-0.3,-1.7
LZ Hi-Fi Audio Z04A|-1.1,-0.9,-1.2,-2.1,-3.3,-2.5,-1,0.7,1.8,0.7,-2.2,0.1,2.6,3.6,1.7,0.5
LZ Hi-Fi Audio Z05A|1.7,1.5,0.7,-0.6,-2.3,-2.5,-1.9,-1.4,-1.6,-3.9,-7.9,1,1.4,3.7,3.1,4.2
Mackie MP-360|2.4,1.8,0.5,-1.5,-3.9,-4.9,-4.6,-3.4,-2.2,-2.2,-0.4,5.6,5.6,5.2,0.7,-0.6
Madoo Type 711|-2.2,-2.1,-1.9,-2.4,-2.5,-0.1,2.2,4,4.2,1.3,-2.7,4,2.6,0.8,1.8,0.7
Magaosi K3|1.2,0.5,-1.1,-2.9,-5.4,-6,-5.3,-3.7,-1.4,-0.4,1,4.3,5.4,4.7,0.1,3.2
Magaosi K3 (cross filter)|0.8,0.1,-1.3,-3.1,-5.4,-6,-5.1,-3.5,-1.3,-0.5,0.7,4.4,5.5,5.1,0.8,3.1
Magaosi K3 (harsh filter)|1.1,0.5,-0.9,-2.8,-5,-5.7,-4.9,-3.3,-1.3,-0.7,0.2,4.1,5.4,4.9,0.5,2.2
Magaosi K3 (no filter)|1.3,0.6,-0.9,-2.6,-5,-5.7,-4.9,-3.3,-1.3,-0.7,0.1,4,5.4,4.8,0.4,2.3
Magaosi K5|2,1.2,0,-1.5,-3.7,-4.2,-3.2,-1.6,0.3,0,-2.5,3.6,2.3,4.5,-0.5,1.9
Magaosi K5 (15 ohm adapter, IEMatch, high)|2.4,1.7,0.2,-1.6,-4,-4.7,-3.8,-2.4,-0.4,-0.6,-2,4.9,4,4.6,-1.5,0.4
Magaosi K5 (15 ohm adapter, iFi IEMatch, ultra)|2.5,2,0.6,-1.3,-3.6,-4.2,-3.2,-1.9,-0.1,-0.3,-2.3,4.5,3.1,3.9,-2,-0.5
Magaosi V3|-1.2,-1.5,-2,-2.9,-3.5,-2.5,-0.7,1,2.5,1.6,1.2,4.8,4,1.7,0.3,0.7
Mangird MT4|0,0.1,-0.3,-1.3,-2.5,-1.8,-0.7,0.4,0.7,-1.1,-2.6,1.4,3,3.4,3.9,1.4
Mangird Tea|0.8,1.2,1.2,0.4,-1.3,-2.1,-2.5,-2.9,-2.6,-2.4,-2.5,-0.7,1.6,5.5,4.9,2.4
Mark Levinson No 5909|0.3,1.5,1.8,-0.7,0.1,0.7,1,0.6,0.3,-0.4,2,1.4,0.7,2.2,-0.6,-2.3
Marshall Major|5.1,5.1,5.1,5,1.7,-0.3,-0.3,-0.3,0.4,-3.7,-8.3,-1.5,-0.8,2.9,-3.3,-3.1
Marshall Major II|-0.1,0.1,-0.7,-1.1,-1.7,-0.3,-1.1,-3.2,-2.7,-2.4,0,3.8,5,4.6,2.1,2.6
Marshall Major III|5,3.8,1.9,2.2,1.2,2.7,4,3.9,0.6,-2.5,-0.6,-4,-8,-2.3,-0.4,-4.8
Marshall MID ANC|1.3,0.6,0,-0.2,0,1.2,2.8,3.3,2.6,2.1,1.8,0,-3.1,1.1,-2.2,-5.4
Massdrop Nobel X|1.1,0.6,-0.8,-2.5,-4.7,-5.2,-4.2,-3,-0.8,-0.1,1,2.7,3.9,2.4,-0.4,2.6
Massdrop Panda|0.6,0.7,-1,-4,-4.9,-3.4,-2.1,-2.1,-1.5,0.6,3.4,3.9,2.3,1.5,1.4,3.4
Massdrop Panda (active)|1.8,1.1,-0.3,-3.5,-5.3,-4.2,-2.3,-2.4,-2.5,-0.2,2.6,4.6,2.6,2.6,0.9,3.3
Massdrop Panda (passive)|0,-0.7,-1.2,-3.9,-4.8,-4.2,-0.3,0.9,0.1,2.1,1.8,2.5,-0.1,-0.7,3.4,4.2
Massdrop Plus Universal IEM|0.9,0.6,-0.4,-1.8,-3.3,-2.8,-1.4,-0.1,1.3,0.2,-2,-2.1,-0.4,0.6,2.1,3.5
Massdrop x Noble X Universal IEMs|0.7,0.2,-1.2,-2.9,-5.2,-5.4,-4.5,-2.5,-0.4,-0.1,0.5,2.1,4.3,1.2,1.8,4.8
Master & Dynamic ME05|-2.1,-2.6,-3.3,-4,-4.4,-2.9,-1.1,0.7,2.7,2.5,2.9,1.6,0.8,-1.1,3.9,4.2
Master & Dynamic MH30|-2.7,-3,-3.5,-3.7,-4.8,-3.5,-2,-2.8,-2.2,0.2,3.9,3.1,2.2,6.7,6.6,5.9
Master & Dynamic MH40|0.6,-2.1,-3.5,-3.3,-2.9,-1.5,-0.7,-0.4,-0.9,-1.5,1.4,4.1,4.1,3.5,0.2,0.1
Master & Dynamic MW07|-0.9,-1.5,-2.5,-3.7,-5,-3.9,-2.1,0.4,3.2,4.2,4.5,4.7,-0.6,-2.6,0.9,3.6
Master & Dynamic MW07 Go|-5.2,-4.4,-3.8,-4.2,-4.6,-2.8,-0.7,1.6,4,5,4.3,3.2,0,-2.7,5.5,6.9
Master & Dynamic MW07 Plus|-6.3,-5.3,-3.9,-4,-3.9,-1.9,0.1,2.5,5.1,5.7,5.4,4.8,-0.9,-3.5,3.4,6.7
Maximo iMetal iM-590|-2.5,-1.7,-1.2,-1.6,-2,-0.7,0.4,1.5,2,0.6,1,-0.6,5.9,5.4,-1.1,1.4
Maximo iMetal iM-590 White|-2.5,-1.7,-1.2,-1.6,-2,-0.7,0.4,1.5,2,0.6,1,-0.6,5.9,5.4,-1.1,1.4
Maximo iMetal iP-HS5|-2.5,-1.7,-1.2,-1.6,-2,-0.7,0.4,1.5,2,0.6,1,-0.6,5.9,5.4,-1.1,1.4""",
"""Mayflower Electronics T50RP Version 2|4.5,4.5,4.5,1.5,-1.8,-2.3,0.1,1.9,3.6,1.6,-0.8,-3.9,-8.1,-1,-4,-2.3
Meccaudio ME04|2.3,1.4,0,-1.8,-4.1,-4.8,-4.4,-3.3,-2.2,-3.9,-3.1,5.2,4.2,5.2,3.4,4.2
Meccaudio ME20|-0.5,-0.2,-0.1,-1,-2.4,-2.6,-2,-1.2,-0.3,-1.2,-4.1,1.1,5.5,4.1,6.6,4.4
MEE Audio A161P|3,2.5,1.2,-0.6,-3.1,-4.2,-3.8,-3.2,-1.5,-1.6,-0.9,-2.7,4.7,-1.2,-0.5,4.7
MEE Audio Air-Fi Matrix2 AF62 (wired)|1.5,-1.3,-2.1,-2.1,-2.6,-3,-2.9,-3.2,-2.4,-0.9,1.6,3.9,0.6,3,1.6,2.3
MEE Audio Air-Fi Matrix2 AF62 (wireless)|3.1,-0.1,-1.4,-1.3,-2.1,-2.5,-2.5,-3,-2.5,-1.4,0.6,3,-0.7,1.6,-0.2,0.2
MEE Audio M6 Pro|-0.9,-1.4,-1.9,-2.7,-3.4,-2.2,-0.3,1.5,3.2,2.7,0.4,-3.6,-0.1,-1.2,1.5,3.6
MEE Audio M6 Pro 2nd Generation|-4.4,-3.7,-3,-3.2,-3.4,-1.7,0.1,2,3.4,2.8,2.3,3.1,-0.2,2.1,4.4,1.5
MEE Audio M9B|-6.2,-5.4,-3.4,-1.7,-0.2,2.4,4.2,6.6,7,5.6,7,7.1,-0.5,2.3,-3.2,-2.8
MEE Audio Planamic|0.3,-0.6,-1.9,-3.3,-4.9,-4.1,-2.2,0.2,2.9,3.5,1.1,-0.7,-1.1,2.7,6.4,6.1
MEE Audio Sport-Fi M6|-6.6,-5.4,-4.3,-4.3,-4,-1.5,0.8,4.3,6,4.7,5.2,1.6,-2.5,-6.1,6.4,7.4
MEE Audio X6 Plus|-0.9,-0.9,-0.6,0.2,0.3,1.6,3.2,4.4,3.6,1,0.7,-3.1,-1.8,-8.5,-0.8,1.8
MEE Audio X7|3.4,2.2,0.7,-0.7,-2.1,-1.3,0.4,2.6,3.3,1.7,1.7,-0.7,-6.5,-8.2,1.5,0.5
Meizu Live (black filter)|0.2,-1.4,-3.3,-5,-6.5,-5.6,-3.9,-1.4,1.2,1.2,1.7,4.1,1.9,6.4,4.4,-1.3
Meizu Live (blue filter)|1,-0.8,-3.1,-4.9,-6.7,-6,-4.3,-1.8,1,1.3,1.7,3.2,1.2,6.2,5.5,-0.8
Meizu Live (white filter)|-0.3,-1.7,-3.4,-5,-6.5,-5.5,-3.7,-1.3,1.2,1.2,1.6,4.3,2.3,6.4,4.4,-1
Metal Magic Research Homunculus|2.5,1.6,0.2,-1.8,-3.8,-3.7,-2.5,-0.6,1.3,0.7,-3.1,-1.6,1.9,0.3,4.6,5.3
Metal Magic Research Thummim|-0.7,-1.2,-2.3,-3.6,-5.1,-4.2,-2.2,0.4,3.8,4.8,0.1,-1.3,5,2.7,4.5,3.8
Meze 109 Pro|3,2.6,0.3,-1.1,-2.6,-2.5,-1.5,-1,-1,0.3,3.2,2.6,-0.2,1.1,0.3,-2.3
Meze 109 Pro (ZMF Acoustic Titan Mesh)|1.5,1.1,0.4,-1.7,-3.2,-3.1,-2.1,-1.3,-1.2,-0.1,3.3,0.4,2.1,0.8,1.5,1
Meze 11 Deco|-5.1,-5.1,-5.5,-6.4,-7.1,-5.6,-3.4,-1.1,2.6,4.5,5.2,6.7,7.7,7.7,4,5.9
Meze 11 Neo|2.6,1.1,-1.1,-3.1,-5.3,-5.6,-4.3,-2.6,0,0.2,0.5,-0.1,1.8,2.4,5.4,2.9
Meze 12 Classics|3.7,2.3,0.4,-1.6,-4.1,-4.5,-3.3,-1.2,1.2,2.6,1.5,-1.7,-0.2,-6.4,2.9,4.8
Meze 12 Classics V2|3.4,1.8,-0.5,-2.7,-5.4,-5.7,-4.7,-2.3,1.6,2.9,2.8,2.2,0.7,-4.6,2.7,4.9
Meze 66 Classics|-2.9,-3.7,-4.6,-5.3,-6.9,-7.3,-6,-1.2,1.2,2.3,7.3,6,1.4,6.1,7.3,6.9
Meze 88 Classics|0.4,-0.3,-1.1,-2.6,-4.6,-4.9,-3.8,-0.3,-2.7,-5.8,-4.2,4.9,5.8,4.2,5.5,4.9
Meze 99 Classics|-2.2,-2.9,-3.6,-3.2,-5,-4.4,3.3,2.5,2.3,4.3,2.1,4.2,6.5,-2.3,1.2,1.6
Meze 99 Classics (Brainwavz memory foam hybrid earpads)|0.5,-0.3,-0.9,-0.6,-0.4,3.7,6.8,3.7,1.6,0.9,1,-1.2,1.9,-4.7,-3.9,-2.6
Meze 99 Neo|-2.4,-3.1,-4,-3.8,-6.6,-7.5,-1.3,4.2,1.8,1.8,2.3,5.4,6.8,-1.1,2,4.8
Meze 99 Neo (99 Classics earpads)|4.7,3.9,2.1,0.5,-1.7,-1.6,1.1,-1.6,-3.5,-3.7,-2,-2.2,-2,0.1,-1,-0.1
Meze 99 Noir|-4.4,-5.1,-5.9,-6,-7.8,-6.8,1.8,2.9,1.9,4,4.3,6.8,7.5,2.9,2,4.5
Meze Advar|1.3,1,0.1,-1.2,-2.7,-2.5,-1.6,-0.3,1.1,1.1,1.4,3.4,4.7,3.5,-1.2,-3.9
Meze Elite (alcantara earpads)|5.7,4,1,-0.7,-2.9,-3.7,-2.1,-1.3,-1.7,-1,2.1,2.3,0,-0.4,3.5,-0.5
Meze Elite (hybrid earpads)|3.5,2.8,0.3,-0.8,-2.8,-4.8,-4.6,-2.2,-1.7,-1,3.8,2.1,0.7,2.2,4.7,1
Meze Empyrean|2.4,1.6,-0.2,-1.5,-3.9,-4,-3.3,-1.8,-2.3,-1.8,2.4,3.3,2.3,2.9,5.9,1.7
Meze Empyrean (alcantara earpads)|3.3,1.8,0.3,-0.8,-2.5,-2.8,-0.9,-0.8,-0.9,-0.3,2.5,2.2,0.2,-0.1,3.8,-1.3
Meze Empyrean (leather earpads)|3.3,2,0.5,-0.6,-2.9,-4,-1.6,-0.5,0,0.2,2.7,2,1,1.7,4.5,-1.6
Meze Liric|-0.1,-0.5,-2.2,-3.3,-1,0.6,0.7,0,-1.5,-2.5,0.3,1.5,5,3.4,3.2,-0.5
Meze Rai Penta|2.1,1.4,0.1,-1.5,-3.8,-4.1,-3.1,-1.1,0.1,-1.4,-5.6,-0.9,0.9,4.1,2,4.5
Meze Rai Solo|0.5,0.1,-1.1,-2.6,-4.4,-3.9,-2.5,-0.8,0.9,0.8,0.6,-1,-3.9,2,6.7,5.9
Microsoft Surface Earbuds|4.7,4.7,3.9,0.1,-1.4,-4.6,-4,-3.3,-1.5,0.3,-2.3,4.2,1.7,-4,-1.9,-2.2
Microsoft Surface Headphones|-1.8,-2.8,-4.1,-5.5,-4.8,-3.2,-3.3,1,3.8,1.5,1.6,0.7,4.1,6.7,3.5,0.8
Microsoft Surface Headphones 2|0.7,-2.8,-5.7,-6.6,-6.2,-2.3,2.1,2.7,-1.8,1.3,2.5,1,6.4,2.8,0,1.2
Microsoft Xbox Wireless Headset|-4.2,-8.5,-7,-9,-11.4,-4.3,2.8,4.7,6.4,6.5,8.3,3.7,6.8,4.6,5.1,2.8
MiM Audio Dark Magician|2.8,2.2,0.9,-0.7,-2.9,-3.6,-3.2,-2.3,-1,-1.3,-2.4,-0.3,2,0.7,4.1,2.2
MiM Audio Dark Magician (revised)|0.6,0.7,0.4,-0.7,-1.9,-2.6,-1.8,-0.6,0.7,0.2,-1.1,0.2,3.3,-1.8,2,1.1
Mixcder E9|-0.1,-0.2,-0.8,-0.6,-0.9,-1.4,-2,-0.6,-0.6,-0.7,5.4,6.8,1.9,1.9,0.6,-1.6
MK801|3.6,1.5,0.7,-1.8,-3.8,-3.3,-0.5,-1.2,-2.5,-3,1.2,2,-0.5,-3,1.5,2.2
MMR Gae Bolg|0.6,0.2,-1.1,-2.7,-4.8,-4.7,-3.3,-1.2,1,1.1,-1,1.9,5.9,0.8,-0.3,2.4
MMR Homunculus|2.2,1.5,0.1,-1.8,-4.1,-4.5,-3.7,-2.5,-1.7,-3.7,-4.5,2.8,5.7,4.3,4,4
MMR Thummim|0.9,0.4,-0.8,-2.5,-4.6,-4.9,-3.8,-2.1,0.1,0.6,2,2.4,5,3.2,3.6,1.4
Modhouse Audio Argon Mk3 (ZMF oval pleather earpads)|0.5,-0.5,-5.8,-7,-7.7,-5.8,-2.6,0.6,-0.7,2.1,2.5,5.5,6.7,0.9,6.1,1.3
Modhouse Audio T60RP Argon (Xenon suede earpads)|0.9,1,-1.7,-4.7,-6.1,-1.8,0.6,2.3,-1.5,-0.1,4,5.5,5.5,-3.5,1.4,-0.7
Modhouse Audio T60RP Argon (Xenon suede earpads, baffle upgrade)|2,2.3,-0.8,-4,-5.8,-2.3,0.4,1.3,-1.9,-0.3,3.5,4.4,3.6,1.6,-0.1,-3
Moe Audio SS01|-0.7,-0.7,-1.3,-2.4,-3.5,-2.7,-1.2,0.3,2.2,1.9,2.1,1.4,-0.4,-2.8,0.6,3.1
Mofasest M14 (off-off)|0.2,0.2,0,-0.7,-1.4,-0.7,0.3,0.9,0.6,-1.8,-3.3,1.3,1.9,4.4,2.3,-0.1
Mofasest M14 (off-on)|1,1,0.7,-0.2,-1.3,-1.1,-0.3,0.6,0.5,-2,-3.9,0.7,1.3,4.4,2.4,-0.3
Mofasest M14 (on-off)|-1.5,-1.5,-1.6,-2.2,-2.9,-1.6,-0.3,0.9,1.2,-0.7,-2.3,2.5,3.1,5.7,3.6,1.2
Mofasest M14 (on-on)|-1.2,-1,-1.2,-1.9,-2.6,-1.7,-0.3,0.9,1.3,-0.8,-2.7,2,2.5,5.6,3.7,1.2
Monoprice 110010|2,0.6,-1.1,-2.6,-4.4,-4.1,-3.3,-3.5,-2.8,1.6,1.6,3.2,4.5,5.1,3.6,1.6
Monoprice 8320|4.2,4.2,4.2,4,1,-1.2,-1.8,-2.3,-2.2,-3.2,-5.1,-8.5,-3.2,-2.4,4,4.2
Monoprice 8323|5.2,2.5,-0.5,-0.9,-1.9,-2.6,-0.8,-2.8,-3.7,-3.9,-3.6,2.5,5.2,3.1,-0.7,-1
Monoprice M1060|4.7,3.5,2,0.9,-1.1,-2.1,-0.1,-0.5,-1.6,-0.7,-1.7,-2.1,-1.2,-1.3,5.6,2.5
Monoprice M1570 (leather earpads)|3.3,2,1.3,0.1,-1.7,-3.3,-2.6,-2.4,-3.8,-4.8,0.3,2.4,2,-1.2,0.6,0.3
Monoprice M1570 (velour earpads)|5,4.6,2.3,0.7,-1.2,-2.3,-1,-1.4,-1.9,-3.7,-2.1,0.3,-0.9,-1,0.1,-0.7
Monoprice M350|5.5,5.2,3.9,2,-0.7,-2.4,-2.9,-3.6,-5.4,-7.4,-4.7,5.8,5.8,5.8,-0.9,-6.7
Monoprice M650|-0.8,-1.8,-2.6,-4,-6.2,-6.6,-4.6,-2.2,2,5.6,5.3,1.3,5.1,3.1,-0.3,3.2
Monoprice Modern Retro|-5.1,-5.9,-6.8,-6.9,-3.8,-3.9,1.3,1.7,3.5,2.4,4.2,2.9,7.9,3.4,5.9,6.8
Monoprice Modern Retro (Dekoni elite hybrid earpads)|-0.5,-1.8,-1.1,-0.3,0.5,4.5,7.5,5.8,2.6,2.5,1.8,-5.5,-1,-7.1,-2.2,-0.9
Monoprice Monolith M-TWE|-1.4,-4.1,-3.8,-2.4,-2.5,-1.7,0.9,-0.3,1.3,3.8,3.4,4,4.1,1,1,-0.4
Monoprice Quartet|1.9,1.4,0,-1.6,-3.7,-4.1,-3,-1.2,0.6,0.7,-1,2.5,2.4,6.1,-2.1,-5.2
Monster Clarity 101 AirLinks|-7.1,-4.8,-0.8,0.5,-0.1,-3.2,-2.9,-2,2.3,2,2.9,3.9,7.7,7.8,0.9,-1.2
Monster DNA Pro2|0.6,-0.6,-0.9,-0.8,-2.3,-2.6,0.7,-1.5,-2.2,0,3.1,-5,-3.2,0.6,3.9,0.7
Monster Inspiration|-0.5,-1.6,-2.9,-3.6,-5.3,-2.3,2.3,2.9,0.9,-1.6,-1.2,-1.3,-5,0.5,4.5,5.5
Monster Jamz|-3.3,-2.6,-2.2,-2.6,-2.8,-1.6,-0.2,0.9,2.3,1.3,1,-0.7,3.2,1.9,6.5,2.9
Monster Lil Jamz|-3.4,-2.7,-2.4,-3,-3.8,-3,-1.7,-0.1,1.4,1.3,1.9,1,4.2,3.2,7.3,4.7
Monster Miles Davis Tribute|-3.7,-3,-2.7,-3.1,-3.3,-1.8,-0.1,1.3,3,2.2,1.4,-0.5,3.2,2.3,5.6,2.4
Monster Solo|-2.2,-3.4,-5.7,-5.8,-9.2,-5.3,-1.8,1.4,0.5,1,4.6,4.7,0.2,2.7,7,7
Monster Turbine|-4.4,-3.6,-3,-3.1,-3,-1.3,0.7,2.3,3.9,2.8,3.2,-0.1,0.1,0.5,5.4,1.5
Monster Turbine Pro|-4.1,-3.6,-3.2,-3,-2.8,-0.6,0.4,2.4,3,3,2.7,0.8,-0.7,-4.7,2,5.1
Monster Turbine Pro Copper|-2.4,-2.1,-2.1,-2.8,-3.7,-3.3,-1.9,-0.8,1.1,1.2,1.8,1.8,2.4,2,5.8,2.7
Monster Turbine Pro Gold|-1.4,-1,-0.8,-1.3,-2,-0.4,0.3,1.8,2.1,2.1,2,-0.1,-1.7,-4.2,0.9,1.1
Monster Turbine-Pro|-3.7,-3.8,-4.5,-5.5,-6.5,-5.2,-3.1,-0.7,1.8,2.2,2.3,2.5,1.9,6,6.8,6.8
Montblanc MB 01 (wired, ANC off)|-1.4,-1,-1.9,-5.2,-7.7,-6.5,-5.1,-4.3,0.1,-0.5,-0.7,3.3,6.7,6.7,6.7,6.7
Montblanc MB 01 (wireless, ANC)|-1,-0.6,-1.6,-4.1,-6.9,-6.8,-6.6,-3.8,-2.3,-0.5,-1.8,4.1,6.7,6.7,6.7,6.7
Moondrop A8|1.7,0.8,-0.4,-1.8,-2.8,-0.8,0.9,1.2,0.9,-0.3,-1.7,-0.7,-0.7,2.8,4.5,1.7
Moondrop Alice (default EQ)|-2.7,-1.7,-0.3,-0.2,-0.9,-0.3,-0.1,0.6,1.6,1.3,1.8,1.5,0.9,-0.3,0.2,-2
Moondrop Aria|0,0.5,-0.3,-1.1,-2,-1,0,1,1.7,0.6,0.2,1,1.3,1.6,2.2,-1.9
Moondrop Aria (2021)|0.5,0.6,0.2,-0.7,-1.9,-1.6,-0.6,0.3,1.1,0.3,-1.7,-0.5,1.6,1.5,5.3,1.5
Moondrop Aria 2|0.4,0.2,-0.6,-1.8,-3.1,-2.5,-1.2,0.3,1.5,0.8,-0.3,0.7,1.3,2.2,2,-0.6
Moondrop Aria Elven Maiden|1.2,0.9,0.1,-0.8,-1.8,-1.5,-0.5,0.6,1.5,0,-0.6,0.1,1,0.8,2.5,-2.3
Moondrop Aria Snow Edition|2.7,1.8,0.7,-0.7,-2.3,-2.3,-1.5,-0.6,0.4,-0.4,-2.2,-1.2,-0.5,0.4,4.1,1.6
Moondrop Blessing|1.9,2.1,1.9,0.9,-0.7,-1.1,-0.8,-0.1,0.4,-0.5,-1.2,-1.8,-0.8,0.3,1.7,0.3
Moondrop Blessing 2|2.8,2.7,2,0.8,-1,-1.3,-0.7,0.1,0.6,-0.4,-1.7,-1.2,1,1.3,0.6,0
Moondrop Blessing 3|2.3,2.2,2.1,1.2,0.2,-0.3,-0.4,-0.2,0.4,0.1,-0.9,-0.6,-0.8,0.7,1.9,-0.2
Moondrop Chu|1.1,1.6,1.9,0.8,-0.8,-0.7,-0.2,0.5,0.9,-0.2,-1,-0.6,0,0,1.7,0.1
Moondrop Chu 2|-0.8,-0.4,-0.4,-1,-1.8,-0.9,0.2,1.3,1.9,0.8,0.1,-0.6,-0.9,2.5,2.7,-0.9
Moondrop Crescent|-1.4,-1.4,-1.5,-2.1,-2.3,-1.1,-0.2,0.5,0.9,-0.3,0.8,2.2,-0.3,3.8,1.9,-0.7
Moondrop Droplet|-3.4,-3.5,-3.2,-3.1,-2.5,-0.8,-0.8,-0.1,1.5,1.9,1.2,2.3,2,3,1.7,1.1
Moondrop Illumination|2.6,2.1,0.8,-0.9,-3,-3.4,-2.6,-1.1,0.5,0.4,-0.4,-1,-0.2,-1.6,0.8,1.5
Moondrop Jiu|-0.1,0.2,0.4,0,-0.4,-0.4,-0.2,0.2,0.9,1.1,0.9,1.8,1.4,-1.2,0.6,-1.7
Moondrop Joker|5.6,4.4,2.1,0.3,-0.7,0,2.9,3.1,1.8,-0.9,-2.1,-4.6,-0.8,-0.4,-0.5,-3.5
Moondrop Kanas Pro|-0.8,-0.8,-1.2,-1.9,-2.8,-2.1,-1,0.1,1.1,0.4,-1.2,-0.3,0.6,3,6.3,3.9
Moondrop KATO|2.7,2.6,2,1,-1.1,-0.8,0.3,1.4,2.2,-0.8,-1.4,-0.5,-0.5,0,-1,-2.9
Moondrop Kato (brass nozzle)|0.6,0.5,-0.1,-1.3,-2.7,-2.3,-1.4,-0.3,0.6,-0.3,-1.9,-0.9,0.9,2.6,4.2,3.7
Moondrop Kato (gold nozzle)|0.2,0.2,-0.3,-1.8,-2.8,-2.5,-1.4,0,1.1,0.3,-0.2,1.1,1.8,2.2,1.9,-0.5
Moondrop Kato (silver nozzle)|0.3,0.2,-0.6,-1.7,-3.1,-2.5,-1.5,-0.1,1,0.1,-0.5,0.8,1.7,2,1.7,-0.2
Moondrop Kato (steel nozzle)|0.6,0.4,-0.1,-1.2,-2.6,-2.2,-1.2,-0.1,0.7,-0.1,-1.8,-1.1,1,2.4,3.8,3.4
Moondrop KXXS|1,0.7,0,-1,-2,-1.7,-0.7,0.4,1.4,0.3,-1.5,-1.1,0.8,-0.4,4.9,2
Moondrop KXXX|-0.2,-0.1,-0.4,-1.1,-2,-1.3,-0.4,0.5,1.1,-0.2,-0.6,-0.1,0.7,1.5,5,1.1
Moondrop LAN|1,1.3,1,0.3,-1.1,-1,-0.4,0.4,1.1,0.2,-0.5,0.5,0.9,-0.1,2.1,-1.7
Moondrop Nekocake|1.9,1.6,0.9,0,-0.8,-1.4,-2.6,-3.5,-3,-0.9,-1.2,-1,-0.9,-1.3,2.5,4.6
Moondrop Nekocake (ANC off)|1,0.8,0.3,-0.4,-1.1,-1.8,-3.2,-3.9,-3.2,-0.8,-0.1,1.3,1.5,0.9,1.6,4
Moondrop Nekocake (ANC on)|1,0.8,0.2,-0.5,-1.1,-1.9,-3.2,-4,-3.2,-0.9,-0.1,1.3,1.4,1.7,1.8,3.9
Moondrop Para|5.2,4.6,1.8,1,-0.8,-2.3,-1.9,-1.2,-0.5,-0.7,1.1,-0.3,-2.7,-1.1,0,-1.5
Moondrop Para (fenestrated PU earpads)|5.4,5.4,3,1.3,-0.7,-1.1,-0.1,0.4,-0.8,-2.4,-1.2,-0.9,-0.8,-0.3,-1.1,-3.9
Moondrop Para (hybrid earpads)|5.8,5.8,3,1.7,-0.2,-1.3,-0.7,-0.1,-0.2,-0.9,0.8,-2,-2.5,1.5,-0.3,-6.9
Moondrop Para (ZMF Auteur perforated lambskin earpads)|5.5,5.5,3.4,1.9,0,-1.1,-0.5,0.3,-1.4,-2.2,1.3,0.3,-1.5,-2.7,-1.4,-4.1
Moondrop Para (ZMF Eikon perforated suede earpads)|5.2,5.2,2.7,1,-0.8,-1.7,-1.2,-0.8,-2,-2.5,1.4,0.7,-1.7,-2,-0.8,-1.8
Moondrop Para (ZMF Universeperforated suede earpads)|5.5,5.5,3.4,1.4,-0.4,-1.1,-0.4,0.1,-1.5,-2.7,2.1,0.2,-2,-2.1,-1.6,-3.9
Moondrop Quarks|1.7,1.4,0.3,-1.2,-3,-2.6,-1.7,0.1,1.6,1,0.1,-2.5,-0.1,2.1,2.3,2.4
Moondrop Quarks DSP|-0.5,-0.7,-1,-1.3,-0.4,-0.1,-0.3,0.3,1.2,0.4,-0.2,0.2,1.5,1,-0.2,-0.1
Moondrop S8|0.7,1,1,0.4,-0.5,-0.2,0.2,0.7,0.9,-0.2,-0.7,0.1,-0.2,2.4,2.5,-1.2
Moondrop Solis|3.5,2.5,0.7,-1.2,-3.6,-4.4,-3.7,-2.3,-0.6,-0.5,-0.2,-0.4,1.6,2,1.8,-0.1
Moondrop Solis 2|-0.3,0.4,0.6,-0.1,-1.5,-1.6,-1,-0.1,0.9,0.8,0.8,1,0.9,-0.4,-0.6,-0.6
Moondrop Space Travel|0.4,0.1,0,-0.4,-0.8,-0.2,0.2,0.4,1.1,1.1,1.8,-0.6,-0.4,1.9,1.1,-2.9
Multi Unit Earphones|1.3,0.3,-0.9,-2.2,-3.5,-2.6,-1.1,0.4,2,1.2,-0.1,0.5,1,4.1,7.2,-2.8
NAD RP18 Bass Light Version|5.3,5.3,5.3,5.3,5.3,0.8,-2,-3.7,-4.4,-4.1,-3.1,-4.2,-3.6,-3.8,0.7,-1.6
NAD VISO HP50|-0.3,-0.1,0.8,-1.8,-4.3,-3.7,-1.2,-0.7,0.4,1.1,0.5,2.1,1.9,-0.9,-0.5,2.7
Nan-6 Nan-6|3.1,2.8,1.8,0.8,-1.2,-2.8,-1.6,-2.6,-2.3,-1.7,0.7,2.5,1.6,-2.3,-0.2,0.9
NarMoo B2M|-3.7,-3,-2.6,-3,-3.6,-3.1,-2.2,-1.5,-0.2,0.2,1.4,3.5,6.8,4.9,7.3,3.1
NarMoo R1M (black ports)|-5.3,-4.9,-4.9,-5.3,-5.5,-3.3,-0.7,2,4.5,4.5,5.8,6.3,1,-2.2,6.1,6.1
NarMoo R1M (gunmetal ports)|-2.8,-2,-2,-2.7,-3.8,-3.4,-2,-0.5,2.1,3.4,4.9,5.3,0,-3,4.9,5.7
NarMoo R1M (silver ports)|-0.4,-0.7,-1.6,-3.1,-4.8,-4.6,-2.9,-1.1,1.5,2.9,4.5,5,-0.6,-3.8,4.5,4.8
NarMoo S1|-3.6,-3.5,-3.9,-4.7,-5.2,-3.7,-1.3,1.3,4.6,5.4,5.2,1.8,0.7,-2,4.5,4.8
NarMoo W1M|-3.7,-3.3,-3.3,-3.9,-4.7,-4.4,-3.6,-3.2,-1.8,-0.3,3.2,4.4,7,4.4,7,5.1
NCM Bella|-1.6,-1.3,-1.2,-1.8,-2.4,-1.3,0,1.5,2.7,1.7,1.4,2.3,6.2,-2.3,-0.4,1.5
Nectar Hive|4.2,3.6,2.3,1.5,-0.2,-1.6,-1.3,-1.7,-2.4,-3.2,-0.5,0.1,-2.1,-1.7,-1.7,1
Neumann NDH20|-2.8,-2.7,-1.6,-1.8,-3.5,-2.6,-0.9,-0.4,-0.3,1.1,4.5,5.5,-1.9,0.4,2.7,4.8
Neumann NDH30|1.2,0.4,0.2,-1.2,-2.2,-2.8,-2.1,-1.9,-1.7,-2.1,0.2,1.8,-2.9,0.1,4.7,4.9
Neusonik direm E3|-2,-1.2,-0.6,-1,-1.6,-0.6,0.5,1.8,2.9,2.1,-0.1,-1,0.7,0.3,4,3.7
Neusonik direm PRO|0.9,0.8,0.6,-0.2,-1.1,-0.7,0.5,1.6,2.4,1.2,-1.4,-4.4,0.7,-1.3,2.4,1.6
NF Audio NA1|2,1.5,0.4,-1.1,-2.9,-2.8,-1.6,-0.2,1.2,0.9,-0.2,-1.9,-1,-1.9,-0.4,0.8
NF Audio NA2|-0.8,-0.7,-1.2,-2.2,-3.2,-2.1,-0.5,1.4,2.8,2.2,0.8,0.7,0,2.2,2.2,0
NF Audio NA2+|3.9,3.3,1.9,0.3,-1.9,-2.1,-1,0.5,2.3,1.5,0.3,-0.5,-0.6,-2.6,-1.2,-4.5
NF Audio NA2+ (Atmosphere eartips)|3.3,2.7,1.3,-0.4,-2.4,-2.5,-1.5,0.1,1.7,1.2,0.1,-0.8,-1,-2.3,0.1,-2.4
NF Audio NA2+ (Balanced eartips)|3.1,2.5,1.2,-0.4,-2.3,-2.7,-1.5,0,1.5,1,-0.4,-0.9,-0.9,-0.3,-0.6,-3.3
NF Audio NA2+ (Final Audio Type E eartips)|3,2.4,1.1,-0.6,-2.7,-2.9,-1.7,-0.2,1.3,0.8,-0.8,-0.5,-1.1,0.5,-0.9,-3.1
NF Audio NF2u|1.7,1.2,-0.3,-2.1,-4.5,-4.8,-3.5,-1.7,-0.4,-1.3,-2.2,1.3,-1.2,4,3.4,4.9
NF Audio NM2|0.2,-0.1,-0.9,-2,-3.2,-2.2,-0.4,1.7,3.5,2.7,1.9,0.2,0.5,-2.9,1.7,-1
NF Audio NM2+|4.4,3.5,1.7,-0.1,-2.6,-3.1,-2,-0.3,2.2,1.1,-0.3,-2.2,-1.2,-2.1,2.3,-0.4
NHT Super Buds|-6,-5.4,-5,-5.2,-5.1,-3.3,-1.3,0.3,1.8,1.1,0.3,1.1,4.9,7.4,7.4,6.4
NiceHCK DB1|2.1,1.7,0.6,-1,-3,-2.9,-1.7,0.2,2.1,1.7,0.4,-2.3,-0.7,-1.8,-1.7,-1.5
NiceHCK DB3|-1.2,-1.6,-2.3,-3.4,-4.5,-3.1,-1.1,1.1,3.4,3,0.9,2.2,1.3,2.7,0.5,0.1
NiceHCK EB2S|4.6,3.1,0.7,-1.8,-5.4,-8.1,-9.5,-10.5,-7.9,-3,3,5.6,5.6,5.6,5.6,3
NiceHCK EBX21|4.9,4.9,4.9,4.9,4.9,0.7,-0.2,0,-1.1,-0.9,-1.9,-3.4,-7,-5.9,-5.3,-2.2
NiceHCK F1 (black filter)|-1.6,-1.5,-1.8,-2.7,-3.6,-2.7,-1.4,-0.1,1.2,0.9,1.8,5.9,6.1,3.1,-0.1,-0.3
NiceHCK F1 (gold filter)|0.5,0.5,0,-1,-2.2,-1.5,-0.4,0.6,1.2,-0.3,-2.4,-0.8,4.4,2.3,1.2,0.4
NiceHCK F1 (silver filter)|-0.2,-0.1,-0.7,-1.6,-2.7,-1.9,-0.8,0.3,1,-0.1,-1.1,2.1,5.1,2.5,0.6,0.2
NiceHCK Lofty|0.7,0.1,-0.7,-1.9,-3,-2.2,-1,0.4,1.4,0.2,-1.7,-3.2,0.2,-0.1,4.6,2.1
NiceHCK M5 (blue filter)|-3.3,-2.5,-1.9,-2,-2.1,-0.1,1.8,3.7,5.3,4.2,1.8,-1.1,-1.2,3.5,3,-2.6
NiceHCK M5 (grey filter)|2.2,2.1,1,-0.4,-2.1,-1.6,0,1.9,3.7,2.9,0.7,-3.8,-3.6,1.6,1.4,-4.2
NiceHCK M5 (red filter)|5.3,5.3,4.8,2.3,-1.5,-3.7,-2.5,0.2,2.4,1.8,-0.3,-5,-4.7,0.6,0.4,-5.2
NiceHCK NX7 Mk3 (black)|-1.1,-1,-1.3,-2.1,-2.8,-1.5,0.3,2,3.6,2.9,1.6,3.5,4.2,4.6,2.9,-3.7
NiceHCK NX7 Mk3 (blue)|0.1,0,-0.5,-1.2,-2.1,-0.9,0.7,2.3,3.5,1.9,-1,3.8,1.7,3.8,3,-3.5
NiceHCK NX7 Mk3 (red)|1.2,1.1,0.5,-0.4,-1.4,-0.3,1.2,2.7,3.6,1.3,-3.3,2,-0.5,3,2.9,-3.6
NiceHCK NX7 MK4 (black filter)|-0.5,-0.5,-1.1,-2.1,-3.2,-2.2,-0.6,0.9,2.1,1,0.2,3.3,6,5.9,1.5,-3.8
NiceHCK NX7 MK4 (gold filter)|0.7,0.6,-0.1,-1.3,-2.7,-1.9,-0.6,0.8,1.6,-0.1,-2.4,3,3.1,5.7,2.1,-3.4
NiceHCK NX7 MK4 (red filter)|1.8,1.6,0.7,-0.6,-2,-1.5,-0.3,1,1.7,-0.6,-4.6,2.8,-0.3,4.7,1.8,-3.5
NiceHCK NX7 Pro (blue)|-0.8,-0.9,-1.2,-2,-2.8,-1.5,0.2,2,3.6,3.3,2,3.8,4.4,5.2,2.3,-4.7
NiceHCK NX7 Pro (red)|1.3,1.2,0.6,-0.3,-1.2,-0.1,1.4,2.9,3.8,1.6,-3.1,2.1,-1.1,3.1,1.7,-4
NiceHCK NX7 Pro (stock)|0.4,0.2,-0.2,-1.1,-2,-0.9,0.8,2.4,3.6,2.1,-0.5,2.9,1.9,4,2.2,-4.3
NiceHCK PK2|4.5,4.5,4.5,1,-4.8,-6.6,-7,-6,-4.9,-4.6,-3.2,4.5,4.5,-1.5,2.3,4.5
NiceHCK Topguy|2.6,1.4,-0.4,-2.1,-4,-3.7,-2.3,-0.7,0.8,-0.8,-1.9,-2.9,-1,1.4,5.2,1.6
NiceHCK Traceless|1.3,0.5,-1.2,-3,-5.8,-7.1,-7.2,-7,-5.9,-2.8,1.1,5.8,5.8,5.8,5.8,5.8
Night Oblivion Butastur (off-off)|-0.2,-0.6,-1,-1.7,-2.6,-1.8,-0.5,0.5,1,-0.8,-1.9,3.2,2.9,1.3,1.6,-1.1
Night Oblivion Butastur (off-on)|-0.3,-0.4,-0.6,-1.2,-2.1,-1.4,-0.1,0.8,1.4,-0.5,-1.6,3.4,2.7,-0.9,-2.6,-1.7
Night Oblivion Butastur (on-off)|-1.1,-1.2,-1.5,-2.1,-2.8,-2.2,-0.9,0.2,0.9,-0.7,-1.4,3.9,3.3,2,2.2,-0.1
Night Oblivion Butastur (on-on)|-0.9,-1,-1.1,-1.8,-2.6,-1.9,-0.5,0.5,1.3,-0.4,-1.1,4,3.3,-0.2,-1.7,-0.8
Nintendo Switch stock earbuds|4,4,2.3,-1.2,-5.6,-7.6,-6,-0.4,-0.3,-3.5,-4.9,3,4,1.7,1.5,4
Nixon RPM|2,-1.1,-3.3,-4,-5,-5.4,-5.5,-3.6,-3,-2.8,0.6,5.8,3.8,2.4,5.7,1.6
Noble Audio Django|0.3,-0.2,-1.6,-3.4,-5.9,-6.7,-5.9,-4.4,-1.8,-0.1,2.6,5.5,5.3,5.6,5.5,5.7
Noble Audio Dulce Bass|-1.3,-1.7,-2.8,-4.3,-6,-5.5,-3.8,-1.5,1.3,2.4,2.5,4.9,6.5,0.9,5.1,6.5
Noble Audio Falcon|-2.9,-1.5,-1.3,-2.6,-3.3,-0.8,1.1,3.1,4.9,4.7,3.6,2.8,-2.6,1.1,-3.3,-2.5
Noble Audio Fokus Mystique|-5.5,-4.4,-3.5,-3.5,-3.3,-1.2,1,3.2,4.8,4,3.1,2.9,3.3,3.1,-2.4,-1.2
Noble Audio Fokus Pro|-3.9,-2.9,-2.1,-2.2,-2.4,-0.8,0.9,2.8,4.3,3.7,2.7,1.7,2.7,0.4,-1.2,0.1
Noble Audio K10|2.2,1.4,-0.1,-1.7,-3.7,-3.7,-2.5,-1,0.5,-0.4,-1.9,0.4,4.7,3.6,-0.2,2.2
Noble Audio K10 CIEM|0.8,0.2,-1,-2.4,-3.9,-3.8,-2.4,-0.3,1.4,-0.3,-1.3,1.5,5.8,-0.6,1.4,1.2
Noble Audio K10 Encore|2.8,2,0.5,-1.3,-3.6,-4,-3,-1.7,-0.3,-0.1,-0.4,0.3,4.5,2.5,-0.7,2.5
Noble Audio Kadence|1.8,1.3,0.5,-0.8,-2.3,-1.9,-0.7,1,2.7,2.2,0.2,1.5,-1.2,-3,-1.8,0.2
Noble Audio Katana|2,1.3,-0.2,-1.9,-4.2,-4.5,-3.3,-1.8,0,0.1,-0.4,0.6,4.5,2,1.3,4
Noble Audio Khan|1.7,2.1,2.2,1.7,0.8,0.9,0.8,0.1,-0.6,-0.6,-0.5,2.1,3.9,1.4,-5.4,-6.7
Noble Audio Kublai Khan|-0.1,0.8,1.4,1,0.2,0.2,-0.1,-0.4,-1.5,-1.9,-0.1,3.3,6.3,4.3,-0.4,-3.7
Noble Audio M3|-1.8,-1.2,-0.5,-0.7,-1.2,0.2,1.6,3.1,4.1,3.3,1,-5.7,1.3,-3,1.9,-1
Noble Audio PR P Tuning|5.1,5,3.5,1.7,-0.7,-1.9,-1.7,-1.3,0,0.1,1.2,1.8,2.8,-4,-5.9,-3.8
Noble Audio PR R Tuning|4.5,4.5,4.5,2.9,0.4,-1.2,-1.3,-1.4,-1.4,-3,-4,-3.1,-1.5,-3.3,-1.6,-3.2
Noble Audio Sage|1.2,0.6,-0.7,-2.5,-4.8,-5.2,-4.2,-2.5,-0.3,0.2,-0.3,1.3,4.1,-0.6,2.6,4.9
Noble Audio Savanna|4.5,4.5,2.3,0,-3,-4.5,-4.4,-3.6,-2.2,-2,-1.6,0.7,4.3,4.3,-0.8,2.3
Noble Audio Savant 2|0.6,-0.1,-1.5,-3.1,-5.5,-6,-4.9,-3.2,-0.8,-0.1,-0.1,2.1,5.1,3.6,3.3,5
Noble Audio Sultan|-1.3,-0.9,-0.8,-1.5,-2.4,-1.8,-0.8,0.3,1.3,1,1,2.8,6,0.1,-1.8,0.2
Noble Audio Sultan SE|-2,-1.2,-0.8,-1.3,-2.2,-1.7,-0.8,0.1,1.1,0.7,0.9,2.2,5.7,-0.9,-0.7,1.6
Noble Audio Trident|1.3,0.6,-0.7,-2.5,-4.9,-5.5,-4.5,-2.7,0,1.5,2,4.4,5.6,-1.5,0.5,5.7
Noble Audio Tux 5|-2.9,-2.3,-2,-2.4,-3,-2,-1.1,-0.3,1,2.1,3.2,6.3,7.2,1.2,0.4,-0.1
Noble Audio Viking Ragnar|2.3,3.1,3.5,2.9,1.8,1,0,-1.8,-3.1,-3.2,-0.8,2.9,5.3,2,-5.7,-7.5
Nocs NS 400|-4.9,-4.2,-3.7,-4,-4,-2,0.1,1.9,4.1,3.9,4.5,2.2,4.5,4.5,0.7,3.8
Nocs NS200|-2.3,-1.4,-1.1,-1.9,-3,-2.7,-1.3,0,2.1,1.9,2.1,1,3.8,2.5,1.4,4.2
Nocs NS300|3.7,0.9,-1.3,-1,-1.1,1.6,-2.5,-4.3,-5.2,-4,0.9,-0.3,-4.1,-0.9,-0.6,4.6
Nocs NS700|-1.9,-2.8,-3.8,-3.2,-3.3,1.4,-1.3,-1.4,-0.2,0.8,2.6,2.2,-3.2,0.6,3.9,4.8
Nocs NS800|1.3,1,-0.1,-1.7,-3.7,-4.4,-3.9,-3.2,-1.7,-1.6,-0.4,0.1,2.8,5.5,2.3,1.3
NocturnaL Audio Atlantis|1.8,1,-0.5,-2.2,-4.6,-5.4,-4.7,-3.4,-1.5,-1,-1.1,1.9,4.6,2.3,2.6,5
NocturnaL Audio Avalon|0.3,-0.4,-1.7,-3.3,-5.5,-6,-5,-3.1,-0.2,1.8,3.5,3,5.8,4.5,1.3,2.4
NocturnaL Audio Eden|4.3,3.5,2,0.2,-2.4,-3.6,-3.4,-2.8,-2,-1.2,-4.9,-1.1,3,0.4,1.6,4.3
NocturnaL Audio Gorham|0.7,-0.1,-1.3,-3,-5.1,-5.4,-4.5,-2.6,-0.1,1,1.2,2.9,5.7,5.3,2.2,0.8
Noontec Hammo S|0.9,0.2,-0.6,-0.5,-3.6,-3.4,-0.4,0.9,-0.5,-2,-1.9,1.3,-3.9,3.1,2.1,2.2
Noontec Rio|-5.8,-4.5,-3.3,-3.1,-2.9,-1,0.9,2.3,4.3,3.5,3.2,0.2,-3.5,-0.7,7.8,7.8
Noontec Zoro|0.3,-0.6,-1.9,-2.9,-4.3,-5,-3.9,-3.5,-1.3,-0.4,-0.2,6.6,6.8,6.7,0.1,1.9
Noontec Zoro HD|-1.7,-2.3,-3.5,-4.4,-5.4,-5.1,-3,-1.7,0,1,3.1,4.2,1.3,5.1,4.8,5.9
Noontec Zoro II HD|0.2,-0.1,-1.2,-2.2,-3.9,-4.1,-2.2,-1.7,-0.3,-0.9,-0.7,1.7,2,1.9,2.7,4.9
Noontec Zoro II Wireless (active)|0.8,-0.3,-1.5,-3.1,-5.2,-5.4,-3.3,-2.4,0.4,0.7,0.9,2.9,0.8,2.7,4.2,5.5
Noontec Zoro II Wireless (passive)|0.2,-0.3,-1.1,-2.7,-4.7,-4.9,-2.8,-2.1,0.4,0.6,0.6,2.6,0.9,1.8,5.9,5.9
Nostalgia Audio Benbulbin|-1.2,-0.1,0.7,0.2,-0.9,-1.4,-1.4,-1.3,-1.1,-1.9,-0.2,0,0,1.9,2.5,3.3
Nothing ear (1)|-0.9,0.8,1.5,0.9,1.1,1,0.1,0,2.7,4.5,4,2.6,-1.7,-5.8,0,-2.1
Nothing ear (2)|-1.3,-2,-2.1,-1.8,-0.5,0.1,-1,-1.6,-1,-0.4,-0.4,0.3,0.4,1.4,2,3.1
Nothing ear (stick)|3.8,1.7,2.4,2.3,0.8,-0.6,-2.1,-1.6,2.1,1.6,1,-1.3,-2.2,-3.4,-4.2,-2.8
NS Audio NS3|4.5,4.5,4.3,2.2,-0.5,-1.5,-1.5,-1.1,-0.7,-2.6,-4.6,-5.5,-2.6,2.1,2,1.1
NS Audio NS5 MKII Extra Bass|1.7,1.6,0.9,-0.3,-1.7,-1.4,-0.6,0.3,1,0.1,-0.6,-0.1,0.7,-0.1,2.7,-0.6
Nuarl NT01|-1.8,-2,-0.5,-1.6,-4.1,-4.2,-2.7,-0.6,1.7,2.3,1.6,0.5,5,5.6,-1.2,-0.2
Nuarl NT100|-3.8,-3.7,-3.9,-4.4,-5,-3.3,-1.3,1,3.4,3.9,2.9,2,2.4,5.3,1.4,2.5
Nuarl NT110|-4.3,-4,-3.9,-4.3,-4.7,-2.9,-0.8,1.4,3.5,3.7,2.2,1,3.8,7.2,3.4,2.6
NuForce EDC3|2.6,2.2,0.9,-0.8,-3.1,-5,-4.8,-4.2,-3.1,-3.7,-4,0.8,4.9,3.6,3.2,4.5
NuForce Every Day Car|-7.4,-6.5,-5.5,-5.5,-5.5,-3.7,-2,-0.1,1.7,1.6,1.6,2.1,6.5,8.1,8.1,6.5
NuForce HEM1|0.6,0,-0.9,-2.3,-4.3,-4.8,-4,-3,-1.2,-0.7,-1,-1,2,3.4,5.1,5.1
NuForce HEM8|1,0.5,-1,-2.8,-5.1,-6.1,-5.5,-4.5,-2,-1.9,3.2,6,4.9,6,0.4,2.4
NuForce HP-800|-0.8,-2.1,-2.9,-3.7,-4.8,-4.4,-0.1,2.3,1.3,2.7,2.5,-3,-2.9,5.9,3.4,3.4
NuForce NE-600X|-5.2,-4.3,-3.6,-3.7,-3.4,-1.4,0.7,2.4,4.3,4.1,4,1.6,0.8,0.6,6.9,2.7
NuForce NE-700M|-4.5,-3.3,-2.3,-2.2,-2.1,-0.5,1.2,2.5,3.9,3.1,2.3,-1.1,1,0.9,6.7,2.2
NuForce Primo 8|3.3,2.7,1.4,-0.3,-2.9,-4.2,-4.3,-3.6,-2.8,-3.4,-5.4,-0.9,-0.2,4.6,4.6,4.6
NuForce Stride|0.5,-0.3,-1.5,-2.9,-3.9,-3.8,-1.8,0.7,3.4,3.8,4.3,4.1,-0.6,-1.7,0.2,-0.3
Nura NuraLoop|-2,-1.4,-1.5,-2.2,-3.2,-3.1,-4.5,1.5,5.2,2.8,-0.1,-0.5,0.2,6.8,4.3,-0.2
Nura Nuraphone|2.2,2.5,-5.2,-3.4,-0.3,5.3,4.9,5.8,5.1,2.4,-2.5,-2.4,-2.2,-2.2,-2,-5.5
NVX EX10S|3.2,2.3,0.6,-1.2,-3.5,-4.5,-4.4,-4.7,-4.8,-6.1,-3,4,5,4.5,3.2,2.4
NVX XPT100|3.8,2.6,1.1,0.1,-2.7,-0.9,2.1,-1.2,-1.5,-2.8,-3.3,-2.8,-4.1,1.9,5.3,4.4
NVX XPT100 (flat earpads)|4.4,3.4,1.6,0.5,-1.5,-0.4,1.6,-1.1,-1.3,-2.6,-3.6,-4,-5.4,0.4,4.7,2.7
NXEars Basso|3,1.6,-0.2,-2.3,-4.9,-6.1,-5.6,-4.3,-2.1,-0.9,0.5,4.1,5.1,3.5,1,5
NXEars Opera|2.6,2,0.4,-1.4,-4.5,-7.1,-8,-5.2,1.2,0.8,2.7,1.3,5.4,2.3,2.7,1.2
NXEars Sonata|3.2,2.5,1.3,-0.4,-2.8,-3.7,-3.1,-2,-0.4,0.1,-3.5,-1.9,1.4,-0.7,1.5,1.7
Ocharaku Co-Donguri Shizuku|0.7,0.2,-0.7,-2,-3.3,-2.4,-1,1.1,3,2.8,1,-5.4,-1.9,-2.7,5.6,4.3
Ocharaku Donguri Keyaki|4.9,4.9,4,1.4,-2.2,-4.3,-4,-1.9,2.1,2.8,1.7,-0.1,-1.8,-5.2,-0.3,-2.3
Ocharaku Donguri Ti Plus|0.9,0.1,-1.1,-2.5,-4.1,-3.3,-1.7,0,1.6,0.6,-3.7,-5.1,-1.7,1.9,5.6,5.6
Ocharaku Flat-4 Akakeyaki|2.8,2.4,1.6,-0.1,-1.9,-2.1,-1.4,-0.5,0.2,0.1,2.9,3.1,2.4,-4.7,-2.7,-1.8
Ocharaku Flat-4 Akazakura|2.1,1.6,0.5,-1,-2.7,-2.3,-1.2,0,1,0.4,3.5,4.2,0.4,-3.4,-0.6,-0.8
Ocharaku Flat-4 Kaede Type 1|3.2,2,0.5,-1.5,-3.8,-3.8,-2.4,-0.7,1.2,0.4,3.6,5.9,2.4,-2.8,-3.5,-2.5
Ocharaku Flat-4 Kaede Type 2|5.5,4.8,2.9,0.3,-2.6,-3,-2.5,-0.9,0.4,-0.7,2.4,5.5,0.9,-3,-5.9,-4.6
Ocharaku Flat-4 Kuro Type II|2.4,1.8,0.9,-0.4,-1.8,-1.5,-0.5,0.2,0.6,-0.7,3.8,4.5,-1.9,-5.1,-1.9,-3.5
Ocharaku Flat-4 Ti Plus|1.8,1.5,0.9,-0.6,-2.2,-1.5,-0.5,0.3,0.7,-0.8,4,2.7,2.8,-3.6,-2.9,-1.4
OLLO S4R|5.4,-0.4,-1.4,-1.9,-4.5,-2.6,3.4,4.1,0.4,-0.5,-1.7,-2.1,-0.6,0.4,3.8,1.2
OLLO S4R 1.1|4,4.6,1.6,-0.9,-1.8,-2.6,-2.4,-2.9,-3.6,-4.2,-4.7,-2.2,1.7,0.7,4.6,4.6
Omthing Airfree 2|2.1,-0.2,-2.5,-4.2,-5.5,-4,-2.9,-2.3,-0.7,1,4.2,3.8,5.6,6.4,0.3,-3.5
OneOdio A10|4.8,4.8,4.8,4.7,-1.3,-4.9,-4.6,-5.2,-2,1.7,2.9,-1,-2.8,1.4,0.5,-0.6
OneOdio A71D|-0.5,0,-2,-5.2,-6.5,-3.4,0.6,5,5.4,3.5,3.7,0.4,2.1,0.6,-1.7,-0.8
OneOdio OpenRock Pro|4.7,4.7,4.7,4.7,-2,-3.8,-3.1,-2.3,-1.6,-0.4,3.2,-4.1,-2.5,1.5,-1.3,-2
OneOdio OpenRock S|4.7,4.7,4.7,4.7,2.3,0.9,-0.9,-1,-0.9,-0.6,-3.3,-5,-3.2,-3.7,-3.3,-3.2
OnePlus Buds|4.3,4.3,4.3,4.3,1.6,1.6,1.4,-0.8,-1.3,-2.4,-6,-2.8,-3.3,-3.7,-5.3,-1.9
OnePlus Buds Pro|-0.6,-1.4,-2.4,-2,-0.2,-0.6,-0.7,-0.3,1.7,1.2,-0.1,0.1,3.6,0.5,1.2,0.9
OnePlus Buds Pro 2|-0.4,-1.7,-2.3,-1.7,0.4,-0.6,0.1,2.5,4.1,1.8,0.3,1.6,4.2,0,-0.2,-2.2
OnePlus Buds Z|-7.5,-6.5,-5.1,-4.2,-3.4,-1,0.4,1.3,3.7,4.1,6.1,6.9,5.1,1,4,2.6
Onkyo A800|3,0.4,-0.9,-0.9,-1.2,-2.6,-1,-0.5,-0.6,-0.8,-0.7,2,2,-0.6,0.9,0.7
Onkyo E900|2.6,1.7,0.2,-1.6,-3.4,-3.4,-2.4,-1.4,-0.1,0.3,0.8,3.5,6.2,2.7,-0.6,-1.2
Onkyo ES-FC300|-2.4,-3.2,-2.3,-3.8,-4.6,-0.2,-2,-2.2,-0.6,0.5,2.3,2.2,0.4,6.5,3.4,3.4
Onkyo IE-HF300S|0.2,-0.6,-2.1,-3.7,-5.5,-5.4,-4,-2.4,-0.4,-0.1,0.8,4.3,3.6,0.3,1.7,5.3
OpenAudio Mercury|-2.9,-1.9,-0.9,-1,-1.3,-0.2,0.8,1.6,1.9,0.6,-2.1,0.5,3.6,3.3,6,2.9
Oppo Enco Air3|1.2,-1.8,-5.7,-6.5,-5.4,-5.5,-3.5,-0.1,-0.5,0.7,2.9,1.5,5.2,5.1,0.7,2.2
Oppo Enco X|-0.4,-0.8,-1.2,-1.8,-0.7,2.2,0.3,-0.1,0.9,1.9,2.5,3.2,1.3,-1.7,-1,-1.1
Oppo Enco X2|-1.7,-1.8,-1.8,-0.7,-1.3,-5,-4.9,-3.4,-0.3,1.9,4.3,5.6,3,1.8,1.2,0.4
Oppo Enco X2 (Enco classic preset)|-2.1,-1.8,-1.8,-0.9,0.2,0.9,0.5,0.3,1.4,2,3.1,4.5,2.1,2.5,-0.2,-3.1
Oppo PM1|2.9,2.3,0.2,-1.1,-3.1,-4.6,-3.6,-3.6,-4.8,-2.7,-1.6,0.8,-0.9,3.7,2.1,3.1
Oppo PM1 (alternative earpads)|2.2,1.8,0,-1.3,-3.2,-4.5,-4,-3.1,-3.9,-2.1,-1.2,0.8,-0.3,4.7,2.1,1.9
Oppo PM1 (PM2 earpads)|2.4,2.1,0.4,-1.2,-2.9,-4.2,-3.4,-2.7,-3.3,-1.9,-1.7,-0.7,-1.7,3.3,2.6,2.6
Oppo PM1 (velour earpads)|3.1,2.9,-0.1,-1.4,-3.3,-4.4,-4.3,-3.4,-3.9,-1.4,-0.1,1.8,-0.7,3.5,0.5,1.2
Oppo PM2|4.4,3.1,1,-0.2,-1.9,-4.6,-3.7,-2.4,-4.4,-3.5,-2.1,-0.9,0.7,3.1,1.9,3
Oppo PM2 (PM1 alternative earpads)|2.5,2.5,0.8,-0.6,-2.1,-4.1,-1.9,-3.3,-2.5,-3.1,-1.8,0.1,-1,4.3,0.7,1.2
Oppo PM2 (PM1 leather earpads)|2.7,2.4,0.7,-0.5,-2.3,-4.5,-2.4,-3.9,-3.2,-3.5,-1.4,1,-0.1,3.8,0.1,2.5
Oppo PM2 (PM1 velour pads)|2.3,2.2,0.4,-0.8,-2.4,-4.5,-2,-3.4,-2.3,-2.4,-0.7,1.7,-0.8,3.5,-0.4,0.7
Oppo PM3|1.9,2.2,-0.6,-3.5,-4.4,-1,2,0.9,-0.5,0,-0.8,-1.2,0.7,1.4,0,2.3
Oppo PM3 (Aliexpress earpads)|3.5,3.4,-2.8,-4.1,-3.7,0.5,3.4,1,-0.4,-0.7,-1.9,-2.4,-0.3,0.4,1.6,3.2
Oppo PM3 (Aliexpress leather earpads)|2.3,2.8,-0.1,-2,-3,-0.7,1.7,0.6,-1,-1,-2.4,-2.5,-1,0.8,3,2.5
Oppo PM3 (sample A)|0.5,0.3,-0.3,-1,-2,-1.3,0.2,-1.5,-1.9,-2.8,-3,-3.8,-3.8,3.4,5.2,5.2
Oppo PM3 (sample C)|2.1,1.8,0.9,-0.1,-2.8,-1.7,1.4,-0.5,-2.2,-2.8,-1.9,-3,-4.9,1.8,2.1,3.7
ORA GrapheneQ|2.1,0.3,-4.3,-4.7,-0.6,6.1,5.3,2.2,0,-0.1,1.7,0.7,-1.8,0.6,-0.2,-1
ORB CF-IEM Stella|1.1,0.2,-1.3,-3.2,-5.7,-6.6,-5.9,-4.3,-1.6,1,-0.3,2.1,2.5,5.2,5.2,5.2
Origami Audio Silver|-1.3,-2.3,-3.7,-5.1,-6.6,-5.6,-3.4,-0.7,2.7,1.9,1.3,2.5,3.5,2.5,6.4,3.5
Oriolus Finschi|-3.5,-3.3,-3.4,-4,-4.5,-3.3,-1.7,-0.3,0.7,-0.3,-1.4,2.4,6.1,5.2,6.3,6.4
Oriolus Finschi Hifi|-4.2,-3.8,-3.6,-4,-4.5,-3,-1.4,0.2,1.3,0.4,-0.8,2.5,6.1,5.6,4.5,6.1
Oriolus Forsteni|0.3,0.4,0,-0.9,-2.2,-2,-1.1,-0.1,0.8,0.1,-0.1,3.6,6.3,4.3,-3.3,-1.2
Oriolus Isabellae|2.1,1.7,0.7,-0.7,-2.5,-2.3,-1.1,0.5,1.9,1.3,-0.9,-1.8,1.8,0.4,0.5,-0.4
Oriolus Mellianus|1.7,1,0,-1.1,-2.3,-1.6,-0.4,0.6,0.9,-1.4,-4.2,1.6,-0.7,5.8,1.2,-0.3
Oriolus Mk2|-0.6,-0.4,-0.7,-1.8,-3.3,-3.4,-2.7,-1.7,-0.5,-1,-2.8,0.8,1.9,4.9,4.4,5.3
Oriolus Percivali|-0.4,-0.6,-0.8,-1.5,-2.2,-1.2,0,1,0.9,-1.6,-4.9,1.2,1,1.1,3.5,2.6
Oriolus Traillii|-0.3,-0.6,-1.2,-2.3,-3.2,-2.1,-0.8,-0.2,0.1,-0.4,-1.3,0.6,4,5.2,3.4,0.7
Oriveti Basic|-3.2,-3.2,-3.6,-4.6,-5.7,-4.6,-3.3,-1.6,0.2,0.7,3,7.1,6.4,3.1,7.1,7.1
Oriveti New Primacy|1.1,0.4,-0.9,-2.5,-4.7,-5.4,-4.8,-3.9,-2.3,-0.5,0.5,5.4,5.4,0.6,-0.9,4
Oriveti OD100|2.1,1,-0.4,-1.8,-3.1,-2.3,-0.8,0.9,2.3,2.3,1.3,-2.1,-1.3,-3.4,0.7,-0.9
Oriveti OD200 (black nozzle)|3,1.7,-0.1,-1.7,-3.5,-3.2,-1.9,-0.5,0.6,-0.1,-1.3,-4.7,-2.4,2.4,2,0.9
Oriveti OD200 (silver nozzle)|1.8,0.6,-0.9,-2.4,-4,-3.4,-2,-0.4,0.9,0.5,0,-1.3,-0.9,2.9,0.4,-0.4
Oriveti OH300|-1,-1,-1.2,-1.9,-2.8,-1.9,-0.5,1.4,1.6,0.6,-1.7,0.2,1.9,6.4,0.6,0.6
Oriveti OH500|-0.4,-0.6,-1,-1.8,-2.9,-2.4,-1.3,0.3,-0.2,-1.8,-4.5,-0.1,1.3,5.5,2.7,5.1
Oriveti OH700VB (bass switch off)|-0.7,0.2,1,1.1,1,0.3,-0.1,-0.1,0.1,-0.6,0.2,2.4,2.3,4.2,1,-3.6
Oriveti OH700VB (bass switch on)|0.2,1.4,2.7,3.1,2,-0.3,-1,-1,-0.7,-1.3,-0.5,1.9,1.7,3.7,0.5,-4.2
Ortofon 0-One|4.9,3.1,1.1,-0.1,-0.8,1.9,-0.7,-4.8,-2.1,-3.1,-2.5,5.5,0.3,-1.5,0.3,-1.8
Ortofon 1|5.5,5.5,5.5,4.1,2.9,5.5,-0.2,-6.3,-3.7,-4.6,-3.1,4.1,-1.2,-6.6,-1.7,-3.5
Ortofon 2|4.9,4.8,4.4,2.7,1.8,-1.2,-5.7,-4.9,-4.1,-4.5,-2.3,-0.6,-3.4,0.1,0.4,-2.6
Ostry KC06|4.4,3.2,1.2,-0.7,-3,-3.3,-2.2,-0.9,0.5,-0.7,-2.1,0.1,5.3,5.5,-2.9,-3.9
Ostry KC06A|-0.8,-1.2,-2.2,-3.5,-4.7,-3.5,-1.5,0.9,2.9,2.5,2.4,4,6.3,3.4,-1.5,-1.3
Ourart Wine|-1.4,-1.8,-2.5,-3.4,-4.3,-2.7,-0.9,1.3,3.5,3.5,2.9,-1.1,-2.6,-6.7,5.5,5.9
Ovidius RX-100|-0.8,-0.5,-0.6,-1.1,-1.9,-1.1,0,0.9,1.6,0.2,-2.1,-0.6,0.1,3.3,6.1,1.2
Ovidius TX-901|2.2,1.8,0.6,-0.7,-3,-3.8,-3.7,-4.1,-1.7,-3.7,-2.3,5.9,5.9,0,2.2,0.3
Pai Audio DM2A|1.9,1.6,0.6,-0.9,-2.7,-2.5,-0.9,1.1,2.8,-1.1,-3.5,0.6,-0.6,1.8,-0.1,-0.4
Pai Audio DR2|-5.8,-4.4,-3.2,-3.1,-2.8,-0.6,1.7,4.2,6.2,2.5,0.4,-1.5,0.1,3,3.4,2.9
Panasonic RP HT600 S|0.2,-0.9,-1.3,-1.6,-2.9,-4,-3.8,-4.1,-2.4,1.4,-3.3,2.5,2.8,5.6,2.6,1.6
Panasonic RP-HC101|5.2,3.4,0.1,-1.2,-2.8,-3.2,-3.6,-5.1,-1.9,0.6,-0.9,1.5,5,5.2,0.5,1.1
Panasonic RP-HC800|-1.6,-1.4,-1.7,-2.2,-3.5,-3.4,-1.6,0.9,7.6,7,7.4,2.6,6.5,1.3,-3.3,-3.9
Panasonic RP-HJE120|-0.9,-2,-3.7,-5.3,-6.9,-6.1,-4,-1.2,2.1,3.6,4.1,2.4,3.6,0.4,5.5,6
Panasonic RP-HJE125E|-1.7,-2.5,-4,-5.3,-6.6,-5.4,-3.4,-0.7,2.1,3.5,4.7,1.3,3,-0.9,4.9,5.6
Panasonic RP-HJE270E|1.2,-0.3,-2.3,-4.2,-6.2,-5.3,-3.5,0,4.6,5.5,5.5,1.4,-2.7,-1.6,0.9,2.8
Paradigm Shift E1|-3.2,-3.4,-4.6,-6.1,-7.8,-7.5,-5.5,-3.1,-0.2,0.7,5.4,7.5,7.5,7.1,7.4,6.2
Paradigm Shift E3m|-2.6,-3,-4.3,-5.9,-8,-8.3,-6.8,-4.4,0.2,3.4,5.1,7.4,7.4,7.4,6.9,6.1
Parrot Zik 2.0|-1.9,-0.3,1,2,2.4,2.9,4.1,4.8,6.5,-0.3,-5.6,-3.8,5,2.1,-6.3,-4.8
Parrot Zik 3.0|-2.2,-1.4,0.4,0.6,0.6,2,3.5,3.3,5.1,-1.5,-3.9,-2.3,5.9,4.8,-1.1,-5.3
Peacock Audio P1|-0.6,-1.3,-2,-2.7,-3.7,-1.3,1.3,2.5,2.8,3.2,3.3,0.4,-0.2,-2.8,4.9,0.5
PEARS SH2|2.2,1.6,0.2,-1.5,-4,-4.9,-4.4,-3.3,-1.8,-1.2,-0.4,2.6,3.7,1.3,1.4,1.8
PEARS SH3|3.8,2.8,1.2,-0.7,-3.3,-4.3,-3.8,-2.7,-0.9,-0.5,-2,0.6,0,-0.7,0.9,1.9
PEARS SH3 (CIEM)|2.8,2.1,0.7,-1,-3.6,-4.4,-3.9,-2.5,-0.5,-0.3,-2.7,0.3,-0.3,1.4,1.4,3.3
Peerless MB PMB 85 Double Action|5,5,5,1.6,-1,-0.3,0.4,0.5,0.1,-1.7,-4.7,-6.7,-3.9,1.3,0.2,-1.2
Pendulumic Stance S1 Bluetooth|5,3.8,-1.5,-3.5,-4.4,-4.5,-3.8,-2.3,-1,-1.7,-1.9,3.7,2.2,2.5,1.2,-0.4""",
"""Penon 10th Anniversary IEM|-0.8,-1,-1.8,-2.9,-4.1,-2.9,-0.7,1.2,1.8,-0.4,-0.2,3,1.1,3.1,-0.2,-0.6
Penon DOME|0.9,0.7,-0.2,-1.3,-2.2,-0.5,1,1.5,-1.3,-2.4,-2.2,1.5,2.3,-0.5,0.6,0.2
Penon Fan|-3.5,-3.1,-3,-3.5,-4,-2.3,-0.4,1.7,3.6,3.4,2.7,0.9,2.9,-1.8,3.3,2.7
Penon Fan 2|0.9,0.5,-0.6,-2.3,-4.4,-4.8,-3.9,-2.2,-0.3,-1.6,0.5,1.5,3,4.2,2.6,1.1
Penon Globe|-1.7,-1.8,-2.4,-3.6,-5,-4.5,-3.4,-1.9,0,1.3,3,5.8,4.3,2.7,0,1.4
Penon Orb|-4.3,-3.6,-2.6,-3,-4.1,-3.4,-2.6,-1.3,0.3,0.6,2.2,4.4,4.1,2.9,3.3,6.3
Penon Serial|-0.2,-0.3,-0.8,-2,-3.2,-2.5,-1.4,-0.1,0.6,-0.9,-1.7,1.1,1.9,3.6,2.8,1.1
Penon Sphere|0,-0.6,-2,-3.7,-5.9,-6.1,-4.9,-2.9,-0.3,0.8,2.2,2.7,2.5,1.6,4.1,5.3
Penon Turbo (off, off-off-on)|2.4,1.9,1.1,0.1,-0.7,0.7,1.4,0.7,-1,-2.8,-2.3,-2.1,-0.8,-2.6,-0.8,-0.3
Penon Turbo (off, off-on-off)|1,0.7,-0.1,-0.9,-1.5,0.3,1.7,1.5,-0.1,-1.9,-1.4,-1.2,-0.1,-2,-0.2,0.4
Penon Turbo (off, off-on-on)|0.6,0.2,-0.4,-1.3,-1.8,0.1,1.8,1.7,0.1,-1.8,-1.3,-1.2,0.1,-1.9,0.1,0.6
Penon Turbo (off, on-off-off)|-0.9,-1,-1.3,-1.9,-2.1,0.4,2.4,2.7,1.1,-0.9,-0.5,-0.2,0.6,-1.5,0.4,0.8
Penon Turbo (off, on-off-on)|-0.4,-0.7,-1.3,-2.1,-2.5,-0.2,1.8,2.4,0.8,-1.1,-0.6,-0.6,0.6,-1.4,0.6,1.1
Penon Turbo (off, on-on-off)|-0.4,-0.7,-1.3,-2.1,-2.5,-0.3,1.8,2.4,0.8,-1.1,-0.7,-0.6,0.6,-1.3,0.6,1.1
Penon Turbo (off, on-on-on)|-0.4,-0.7,-1.4,-2.1,-2.5,-0.3,1.8,2.4,0.8,-1.1,-0.7,-0.6,0.6,-1.4,0.5,1.1
Penon Turbo (on, off-off-off)|-3.5,-3.5,-3.8,-4.3,-4.5,-1.8,1.4,4.2,3.6,1.2,1.3,1.1,2.3,0.1,1.9,2.3
Penon Turbo (on, off-off-on)|-3.8,-3.8,-4,-4.5,-4.7,-1.9,1.3,4.3,3.9,1.6,1.5,1.2,2.4,0.2,2,2.5
Penon Turbo (on, off-on-on)|-3.9,-3.9,-4.1,-4.6,-4.8,-2,1.2,4.4,4.1,1.6,1.6,1.3,2.4,0.2,2,2.5
Penon Turbo (on, on-off-off)|-4,-4,-4.1,-4.7,-4.8,-2,1.3,4.5,4.2,1.8,1.7,1.3,2.5,0.3,2,2.5
Penon Turbo (on, on-off-on)|-3.8,-3.8,-4,-4.6,-4.7,-2,1.2,4.4,4,1.6,1.7,1.3,2.5,0.2,2,2.5
Penon Turbo (on, on-on-off)|-3.9,-3.9,-4,-4.6,-4.8,-2,1.2,4.4,4.1,1.6,1.6,1.2,2.4,0.2,2.1,2.5
Penon Turbo (on, on-on-on)|-4,-3.9,-4.1,-4.6,-4.8,-2,1.2,4.4,4.1,1.7,1.7,1.3,2.5,0.2,2.1,2.6
Penon Vortex|1.6,1.4,0.4,-0.9,-3,-3.3,-2.4,-0.9,0.6,0.3,0.4,2.5,2.4,0.8,2.3,-0.9
Penta Driver|0.5,-0.1,-1,-2.1,-3.1,-2.3,-1.1,0.2,0.7,-1.3,-3.3,3.1,5.7,3.5,6.8,0.4
Periodic Audio Be|-2.8,-2.5,-2.3,-2.9,-3.4,-1.4,-0.4,1.4,3.3,2.4,1.6,1.5,5.1,4.5,2.6,3.3
Periodic Audio Beryllium V3|-4.2,-3.8,-3.8,-4.3,-4.7,-2.8,-0.8,1.6,3.6,3.6,2.8,1,1.5,4.6,7.3,4.7
Periodic Audio Carbon|-4.9,-4.3,-4,-4.3,-4.3,-2.1,-0.1,1.9,3.6,2.9,1.2,-0.9,3.4,0.3,6.2,7
Periodic Audio Carbon V3|-5.2,-4.3,-3.6,-3.7,-3.7,-1.6,0.3,2.4,3.9,3.3,1.7,-1.1,0.6,-0.3,7.3,6.8
Periodic Audio Magnesium V3|-3.4,-3,-3,-3.6,-4,-2.3,-0.4,1.7,3.3,3.4,1.8,0.8,0.7,2.1,7.6,4.3
Periodic Audio Mg|-2.5,-2.7,-3.1,-4.1,-5,-3.6,-1.6,1,3.9,5.2,4.6,3.1,3.4,1,5.6,2.7
Periodic Audio Ti|-0.4,-1.3,-2.4,-3.8,-5.1,-3.8,-2,0.6,3.8,5.5,4.7,3.6,-0.6,-4.1,3.1,2.5
Periodic Audio Titanium V3|-4.5,-3.6,-2.9,-3.1,-3.1,-1.1,0.9,3.2,5.1,5.3,4.4,1.5,-5.1,-3.2,5.8,4
Phiaton Bridge MS 500|-0.6,-1.8,-2.7,-2.4,-2.7,1.2,1.9,-0.6,-2.8,-4.4,-1.4,3,-3.8,3.9,5.4,3.4
Phiaton Chord MS 530|1.3,0.2,-1.3,-1.8,-3.7,-2.5,-0.8,-1.4,-3.3,-6.2,-4,-1.3,1.7,5.5,5.5,5.5
Phiaton Chord MS 530 (ANC on)|4.3,2.9,1.7,0.5,-1.4,-3.4,-4,-5.4,-7.7,-9.5,-4.1,-0.1,2.5,4.9,4.9,4.9
Phiaton Chord MS 530 (wireless)|-1,-2.3,-3.4,-3.7,-2.8,-2.2,-0.5,-1.3,-2.2,-5.1,-3.6,-0.5,2,6.3,6.3,6.3
Phiaton Moderna MS200|2.4,0.4,-2.1,-4.4,-6.7,-7.3,-6.2,-4.7,-0.4,2.8,2.6,3,5.3,5.3,-0.7,0.8
Phiaton MS 100 BA|1.4,0.8,-0.8,-2.5,-4.9,-5.8,-5.1,-4.1,-1.9,-1.5,-0.5,2.9,2.1,5.3,4.4,5.2
Phiaton MS 300|4.5,1.3,-0.5,-1.6,-3,-3.1,-3,-2.5,-0.4,-5.8,-4.9,1.8,4.2,4.6,3.4,4.2
Phiaton MS 400|4.3,1.8,0.4,-2.4,-1.2,-3,-1.9,-1.7,-2.1,-5.1,-4.2,2,2,4.2,1.5,2.2
Phiaton PS 20|4.4,3.1,0.7,-1.6,-4.5,-6.4,-6.8,-7,-3.1,1.1,0.2,0.9,4.4,-1.2,1.9,4.4
Phiaton PS 200|3.9,3.5,2.2,0.4,-2.1,-3.4,-3.1,-2.9,-1.8,-2.2,-1.5,1.1,4.5,-0.2,-4,-0.9
Phiaton PS 210|4.3,3.3,1.1,-0.9,-3.6,-5.1,-5,-5.3,-4.1,-0.8,-0.5,-2.2,2.4,0.8,2.8,4.3
Phiaton PS 300 NC|1,-1.6,-3.9,-5.7,-7.2,-6.9,-6.4,-0.1,5.4,1.5,-4.5,1.4,5.9,6,4.2,3.6
Phiaton PS 320|3.2,2.2,1.6,-0.9,-3.5,-3.3,-2.2,2.4,2,-1.2,-1.6,-1.6,2,5.4,1.3,0.2
Phiaton PS 500|2.6,0.3,-1.7,-3.6,-5.5,-4.2,0.1,-0.3,-1.3,-3.5,-3.6,3,5.2,4.2,2.1,3
Philips ActionFit SHQ5200|2.6,-0.6,-3.5,-4.7,-5.8,-5.6,-2.8,-4,-2.4,-2,0.7,1.7,4,1.8,5.7,5.7
Philips Cityscape Downtown|1.2,1,0.8,0.4,-0.7,-2.5,-2.1,-3.8,-5.1,-5.8,-2.4,4.6,0.8,2.9,5.8,5.8
Philips Construct|-3,-2.8,-2.9,-4.9,-4.9,-0.8,1,-3.9,-5.1,-3.2,2,6.6,4.8,5.3,6.6,6.5
Philips Fidelio L1|-1.6,-2.3,-2.9,-2.8,-3.6,-3,-0.9,1,3.8,3.5,-0.7,-3.3,-0.2,6.5,2.9,2.6
Philips Fidelio L2|1.5,0.7,0,-1.4,-2.7,0.8,4.6,6.6,5.7,3.1,-0.9,-5.2,-1.8,0,-0.4,-2
Philips Fidelio M1|-1.5,-1.7,-2.3,-3.2,-2.4,-0.1,0.8,0.7,0.9,0.9,1.3,0.8,1.1,3,3.9,2.4
Philips Fidelio M2L|5.3,4,-0.8,-3.3,-7.8,-5.2,-2.4,-0.9,-0.5,0.3,1.1,2.7,2,3.1,4.3,1.8
Philips Fidelio NC1|0.8,1.3,-0.8,-1.4,-1.8,-2.5,-1.8,0,1.3,3,3.9,1.5,-2.8,-2.2,2.6,4.2
Philips Fidelio S1 Early 2013|0.7,0.7,0.1,-1.1,-2.4,-2.1,-1.2,-0.2,0.2,-0.7,-1.1,0.3,-0.4,-2.9,3.8,2.5
Philips Fidelio S2 (2013)|0.6,0.3,-0.6,-1.8,-3,-2.6,-1.5,-0.9,0.1,-0.5,-0.1,2.9,1,-3.7,0.9,3.2
Philips Fidelio S2 (early 2013)|1.3,1.1,0.2,-0.9,-2.3,-2.1,-1.3,-0.7,0.1,-0.9,-1.3,-0.1,-0.2,-2.4,3.1,2.2
Philips Fidelio X1|2.4,-1.5,-3.5,-3.4,-2.8,-2.4,-0.5,0,0.9,2.6,1.2,0.9,-1.7,-0.6,-1.4,1.6
Philips Fidelio X2|1.7,-1.1,-2.7,-2.5,-1.8,-2.6,-1.1,-1.4,-1,1.6,0.2,1.4,-0.4,-0.7,1.7,-0.8
Philips Fidelio X2HR|3.4,0.5,-1.1,-1.2,-1.2,-2.1,-0.7,-0.5,-1,0.1,-2.3,0.4,1,-4.3,1,1
Philips Fidelio X3|4.7,2.2,-0.3,-1.5,-3.2,-3.8,-2.2,-1.1,-0.8,-0.3,1,5.4,1.4,-0.8,-3.1,-0.8
Philips L2|-1.1,-1.6,-1.6,-1.2,-1.5,-1,0.9,2.2,3.5,2.5,-1.2,-2.5,-1,4.7,1.2,1.6
Philips O'Neil Bend|-0.1,-1.7,-3.5,-4.8,-6.3,-5.7,-3.1,-2.6,-2.4,-2,2.2,5.9,3.1,3.7,5.9,5.9
Philips O'Neil Crash|-2.2,-3,-4.1,-4.9,-6.4,-4.1,-0.8,0.4,3.9,5.3,5.4,-1,1.4,6.7,3,2.3
Philips SBC HP910|4.5,4.5,2.6,0.8,-1,-1,0.4,1.1,0.9,-0.6,0.7,-3.4,-4.5,-4.7,-4.8,0.3
Philips SHE3590|-4.2,-3.1,-2.2,-2.2,-2.1,-0.5,1.2,2.3,3.3,2.7,1.3,-3.2,2.3,-0.1,3.1,4.6
Philips SHE3590PK28|-4.7,-3.9,-3.2,-3.4,-3.3,-1.9,0.3,2.4,4,2.7,3.7,-0.5,1.1,-2.8,3.3,4.6
Philips SHE3855|-2.4,-1.8,-1.5,-1.9,-2.1,-1.4,0.4,1.8,2.7,1.5,0.1,-0.7,2.9,-1.7,0.1,2.1
Philips SHP9500|5.2,5.1,1.9,0,-1.5,-1.1,-0.2,-0.2,-0.5,-1.2,0.3,1.5,0,-4.1,-1.4,-1
Philips SHP9600|5.4,2,-1.1,-2.7,-3.8,-3.1,-1.7,-0.6,-0.2,1.8,4.6,1.2,1.5,0.6,-2.6,-1.3
Philips SHS8000|3.4,2.6,1.1,-0.7,-2.9,-2.9,-1.9,-0.6,0.7,0.1,-0.8,-4.9,-2.8,-4.7,1.3,4.2
Philips SME3580|-4.9,-3.7,-2.6,-2.6,-2.4,-0.8,1.1,2.3,3.7,3,1.6,-2.2,2.2,-2.2,4,4.9
Philips TAE1105|-0.9,-1.3,-2.1,-3.3,-4.3,-3,-0.9,1.1,3.1,2.9,2.6,0.8,1.5,0.5,2.2,3.8
Philips TX1|-3.9,-3.5,-3.4,-3.9,-4.2,-2.7,-0.8,0.7,2.2,2,2,4.3,4.5,0,0.1,3.9
Philips TX2|-2,-1.8,-2.1,-3,-3.6,-2.5,-0.8,0.8,2,1.6,1.3,1.2,3.3,0.6,-0.8,2.9
Phonak PFE 112|3.7,3.2,1.9,0.1,-2.3,-3.2,-2.5,-1.5,-0.3,-0.8,-0.7,0.2,0.4,0.3,-2,-3.5
Phonak PFE 121 (black filter)|1.6,1.2,-0.2,-1.8,-4.1,-4.7,-3.9,-3.1,-1.1,-0.8,1.1,4.7,2.5,5.7,0.5,-3.4
Phonak PFE 121 (gray filter)|5.1,4.2,2.4,0.5,-1.8,-2.7,-2.3,-1.7,-0.8,-1.9,-2,-0.8,0.8,1.5,0.1,-4.9
Phonak PFE 121 (green filter)|0.1,-0.3,-1.6,-3.2,-5.2,-5.6,-4.7,-3.3,-1,-0.1,2.3,6.2,4.2,6.2,3.2,-1.9
Phonak PFE 232|0.2,-0.4,-1.6,-3.2,-5.3,-5.8,-4.9,-3.2,-1,0.1,0.8,5,3.1,5.4,1.5,2.2
Phonon SMB2|1.5,-0.4,-1.7,-2.1,-3.2,-4,-2.1,0,-0.3,-1.9,-2.1,1.8,0.8,5.8,0.8,-0.9
Pioneer HDJ-1000|4.7,4.7,1.9,-1.3,-2.3,-2.4,-1,-1.7,-3.1,-4.8,-5.7,0.2,-0.7,2.1,4.3,3.1
Pioneer HDJ-1000 Gold|5,5,4.6,-0.1,-2.3,-2.5,-1.6,-1.7,-1.8,-4.3,-5,2.4,1.2,1.9,2.7,1.9
Pioneer HDJ-2000|5.3,5.2,-0.7,-3.2,-3.7,-4.3,-3.1,-2.9,-3.2,-3.2,-1.9,4.4,-1.2,5.2,4.9,0.2
Pioneer HDJ-500|5.5,3.4,1,0.2,-1.7,-2.8,-2.9,-3.5,-3.6,-2.6,1.5,5.5,2.8,-4.3,1.7,2.4
Pioneer Monitor 10|4.4,4.4,3.1,0,-3.8,-3.4,-1.3,-1.2,-1.4,-4,-9.1,0.3,-1.2,2.8,3.9,2.3
Pioneer Monitor 10 II|-2.2,-2.6,-2.9,-2.4,-5.2,-6.5,-3.6,0.9,5.2,2.9,-5.3,3.2,3,5.8,6.5,2.8
Pioneer Monitor 10 II (sample 1)|-1.6,-2.2,-2.6,-2.6,-4.1,-5.9,-3.8,0.6,4.6,1,-5.9,1.8,1.7,5.5,6.7,3.8
Pioneer Monitor 10 II (sample 2)|-2.7,-2.9,-3.2,-2,-6.5,-7,-3.4,1.2,5.9,4.8,-4.7,3.7,4.2,7.1,6.5,1.9
Pioneer SE-2P|6,4.1,3.1,0,-7,-8.8,-10.3,-15.5,-6.2,-1.7,-0.6,3.4,6.1,6.2,6.1,6.1
Pioneer SE-A1000|5.1,5.1,3.3,0.1,-1.8,-1.7,-0.6,0.5,0.2,-0.9,1.9,3.3,-0.9,-6.1,-3.4,-4.3
Pioneer SE-CX9|2.1,0.8,-1.2,-2.9,-5,-5.6,-4.8,-3.3,-0.4,1,1.7,0.7,-0.1,2.6,3.8,5.1
Pioneer SE-M290|1.6,-1.4,-1.7,-4.4,-6.2,-6.4,-4.7,-3.2,-4,-2.1,4.6,5.5,5.2,2.6,1.4,4.1
Pioneer SE-Master1|3.1,1.9,-0.9,-2.3,-4.1,-4.4,-2.6,-0.6,1.4,3.3,4.7,0.9,5.8,-1.3,-1.5,3.8
Pioneer SE-MJ31|5.6,5.6,3.1,-0.5,-2,-1.9,-1.1,-1.7,-2.2,-2.8,-6.3,1.5,5,5.6,1.6,-3.2
Piston|2.1,0.5,-1.5,-3.4,-5.6,-5.3,-3.6,-1.3,1.7,3.3,3.4,3.1,0.7,-2.6,2.3,4.8
Piston Classic|2.2,0.5,-1.8,-3.9,-6.2,-6.1,-5,-2.2,0.9,1.6,5.3,5.6,1,-0.1,0.5,2.7
Piston Fit|4.6,4.6,4.6,2,-1.7,-3.3,-3,-1.5,0.9,0.6,1.1,0.4,-3.5,-4.8,-2.4,-0.8
PistonBuds Pro|-0.3,0,-0.1,-1,-2.6,-3.4,-3.5,-3.5,-3.2,-2.5,0,-0.7,5.1,6.1,6.1,4.3
Plantronics 325T|4.5,4.5,-1.2,-4.6,-5.1,-3.6,-1.9,-1.3,-1.2,-2.2,-4,2.3,-5.4,2.5,3.7,4.5
Plantronics BackBeat 500 (wired)|-0.5,-0.9,-2.1,-2,-4.2,-2.9,-1.7,-0.6,-1.1,-0.7,-0.6,2.2,1.6,4.9,6.2,6
Plantronics BackBeat Fit|5.2,4.2,0.1,-2.8,-5.7,-6.4,-5.6,-4.3,-1.5,2.5,5.2,3.1,2.1,0.2,0.8,0.1
Plantronics BackBeat Go 600|0.1,-0.5,-1.5,-2.6,-2.8,0.2,3.5,6.1,3.2,2.9,2.5,4.2,3.2,0.2,-7.4,-8.4
Plantronics BackBeat Go 600 (wired, pad swap)|3.6,0.6,-1.3,-3.2,-5.1,-3.1,2.1,6.6,6.6,1.5,2.6,-2.9,-2.1,1.5,-2.3,-3.4
Plantronics BackBeat Go 600 (wireless, pad swap)|2.8,0.1,-1,-2.2,-2.6,1.1,5.6,7.2,7.2,1.1,1.5,-0.7,-0.2,-0.5,-6.8,-7.3
Plantronics Backbeat Go 810|3.9,0.7,-1,-1.1,-1.5,-0.5,0.9,0.8,-0.2,-0.9,0.6,-0.8,-0.2,1.9,-2.7,-3.6
Plantronics Backbeat Pro|-4.9,-3.9,-0.3,5.4,3.7,4.8,5,5.2,3.5,2.7,3.9,0.8,0.9,0,-2.5,-6.3
Plantronics BackBeat Pro 2|-6.1,-3,1.6,3.5,4.3,3.1,2.8,0.6,1.1,0.6,1.7,0.5,-0.9,0.6,-3.1,-3.2
Plantronics RIG 800LX|0,0,0.8,0.7,0.2,1.8,2.8,2.9,1.7,0.6,3.2,-1.2,-4.5,0.3,-1.7,-3.9
Plantronics Voyager 3200|6.3,6.3,6.3,6.3,6.3,2.8,-1.1,-2.8,-2.7,-0.1,0.1,-2,-5.4,-4.7,-6.5,-8.7
Plantronics Voyager 5200|5.1,5.1,5.1,4.6,0.6,-1.2,-2.1,-1.5,-3,-2.7,-1.5,-1.6,-1.4,3.7,-2.4,-6.1
Plantronics Voyager Legend|5.3,5.3,5.3,5.3,5.3,0.5,-1.7,-1.4,-2.8,-2.8,-0.1,-4.7,-8.9,-0.5,-6.3,-5.7
Plugged Crown|0.7,-0.6,-2.2,-3,-6.6,-8.1,-6.4,-2.3,2.4,-1.2,-0.2,1.3,3.2,5.6,5.6,5.6
Plunge Audio Unity IEM|4.6,4,2.4,0.4,-2.4,-3.7,-3.7,-3.3,-2.5,-2.7,-2.6,1.9,4.9,5.3,1.1,-1.9
PNY Midtown|-4.7,-4.4,-4.5,-5.1,-5.6,-4,-2,-0.1,2.6,3.6,3.5,1.9,6.7,5.7,1.8,6.7
PNY Uptown|-5.2,-4.5,-3.8,-3.9,-3.9,-2.1,-0.3,1.3,2.8,2.4,1.3,0.2,6.9,1.9,1.7,6.9
Polk Audio Buckle|-3.3,-2.7,-1.9,-1.9,-2.1,-2.1,-2.4,-1,-3.5,-2.6,1.1,3.2,3.3,6.5,3.9,1.6
Polk Audio Hinge|-3.7,-4.2,-4.7,-5.8,-7.6,-3.2,-0.9,-1.4,2.5,6.2,0.9,2.7,2.5,3.1,6.8,6.7
Polk Audio Melee|-0.8,-1.4,-1.8,-3.4,-5.3,-5.1,-3.4,-2,-0.8,-0.1,3.2,6.4,2.3,4.4,3.4,1.4
Polk Audio UltraFit 1000|6.6,6.6,1.4,-2.6,-7.7,-10.9,-11.9,-11.9,-11.2,-4.5,-0.5,6.6,6.6,6.6,6.6,6.6
Polk Audio UltraFit 2000|4.9,4.9,-0.2,-3.1,-4.3,-3.1,-0.8,1.2,3.1,-4.7,-4.9,-0.1,-2.3,4.4,0.6,0.5
Polk Audio UltraFit 2000 (sample A)|4.7,4.7,1.7,-1.3,-2.8,-1.9,0.6,2.7,4.6,-5.8,-5.4,-2.9,-3.9,2.9,-2.4,-0.5
Polk Audio UltraFit 2000 (sample B)|5.2,4,-1.9,-4.7,-5.6,-4.2,-2.1,-0.1,1.1,-3.5,-4.2,2.8,-0.6,5.2,3.8,1.7
Polk Audio UltraFit 300|-5.1,-5.1,-5.6,-6.4,-7,-5.4,-3.3,-1.3,1.6,2.3,2.7,4,6.3,8.7,8.7,8.7
Polk Audio UltraFit 3000|-4.8,-4.8,-5.5,-6.7,-7.6,-6.5,-4.4,-2.2,1,2.6,4.1,7.2,8.1,5.5,8.1,8.1
Polk Audio UltraFit 3000 (sample A)|-3.9,-4,-4.9,-6,-7.3,-6.1,-4.1,-1.7,0.8,2.6,3.4,5.9,7.6,4,7.9,7.9
Polk Audio UltraFit 3000 (sample B)|-4.9,-5,-5.3,-6,-6.6,-4.8,-2.6,-0.2,1.9,2.5,2.4,1.6,1,8.1,8.1,8.1
Polk Audio UltraFit 500|5.4,5.4,4.1,-0.2,-5.6,-10,-11.3,-11.9,-9.9,-5.2,1.2,5.4,5.4,5.4,5.4,5.4
Polk Audio UltraFit 500 (sample 1)|4.7,4.7,4.7,3.2,-2.9,-7.5,-9.1,-9.7,-9.5,-7.2,-1.2,4.7,4.7,2.2,4.3,4.7
Polk Audio UltraFit 500 (sample 2)|4.4,4.4,4.4,1.7,-3.4,-6.1,-6.4,-7.5,-9.4,-6.5,-0.9,4.3,3.3,0.9,4.4,4.4
Polk Audio UltraFocus 8000|-0.9,-0.1,0.3,0.4,-0.1,-0.7,0.8,1.5,1,0.5,0.5,1.5,1.6,-1.1,1.7,-0.8
Polk Audio UltraFocus 8000 NC|-1,-0.4,0.3,-0.3,-0.7,-0.8,-0.6,-1.1,-0.5,0.4,2.9,3.4,3.4,0.2,1.2,-0.6
PopClik EVOLO|-3.9,-3.8,-4,-4.5,-4.8,-3,-0.8,1.2,3.7,4.2,3.7,2,4.2,3,3.3,4.3
PopClik String|-4.6,-4,-3.6,-3.9,-3.9,-1.8,0.4,2.2,3.4,3,1.7,-0.3,4.8,1,4.2,4.3
Precide ERGO 2|4.6,4.6,4.6,2.1,-2,-3.2,-3.5,-3.6,-3.6,-4.3,-4.9,-3.9,0,3.8,4.6,2.4
Prisma Audio Azul|3.9,3.4,2.2,0.5,-2,-2.8,-2.3,-1.5,-0.3,-0.9,-2.1,-0.1,2.4,2.8,3,0.1
Prisma Audio Azul (AZLA Sedna eartips)|4.1,3.6,2.2,0.4,-2,-2.5,-2.1,-1,0.3,-0.4,-0.8,0.8,1.7,1.2,0.3,-3.2
Prisma Audio Azul (Final Audio Type E eartips)|4.4,3.8,2.4,0.5,-1.9,-2.5,-1.9,-0.8,0.4,-0.4,-0.8,0.5,1.3,1,-2.2,-4.5
Prisma Audio Azul (JVC Spiral Dot eartips)|4.2,3.7,2.3,0.4,-1.8,-3,-2.1,-1,0.1,-0.4,-0.8,0.7,1.6,1.1,0.5,-2.9
Prisma Audio Azul (SpinFit eartips)|4.4,3.9,2.5,0.7,-1.7,-2.6,-1.8,-0.7,0.4,-0.2,-0.5,0.5,2.1,1.2,-1.6,-4.8
PSB M4U 1|1.4,2.1,3.3,-1.3,-2.4,-1.8,-0.1,1.3,1.3,-0.7,-0.1,-0.9,-6.1,-2.1,-2.4,0.6
PSB M4U 2|0.1,1.3,1.7,-0.1,-1.6,-1.4,0.9,1.4,0.4,-2.8,-4.1,-1.3,-4.8,0.2,0.6,3.6
PSB M4U 2 (passive)|0.4,0.9,2.3,-2.3,-2.4,-1.4,1.1,1.2,-0.4,-1.8,-0.5,0.9,-4.3,-0.9,0.5,2.4
PSB M4U 4|-1.3,-1.1,-1.2,-1.7,-2.3,-1.7,-0.9,-0.8,-0.8,-1.7,-0.8,-0.1,-1.9,4.7,4.8,0.3
PSB M4U 8|-0.1,-0.9,-3.5,-2.6,-1.2,0.7,1.2,0.1,0.1,0.2,2,0.6,-1.3,1.6,2.6,2.3
PSB M4U 8 (wired, passive)|-1.5,-1.1,-1.7,-1.9,-4.6,-2.6,0,2.2,2.9,1,1.9,2.1,-3.1,0.7,1.3,3.6
Pump Audio Earphones|-6.7,-6.2,-6,-6.3,-6.2,-4.1,-1.8,0.5,3.8,4.5,5.4,4.4,4.7,-3,7.7,7.3
PureSound Clarity One|0.6,-0.6,-2.2,-3.6,-4.9,-3.8,-1.8,0.4,3.6,4.9,5,5.5,-0.4,0.8,-1.4,0.4
Puro JuniorJams|-2.6,-2,-1.6,-1.6,-2.2,-1.1,2,5.8,4.3,4.9,5.5,2.2,0.8,1.2,-0.2,-0.4
QCY G1|-4.5,-4.9,-5.1,-5.3,-5,-1.7,-1,-0.5,0.3,0.4,1.6,2.8,4.7,5.4,3.9,6.4
QCY HT05 Melobuds|0.8,-1,-1.1,-1.4,-1.1,-0.6,-0.7,-0.7,0.1,-0.9,-1.3,-0.3,0.4,2.6,0.9,0.6
QCY HT07 Arcbuds|-1.3,-2.5,-3.3,-3.3,-1.9,0.2,-0.7,-0.6,0.6,-0.3,-0.7,-0.7,-0.6,-1.3,2.8,5.6
QCY T13|-4.4,-5.3,-5.4,-4.7,-1.3,-2.6,-0.6,1.6,2.9,2.3,2,1.3,5.4,7.4,0.1,-1.6
QCY T16|-3.5,-3.9,-4.1,-4.4,-3.5,-1.4,-0.8,0.7,2.1,2.5,3.5,4.9,4.5,5.7,2.5,-0.2
QCY T17|-0.7,-1.5,-1.6,-0.9,1.1,-0.8,-0.3,0.9,1.6,0.9,1.5,1,0.9,4.2,-2.4,-3.7
QCY T18|0.1,-0.4,-1.4,-2.5,-1.7,-0.2,-0.5,-0.3,0.7,0.8,1.2,0.9,-1.2,-1,-1.6,3.5
QCY T20|6.5,6.5,6.5,6.5,6.5,6.5,6.5,4,1.8,2.1,-0.7,-8.7,-8.4,-6.7,-13.7,-12.3
QCY T5|-0.2,-0.6,-1.2,-1.9,-2.7,-3.9,-2.7,-1.1,0.6,0.2,-1.8,-3.8,3.5,5.6,2.4,0.9
QKZ AK6|-2.9,-2.8,-3.4,-4,-4.8,-3.2,-1,1.5,4,3.9,2.6,0.8,4.1,-3.5,1.1,4.7
QKZ VK4|-2.3,-1.4,-0.8,-1.3,-1.7,-1.1,0,1.1,1.8,1,1,3,1.3,1.2,1.8,1.6
QKZ VK4 (pre-revision)|-1.8,-1.4,-1.3,-1.8,-2.5,-1.5,-0.3,1.1,2.3,1.6,0,1.2,2.3,-0.1,2.3,2.6
QKZ VK4 (revised)|-1.1,-1.4,-2,-2.8,-3.5,-2,-0.2,1.9,3.8,2.2,-0.3,-0.3,4.2,-2.5,2.3,1.8
QKZ x HBB|-1.7,-1.9,-2.2,-3.1,-3.9,-2.8,-1.1,0.4,2.1,1.8,0.7,2.4,2.9,5.3,5.4,1.4
QKZ x HBB Khan|-1.1,-1.8,-2.2,-2.3,-0.3,1,0.2,0.8,1.7,1.1,1.3,2.3,0.3,1.3,0,-2.5
QKZ ZXD|-1.3,-1.5,-2.2,-3.2,-4.3,-3,-1.1,1.3,3.9,4.9,1.7,0.9,4.1,-1.1,3.4,1.7
QKZ ZXN|-0.4,-0.7,-1.7,-2.8,-4.2,-3.1,-1.2,1.1,3.5,4.2,1.7,0.5,3.3,-1.9,3,1.7
Quad Driver|4.4,2.4,-0.4,-2.8,-5.4,-5.5,-4.1,-1.8,1,2.2,2,1.5,1.7,1.5,5.9,1
QUAD Era-1|2.8,1.9,0.6,-0.6,-2.7,-2.7,-2.3,-1.5,-2.5,-4.7,3.1,5.8,5.2,5.1,-0.3,-0.8
Qudelix Qx-over|-3.9,-3.8,-4.2,-5.1,-5.8,-4.9,-2.5,0,2.4,3.2,2,1.9,2,2.4,6.7,6.7
Queen of Audio Adonis|2.3,1.9,0.8,-0.8,-2.8,-3.1,-2.1,-0.6,1,0.7,0.7,2.2,1.9,2.2,-0.9,-0.7
Queen of Audio Gimlet|0.2,-0.3,-1.1,-2.1,-3,-1.9,-0.4,1.5,2.9,2.2,0.4,-0.5,0.6,0.5,3.8,0.2
Queen of Audio Mojito|3,2.2,0.9,-0.9,-3,-3.6,-2.9,-1.8,-1.2,-2,-2,1,-1,-4.8,3.4,4.3
Queen of Audio Pink Lady|-1,-1.1,-1.6,-2.7,-4.1,-3.7,-2.5,-0.8,0.9,0.6,0.8,3.9,4.2,6.2,0.7,1.5
Queen of Audio QM50|0.7,0,-1.3,-2.8,-4.8,-4.7,-3.2,-0.9,2.1,2.6,-1.5,0.3,-0.9,-1.7,2.9,5
Queen of Audio Vesper|-2.3,-2,-2.2,-3.1,-4.2,-3.7,-2.6,-1,0.9,0.1,0,3.5,4,4.5,1.6,5
Questyle NHB12|-3,-2.7,-2.8,-3.4,-3.9,-2.1,-0.1,2,3.8,3.4,2.2,3,4.3,-0.4,-0.4,0.5
RAAL CA1a (open earpads)|4.6,3.3,2.5,-0.3,-2.2,-3,-1.6,-1.6,-2.1,-4,-0.9,4.4,2,-4.6,1,2.7
RAAL SR1a|4.4,4.4,3.3,1.4,-0.2,-1.8,-1.5,-1.3,-1.2,-2.2,-5.1,-3,-1.6,-1.1,-1.3,2
RAAL SR1a (ear contact, OBC off)|4.6,4.6,4.6,4.6,2.8,0.5,0.2,0,-0.9,-2.6,-5.7,-5,-2.4,-1.1,-2.9,-0.9
RAAL SR1a (ear contact, OBC on)|4.6,4.6,3.7,2.9,0.3,-1.4,-1.6,-1.4,-1.6,-2.2,-4.5,-3.1,-0.8,0.3,-0.9,0.7
RAAL SR1a (no ear contact)|4.5,4.5,3.1,1.7,-0.1,-1.4,-1,-0.5,-0.4,-1.6,-3.8,-3.1,-2.1,-2.7,-1.6,2.4
RAAL SR1a (no ear contact, OBC off)|4.9,4.9,4.9,4.9,3.9,1.5,1,0.8,-0.3,-2.1,-5.2,-5,-3.6,-3.1,-3.3,-2
RAAL SR1a (no ear contact, OBC on)|4.5,4.5,4.5,4.1,1.5,-1,-1.2,-1.1,-1.4,-2.2,-4.7,-3.4,-1.9,-1.9,-1.2,0.5
Radius HP-NEF11|2.4,2.3,1.3,-0.3,-2.4,-2.8,-2.3,-0.3,3,5.1,4.9,0.6,-3.3,-5.1,-3.1,-0.4
Radius HP-NHA11|-5.8,-4.9,-4.2,-4.3,-4.2,-2.1,0,1.7,3.8,3.4,2.6,1.9,1.8,-2.7,4,6.6
Radius HP-NHR11|-5.8,-5.3,-5,-5.2,-5.1,-3.2,-0.9,1.7,4.1,4.2,2.8,3,4.9,1,1.2,5
Radius HP-TWF31|2,1.5,0.2,-1.6,-3.9,-4.9,-4,-3.1,-0.6,1,3,4.9,2.9,5.8,5.7,-3
Radius HP-TWF41|3.8,2.5,0.7,-1.2,-4.2,-5.5,-5.6,-4.6,-2.1,0.6,1.9,3.1,4.1,5.8,4.9,-1
Raptgo Hook|0.2,0.2,-0.2,-1.2,-2.5,-1.6,-0.3,1,1.9,1,-0.1,1.2,4.9,2.5,0.5,-2.7
Raptgo Hook-X|0.3,0.4,0.2,-0.6,-1.5,-0.4,0.8,1.8,2.3,0.9,-1.5,-0.9,3.9,1.5,0.5,-2.1
Raptgo Leaf D01|0.6,0.4,-0.1,-1.1,-2.1,-1.1,0.1,1.5,2.4,1.2,-1.5,-3.3,3,-2.4,1.1,1.3
Raycon E100|-3.2,-2.6,-2.2,-2.6,-2.8,-1,1.2,3.8,6.6,5.3,5.4,5.7,1.9,5.5,-5.4,-6.6
Raycon E25|-6.5,-6,-5.5,-5.6,-5.8,-4,-0.3,4.8,7.7,7.3,6.9,7.1,0.7,4.2,-2.5,-0.6
Raycon E50|3.1,1.3,-1.1,-3.4,-6.2,-6.9,-5.7,-3.4,0,0.1,1.5,4.4,4.6,0.1,0.8,4.8
Raycon E55|0.3,-2.2,-3.6,-5.3,-8.8,-9.8,-5.7,0.4,4.4,3,2.4,4.6,6.3,2.1,6.3,4.7
Raycon The Fitness Earbuds (2021 Edition)|-4.8,-3.7,-2.2,-1.8,-0.8,2.2,5.7,8.4,8.4,7.1,4.2,-0.9,-3.9,-2.4,-1.3,-5.6
Razer Barracuda|1.2,1.2,-0.7,-2.2,-2.8,-1.8,0.6,1.6,1.9,-0.7,-1,-1.7,-2.5,3.2,-1.8,0.4
Razer Barracuda Pro|0.4,1,1.6,1,0.2,1.8,2.9,2.8,2.2,-1.1,-3,-1.2,3.5,-0.5,-3.8,-1.7
Razer Barracuda X|-2.1,-3,-4.2,-6.8,-7.6,-0.1,0.7,-2.8,-1.2,1.8,5.5,5.8,7.3,7.3,3.1,1
Razer Barracuda X (2021)|0.5,-0.9,-1.5,-1.4,-2.4,-0.2,-0.6,-0.3,1,2.7,2.5,0.6,1.5,5.6,-2.2,-5.2
Razer Barracuda X (2022)|-3.1,-4.8,-5.6,-4.2,-3.7,-1.7,2.1,1.6,2.2,1.1,0.8,-0.1,5.5,6.9,1.5,1.4
Razer BlackShark V2|3.7,2.4,1.2,0.4,-1.8,-1.5,0.1,1.2,2.6,1.1,2.1,-2.6,-3.9,-0.4,-3.9,-3.5
Razer BlackShark V2 HyperSpeed|4.5,4.5,4.5,3.5,0.6,0.2,1.3,2.2,-1.3,-2.4,-4.7,-6.1,-0.2,-0.1,-5.8,-3.7
Razer BlackShark V2 Pro|1.1,0.5,-1.3,-4.8,-6.2,-3,2.2,5.1,5.6,2,1.1,3,-3.7,0.6,-0.6,-0.3
Razer BlackShark V2 Pro 2023|4.9,4,1.5,0.2,-1.9,0.2,1.8,2.5,0.8,-2.1,-1.8,-2.4,0.2,-0.9,-5.9,-4.6
Razer BlackShark V2 X|-3.4,-4.1,-4.6,-5.8,-7.9,-1.9,2.3,-0.7,-0.9,0.3,3.9,5.2,5.6,5,5.3,4
Razer Hammerhead Pro HyperSpeed|-0.3,-0.2,-0.7,-1.1,-1.3,-0.1,0.4,3.3,0.7,-0.9,-1.8,0.7,2.9,-1.5,-1,0.5
Razer Hammerhead True Wireless (2019)|-1.9,-4.1,-5.8,-7.6,-8,-6.3,-4.1,-1.6,2.5,5.9,4.9,3.8,6.5,5.1,1.6,4.6
Razer Hammerhead True Wireless (2021)|-0.8,-1.2,-2.5,-4,-5.8,-6.3,-5,-2.6,1.3,1.6,3.6,6.4,6.4,4,1.8,4.2
Razer Hammerhead True Wireless Pro|2.1,1.3,1.2,0.3,0.2,0.9,0.6,0.4,-0.8,-1.6,-2.2,1.1,1.9,-1.4,-2.9,-2
Razer Hammerhead USB-C ANC|4.9,4.9,4.7,3.9,2.4,-0.1,-1.4,-2.9,-3.3,-4.9,-2.5,3.4,-3.6,2.7,0.2,-5.3
Razer Kaira Pro for Xbox|2.8,1.7,0.6,-0.6,-2.5,-3.2,-0.1,3.1,3.1,-1.2,-3.8,-2.1,5.7,2.7,-5.3,-2.3
Razer Kaira X for PlayStation|3.3,2.8,1.1,-0.9,-3,-2.7,0.2,2.9,1.4,-0.8,-2,-0.5,2.6,0.3,-6,-1
Razer Kraken Pro V2|4.3,0.7,-3.1,-4.5,-5.7,-4.2,-4.9,-2.7,-0.6,0.8,3.8,5.4,4.6,5,-1.3,-2.3
Razer Kraken Tournament Edition|5.5,2.1,-3.3,-5.1,-5.9,-4.7,-4.9,-6.3,-0.5,3.8,5.5,5.5,5.5,1.8,-3.6,-2
Razer Kraken Ultimate|4.4,0.4,-2.2,-2.9,-3.6,-3.3,-2.9,0.1,0.7,1.8,2.9,1.2,1.5,1,2,-1
Razer Kraken USB|5.5,1.5,-3.7,-5.5,-5.7,-3.4,-5.9,-6.3,-2,1.3,5.9,6.2,6.2,4,-3.6,-2.3
Razer Kraken V3|2.5,2.4,-0.4,-3.2,-5.5,-4,-0.4,0.3,0.3,0.1,1.8,0.9,2,4.5,0.1,0.4
Razer Kraken V3 HyperSense|1.8,1,-1.4,-3.9,-5.6,-4,-0.9,0.2,0.5,0.4,3.8,2.1,2.7,5.5,0.3,-0.8
Razer Kraken V3 Pro|1.3,0.8,-0.1,-3.4,-4,1.7,4.1,1.9,-1,-2.4,-0.2,0,-0.3,0.8,0.5,0.5
Razer Kraken V3 X|1.3,-1.2,-4.3,-5.4,-7,-6,-1.5,-1.9,-0.7,-0.6,-1.2,1.7,6.8,6.8,5.4,2.2
Razer Kraken X|3.5,2.2,-1.3,-3.5,-5.7,-4.2,1.1,1.4,-0.5,1.1,3.3,6.3,6.6,3.7,-6.6,-6.1
Razer Man O'War|-0.5,-2.3,-3,-3.7,-4.6,-4.6,-0.5,0.9,1.4,2.2,4.1,4.5,6.2,1.9,-1.3,-2.3
Razer Moray|0.4,0.3,-0.1,-0.8,-1.1,-0.5,0.8,2.1,1.2,-1.1,-4.2,-2,-0.3,-0.2,-1.8,5
Razer Nari Ultimate|1.7,-0.3,-2.8,-4.8,-5.8,-4.1,-1.7,0.9,2.2,2.9,3,0.5,1,3.3,2.5,0.9
Razer Opus 2020|2.1,2.6,2.7,2.5,1.1,0.6,0.1,0,-0.1,-0.6,-0.8,-1.3,0.5,-0.9,-4.8,-6
Razer Opus 2020 (ANC off)|2.2,2.4,2.5,2.5,1.2,-2.4,-0.8,0.9,-1.2,-2.2,-0.9,-1.5,4,3.2,-1.4,-4
Razer Opus 2020 (ANC on)|0.3,2.4,3.5,3.3,2,0.8,0.7,0.7,-0.2,-0.7,-0.5,-0.8,2.1,-0.3,-3.5,-4
Razer Opus 2020 (passive)|0.9,0.6,0.1,-1,-3.7,-2.3,1.4,4,2.5,1,0,3,2.3,0.1,-1.2,-2.5
Razer Opus 2021|1.2,0.5,-1.2,-1.7,-1.8,-1,0.8,0.2,-1.1,-2.4,-2.9,4.8,0.6,4.4,-3.5,-1.6
Razer Opus X|2.8,2,0.5,-1.2,-3,-1.9,0.9,2,1.6,2,2.8,2.3,-2.6,2.2,-2.4,-4.9
RBH EP1|-4.3,-2.9,-2.4,-2.7,-2.7,-1.8,-0.5,0.6,2.2,1.3,0.5,0.3,0.5,-2.5,4.7,6.4
RBH EP2|-5.7,-4.4,-3.4,-2.7,-0.9,-0.6,2,3.3,3.5,2.7,1.4,2.8,3.4,-7.5,-0.8,6.9
RBH EP3|-6.2,-5.3,-4.6,-4.7,-4.5,-2.5,-0.2,1.9,4.6,5.5,4.2,4.1,5.5,-0.7,6,4.9
Redragon H510 Zeus Pro|3.1,1.4,-1.8,-2.6,-3.3,1.2,3.2,1.2,-0.8,0.1,-2,-2.8,5.2,-0.2,-2.5,-1.6
Redragon H510 Zeus-X|4.2,2.2,0,-1.3,-1.9,3.4,3.3,-0.1,0,-1.4,-2.4,-3.8,3.1,-1.7,-4.5,-1.2
Redragon H848 IRE PRO|2.4,-1.4,-3,-4.8,-6.8,-6.5,1.9,7.1,7.1,2.8,4.2,-0.1,4.9,0.6,-3.7,-5.1
Reecho SG-01|-1.2,-1.3,-1.8,-2.7,-3.7,-2.3,-0.7,1.2,2.5,2,0.4,-2.3,2.1,-1.6,3.9,1.5
Reecho SG-01 OVA|-0.1,-0.3,-1,-2.1,-3.2,-2.2,-0.7,1,2.3,1.5,-0.5,-2.7,0.6,0.5,4.2,-1
Reecho SG-03|-3.9,-2.9,-2.2,-2.3,-2.4,-0.8,0.8,2.4,3.1,1.4,-0.5,-2.7,-1.7,2.9,3.5,3.1
Reecho Summer|0.7,0.3,-0.9,-2.8,-5,-5.5,-4.4,-2.8,-0.6,0.1,0.8,-2.2,4.6,-0.2,5,5.1
RevoNext QT2|-2.9,-2.8,-3.1,-3.9,-4.7,-3.3,-1.5,0.6,2.6,2.5,3,5.5,1.5,7,-0.2,-3.2
RHA CL1|4.4,4.2,3.4,1.8,-0.4,-1.1,-0.4,0.9,3.1,4,4.2,2.4,2.1,-5.7,-5.5,-9
RHA CL2|-0.4,-0.7,-1.3,-2.2,-3.1,-2,-0.7,0.7,1.7,0.7,-2.1,-5.4,-6.3,4.6,5.5,5.5
RHA CL750|3.2,3.2,2.6,1.3,-0.8,-1.5,-0.6,1.1,3.6,5,6.1,6.8,0.3,-5.5,-3.1,-4.6
RHA MA-350|-4.6,-4.1,-3.7,-4.1,-4.3,-2.5,-0.5,0.9,2.1,2.5,4.6,6.9,1.3,-4.3,6.1,5.5
RHA MA450i|-4.5,-3.9,-3.5,-3.9,-4,-2.1,0,1.7,3.6,3.9,4.8,5.3,0.5,-5.7,4.7,5.1
RHA MA600|-2.4,-2.7,-3.4,-4.5,-5.6,-4.2,-2.1,0.4,4.1,6.4,8,7.4,0.7,-4,6.7,1.5
RHA MA750|1.8,1.6,0.9,-0.6,-2.7,-3.5,-3,-1.9,0,1.3,2.6,4.7,-0.2,-3.2,4.8,2.6
RHA S500i|5.2,4.6,3,1.3,-0.7,-1,-0.1,0.5,1.4,0.4,-0.6,-5.4,-1.7,2.6,-2,-4.3
RHA SA-850|3.2,1.7,1.1,1,-1,-4.4,-3.8,-5.6,-3.7,-2.4,-3.6,4,2.5,-3,-2.5,4.6
RHA SA950i|-2.2,-2.4,-2.8,-2.9,-4.5,-4.9,-5,-5,-0.6,1.8,-1.6,4.8,4.5,1.9,4.8,6.2
RHA T10i (bass filter)|-4.8,-5,-5.2,-5.9,-6.4,-4.6,-2.5,-0.5,2,3.2,3.1,3.4,2.6,7.8,6,1.6
RHA T10i (reference filter)|-4.2,-4.3,-4.8,-5.4,-6,-4.3,-2.2,-0.2,2.1,3.1,2.8,2.2,2.6,8.2,5.3,-0.4
RHA T10i (treble filter)|-4.1,-4.2,-4.6,-5.3,-5.8,-4,-1.9,0.2,2.6,3.8,3.5,-0.7,0.3,7.5,5.5,1.3
RHA T20 (bass filter)|-1.9,-1.8,-1.9,-2.5,-2.9,-1.6,0,1.1,2.3,1.3,-0.2,1.4,-0.9,-1.7,7.1,2.7
RHA T20 (reference filter)|-1.3,-1.3,-1.6,-2.1,-2.5,-1.2,0.4,1.4,2.7,1.6,0.3,-0.4,-1.9,-3.4,6.4,2.3
RHA T20 (treble filter)|-1,-1.1,-1.2,-1.7,-2.2,-0.9,0.7,1.8,3,2,0.2,-0.7,-2.9,-5.2,6.4,2.6
RHA T20i|-2.9,-2.4,-2,-2.4,-2.8,-1.4,0.3,2,3.3,2,-1.3,1.2,1.4,-2.2,5.3,3.7
RHA TrueConnect|-5.4,-4.3,-3.3,-3.2,-3.4,-1.7,0,1.8,3.5,3.6,2.7,3.8,8.3,8.3,1.7,-2.3
Rhapsodio Clipper|-2.8,-3.1,-3.7,-4.7,-5.7,-4.4,-2.2,0.4,3.2,4.3,3.6,0.9,0.8,-5.1,5.9,6.3
Rhapsodio Infinity Mk1|-0.9,-1.4,-2.2,-3.4,-4.5,-3.4,-1.7,-0.1,1.3,3.3,3.4,2.2,1.6,0.8,3.3,2.2
Rhapsodio Infinity Mk2|2.4,1.3,-0.3,-2.2,-4.6,-5.2,-3.9,-1.5,1.3,1.1,-0.9,-2.8,-1.2,-0.4,2.3,4.6
Rikubuds Alter Rider 3|4.3,4.3,4.3,3.1,-0.8,-2,-1.6,-1.6,-2.6,-2,-1.7,0.9,-3.2,-3.2,-2.7,-0.9
Ritmix RH-508|4.7,4.4,2.1,2,1.6,1.9,-1.2,-3.3,-4.6,-4.4,-3.8,-1,0.1,-4.6,-1.8,0.4
ROCCAT Elo 7.1 Air|3.5,2.6,0.5,-1.6,-3.8,-2.3,1,1.4,-0.5,0.9,2.3,1.7,1,0.2,-4.5,-3.1
ROCCAT Syn Pro Air|2.9,1.7,0.3,-0.4,-0.6,3.4,6,1.3,-0.8,-0.4,0.3,-1.4,4.1,-1,-7.9,-5.5
Rock Jaw Alpha Genus (black filter)|2.2,1.1,-0.6,-2.2,-4.1,-3.9,-2.5,-1,1.4,1.8,0.6,-1.3,2.1,-2.7,-0.1,1.2
Rock Jaw Alpha Genus (silver filters)|-4.9,-4.4,-4,-4.3,-4.2,-2.1,-0.1,1.9,4.3,4.2,3.5,0.6,1.8,-4.3,3.9,3.3
Rock Zircon|3.1,2.3,0.8,-1,-2.6,-1.8,1.2,3.8,4.9,3.2,0.4,-0.5,-0.7,-3.5,-2.9,-1.2
RockIt Sounds R30|3.6,2.6,0.8,-1.2,-3.9,-5.3,-5.3,-5.2,-4.5,-4.9,-4.3,-0.3,3.2,5.2,5.2,5.2
RockIt Sounds R50|5.1,5.1,3.3,1.4,-1.3,-2.6,-2.6,-2.4,-1.4,-1.6,-0.6,2,5.1,0.5,-5.9,-4.6
Rose BR5 Mk2|4.3,3.7,2.2,0.4,-2.2,-3.2,-2.7,-1.7,-0.2,0.8,-0.5,0.8,-0.2,0.8,1.6,-0.2
Rose Mini2|3.9,2.5,0.4,-1.7,-4.5,-5.6,-5.1,-3.9,-1.7,-0.9,-0.6,1.2,5.5,5.3,3.7,1.2
Rose Mini2 MKII 2.0|3.1,2.8,1.6,-0.1,-2.3,-3.6,-2.9,-1.6,0.3,1,2,2.9,6.1,3.8,-4.4,-4.6
Rose QT9 MK2|-0.8,-0.8,-1.4,-2.4,-3.5,-2.5,-0.9,0.6,0.9,-1.4,-1.4,-1.8,3.3,5.2,0.6,2.3
Rose QT9 MK2s|0.4,0.4,-0.2,-1.2,-2.3,-1.5,-0.2,0.9,0.6,-1.9,-1.8,0.7,1.7,6,1.5,-1.4
Rose Technics QuietSea|4.8,3.7,2,0.1,-2.2,-2.6,-1.8,-0.5,0.7,0,-1.1,-3.4,0.6,-3.5,0,-2.5
Rosson Audio Design RAD-0|2.7,1.8,0.1,-1.1,-3,-3.9,-3.6,-2.9,-3.5,-2.5,0.6,4.2,5.2,3,3.1,1.1
RUNMUS RGB K1 Gaming Headset|5.7,1.3,-4,-5,-5.5,-4.8,-3.3,-2.6,-2.4,-2.2,3,5.7,5.7,0.1,0,1.2
RØDE NTH-100|2.5,0.9,-0.7,-2.3,-3.1,-3.1,-3.4,-2.5,-0.6,0.1,1.5,-0.1,-0.3,-1.3,2.4,2.3
RØDE NTH-100 (front dampening removed)|1.7,-0.1,-1.8,-2.8,-1.7,-1.8,-2.4,-1.4,1.4,1.6,1.5,-0.3,-0.8,-3,3.1,2.3
RØDE NTH-100M|4.9,3.3,0.6,-0.9,-1.5,-2.8,-4.1,-2.4,-0.2,0.4,1.4,-1.7,-1.5,-1.2,-0.2,-0.1
SA02|4.1,3.4,1.8,0.1,-2.5,-3.6,-3.2,-2.1,-0.4,-0.1,-0.9,-5,4.3,1.4,2.8,2.8
SA03|1.2,0.4,-0.7,-2.3,-4.4,-4.8,-3.7,-1.8,0.8,1.9,2.3,6,6,2.8,-0.7,-0.3
SA05|1.4,0.4,-0.6,-2.4,-4.4,-4.6,-3.5,-1.5,1.1,2.1,2.2,6,4.8,1.8,1.3,1.7
Sabbat E12|-4.3,-3.6,-3.1,-3.4,-3.4,-1.6,0.7,3.4,5.7,4,3.6,-0.3,-1.5,-3.4,6.9,7.1
Samson SR850|6.1,3.2,1,0.4,0.4,1.8,2.8,4.2,4,1.5,2.4,-1.9,-0.7,-1.7,-8.5,-8.2
Samsung AKG EO-IG955|0.3,-0.3,-1.1,-2.2,-3.1,-1.9,0,2.1,4.1,3.9,2.8,0.9,0.7,2,0.6,-3.7
Samsung AKG EO-IG955 Type-C|-0.2,-0.4,-0.7,-1.5,-1.9,-0.8,1.2,3.5,5.7,2.6,2.2,1.3,-0.4,2.3,-1.4,-4.6
Samsung Earphones Tuned by AKG|3.3,1.9,-0.1,-1.8,-3.5,-2.7,-1,1,2.8,2.5,1.7,0.9,-0.3,1.9,-0.7,-4.8
Samsung Earphones Tuned by AKG (2019)|0.7,-0.1,-1.2,-2.3,-3.3,-2,-0.2,2,3.9,3.5,2.4,1.5,0.7,2.7,-0.4,-4.5
Samsung EO-IC100 Type-C|-2.3,-2.1,-2,-2.1,-2.5,-0.4,1.1,2.6,3.8,3.1,2.3,1.2,0.7,3.7,1.7,-2.7
Samsung EO-IC500 ANC Type-C|5.4,1.8,0.1,-0.9,-2.1,-1.6,-0.3,-0.7,0.7,-4,-5.1,2.2,2.8,-0.4,2.5,-1.1
Samsung Galaxy Buds|0.4,0.2,0.2,-0.2,-0.7,-1.5,-1.6,-0.9,-0.1,-0.6,-1.4,-1.9,-0.1,0.1,1.1,2.6
Samsung Galaxy Buds FE|-2.5,-1.5,0.2,0.6,0.5,0.5,0.7,1.3,1,-1.2,-1.3,1.6,2.8,2.2,-0.2,0.9
Samsung Galaxy Buds Live|-0.6,-1.4,-3,-4.3,-4.1,-0.8,-1.9,-0.8,-0.3,-0.7,0.4,-2,0.8,4.6,2.9,5.7
Samsung Galaxy Buds Plus|-2.3,-2,-1.5,-1.4,-1,-0.8,-0.6,0.1,1,0.3,-0.9,-1.8,-0.3,1.2,3.7,3.1
Samsung Galaxy Buds Pro|0.5,0.3,-0.2,-1.1,-2.1,-1.6,-0.7,0.6,2.5,3.1,3.3,1.7,2,0,-1.2,-3.4
Samsung Galaxy Buds2|-0.5,-0.7,-0.9,-1.5,-1.9,-1.5,-1.4,-1,0,-0.1,-1,0.7,2.7,3.8,-0.7,2.2
Samsung Galaxy Buds2 Pro|-0.9,0.2,0.7,0.4,0.1,0,0,0.7,0.5,1.1,0.4,1.3,-1.1,-0.5,2.5,-0.9
Samsung Galaxy Buds2 Pro (Ambient mode)|-1.3,-0.2,0.2,0,-0.2,-0.4,-0.4,0.2,-0.8,1.9,0.4,1.5,-0.4,-0.5,4,1.5
Samsung Galaxy Buds2 Pro (ANC mode)|-1.3,-0.2,0.4,0.1,-0.2,-0.3,-0.3,0.3,-0.7,1.8,0.4,1.6,-0.5,-0.5,3.9,1.3
Samsung Galaxy Buds2 Pro (passive mode)|-1.7,-1,-0.6,-1,-1,-0.5,-0.3,0.3,0.8,1.3,0.9,1.7,0.2,1.2,4.8,1.6
Samsung Gear IconX|0.8,-0.3,-1.5,-2.1,-2.6,-1.8,0.3,2.3,3.7,0.7,0.1,0.9,1.6,2.6,-1.1,-1.4
Samsung Level On|-1.8,-2.5,-3.6,-4.2,-5.8,-3.7,-0.9,-1.6,0.4,2.3,2,-1.8,-1.7,5.6,5.9,5.9
Samsung Level On Wireless|5,2.7,0.6,-1.1,-3.7,-4,-1.1,0.8,-1.2,-0.7,2.7,1.5,0.9,5.1,-1.7,-5.7
Samsung Level Over Wireless|4,2.5,0.4,-1.1,-2.4,-2.2,-2.5,-0.2,-0.3,0,1.2,4.2,0.9,0.4,-1.1,-3.2
Samsung Level U Pro|-2.7,-2.5,-2.6,-3.3,-3.8,-2.1,0,2.7,4.5,3.2,4,5.8,3,1.1,2.3,-0.6
Samsung U Flex|4.2,3.8,2.7,1.3,-0.3,-0.3,0.7,2.1,3.2,1.4,-0.1,-2.3,-3.1,-1.3,-2,-5.9
Sansui SS10|3.1,1.9,-3.8,-4.2,-4.8,-5,-6.2,-8.3,-10,-3.1,3.9,6,5.7,6.1,4,6
Sansui SS100|4.6,4.6,4.6,4.6,1.1,-1.9,-2.8,-2.8,-3.6,-3.7,-1.8,-2.8,-4.5,0.1,1.8,0.2
Sansui SS35|4.4,4.4,4.4,4.4,4.4,4.4,-3.2,-7.7,-7.5,-9.5,-7,-6.7,-1.1,-3.5,4.4,4.4
Santa Cruz Audio SC1000 (active)|4.5,4.2,2.8,1.1,-1.3,-2.4,-2.7,-2.7,-2.4,-2.6,-1.8,-2.5,-2.6,-2.4,0.4,-0.2
Santa Cruz Audio SC1000 (passive)|4.4,4.4,4.4,4.4,3.2,2,1.8,1.6,1.4,0.5,-0.1,-3.6,-7.4,-12.9,-11,-3.2
Sash Tres 45|3.7,2.4,1,-0.3,-2.4,-2.5,-1,-1.2,-2.7,-0.9,0.5,0.2,0,0.4,0.8,1.6
Sash Tres 45 (closed back, leather earpads)|5.3,4.8,2.2,1.1,0.9,0.8,1.8,1.1,1.8,-0.5,-2.5,-4.9,-3,-2.5,-0.4,-2.4
Sash Tres 45 (open back, leather earpads)|4.6,3.5,1.6,0.7,-1.1,-2.6,-2.4,-2.5,-0.1,-0.3,1,1.4,0,-1.7,0.6,-0.3
Sash Tres 45 (perforated leather earpads)|5,5,4.9,2.9,0.1,-1.7,-1.4,-1.1,-0.8,-1.4,1.4,0.1,-2.5,-4,-1.3,-1.7
Sash Tres 45 (velour earpads)|5.1,5.1,4.7,2.5,-0.2,-1.8,-1.4,-1.1,0.1,-0.4,0.8,1.5,-1.9,-4.8,-2.2,-2.1
Sash Tres Second Edition (honeycomb grill)|4.7,3.5,1.3,0.4,-1.6,-3.1,-3.1,-1.8,-1.6,0,2.1,1.9,-0.9,-2.6,1.9,1
Sash Tres Second Edition (S-style grill)|4.7,3.1,1.3,0.4,-1.6,-3.1,-3,-2.1,-0.7,0.4,2.6,2.4,-1.2,-2.8,1.7,0.8
SATOLEX Tubomi DH298-A1Bk|-2.6,-3.3,-4,-5,-5.9,-4.3,-2.2,0.6,3.8,4.9,3.8,3.3,2.7,2.3,2.1,5.3
SATOLEX Tubomi DH302-A1Bs|-0.5,-1.7,-3.2,-4.7,-6.3,-5.4,-3.3,-0.7,2.5,4,3.9,1.1,2.8,0,5.2,6
SATOLEX Tubomi DH310-A1SS|-0.8,-1.9,-3.3,-4.6,-5.9,-4.7,-2.7,-0.1,2.8,4.3,4.2,2.3,1.5,1.5,1.3,5
SATOLEX Tumuri DH303-A1|3.3,1.9,0,-1.9,-4.3,-4.4,-3,-1,1.8,2.7,1,0,0.9,1.2,0.4,2
Scosche RH1060 Bluetooth|-1.6,-3.1,-4.4,-4.8,-5.5,-3.9,1.5,5.9,3.6,1.7,2.6,1,-2.8,-0.9,2,1.5
Seahf AWK-009|-2,-1.4,-0.9,-1,-1.4,0.1,1.8,3.4,4.8,3.9,1.6,3.7,-2.9,-0.7,-0.8,1.6
SeeAudio Bravery|0,-0.1,-0.7,-1.7,-2.7,-1.8,-0.3,1.6,2.3,0.2,-1.8,2.5,2.2,6.4,2.9,-0.9
SeeAudio Kaguya|-1.3,-1.1,-1,-1.4,-1.9,-0.4,1.2,2.7,3.2,1.3,-1.5,0.3,0.4,3.8,2,-1.8
SeeAudio MIU|-1.1,-0.3,0.2,-0.3,-1,-0.4,0.4,1.3,2.2,1.3,-0.2,2,2.1,2.5,1.3,-2.7
SeeAudio Neo|-1.1,-1,-0.9,-1.5,-2,-0.8,0.4,1.3,1.7,-0.7,-2.8,2,3.8,3.9,4.2,0.4
SeeAudio x Crinacle Yume Midnight|-1.1,0,0.8,0.3,-0.8,-1,-0.5,0.1,1,0.4,-0.8,0.5,3.3,5.5,2.9,-0.2""",
"""SeeAudio Yume|-0.2,1.2,1.8,1.1,-0.4,-1,-0.7,-0.3,0.3,-0.9,-1.1,-0.6,1.2,1.5,-0.3,-0.9
SeeAudio Yume 2|0,0.2,0,-0.6,-1.3,-0.5,0.6,1.4,1.6,-0.3,-1.3,0.1,2.6,3.1,1.7,-2.7
Seek Real Audio Airship|-1,-0.7,-0.6,-1.1,-2,-1.1,-0.1,1,1.7,0.6,-1.7,-1.6,1.5,-0.8,3.1,2.9
Sendy Aiva|3.7,3,1.7,0.6,-1.2,-2.4,-2.2,-2.2,-2.9,-3.1,-0.9,1.9,0.7,3.9,2.7,-3.3
Sendy Aiya|-5,-4.5,-4,-4.3,-4.5,-2.3,-0.1,2.4,5,4.6,3.6,0.4,-2.6,-1.6,7.3,5.4
Sendy Peacock|3.8,2.3,0.4,-0.6,-2.4,-3.6,-3.5,-2.7,-2.8,-2.7,1.1,2.5,3.1,4.2,1.8,0.4
Senfer DT6|-0.7,-1,-1.8,-3,-4.5,-4.4,-3.4,-2.1,-0.4,0.7,3.1,6.7,0.6,4.4,3.1,1.6
Sennheiser ACCENTUM|-3.2,-2.9,-1.8,0.5,0.7,1.5,0,0,1,0.5,3.4,0.5,2.9,4.4,1.4,-1.2
Sennheiser AMBEO Smart Headset|-0.1,-1.1,-2.2,-3.5,-4.8,-3.8,-1.7,0.8,3.4,2.2,2.6,5.8,6.3,4.4,-5.5,-5.1
Sennheiser Amperior|4.4,3.1,0.4,-0.7,-0.7,1.7,2.8,-0.3,-3.1,-5.1,-5,-1.8,-0.5,1.5,-3.9,-1.7
Sennheiser CX 1.00|-1.3,-1.6,-2.3,-3.2,-4.1,-3.2,-1.7,-0.6,0.9,0.6,1.7,5.9,6.8,5.1,1.1,-1.9
Sennheiser CX 2.00|-0.3,-0.4,-1,-2.1,-3,-1.9,-1.1,0.6,1.8,1.4,1.9,5.2,6.5,4,-1.9,-3.5
Sennheiser CX 3.00|-1.3,-1.1,-1.4,-2.1,-2.9,-1.3,-0.5,1.2,2.2,1.6,1.7,4.4,5.9,4.4,-4.4,-3.5
Sennheiser CX 300-II|1.2,0.4,-1.2,-3,-5.2,-5.3,-4,-2,0.4,1.6,4.3,6.1,4.7,1.7,0.1,2.4
Sennheiser CX 400BT|0.5,-0.4,-1.2,-1.8,-2.4,-2,0,2,3.8,-0.1,-0.3,5.2,6.8,3.7,-5,-4.7
Sennheiser CX 500|-1.3,-1.7,-2.8,-4.2,-6.1,-5.6,-4,-1.9,0.9,2.6,4.8,6.5,6.1,3,1.7,4.8
Sennheiser CX 680|4.3,3.2,1.1,-1.1,-4.3,-5.8,-4.8,-2.8,0.4,0.3,0.1,-0.8,0.9,-4.5,2.1,3.2
Sennheiser CX 880|-0.2,-1,-2.4,-3.6,-4.9,-3.7,-1.8,0.3,2.6,2.8,3.7,4.8,3.6,0.3,-4.3,0
Sennheiser CX Plus True Wireless|0.3,-0.1,-0.8,-1.6,-1.5,-2.7,-1.4,0.3,2.4,0.6,2.4,6.8,7,4.8,-4.2,-7.3
Sennheiser CX True Wireless|-1.4,-1.7,-2.3,-3,-3.8,-2.9,-1.5,0.5,3,1.1,2.6,7.2,7,6.2,-3.1,-4.7
Sennheiser CXC-700|-2.1,-2.3,-2.6,-3.2,-3.6,-1.6,0,2.6,4.2,2,2.1,3.2,3.6,1.3,-3.1,0.1
Sennheiser EH 150|5.3,5.3,5.3,3.7,4.4,5.3,3,-3.8,-7.2,-7.5,-5.3,-1.2,-0.4,-4.3,2.4,1.3
Sennheiser EH 250|6,6,3.1,0.9,1.6,2.4,-0.7,-1.3,-2.1,-2.6,-0.8,0.4,0.1,-0.5,1,-3.3
Sennheiser EH 350|5.9,5.9,5.9,4,2,-0.2,-0.5,-0.7,-1,-1.3,-1,1.3,-2.2,-1.4,-0.7,-5.9
Sennheiser GAME ONE|4.9,4.9,2.2,0,-2.5,-3.2,-2.5,-2.1,-1.5,-2.1,-0.9,-1.1,2.3,-2.2,3.2,2.4
Sennheiser HD 201|5.1,2.1,0.6,-2.6,-4.4,-1.8,2.6,2.9,0,-1,-0.1,0.3,-1.4,-2.3,-2.6,-0.4
Sennheiser HD 202|1.3,-1.4,-4,-3.8,-4.8,-0.8,0.2,-1.1,-2.6,-4,-3.6,2.2,4.9,0,5.8,3.9
Sennheiser HD 203|2.6,-1.1,-3.2,-4.5,-3.4,3.2,-1.2,-2.8,-2.3,-3.2,-1.5,3.8,2.7,-0.3,3.3,2.6
Sennheiser HD 205|3.7,2.3,0.5,-1.8,-2,-2.8,-0.8,-1.2,0.8,-4.2,-3.6,-0.5,-0.7,-3.7,1.5,3.1
Sennheiser HD 205 II|0.3,-0.5,-1.7,-3.4,-3.3,-3.9,-0.9,-0.2,2.8,-2.5,-2.1,1.7,1.2,0.6,1.1,3.6
Sennheiser HD 212 PRO|5.9,5.6,1.9,-0.3,0.6,0.8,-0.6,-0.5,-2.7,-3.6,-1.4,1.7,-1.7,-0.9,2.6,-0.7
Sennheiser HD 218|4.5,2.1,-1.2,-3,-6.2,-5.1,-3.3,-1,0.3,-0.7,2.2,3.4,3.9,-1.3,1.2,2.9
Sennheiser HD 219|1.7,-0.7,-3,-3.9,-3.6,-2.4,1.5,1.5,1.5,-0.5,2.2,1.2,-2.2,0.5,3.3,2.3
Sennheiser HD 228|2.5,0.3,-2.6,-3.9,-6.3,-6.6,-3.2,-0.8,0.5,-0.1,1.8,4.2,5,-3,3.4,4.7
Sennheiser HD 229|3.5,0.9,-1.7,-3.2,-3.9,-3.5,0.1,0.3,0.1,-0.8,1.3,1.1,-2.2,-0.3,0.8,1.8
Sennheiser HD 229 Black|1.4,-0.9,-2.9,-3.6,-3.8,-2.3,1.8,2,1,0,2.3,2.1,-2.4,-1.3,0.4,1.7
Sennheiser HD 238|3.1,1.6,-0.2,-1.6,-3,-3.1,-1.5,-0.5,0.6,1.6,1.4,1.9,-2.3,-0.9,-0.6,0.3
Sennheiser HD 239|2.4,0.8,-0.7,-1.6,-3,-2.8,-1.4,-0.7,0.3,0,0.2,4.2,-1.5,-1.2,-1.6,1
Sennheiser HD 25 Aluminium|4.6,2.5,-0.7,-2.2,-4.1,-3.6,0,0.5,-0.3,-2,-2.3,-2.9,-1.2,4.4,-1.5,1
Sennheiser HD 25 Aluminum|-1.8,-2.2,-2.1,-3.7,-4.9,-0.9,3,2.7,1.8,0.4,-0.8,-0.4,-1.2,6.4,1.3,-0.3
Sennheiser HD 25 Plus|2.2,2.1,-1.7,-3.3,-3.4,-1.3,0.2,-0.3,-0.7,-2,-2.1,-2,-0.9,3.4,-0.4,1.9
Sennheiser HD 25-1|1.9,1.6,0.4,-3.6,-3,-1.1,1.1,0.4,-0.4,-2.2,-3,-0.7,-0.8,5.3,-1.4,-1.7
Sennheiser HD 25-1 II|4.7,4.7,3.2,1.9,-3,-3.7,-2.6,-2.2,-1.6,-2.2,-2.5,-1.8,-2.1,2.6,0.8,-1.1
Sennheiser HD 25-1 II B (2012)|0.6,-0.3,-1.7,-1.5,-4.1,-1.8,-0.2,-0.6,-0.6,-1.4,-1.2,0.5,-0.9,4,1.6,0.3
Sennheiser HD 25-SP|2.6,0,-1.6,-3.8,-4,-4.3,0.2,2.1,1.5,0,-1.2,-1.5,-1.2,0.4,1.4,-0.8
Sennheiser HD 250 II|-0.1,-1.1,-0.1,-1.6,-3,-0.1,1.9,1.6,1.4,0.4,1.4,1.4,0.5,0.6,-1,-2.4
Sennheiser HD 250BT|2.8,2.3,-0.9,-1.1,-2.5,-0.9,0.4,-0.6,-0.5,0.4,-0.4,0.9,3.1,-1,-1.7,-2.2
Sennheiser HD 280 Pro|-5.7,-4.7,1.1,1.7,-1.3,-1,-1.5,-1.4,-0.8,-0.9,0.7,1,5.4,3.7,2.9,3.3
Sennheiser HD 280 Pro (SPC Gear velour earpads)|4.2,3.2,2.9,3.2,4.1,-0.6,-3.5,-5.3,-6.4,-7.4,-7.3,-5,3.9,2.1,0.1,3.2
Sennheiser HD 380 Pro|-5.8,-4.8,-1.3,2.3,-1.5,3.2,1,-0.7,1.2,1.9,3.3,-0.9,-1,3.9,3.2,2.9
Sennheiser HD 4|3.4,1.6,-0.3,-1.4,-3.3,-3.7,-1.8,-2.5,-2.2,-2.8,-2.6,-0.5,2.6,-3.1,1.9,3.6
Sennheiser HD 4.40BT|-1.9,-1.9,-1.4,-1.1,0.2,4.3,1.6,-0.1,-0.5,-1.2,-1,-0.5,4.6,0.9,0.6,-0.3
Sennheiser HD 4.50 BTNC|0,-0.2,-0.1,-0.1,0,1.5,2.1,0.7,-1.6,-3.8,-5.9,-4.6,4.3,1.2,-3.1,3.5
Sennheiser HD 400 Pro|5.7,5.6,4,2.5,0.3,-0.5,-0.2,-0.4,0,-1.4,-0.8,-0.6,0.1,0.4,1.8,-2.7
Sennheiser HD 400 S|1.4,0.1,-0.6,0,1,2.6,-0.7,-3.2,-3.3,-3.9,-3.3,-1.5,2.6,-2.4,2.8,2.6
Sennheiser HD 414|4.1,4.1,3.7,0.8,-2.5,-3.9,-3.1,-2.8,-2.3,-2.8,-3.1,-6.3,-5.6,3.9,4.1,4.1
Sennheiser HD 419|-2.6,-3.2,-4,-2.7,-7.4,-6.8,-2.1,-0.9,0.4,0.9,1.5,4.3,2.8,6.3,3.7,5.7
Sennheiser HD 424|4.5,4.5,4.5,4.2,0,-1,-0.5,-0.4,-0.8,-2.2,-3.9,-3.9,-3.6,-0.4,-2.7,-2.1
Sennheiser HD 428|5,5,5,1.2,-2.3,-1.7,-2,-1.6,-3.2,-2.4,-3.3,0.1,-4.3,-0.8,0.7,0.1
Sennheiser HD 429|-0.8,-1.1,-1.5,0.3,-4,-4.2,-1.5,-2.8,-3,-2.6,-1.3,2.9,0.3,5.3,2.3,4.1
Sennheiser HD 438|3.8,1.9,0.5,0.2,-2.1,-0.9,-1.2,-2.4,-3.5,-5,-1.9,4,-1.7,1.6,-0.2,1.5
Sennheiser HD 439|0,-1.1,-1.9,-0.5,-4.7,-5.7,-2.5,-2,-1.9,-2.1,-2.1,2.8,0.2,4.6,3.2,5.7
Sennheiser HD 448|4.6,4.7,2.1,0.8,-3.7,-4.1,-1.6,-2.5,-3.3,-3.5,-5,-0.3,-2.1,0.7,2.9,2.8
Sennheiser HD 449|-0.8,-1.9,-2.4,-0.7,-3.9,-3.5,-0.7,-0.8,-1.8,-1.7,-2,2,0.3,5.8,2.2,2.9
Sennheiser HD 450BT|-2.8,-2.7,-2.6,-2.9,-3.1,-1.3,0.3,-0.4,-1.3,-1.4,-2.3,-0.9,5.8,4.1,2.1,4.6
Sennheiser HD 471i|-0.8,-1,-0.4,0.1,-3.6,-5.2,-2.9,-2.9,-3,-2.5,-1.8,1.8,0.5,5.2,2.8,4.8
Sennheiser HD 518|5,2.7,-0.1,-0.3,-3.9,-4.8,-3.9,-2.8,-1.1,0.8,3.7,-0.1,0.5,0.5,0.1,1.3
Sennheiser HD 540 reference-II|5.3,5,2.8,-0.4,-3.1,-4.2,-3.2,-2.1,-0.8,1.3,3.2,1.2,2,-3.1,-0.3,-1.3
Sennheiser HD 545 Reference|4.9,4.9,4.9,3.8,0.4,-1.6,-1.4,-1.6,-2.2,-3.1,-3.2,-4.8,-0.1,1.2,1.2,-0.3
Sennheiser HD 555|4.7,4.7,3.5,1.3,-1.4,-3,-2.5,-2.3,-2.4,-3.1,-0.8,-3.4,-4.3,0.8,4.7,3.1
Sennheiser HD 558|5.3,3.9,1.6,-1,-3.2,-3,-1.5,-0.3,0.9,1.7,3.5,-0.6,-2.5,-1.5,1.1,0.3
Sennheiser HD 559|2.1,-0.5,-2.9,-4.5,-6.5,-6.4,-4.4,-2.6,-0.5,2.8,5.6,1,2,0.4,5.4,5.5
Sennheiser HD 560 Ovation II|5.1,5.1,5,2.9,0.1,-1.1,-0.5,-0.4,0,-0.5,-0.8,-3.4,-4.7,-2.3,-0.6,-2.8
Sennheiser HD 560S|3.3,2.2,1.3,0.5,-1,-1.2,-0.5,-0.5,-0.3,-1.9,-0.8,0.1,-0.4,-2.3,2.4,1.5
Sennheiser HD 560S (worn earpads)|4.9,3.8,2.1,1.3,-0.6,-1.5,-1.1,-1.3,-1.7,-2.8,-2.5,-3.5,0,1.7,2,0.7
Sennheiser HD 565 Ovation|4.6,4.6,4.5,1.9,-1,-2.8,-2.3,-2.6,-2.5,-3.5,-2.9,-3,-4.8,0.6,3.8,2
Sennheiser HD 569|-3.2,-4.7,-5.8,-5.7,-4.2,0.3,1,-1,-0.7,-0.3,2.1,3.7,2,7,5.3,4.9
Sennheiser HD 575|4.6,4.1,1.3,-1.1,-3.5,-4.1,-2.9,-2,-0.7,-0.8,0.6,-1.6,-6.4,-0.2,3,4.5
Sennheiser HD 579|4.8,2.6,1.3,-1.5,-3.7,-4.2,-3,-1.8,-0.4,0.3,3.4,0,-0.6,-2,3.1,4
Sennheiser HD 580|4.7,4.5,2.2,0.3,-1.8,-2.5,-1.4,-1.3,-1,-1.9,-1.6,-1.7,-5.3,-1.3,2.1,2.2
Sennheiser HD 580 (black paper)|5.2,5.1,2.7,0,-2.2,-2.6,-1.4,-0.7,-0.4,-1.4,-0.2,0,-2.9,-2.6,0.8,0.3
Sennheiser HD 580 (black silk)|5.2,3.3,2,0,-1.6,-2,-1,-0.4,-0.5,-1.8,-1.9,-1.2,0.8,-0.6,2.5,1.7
Sennheiser HD 580 (silver screen)|4.9,4.9,2.5,0.6,-1.6,-2.4,-1.6,-1.3,-1.7,-3.3,-2,-2.1,-2.4,-1,3.1,2.6
Sennheiser HD 580 (white paper)|5,5,2,-0.8,-3.1,-3.7,-2.5,-1.8,-1.5,-2.2,-0.4,0.8,-1.3,-0.8,2.6,2.4
Sennheiser HD 580 precision|5.1,5.1,4.7,1.9,-0.5,-1.3,-0.3,-0.2,-0.8,-2.5,-2.5,-3.5,-4.7,-5.6,5.1,1.1
Sennheiser HD 58X|4.4,2.7,1.1,-0.5,-2.4,-3,-2,-1.5,-1.5,-2.2,-1.3,0.5,-0.3,-2,2.8,2.9
Sennheiser HD 58X (Brainwavz microsuede oval earpads)|4.9,4.9,3.5,0.9,-2.5,-5.1,-5,-4.8,-3.6,-2.4,1.9,-1.4,-1.4,3,4.9,1.3
Sennheiser HD 595|4.7,4.7,3.8,1.2,-1.6,-3.2,-2.8,-2.9,-2.3,-3.1,-1,-0.7,-1.9,0.2,1.1,1.8
Sennheiser HD 598|5,3.2,0.8,-0.2,-3.6,-4,-2.7,-1.8,0,-0.4,2.2,0.7,-1.6,-2,3.2,3.3
Sennheiser HD 598 CS|-2,-2.3,-2.2,-1.9,-3,0.7,1.8,-0.7,-1.4,-2,-1.1,1.1,0.1,4.6,6,5
Sennheiser HD 599|4.9,3.7,1.7,-1.4,-3.9,-4.2,-3,-1.8,-0.4,0,2.9,1.4,-1.3,-2.2,2.1,3.7
Sennheiser HD 600|5.2,4.2,2.1,0.2,-1.8,-2.2,-1.2,-0.7,-0.6,-1.6,-1.5,-0.8,-1.9,-1.2,1.9,0.3
Sennheiser HD 600 (2020)|5.1,4,2.1,0.2,-1.6,-2.3,-1.4,-0.9,-0.8,-2.5,-2,-1.8,-2,-0.7,3.4,1.5
Sennheiser HD 600 (2020, worn earpads)|4.9,4.1,1.7,-0.4,-2.5,-3.1,-2.2,-1.7,-1.9,-2.3,-1.7,-1,-2,1.1,3.3,2.9
Sennheiser HD 600 (pre-2020, semi-worn earpads)|4.9,3.7,1.7,-0.7,-2.6,-3.3,-2.4,-1.8,-1.9,-2.5,-1.7,-0.1,-1.6,0.7,2.8,3.1
Sennheiser HD 600 (pre-2020, worn earpads)|4.9,4.9,2.1,0,-2,-2.8,-1.9,-1.5,-1.7,-2.2,-1.7,-1.4,-2.5,0.2,2.7,2.3
Sennheiser HD 630VB|3.2,2.3,1.6,-1,-2.7,2.3,3,0.6,-3.2,-4.1,-4.3,-0.5,-0.9,4.3,1.1,-0.4
Sennheiser HD 650|5.1,3.6,1.7,-0.4,-2.4,-2.8,-1.8,-1.2,-1,-1.8,-1.1,-0.8,-1.5,-0.9,3.4,2
Sennheiser HD 650 (2020)|4,2.3,1.3,-0.9,-2.5,-2.7,-1.5,-1,-0.1,-1.9,-0.4,-0.8,-1.3,-1,3.7,2.7
Sennheiser HD 650 (2020, worn earpads)|4.4,3,0.6,-1.2,-3.1,-3.7,-2.7,-2.4,-2.4,-3.4,-2.5,-1.8,0.2,4.7,4.6,4
Sennheiser HD 650 (balanced)|4.9,4.9,2.3,0.1,-2,-2.5,-1.4,-0.4,-0.4,-0.6,0.3,-0.3,-4,0.1,-0.8,0.1
Sennheiser HD 650 (pre-2020, semi-worn earpads)|5.1,3.8,1.7,-0.6,-2.7,-3.3,-2.3,-1.6,-0.8,-1.1,-0.3,-0.2,-1.9,-1,2.7,1.5
Sennheiser HD 650 (pre-2020, worn earpads)|4.8,4.3,1.3,-0.6,-2.5,-3.3,-2.3,-1.9,-1.8,-3.1,-2.6,-2.9,-0.6,2.9,4.6,3.6
Sennheiser HD 660 S|4.8,4.2,2,-0.1,-2.7,-3.8,-3,-2.5,-2.4,-3.1,-1.9,0.2,-0.1,-1.6,4.1,3.8
Sennheiser HD 660S (Dekoni Fenestrated Sheepskin Earpads)|5.4,3.5,1.2,-0.8,-3.3,-4.2,-3.4,-2.7,-2.5,-2.5,-0.4,0.6,1.2,-1,5.4,3.7
Sennheiser HD 660S2|5.2,3.8,2.8,0.5,-1.8,-2.9,-2.2,-1.5,-1.7,-2.1,-0.9,1.2,0.1,-0.6,2.3,0.6
Sennheiser HD 6XX|4.8,4,1.6,-0.4,-2.5,-2.9,-1.7,-1.1,-0.8,-1.7,-1.6,-2.3,-2.4,0.3,1.8,3.3
Sennheiser HD 700|5.2,3.6,0.9,-1.1,-3.3,-4.1,-2.9,-1.9,-1,0.3,4,3.2,2.5,-3.8,0.6,1.6
Sennheiser HD 700 (Dekoni elite velours earpads)|5.4,4.9,2,0.2,-2.2,-3.5,-2.5,-1.6,-0.8,0,2.6,2.3,2.2,-5.4,-0.2,0.7
Sennheiser HD 800|5.3,4.7,3.2,1.3,-1.1,-2.3,-1.6,-1.1,-0.7,-0.2,2.2,1,0.5,-3.8,-2.7,-2.1
Sennheiser HD 800 (sample 1)|5.8,5.6,2.6,1.1,-1.4,-2.5,-1.6,-0.8,-0.3,0.8,3.8,5,-1.1,-2,-4.1,-2.9
Sennheiser HD 800 (sample 2)|5.4,5,2.8,0.8,-1.2,-2.3,-1.5,-0.6,0.5,2.4,4.8,4.9,-1.4,-2.6,-4.7,-3.9
Sennheiser HD 800 (SDR mod)|5.3,4.7,2.6,0.9,-1.4,-2.6,-1.9,-1.3,-1.1,-1,1.1,0.4,2.3,-2.1,-1.3,-1.5
Sennheiser HD 800 (SDR mod, Dekoni fenestrated sheepskin earpads)|5.3,5.3,4.8,3,0.2,-1.8,-1.4,-1.3,-1.4,-1.4,1.4,-0.2,0.6,-2.8,-1.7,-4.1
Sennheiser HD 800 S|5,4,2.9,0.7,-1.5,-2.6,-1.8,-1.4,-0.9,-0.5,1.9,0.7,1,-2.2,-0.6,-1.9
Sennheiser HD 800 S (Dekoni fenestrated sheepskin earpads)|5.6,5.6,4.6,2.9,0.3,-1.5,-1.1,-1,-1,-0.5,2.7,0.8,0.6,-3.3,-2.9,-5.4
Sennheiser HD 800 S (sample 1)|4.1,3,1.7,-0.3,-2.4,-3.5,-2.6,-2.1,-0.9,0.4,4,3.2,-0.8,-0.4,-1.5,-0.9
Sennheiser HD 800 S (serial number 01067)|4.4,3.3,2,0.4,-1.7,-3,-2.1,-1.8,-0.7,0.3,3.3,3.2,-1.6,0,-1.5,-2
Sennheiser HD 800 S (serial number 01070)|4.3,3.1,2.1,0,-2.2,-3.5,-2.5,-2.1,-1,0.1,3.2,2.8,-0.7,-0.6,-1,0
Sennheiser HD 820|-3.3,-3.5,1.4,-4.6,-7,3.9,4.8,1.3,-0.7,-1.5,1.6,1.6,5.1,-0.7,2.8,2.9
Sennheiser HD 8XX|3.8,4.3,1.7,-0.5,-3,-4.7,-4.4,-4.1,-3,0.7,5.4,3.6,0.5,-1.4,0.4,0.2
Sennheiser HD R 130|4.3,4.3,4.3,2.7,-1.6,-3.4,-2.6,-1.9,1.1,2.3,-1.2,0.1,-8.2,-5.5,-2.4,2.5
Sennheiser HD1 In-Ear|-1.4,-1.7,-2.5,-3.8,-5,-4,-2.1,0.8,3.9,2.8,3.8,7,7,6.2,-4.5,-5.2
Sennheiser HD1 In-Ear Wireless|-1.7,-1.9,-2.7,-4,-5.2,-4,-2.1,0.9,3.9,3,3.8,6.9,7,6.2,-4.3,-5
Sennheiser HD1 On-Ear|-0.5,-0.8,-1.7,-2.3,-2.9,-0.8,3.5,6.5,3.7,1.3,-1.4,-2.4,4,1.1,-5.5,-3.9
Sennheiser HD1 Over-Ear|-1.6,-1.9,-2.2,-2.3,-2.5,-0.1,-0.1,-1.8,-1.6,-1.5,-0.7,0.2,4.8,0.9,2.4,1.5
Sennheiser HD1 Wireless|0.4,0,-0.4,-0.6,-1.1,-0.2,2.4,1.8,-1.2,-3.3,-5.1,-1.8,3.5,-1.1,3.2,1.2
Sennheiser HD25-1 II|1.3,0.2,-2.8,-4.2,-3.8,-3.6,-0.1,2.5,1.5,-0.9,-0.3,-1.8,0.4,3,-1.9,-0.1
Sennheiser HD25-1 II (bad seal)|4.3,4.3,3.8,0.4,-1.6,-2.9,-1.9,-0.3,-0.6,-2.5,-2,-3.5,-1.3,1.3,-3.6,-1.6
Sennheiser HD600 (2020)|5.1,4.2,1.8,0.2,-1.9,-2.4,-1.4,-0.9,-0.5,-1.9,-1.7,-0.7,-2,-1.5,2.1,1.1
Sennheiser HD800|4.2,3.5,2.2,0.3,-1.8,-2.8,-2,-1.3,-0.8,-0.3,2,2.6,1.2,-2.5,-2.2,-1.4
Sennheiser HD800 (SDR mod)|5,4,2.2,0.4,-2,-3.1,-2.4,-1.8,-1.3,-0.5,1.6,1.7,1.8,-1.7,-1.4,-0.8
Sennheiser HE 1 Orpheus 2|2.5,1.2,1,0.8,0,-0.7,-0.4,-0.1,0.3,0,2.8,-0.2,-0.2,0.6,-0.4,-1.7
Sennheiser HE 1 Orpheus 2 (semi-worn earpads)|2.8,0.8,0.4,0.3,-0.4,-1.2,-0.9,-0.6,-0.3,-0.7,3,0.8,0,0,1,-0.5
Sennheiser HE 60|5.2,5.2,3.8,1.9,0.6,-0.9,-0.4,-0.7,0,-1.5,-0.7,-0.1,-2.1,-3.4,-2,-1.9
Sennheiser HE 90 Orpheus|4.9,4.2,2.9,1.8,-0.3,-1.4,-1.1,-0.8,-0.5,-1.9,0,-2.8,-2.6,0.8,-0.6,-1.1
Sennheiser IE 200|-0.5,0.3,0.6,0,-1.2,-1.3,-0.8,-0.1,0.5,-0.4,-0.6,2.2,4.6,6.3,-0.3,-5
Sennheiser IE 200 (AZLA Max eartips)|-2.1,-1.2,-0.6,-1,-1.7,-1,-0.1,0.7,1.5,0.9,0.6,3.3,6,6.2,0.4,-4.3
Sennheiser IE 200 (JVC Spiral Dot++ eartips)|-2.2,-1.3,-0.7,-1,-1.7,-1,-0.1,0.7,1.5,1,0.6,3.3,6.6,6.5,0.1,-4.3
Sennheiser IE 200 (long nozzle)|5.3,5.3,4.5,2.7,0.3,-0.9,-0.7,-0.5,-0.5,-1.6,-2.6,0.2,2.4,1.1,-5.2,-7.9
Sennheiser IE 200 (short nozzle)|-0.1,0.2,0.2,-0.4,-1.3,-0.9,-0.2,0.4,1.2,0.5,0,2.7,4.9,5,-1.1,-5.2
Sennheiser IE 200 (SoftEars UC eartips)|-1,-0.4,-0.1,-0.7,-1.4,-0.9,-0.1,0.9,1.6,0.9,0.5,3.1,5.2,6.1,-1.6,-5.9
Sennheiser IE 300|-2.6,-2.1,-1.9,-2.3,-2.9,-1.5,0,1.6,3,2.9,3.8,7.3,7.5,3.4,-2.4,-6
Sennheiser IE 300 (AZLA Sedna Light eartips)|-2.7,-2.5,-2.4,-3,-3.6,-2.3,-0.7,1.1,3.2,4,5.4,7.9,7.9,4.7,-2.9,-7.2
Sennheiser IE 300 (Final Audio Type E eartips)|-3.1,-2.7,-2.7,-3.3,-3.9,-2.9,-1.2,0.4,2.5,3.1,4.3,7.1,6.5,2.4,-1.3,-3.1
Sennheiser IE 4|-2.8,-2,-1.4,-1.6,-1.6,-0.4,1.8,3.8,5.4,4.9,5.1,2.2,-6.5,-11,2.2,3.3
Sennheiser IE 40 Pro|2.3,1.5,0.4,-0.9,-2.3,-2.5,-1.6,-0.7,0,-0.5,0.7,4.3,4.4,1.7,-3,-5.3
Sennheiser IE 400 Pro|-1.9,-1.8,-2,-2.9,-4.2,-3.8,-2.5,-0.6,1.8,2.8,2.9,7.1,7.2,4.2,0.8,-2.6
Sennheiser IE 500 Pro|-0.7,-0.8,-1.3,-2.3,-3.9,-4,-3.3,-2.2,-0.9,-1.5,-2.4,6.4,6.8,6.5,4.7,0.9
Sennheiser IE 6|-0.3,-1.2,-2.7,-4.1,-5.6,-4.9,-3.1,-1.2,1.6,2.4,3.9,3.3,0.2,-2.6,6,3.5
Sennheiser IE 600|-1.8,-0.7,0.2,-0.1,-1,-0.1,0.5,1.6,2.2,1,-0.6,1,4.8,4.8,1.3,-4.1
Sennheiser IE 600 (AZLA Sedna eartips)|-2.2,-0.9,-0.1,-0.3,-0.7,0.3,1.2,2.3,3.2,2.1,1.2,1.9,5.4,3.7,-2.2,-6.7
Sennheiser IE 600 (Final Audio Type E eartips)|-2.4,-1.2,-0.5,-0.7,-1,-0.2,0.8,1.9,2.7,1.6,0.5,1.9,4.5,2.8,-1.7,-5.2
Sennheiser IE 600 (foam eartips)|-2.6,-1.1,-0.1,-0.2,-0.6,0.2,1.1,2,2.4,1.2,0.2,1.4,5.1,5.1,0.3,-4.8
Sennheiser IE 600 (Moondrop Spring eartips)|-2.6,-1.3,-0.4,-0.6,-1.1,-0.2,0.7,1.7,2.6,1.6,0.8,2.5,6,5.6,-1.1,-5.6
Sennheiser IE 600 (silicone eartips)|-2.5,-1.1,0,-0.2,-0.5,0.3,1.3,2.1,2.6,1.3,0.4,1.5,5.3,5.1,0,-5.4
Sennheiser IE 600 (Spinfit eartips)|-2.3,-1.2,-0.2,-0.4,-0.8,0.1,1.1,2.1,3,2,0.8,1.9,5.4,2.9,-2.3,-6.3
Sennheiser IE 8|1,-0.6,-2.7,-4.7,-6.8,-6.2,-4.2,-1.7,1.5,3.1,2.4,3.4,1.6,5.9,5.4,2
Sennheiser IE 80 (half)|-1.5,-2.2,-3.2,-4.4,-5.5,-4.4,-2.6,-0.7,1,2,2.8,6,4.3,-3.2,6,6.2
Sennheiser IE 80 (max)|-2.6,-2.9,-3.5,-4.5,-5.6,-4.3,-2.4,-0.5,1.4,2.4,3.3,6.3,4.7,-2.5,6.5,6.6
Sennheiser IE 80 (min)|3,1.2,-0.9,-2.7,-4.7,-4.6,-3.5,-2.4,-1,-0.2,0.9,4.4,3.1,-4.3,4.1,4.8
Sennheiser IE 80 S|-3.7,-3.8,-4.4,-5.5,-6.4,-5,-2.7,0.1,3.3,5,4.9,4.5,2.2,-3.7,5.1,6.4
Sennheiser IE 80 S (half)|-1.7,-2.2,-2.9,-4.1,-5.1,-3.8,-2,0,2,2.8,2.6,3.6,1.4,-4.6,5.6,5.8
Sennheiser IE 80 S (max)|-2.7,-2.6,-2.9,-3.7,-4.6,-3.6,-2.1,-0.1,2,3.2,3,3.7,1.4,-3.4,5.9,6.1
Sennheiser IE 80 S (min)|2.4,0.9,-1,-2.7,-4.5,-4,-2.7,-1.2,0.2,1,0.9,2.4,0.2,-6.3,3.8,4.3
Sennheiser IE 800|-1.5,-1.3,-1.5,-2.1,-3.2,-2.2,-0.8,0.5,1.4,0.9,2.4,7.8,7.8,5.5,0.2,-5.1
Sennheiser IE 800 (sample A)|-2.4,-2.2,-2.4,-3,-3.6,-2.6,-1.4,-0.7,0.6,0.3,1.9,7.3,7.3,3.7,1.8,-1
Sennheiser IE 800 (sample B)|5.9,4,1.6,-0.6,-3.3,-4.3,-3.7,-3,-1.5,-1.6,0.1,5.9,6,4.8,-1.4,-7.1
Sennheiser IE 800 (sample C)|-0.5,-0.6,-1.5,-2.5,-3.5,-2.9,-1.7,-0.9,0.3,-0.1,1.9,7.4,7.4,6.6,-1.1,-4.2
Sennheiser IE 800 S|-0.3,-0.5,-1.1,-2.2,-3.5,-3.3,-1.9,-0.6,0.2,-0.7,-0.5,6.3,6.9,3.8,3.5,0.1
Sennheiser IE 900|-1.7,-1.1,-0.6,-0.9,-1.4,-1.1,-0.6,0.4,1,-0.1,-0.9,4.4,6.9,3.6,0,-2.5
Sennheiser IE 900 (Final Audio Type E eartips)|-2.3,-1.7,-1.3,-1.7,-2.3,-1.7,-0.7,0.1,0.9,0,0.1,6.2,6.7,1.7,-1.3,-2.6
Sennheiser IE 900 (foam eartips)|-2.2,-1.2,-0.5,-0.9,-1.5,-0.8,0,0.7,0.9,-0.3,-0.3,5.1,7.2,4.8,-0.7,-3.5
Sennheiser IE 900 (silicone eartips)|-2.1,-1.1,-0.5,-0.8,-1.4,-0.7,0.1,0.9,1.1,-0.1,-0.2,5.3,7.3,5.3,-1.1,-4.4
Sennheiser MM 50 iP|2.4,0.7,-1.4,-3.4,-5.6,-4.9,-4.1,-1.7,1,2.7,4.2,5.4,2.7,0,-1.5,1.8
Sennheiser MM 550|4.9,4.9,4.5,2.7,3,-0.3,-4.4,-3.2,-1.4,-2.9,-8.5,-3.9,-0.2,-1,1.5,-0.7
Sennheiser Momentum|1.5,0.7,-0.9,-2,-3.4,-4.6,-2.5,-1.9,-2.5,-3.2,-2.8,1.7,1.9,2.8,3.9,2.1
Sennheiser Momentum 2.0|2.2,0.8,-0.7,-1.5,-2,-1.7,-2.1,-3.2,-3.6,-3.6,-3.6,-1.1,1.7,-1.8,3.3,4.5
Sennheiser Momentum 2.0 (wired, active)|2.8,-0.2,-3.8,-5,-1.8,0.8,3.4,2.3,0.8,-1.1,-1.9,-3.5,-0.1,0.5,-1.3,0.9
Sennheiser Momentum 2.0 (wired, passive)|-0.1,-1.5,-2.4,-3.1,-5.9,-0.4,2.5,1.6,1.8,1.1,0.5,-0.3,2.3,1.2,-0.5,2.1
Sennheiser Momentum 2.0 (wireless)|-3.6,-3.1,-2.8,-2.4,-2.3,-0.7,1.3,0.6,-1.6,-2.7,-3.8,-0.8,5.1,2.9,6.7,6
Sennheiser Momentum 2.0 Wireless|-1.7,-4,-3.9,-3.9,-4,-2.4,0.1,-1.1,-2.8,-3.6,-3.6,0.3,5.7,1.2,6.2,5.7
Sennheiser Momentum 3 Wireless|-1.9,-2.1,-2.1,-3.4,-2.7,0,0.9,2.9,3.1,2.8,0.8,-0.8,2.7,2.3,-0.1,0.2
Sennheiser Momentum 4 Wireless|-6.4,-4.9,-3.8,-2.6,-3.2,-1.2,-0.1,1.8,2.5,2.1,4.7,7.4,3,1.6,2.5,3.5
Sennheiser Momentum 4 Wireless (passive)|-1.3,-1.4,-1,-4.1,-3.4,-1.4,-1.1,-2.3,-6.8,-8.4,3.5,6.5,4.7,6.4,6.3,3.9
Sennheiser Momentum In-Ear|-2.4,-2.3,-3.1,-4.5,-6,-4.9,-3.1,-0.2,3.3,5,5.8,6.3,7,5.9,-2.1,-2.2
Sennheiser Momentum On-Ear|-1.4,-1.9,-2.2,-2.4,-3,-0.6,2.3,2.2,0.6,0,-2.4,0.7,3.2,1.4,-0.2,2.4
Sennheiser Momentum True Wireless|-0.6,-0.8,-1.3,-2.1,-4,-3,-2,-0.3,1.1,0.6,1.1,6.5,7.1,7.1,-1.4,-4.6
Sennheiser Momentum True Wireless 2|1.1,0,-1.5,-2.8,-4.1,-4.3,-3.4,-1.4,0.3,0.4,0,5,6.4,6.4,-0.3,-3.7
Sennheiser Momentum True Wireless 3|-1.4,-0.8,-0.2,-0.5,-1.2,-1.1,-0.7,0.4,0.8,-0.6,-0.8,2.4,5.5,6,1.5,-3.5
Sennheiser Momentum True Wireless 3 (ANC off)|-0.7,-0.1,0.2,-0.1,-1.6,-1,-0.3,0.6,1.3,0.1,-0.1,2,6,7.2,0.7,-5.2
Sennheiser Momentum True Wireless 3 (ANC on)|-0.4,0.1,0.4,-0.1,-1.1,-1,-0.3,0.6,1.2,0.1,-0.2,1.9,5.8,7.1,0.5,-5.4
Sennheiser Momentum Wireless (wired, active)|0.7,1.3,1.4,1.4,1.1,1.4,2.7,0.8,-2.5,-5.3,-7.7,-4.9,0.5,-4.3,2.5,4.5
Sennheiser Momentum Wireless (wired, passive)|0.3,-0.7,-2.1,-3.1,-3.6,-2.2,0.2,-1.6,-1.8,-2.5,-2.3,0,3,-0.7,3.7,4.7
Sennheiser Momentum Wireless (wireless)|0.4,1.2,1.4,1.4,1.1,1.6,2.8,0.9,-2.7,-5.5,-8.1,-4.7,0.5,-5,2.9,4.7
Sennheiser MX 560|4.9,4.9,4.9,4.9,4.9,3.1,2.2,2.2,1.3,0.3,-2.3,-9.5,-7.8,-7.5,-4.8,-2.9
Sennheiser MX 680|5.5,5.5,5.5,5.5,5.5,4.1,2.7,1.9,0.4,-0.8,-4.1,-9.2,-4.7,-4.7,-6.5,-4.8
Sennheiser OCX 685|1.7,1.1,-0.3,-2.2,-4.4,-4.5,-3,-0.8,1.5,1.3,0.7,-1,-0.2,-5.9,1.2,4.4
Sennheiser OMX 680|4.3,3.2,1.1,-1.1,-4.3,-5.8,-4.8,-2.8,0.4,0.3,0.1,-0.8,0.9,-4.5,2.1,3.2
Sennheiser PC37X|5,5,2.4,1,-2.3,-3.4,-2.6,-2.1,-1.5,-1.8,0.5,-0.3,1.1,-2.5,1.8,1.4
Sennheiser PC38X|3.6,1.7,0.4,-0.5,-1.7,-2,-1.2,-0.7,-0.8,-2.5,-2.4,-1.4,-0.8,-1.7,4.1,4.5
Sennheiser PC38X (velour earpads)|2.8,0.9,-0.7,-0.8,-2.2,-2.4,-1.5,-0.9,-0.8,-2.3,-1.4,-1.3,-0.5,-1.7,5.1,5.3
Sennheiser PMX 100|1.9,0.3,-1.7,-2.9,-4.3,-3.6,-1.9,-0.7,0.1,-0.2,-0.6,1.9,-1.5,-0.6,4.9,2.1
Sennheiser PMX 680|4.3,3.2,1.1,-1.1,-4.3,-5.8,-4.8,-2.8,0.4,0.3,0.1,-0.8,0.9,-4.5,2.1,3.2
Sennheiser PX 100|1.7,0,-1.7,-2.9,-4,-3.9,-2.5,-1.7,-0.9,-1.6,-2.6,2.1,-0.9,1.6,5.4,5.3
Sennheiser PX 100 II|5.2,1.8,-2.2,-4,-5.5,-4.9,-3.3,-2.4,-2,-3,-4.6,2.9,3.5,1.6,5.2,4.7
Sennheiser PX 100-IIi|4.7,3.6,0.1,-1.8,-3.7,-4.4,-3.2,-2.4,-1.4,-2.1,-2.7,2.2,-2,0.1,2.9,2
Sennheiser PX 200|3.9,3.9,3.9,2.9,0.8,-1.4,-2.1,-4.7,-5.6,-5,-4.6,-2.9,-3.1,-0.6,1.4,3.9
Sennheiser PX 200 II|4.9,4.3,2.1,2.3,-1,-1.7,-2.4,-3.2,-3.7,-4.7,-3.8,-1.7,-1.7,-2,4.9,3.4
Sennheiser PX 200-IIi|4.3,4.3,2.8,1.6,-2,-4.3,-4,-3.8,-2.1,-2.8,-2.9,-2.8,-2.1,3.1,1.2,2.2
Sennheiser PX 360|4.8,4.5,1.6,-0.1,0.3,-2.1,-2,-0.9,-0.9,-3.6,-7.9,-3.9,0.1,1.5,1.6,-1.5
Sennheiser PXC 150|4.6,4.6,1.2,0.8,-1.3,-1.2,0,0.5,-0.6,-3.3,-4.7,-2.2,-0.9,-2.8,-3.7,-0.1
Sennheiser PXC 250 II|4.9,4.9,2.3,1.1,0,0.1,1.2,1.9,1.7,-1.9,-5.9,-5.5,-0.4,2.4,-7.3,-5
Sennheiser PXC 300|4.5,4.5,4.5,2.9,0.5,0.1,0.8,1.1,0.1,-3,-2.5,-3,-4,-4.7,-7,-2.1
Sennheiser PXC 350|3.2,3.1,3.1,2.3,1.2,1.2,0.7,-1.1,-1.3,-0.8,-0.7,-3.5,-1.5,0.5,-2.7,-4.2
Sennheiser PXC 450|1.8,2.4,1.3,0.4,-1.7,-3.5,-3.9,-4.5,-2.2,0.3,2,-0.6,0.5,3.7,1.9,0.5
Sennheiser PXC 550 Wireless|3.3,2.9,3,2,-0.3,-0.2,1.7,1.2,1.6,0.9,-2.1,-2.4,-0.2,-2.9,-4.6,-6
Sennheiser PXC 550 Wireless (wired, ANC on)|3.2,2.7,2.8,2.1,-0.2,-0.4,1.6,0.9,0.1,-2.3,-2.1,0.7,-1.9,-6.4,-3.1,-3
Sennheiser PXC 550 Wireless (wired, power on)|0.5,-0.4,-0.8,-1,-3.5,0.5,2.8,2.3,1.5,-0.4,-0.8,1.2,0.2,-3.1,-0.6,-1
Sennheiser PXC 550 Wireless (wireless, ANC on)|2.5,3,3.2,2.2,0.2,0.3,1.8,0.7,0.1,-2.7,-2.6,0.4,-1.2,-5.6,-3.2,-3.2
Sennheiser PXC 550-II Wireless|1.4,1.1,1.1,0.2,-2.2,-2.9,-0.3,-0.2,0.3,-0.5,0.1,2,1.9,-3.2,-0.9,-0.4
Sennheiser RS 130|4.3,4.3,4.3,2.7,-1.6,-3.4,-2.6,-1.9,1.1,2.3,-1.2,0.1,-8.2,-5.5,-2.4,2.5
Sennheiser RS 160|1.3,0.8,0.5,0,-2.6,-1.3,-0.4,0,1.3,-1.2,-1.5,-1.4,-3.1,2.6,1,-3.6
Sennheiser RS 165|-0.7,0.5,-0.5,-2.8,-3.9,0.2,1.5,3.4,1,-1.9,0,0.9,5.9,1.3,-2.1,-0.4
Sennheiser RS 170|5.6,2.8,0.1,0.1,-3.6,-2.4,0.1,0.3,2.6,2.1,-1.4,-1.4,-4.8,2.8,-0.4,-2.2
Sennheiser RS 175|-4.6,-2.9,-2.9,-3.6,-1.4,4.6,4.8,6.6,3.3,0.9,2.1,-5.4,0,0.9,2,1.5
Sennheiser RS 180|4,2,0.2,-1.3,-2.8,-2.5,-1.2,0.1,0.7,2.3,2.3,-0.3,0.6,-4.1,-2.8,-1.3
Sennheiser RS 185|2.4,1.6,0.1,-1.1,-2.8,-3.1,-1.8,-0.5,1,1.1,2.4,-4.3,-3.1,3.2,0.9,-1.5
Sennheiser RS 195|-4.2,-4.1,-3.8,2.8,4.3,4.5,4.1,2.7,2.2,1.1,-1.1,-6.1,-2.3,4.5,1.9,-2.3
Sennheiser RS 220|5.3,3.6,2.1,-0.2,-2,-2.3,-1.5,-1.2,-0.3,-1.1,5.1,-4,-1.5,1.6,-2.2,-3.8
Sennheiser SC 160 USB-C|5.7,1.6,-4,-5.5,-3.9,-0.2,-0.9,1.8,2.4,2.8,1.7,3.6,0.1,0.6,-5.3,-1.7
Sennheiser Urbanite|-2.2,-2.5,-3.3,-3.7,-4.7,-4.3,-2.6,-2.3,-2.9,-3.3,-1.7,5.1,6.3,4.4,6.1,6.1
Sennheiser Urbanite XL|0.9,-0.8,-2.8,-4,-5.4,-3.9,-3.9,-1.8,0.2,1,1.2,2.6,2.4,-0.5,3.6,2.2
SENSO ActivBuds S-250|-0.6,-1.8,-4.2,-6.9,-10.4,-12.1,-10.8,-7.1,-0.8,4.3,7.2,7.2,7.2,7.2,7.2,7.2
SGOR Venus|0.3,0,-0.8,-1.8,-3,-2.1,-0.6,1.1,2.6,2.2,1.8,2.9,3.7,0.1,-1,-2.4
Shanling ME100|4.6,3.9,2,0,-3.2,-5,-5,-3.4,0,0.3,-1.7,-0.7,1.5,-4.1,1.8,2.3
Shanling ME200|-0.3,-0.7,-1.7,-3,-4.3,-3.5,-2,-0.1,1.7,1.9,-1.3,-1.4,0.8,0.4,3.7,5.4
Shanling ME500|2.1,1.4,0,-1.6,-3.7,-3.8,-2.5,-0.9,1,0.9,-1.6,-1.7,-0.3,0.9,2.1,2.4
Shanling ME700|-0.8,-0.9,-1.4,-2.5,-4,-3.3,-2,-0.7,-0.1,-0.7,-1.4,2.4,2.6,5.7,1.3,1.7
Shanling ME700 Lite|1.8,1.7,1,-0.1,-1.5,-1.2,-0.5,-0.3,-1.1,-2.7,-3,-2.3,-0.8,3,0.2,2.1
Shanling ME800 (off-off)|1.3,1,0,-1.5,-3.3,-3.3,-2.1,-0.4,1.5,0.5,-3.2,-2.8,-1.9,1.9,1.7,4.8
Shanling ME800 (off-on)|2.2,2,0.9,-0.6,-2.4,-2.4,-1.2,0.5,2.4,1.5,-2.2,-2.1,-1.7,-0.8,0.2,1.7
Shanling ME800 (on-off)|0.3,0,-0.9,-2.3,-4.3,-4.1,-2.8,-1,1.2,0.6,-2.6,-1.2,-0.1,4.1,3.1,5.1
Shanling ME800 (on-on)|1.1,0.7,-0.1,-1.6,-3.5,-3.4,-2.1,-0.2,1.9,1.4,-1.8,-0.6,0.2,1,1.6,3.2
Shanling MG600|0.4,0,-0.7,-1.8,-3,-2.1,-0.8,0.6,1.6,0.8,-2.1,-6.1,-1.5,0,3.3,4.6
Shanling MTW200|2.1,1.8,1.6,1,0,0.9,0.4,0.4,0.9,-0.6,-3.1,-3.6,-4.1,-6,1.6,4.3
Shanling Sono|-5,-4.2,-3.5,-3.5,-3.8,-1.6,0.5,2.8,4.6,4.5,4.4,2.2,0.7,-0.3,0.9,-0.8
Shokz OpenFit|4.3,4.3,3,0.9,0.6,-0.8,-1.2,-1.5,-3,-3.6,-2.4,-2.8,1.7,-5.8,-2.2,0
Shokz OpenRun|6.6,6.6,6.6,6.6,6.6,2.4,1.1,-5.9,-1.1,-1.2,-1.5,-3.2,-2.7,-3.8,-7.1,-10.1
Shokz OpenRun Pro|4.8,4.8,4.8,4.8,4.4,-3.1,-3.7,-7.3,-6,-5.1,-3.7,-4.9,-1.6,-1.2,2.6,0.5
Shozy & Neo BG|2.3,1.8,0.6,-1.2,-3.3,-3.7,-2.7,-1,0.7,0.2,-2.2,3.1,2.1,4.5,-1.3,-0.4
Shozy & Neo CP|1.4,0.6,-0.6,-2.4,-4.7,-5.4,-4.8,-3.3,-1.3,-0.8,-1.1,2.6,4.9,4.4,1.4,3.1
Shozy B2|0.7,-0.3,-1.9,-3.6,-5.7,-5.6,-4,-1.9,0.9,2.1,1.5,1.8,5.4,-0.8,4.6,4.2
Shozy BG|2,1.1,-0.2,-1.8,-3.9,-4.3,-3.3,-1.6,0.5,0.3,-2.2,3.8,2.4,4.5,0.1,2.1
Shozy BG Blue Limited Edition|2.1,1.3,0,-1.5,-3.7,-4.2,-3.4,-1.8,0.2,-0.3,-3.1,4.6,3.8,5.5,1.3,1.4
Shozy Black Hole|4.2,4.2,4.2,2.4,-0.7,-2.9,-3.5,-4,-3.2,-1.3,-1,1,-1.7,-6.8,1.5,4.2
Shozy Black Hole Mini|1.5,-0.1,-2,-3.9,-6,-5.8,-4.2,-1.6,1.6,3.8,5.2,5.7,2.2,-3.1,0.6,3.9
Shozy Ceres|-0.2,-1.4,-3.2,-4.9,-7,-6.6,-4.8,-2.2,1.2,2.7,2.2,3.8,6.5,6.5,3.4,-0.2
Shozy Cygnus|4.5,4.5,2.5,-1.1,-4.9,-5.2,-5.9,-5.4,-4.6,-4.6,-3.2,4.5,4.5,-1.5,3.7,4.5
Shozy Elsa|0.2,-0.4,-1.3,-2.9,-4.7,-4.6,-3,-0.7,2.5,3.9,2.8,4.2,4.2,4.9,3.4,-1
Shozy Form 1.1|-0.4,-1.3,-2.6,-4,-5.6,-4.9,-3.3,-1.6,0.4,1.1,1.1,4.5,4.9,0.8,-0.1,3
Shozy Magma|0.3,0.1,-0.8,-1.7,-2.4,-1.8,-0.6,0.3,1.2,0.2,1.1,-3.1,3,-1.6,-1,1
Shozy Rouge|2.3,1.8,0.7,-0.9,-3.1,-3.2,-1.2,1.2,2.5,-0.9,-1.8,1.8,0.5,1.6,0.7,0.3
Shozy Stardust (bK)|4.9,2.4,-1,-3.4,-5.9,-5.3,-6.1,-5.4,-4.9,-4.3,-1.3,4.8,4.8,0.7,4.6,4.8
Shozy T1|2.6,1,-1.2,-3.2,-5.5,-5.2,-3.7,-1.4,1.5,2.9,4.5,5.4,1.6,-1,-1.3,0.7
Shozy V33|4.1,2,-0.6,-3,-5.4,-6.6,-5.5,-3.7,-1.9,-1.6,0.2,5.1,5.1,0,3.7,4.9
Shozy x AAW Hibiki|1.7,0.7,-0.6,-2,-4,-4.3,-3.5,-1.9,0.6,1.7,1,-1.8,-1,-0.8,4.4,5.1
Shozy x AAW Hibiki SE|-2.7,-2.4,-2.4,-3.1,-4.2,-3.4,-1.8,0.1,2.1,2.4,0.4,-2,-1.9,5.3,5.9,5.9
Shozy x AAW Pola|0.8,0.2,-0.9,-2.4,-4.6,-4.8,-4.3,-3.5,-2.7,-2.6,-0.7,7.2,7.2,7.2,7.2,0.9
Shozy x AAW POLA39|-0.3,-1.6,-3.2,-4.3,-5.2,-3.5,-1.3,-0.6,-0.4,0.3,2.3,6.2,6,1.9,0,-1.6
Shuoer Aoshida E20 (narrow bore eartips)|-0.6,0.2,1.4,2,2.2,0.5,0.2,0.8,1.6,0.8,0.5,0.1,0.1,-0.5,-0.1,-2
Shuoer Aoshida E20 (wide bore eartips)|-0.8,-0.1,1,1.5,1.8,0.3,-0.2,0.5,1.3,0.5,0.3,-0.1,-0.2,-0.2,0.6,0
Shuoer Cadenza 12|-1.1,-0.1,0.5,0,-0.8,-0.7,-0.4,-0.1,-0.6,-2.3,-2.3,0.7,3.3,3.5,0.1,-0.2
Shuoer Conductor|1.4,1.7,1.6,0.8,-0.4,-0.6,-0.2,0.3,0.7,-0.6,-0.9,0.8,1.4,2,0.9,-4.1
Shuoer D13 (gold nozzle)|-0.8,-0.6,-0.7,-1.5,-2.4,-1.5,-0.4,0.6,1.2,0.1,-2.7,-2.5,2.8,1,1.5,3.6
Shuoer D13 (silver nozzle)|-1.1,-0.9,-1,-1.7,-2.6,-1.8,-0.6,0.4,1.1,-0.1,-2.5,-1,3.2,1.7,1.6,3.5
Shuoer DZ4|-0.8,0.2,0.7,0.2,-0.9,-1.2,-1,-0.2,0.8,0,-0.4,0.5,1.1,1.5,3.7,0.7
Shuoer EJ07|0.1,1.7,2.7,2.1,0.1,-1.7,-2.1,-1.9,-1.6,-2.5,-4,-2.5,-0.4,2.7,5.3,4.9
Shuoer EJ07M|-1.7,-0.8,0.1,-0.2,-1,-1.3,-1.3,-0.9,-0.3,-0.8,-2.2,-1,2.1,2.4,6,4
Shuoer EJ07M Kinda Lava Edition|-0.1,0.4,0.6,0,-1.3,-1.4,-1,-0.3,0.6,0.1,-1.3,-0.5,1.5,1.8,4,0.5
Shuoer EJ09|1.1,0.9,0.2,-1,-2.4,-2.4,-1.7,-0.5,0.8,0.5,0.2,0.7,2,1,-1.8,-1.4
Shuoer Galileo|-0.6,0,0.2,-0.5,-1.7,-1.7,-1,-0.3,0.6,0,0.1,1.5,0.3,0.7,-1.6,-0.2
Shuoer H27|-0.4,0.5,1.5,2.4,0.6,2.4,4,3.5,2.7,0.1,-3.9,-1.4,-2.9,-4.2,-2.2,0.7
Shuoer S12|0.2,0.3,0.1,-0.8,-1.8,-0.6,0.4,1.7,2.5,1.3,-1,-1.1,2.7,1.5,-0.5,-1.2
Shuoer S12 Pro|-1.7,-1.2,-0.9,-1.4,-1.8,-0.6,0.7,1.9,2.6,1.3,0.1,2.3,5.2,2.4,0.3,-0.4
Shuoer S15|0,-0.2,-0.8,-1.7,-2.7,-1.8,-0.5,0.8,1.7,0.4,-1.4,3.4,6.4,3.5,1.4,-1.1
Shuoer Singer|-3.7,-3.1,-2.8,-3.1,-3.1,-1.2,0.9,3.1,5.1,5,4.3,-0.1,-2.4,0.9,5.8,0.5
Shuoer Soloist|-1.6,-1.2,-1.2,-1.9,-2.5,-1.3,0.3,1.8,3.5,2.8,1.3,-0.2,-2.2,-0.4,1.7,-1
Shuoer Tape|-1.2,-1.2,-1.3,-1.9,-2.7,-1.3,0.1,1.6,3,2.8,1.2,-1.3,-4.5,3.3,4.2,-0.5
Shuoer Tape Pro|0.7,-0.2,-1.3,-2.5,-3.8,-2.9,-1.3,0.3,1.7,0.9,-1.8,-4.8,-3.5,2.2,5.9,5
Shure Aonic 3|2.8,1.9,0.5,-1.2,-3.5,-4.1,-3.9,-2.8,-1.4,-1.5,-1.6,1.4,0.7,2.7,-1.5,3.9
Shure Aonic 4|-0.2,-0.4,-1.2,-2.4,-3.9,-3.5,-2.5,-1.4,-0.5,-1.1,-1.8,0.4,0.8,5.1,3.1,4.3
Shure Aonic 5 (bright filter)|3,2.4,1.1,-0.8,-3.4,-4.8,-4.5,-3.8,-2.3,-1.9,-2.5,-0.6,0.7,4.2,2.5,4.2
Shure Aonic 5 (default filter)|2.3,1.7,0.4,-1.5,-4.1,-5.3,-5,-3.9,-2.1,-1.3,-1.4,0.3,1.8,4.3,3.1,4.5
Shure Aonic 5 (warm filter)|1.5,1.1,-0.3,-2.1,-4.6,-5.7,-5.2,-4,-1.9,-0.8,-0.3,1.4,4,4.7,1.9,4.7
Shure AONIC 50|3.8,2.1,1.2,-1.8,-4.3,-0.8,2,3.5,2.6,1,-0.9,-1.2,0.2,-4.5,-1.5,-0.6
Shure AONIC 50 Gen 2|3.4,1.3,2.3,0.3,-1.7,1.4,3.4,4,2.2,-0.6,-3.3,0.6,2.2,-3.6,-6.3,-4.9
Shure KSE1200|1.3,1,0.2,-1,-2.4,-2,-0.9,0.3,0.9,-0.8,-4.4,3,6.2,6.2,-1,-1.5
Shure KSE1500|0.8,0.7,0,-1.1,-2.1,-1.4,-0.2,0.8,1.3,-0.6,-2.8,1.1,6.3,6.3,-3.1,-4.2
Shure SE110|4.4,3.8,2.2,0.2,-2.7,-4.3,-4.4,-4.1,-4.1,-4.8,-6.2,-1.9,1.3,4.3,4.4,4.4
Shure SE112|-0.7,-1,-2.1,-3.6,-5.4,-5.1,-3.6,-1.5,0.6,0.6,-0.1,1.1,3,0,5.9,5.9
Shure SE210|3.8,3.8,3.5,1.3,-1.7,-3.3,-3.6,-3.5,-3.2,-3.6,-3.8,-0.6,0,0.9,1,3.8
Shure SE215|-0.4,-0.7,-1.6,-3,-4.6,-3.9,-2.2,-0.2,1.8,1.5,0.2,-0.5,3.6,-2.8,2.8,3.7
Shure SE215 Ltd|-0.2,-0.6,-1.7,-3.2,-5.2,-5,-3.6,-1.5,0.8,1.1,-0.2,0.7,4.5,-1.8,3.2,5.5
Shure SE310|4,3.5,1.9,0.1,-2.6,-4,-4.1,-3.5,-3.5,-4.9,-5.6,-1.9,2.6,2.9,4.2,4.2
Shure SE315|3,2.5,1.2,-0.8,-3.4,-4.5,-4.2,-3.5,-2.9,-4.4,-7.4,1.5,4.7,4.1,4.8,4.8
Shure SE420|4.2,3.2,1.4,-0.7,-3.5,-4.9,-4.8,-4.1,-3.2,-3.2,-2.3,-2.2,-1.1,4.3,4.4,4.4
Shure SE425|4.1,4,2,-0.3,-3.3,-4.7,-4.6,-3.9,-2.3,-2,-1.8,-1.3,-3.4,3.2,4.1,4.1
Shure SE530|2.3,1.9,0.3,-1.5,-3.9,-5.3,-4.8,-4.2,-2.4,-1.9,-1,1.1,2,4.9,3.4,2.1
Shure SE535|2.9,2.4,1.1,-0.8,-3.3,-4.6,-4.4,-4,-3.3,-3.5,-2.2,0,2,4.5,2.7,3.3
Shure SE846|-0.1,-0.2,-0.6,-1.5,-2.4,-2.1,-1.6,-1.4,-0.9,-1.5,-1.6,0.6,1.4,2,1.4,4.3
Shure SE846 (black)|-1.5,-1.5,-2,-2.9,-4,-3.8,-3.3,-2.8,-1.6,-1.3,-1.2,2.4,3.2,5.3,5.5,5.5
Shure SE846 (blue)|-1.3,-1.2,-1.6,-2.6,-3.8,-3.7,-3.4,-3,-1.9,-1.7,-1.5,2.4,3.7,5.5,4.9,5.5
Shure SE846 (white)|-0.4,-0.4,-0.8,-1.7,-3,-2.8,-2.6,-2.4,-1.5,-1.5,-1.7,1.6,2.3,2.9,2.9,5.1
Shure SRH1440|4.8,4.8,4.8,4.4,1.3,-0.2,0.5,0.9,1.3,-0.6,-2,-4.8,-8.7,-2.8,-2.9,-2.8
Shure SRH1540|-0.1,-0.9,-3,-4.2,-4.2,-1.5,-0.4,1,1.8,1.5,1.3,-0.2,0.7,3.2,2.2,1.8
Shure SRH1840|5.2,5.2,4.2,1.6,-1.3,-1.5,-0.1,0.4,0.1,-0.5,-0.5,-4,-3.4,3.1,-0.6,-2.2
Shure SRH240|5,5,2.3,0.6,-0.3,1.3,-0.4,-3.6,-3.5,-2.9,-3.1,-1.1,0,2.9,-2,-3.6
Shure SRH240A|2.8,1,-0.1,-2,-3.6,-0.4,-0.6,-1.4,-2,-2.3,-2.5,1.1,3.7,2.1,-0.3,0.2
Shure SRH440|2.5,1,0,-1.3,-1.8,0.3,-1.1,-0.5,-0.2,-0.2,-1.1,0.7,1.6,-0.9,-2.8,-2.1
Shure SRH440A|-1.2,-2.1,-2.6,-3.4,-4.1,-0.4,2,0.3,1.6,2.2,3.6,2,4,1.6,1,-0.6
Shure SRH750DJ|4.6,4.6,1.9,1.3,-1.2,-3.5,-2.2,-2.9,-1.2,-1.2,-1.4,-5.5,-0.9,2.7,0.2,-0.5
Shure SRH840|4,4,-0.2,-3.7,-3.3,0.3,0.4,1.3,1,0.3,-0.5,-0.7,-0.4,-1.9,-0.9,-0.6
Shure SRH840A|-1.4,-2,-2.1,-3.1,-3,0.7,0.5,1.2,3,2.7,3.2,2.1,2.5,0,0.8,-0.6
Shure SRH940|5.1,5.1,3.8,4.9,1.2,-3.3,-3.3,-1.4,0.9,0.5,0.3,-0.3,-3.8,-0.9,-2.8,-4.8""",
"""Signature Acoustics Elements C12|-1.3,-2.5,-4.3,-6,-7.8,-7.2,-5.1,-2.4,2.7,7,7,7,7,2.2,2.1,4
Simgot Audio EA1000 (black nozzle)|0.7,0.8,0.7,-0.1,-0.9,-0.3,0.7,1.7,2.1,1.1,-0.3,-1.9,1.3,0.1,0.5,-1.7
Simgot Audio EA1000 (blue nozzle)|4.9,4.5,2.8,1.1,-0.3,-1.1,-0.4,0.6,1,-0.4,-1.7,-4.2,-1.4,-4,-1.6,-2.2
Simgot Audio EA1000 (gold nozzle)|0.9,1,0.8,-0.1,-1,-0.6,0.4,1.3,1.5,0.2,-1.9,-2.5,1.8,0.9,1.9,-0.9
Simgot Audio EA1000 (red nozzle)|0.3,0.5,0.4,-0.4,-1.2,-0.6,0.6,1.6,2.1,1.1,0,-0.6,1.2,0.8,1.2,-2.1
Simgot Audio EA500|1.5,0.9,-0.3,-1.7,-3.3,-2.8,-1.5,0.1,1.5,0.9,0.1,0.1,2.1,0.5,1.5,-0.9
Simgot Audio EA500 (black nozzle)|2.9,2.4,1.4,-0.1,-1.8,-1.5,-0.4,0.9,2,1.3,0,-1.1,1.4,-1.1,-0.8,-3.1
Simgot Audio EA500 (Moondrop Kato brass nozzle)|2.2,1.6,0.8,-0.6,-2.4,-1.9,-0.7,0.7,1.7,1.2,0.5,0.9,1.8,1.5,0.1,-2.4
Simgot Audio EA500 (Moondrop Kato steel nozzle)|2.2,1.7,0.9,-0.4,-2.4,-1.9,-0.7,0.6,1.7,1.2,0.4,1,1.7,1.8,0.2,-3
Simgot Audio EA500 (red nozzle)|2.8,2.2,1.1,-0.3,-2,-1.6,-0.5,0.8,1.9,1.4,0.3,-0.3,1.4,-0.3,-0.1,-3.4
Simgot Audio EK3 (off-off)|3.5,2.5,1.1,-0.7,-3.2,-4.2,-3.8,-2.6,-1.2,-2.1,-5.4,1.7,3.5,4.7,-1.1,2.2
Simgot Audio EK3 (off-on)|3.4,2.5,1.1,-0.8,-3.2,-4.3,-3.8,-2.7,-1.2,-1.8,-5.1,1.6,3.7,4.7,-0.9,2.1
Simgot Audio EK3 (on-off)|2.2,1.3,-0.1,-1.9,-4.1,-4.7,-4.1,-2.6,-0.7,-1.2,-4.8,2.7,4.3,5,0.6,3.6
Simgot Audio EK3 (on-on)|2.1,1.2,-0.2,-2,-4.2,-4.8,-4.2,-2.7,-0.7,-0.9,-4.4,2.7,4.5,5.1,0.6,3.6
Simgot Audio EM2|1.3,0.5,-1,-2.8,-5.1,-5.4,-4.5,-2.6,-0.1,0.7,0.4,1.7,3.5,1.9,1.5,4.8
Simgot Audio EM5|0.5,-0.3,-1.7,-3.5,-5.5,-5.7,-4.4,-2.4,0.3,1.2,-0.1,1.8,3.9,2.6,3.9,4.7
Simgot Audio EM5H|2.8,1.8,0.1,-2,-4.7,-5.9,-5.6,-4.9,-3.5,-2,-1.1,-0.4,5,1.5,5.1,5.1
Simgot Audio EM6L|-1.2,-0.6,-0.5,-1,-2,-1.4,-0.4,0.8,2,1.9,2,2.1,0.3,2,0.4,-2.3
Simgot Audio EN1000 (black plug)|1.7,1.3,0.5,-0.9,-2.3,-2,-1.1,-0.1,0.4,-1.1,-2.4,-1.1,1.1,-3.2,0.6,2
Simgot Audio EN1000 (blue plug)|1.6,1.3,0.3,-0.7,-2.4,-2.2,-1.2,-0.3,0.3,-1.3,-2.6,-1.3,1.1,-3,0.6,2.5
Simgot Audio EN1000 (red plug)|1.1,0.6,-0.2,-1.3,-2.9,-2.7,-1.7,-0.6,-0.1,-1.7,-2.9,-1.1,1.4,-2.3,2.6,4.9
Simgot Audio EN700|4.2,3.8,1.8,-0.1,-2.9,-4,-3.2,-1.7,0,0.1,-1.4,-4.6,-2.2,-6.1,2.2,4.2
Simgot Audio EN700 Pro|0.8,0.7,-0.2,-1.3,-2.7,-2,-0.7,0.7,1.9,1,0.4,-0.9,-0.1,-2.4,-0.1,1.8
Simgot Audio EW100|-1.7,-1.4,-1.4,-1.9,-1.7,0.5,1.7,1.7,1.5,0.4,0.4,1.1,0.6,0.8,4.3,0.8
Simgot Audio EW100P|0.8,0.7,0.2,-0.6,-1.6,-1.2,-0.4,0.6,1.3,0.5,0,0.4,-0.4,-0.4,2.9,-1
Simgot Audio EW200|-1.2,-0.8,-0.6,-1.2,-2,-1.1,0.1,1.2,1.8,0.9,0,0.4,0.8,-0.1,-0.1,-0.1
Simphonio RX10|0.6,0.8,0.6,-0.2,-1.6,-1.5,-0.7,0.2,1.2,0.3,-1.1,-1,4.4,-0.2,2.4,0.3
Simphonio VR1|-0.1,0.1,-0.1,-1.1,-2.9,-3.3,-2.8,-1.8,-0.6,-0.6,-2.3,-2.8,1.2,3.7,4.8,5.2
Singapore Airlines complimentary earphones|0.4,-0.2,-1.7,-3.9,-7.4,-11,-11.3,-2,5,-5,-4.9,7.2,7.2,3.4,7.2,7.2
Single Driver|3.9,2.2,0.2,-1.8,-4.1,-4.3,-3,-1.1,1,1.4,1.8,0.6,-3.5,-6.4,1.4,3.4
Sivga Audio Phoenix|1.2,0.4,-0.4,-1.3,-2.8,-3.8,-3.5,-1.9,-0.3,1,1.8,1.1,3.4,-0.6,1.2,1.7
Sivga Audio Robin|-2,-1.6,-2.9,-3.9,-6.3,-1.7,4.1,7.5,3.9,0,2.1,3.1,4.8,1.4,1.1,-1
Sivga Luan|3.7,2.1,0.5,-1,-2.8,-2.4,-0.5,0.6,0.8,0.9,1.4,-3.3,-0.2,2.8,2.4,-2
Sivga Nightingale|5.7,4.8,2.2,-0.2,-3.3,-5,-5,-3.9,-1.4,-1.3,-1.5,5.4,5.7,5.7,-1.1,-6.6
Sivga Oriole|-0.9,-1.3,0.3,0.4,0.8,6.1,3.3,1.5,-0.1,-1.6,-0.3,-1.2,0.6,2.5,0.6,-4.5
Skullcandy Aviators|5.5,3.3,-0.6,-2.2,-3.6,-4,-2.4,-1.4,1.7,5.4,1.5,2.4,-3.3,-4.6,0.4,-2.2
Skullcandy Aviators no Lens|5.5,2.4,-1.1,-2.4,-3.7,-4,-2.6,-2,-0.4,1.1,4.7,3.3,-2.8,-2.4,1,-1.7
Skullcandy Crusher|1.1,-0.1,-0.4,-0.3,-1.3,-0.5,-0.9,-4.7,-5.3,-4.8,-0.1,0.6,1.2,4.9,4.6,1.1
Skullcandy Crusher 360|0.5,0.4,-0.4,-1.6,-2.4,0,3.1,4.9,5.1,3.7,3.6,0.6,-2,1.1,-6.5,-7
Skullcandy Crusher Evo|0.5,-0.9,0,-1.4,-2.3,-1,1,3.5,2,2.6,4.6,5.1,2.2,1.9,-6.2,-6.9
Skullcandy Crusher Wireless|-1.8,-1.1,-0.3,-5.7,-6.3,0.8,6.2,7.9,7.8,6.8,6,0.4,-1.3,2.1,-1.1,-5.5
Skullcandy Crusher Wireless (bass boost)|-16.8,-25.4,-17,-14.2,-8.7,7.1,12.8,12.8,12.4,11.4,10.8,4.7,2.9,6.9,3.6,-0.4
Skullcandy Crusher Wireless (wired, sub off)|-0.9,-1.4,0.4,-3.6,-5.8,-1.7,2.9,5.3,3.9,3.9,4.8,1.8,-2.5,1.7,-3.2,-4
Skullcandy Dime|-1.2,-0.4,0.1,-0.5,-1.3,-1,0.6,2.5,4.7,1.6,1.4,3,2.3,1.3,-2.5,-3.5
Skullcandy Dime 2|0.1,0.4,0.5,-0.1,-1,-1.5,-0.4,1.4,3.5,1.4,1.3,3.1,1.9,0.8,-2.8,-3.5
Skullcandy Grind|1,0.2,-0.6,-0.9,-1.5,-1.8,-0.5,2,5.2,1,0,-5,0.5,3.1,-4.2,0.2
Skullcandy Grind Fuel|1.9,1.1,-0.6,-2.1,-3.6,-3.1,-1,1.5,4.3,2,0.8,-0.1,3,3,-2.3,-2
Skullcandy Grind Wireless|1.4,0.3,-0.6,-1,-1.4,-1.6,-0.3,1.4,4.3,-0.4,-0.6,-5,0.9,1.1,-2,1.4
Skullcandy Hesh|4.8,4.8,2,-2.5,-4,-4.5,-3.9,-4.7,-4.3,-6.7,-6.5,4.8,3.9,4.8,1.6,2.7
Skullcandy Hesh 2|0.9,-0.2,-2.3,-4.1,-5.3,-1.9,2.6,2.3,-4.4,-5.3,-1,2.1,5.6,4,5,3.7
Skullcandy Hesh 3|-2.6,-0.3,0,-2.6,-2.5,2.4,5.6,6.2,4.1,3.4,5.6,1.5,1.2,-0.1,-5.8,-4.2
Skullcandy Hesh ANC|-3.8,-2.5,-1.6,-1.6,-2,1.2,4.3,5.6,4.7,3.6,8,7.4,-1.3,-1.6,-3.6,-4.1
Skullcandy Hesh Evo|-0.5,0.1,-1.2,-3.8,-4.4,0.9,5.1,6.3,4.5,4.8,6.7,2.8,-0.9,-1.6,-6.2,-5.6
Skullcandy Hesh NBA|4.8,4.8,2,-2.5,-4,-4.5,-3.9,-4.7,-4.3,-6.7,-6.5,4.8,3.9,4.8,1.6,2.7
Skullcandy Holua|-4.3,-3.6,-3.2,-3.5,-3.6,-2,-0.1,1.3,2.9,2,0.9,-3.2,2.3,-4.6,6.8,6.8
Skullcandy Indy|-2.6,-2.2,-2,-2.7,-3.7,-2,-0.1,1.9,3.7,3.1,4.7,8.2,3.4,7.5,0.9,-6.7
Skullcandy Indy ANC|-4.8,-4.5,-3.6,-3.4,-3.2,-1.9,-0.4,1.4,3.8,0.9,1.1,4.4,4.8,6,3.5,-0.3
Skullcandy Indy Evo|-2.9,-2.5,-2.1,-2.4,-3,-3,-2.4,-1.2,1,-0.8,0.7,4.9,6.4,5.5,2.4,1.3
Skullcandy Indy Fuel|-2.8,-2.3,-1.9,-2.1,-2.8,-2.9,-2.1,-0.7,1.9,0.3,1.2,4,5.5,4.3,2.1,1.5
Skullcandy Inkd 2.0|1.3,1.5,1.3,0.4,-0.5,0.2,1.3,2.4,3.1,2.2,1.4,1.4,-4.7,-4.4,-2.3,0.5
Skullcandy Jib|-3.8,-3.8,-4,-4.6,-5,-3,-0.9,1.6,4.4,3.1,3.4,3.1,3.5,4.7,2.1,3.8
Skullcandy Jib True|-5.4,-4.6,-3.3,-3.4,-3.4,-2.4,-0.2,2.3,4.7,2.4,3,2.9,4.7,5.5,3.6,1.8
Skullcandy Mix Master|1.5,0.3,-0.6,-2,-3.7,-4.7,-3,-2,-0.6,-2.3,-3.3,1.1,-2.6,3.2,4.5,4.6
Skullcandy Navigator|-1.5,-1.8,-2.8,-3.6,-4.9,-5.7,-3.7,-1.8,0.3,-1.1,0.7,3.2,1.6,5.9,6.1,6.1
Skullcandy Push|-5.6,-5.1,-5.2,-5.8,-5.2,-1.9,0.6,3.3,5.5,4.7,4.5,4.4,5.3,1.9,0.3,2.8
Skullcandy Push Active|-1.3,-0.9,-0.9,-1.6,-2.4,-1.6,0,1.9,3.7,1.7,1.2,0.3,2.2,2.1,-1,1.5
Skullcandy Push Ultra|4.8,4.8,4.8,4.8,3.7,-1.9,-4.3,-4.2,-1.5,-0.4,-3.6,-0.6,1.2,-8.2,-1.7,2.7
Skullcandy Riff Wireless 2|0,-2.1,-3.8,-4.1,-3.6,0.6,5.4,6.8,3.6,1.8,2.3,2.2,4,0.4,-8.6,-5.8
Skullcandy Roc Nation Aviator|5.3,3.6,0.1,-1.6,-3,-3.3,-1.7,-0.9,2.3,2.7,0.5,1.7,-3.1,-3.5,-0.1,-2
Skullcandy Sesh|-5.7,-5,-4.2,-4.1,-3.7,-1.1,0.4,2.3,4.6,3.6,4.3,4.5,4.7,8.1,1.7,-1.8
Skullcandy Sesh ANC|-0.9,-0.5,0.2,-0.2,-0.6,0.1,1,1.8,2.8,0.8,0.2,1.3,5,2.6,-6.4,-2.3
Skullcandy Sesh Evo|-5.3,-4.5,-3.7,-3.7,-3.9,-3.4,-2.4,-0.7,2,0.9,2.4,4.1,5.5,3.8,6.1,6.9
Skullcandy SkullCrushers Max Bass|6.1,1.6,-11,-9.6,5.1,1.6,0.3,-1.6,-1.9,-2.7,-1.4,1.9,-3.5,2.8,2.2,-2.4
Skullcandy Smokin Buds|0.2,0.8,1.1,0.6,-0.2,0,1.6,3.4,4.3,1.8,2.1,2.5,1,-0.6,-3.4,-4.2
Skullcandy Smokin Buds 2|-3.9,-3.6,-3.5,-4,-4.3,-2.4,-0.2,2,4.1,4.1,4.2,1.1,3.4,-0.6,2,5
Skullcandy Smokin Buds- Mic'd|4.6,3.5,0.8,-1.5,-4.2,-4.6,-3.6,-2.6,-1.6,2,-0.7,0.5,3.2,-1.1,0.6,3.6
Skullcandy Spoke|-3.9,-2.7,-1.8,-1.8,-1.7,-0.3,1.8,4.6,7.6,5.4,6.4,6.7,-0.4,2.8,-5.8,-6.3
Skullcandy Venue|-2.1,-0.7,0.2,0.2,-0.7,-0.4,2.6,6,6.7,5.1,0.2,1.9,1.5,1.6,-3.9,-5.6
Sleek SA1|-1.1,-1.4,-2.7,-4.3,-6.5,-6.4,-5,-3.1,-0.3,1.3,4.1,6.1,5.9,0.2,3.8,6.1
Sleek SA6|2.6,2,0.2,-1.6,-4.3,-5.4,-5.1,-4.7,-3.4,-3,-1.7,1,5.1,5.4,4.1,1.5
Smabat M0|5.5,4.5,2.1,-0.4,-4.1,-7.3,-8.8,-8.6,-1.4,3.4,1.2,-0.7,5,2.6,5.5,0.3
Smabat M0 + Super Point|3.1,1.7,-0.6,-2.9,-6.2,-9,-10.5,-11.4,-2.3,5.8,-2.5,4.1,5.8,5.8,5.8,5.8
Smabat M2 Pro|4.7,2.4,-0.3,-2.3,-5,-7.6,-9.3,-9.6,-3.1,3.2,5.7,3.4,2.6,1.8,5.7,4.4
Smabat M2s (gold driver)|2.9,1.7,-0.3,-2.5,-5.8,-8.7,-10.4,-11.2,-4.6,3.5,4.8,3.4,5.9,5.8,5.9,5.9
Smabat M2s Pro|2.4,1.1,-0.7,-2.7,-5.9,-8.6,-9.5,-10.5,-5.3,3.4,6.3,3.2,6.3,6.2,6.3,3.2
Smabat M4 (balanced armature driver)|4.2,2.4,0,-2.3,-5.2,-6.4,-6,-4.9,-3.1,-2.3,-0.8,0.3,1.3,4.7,4.7,4.7
Smabat ST-10S|-3.3,-3.9,-5.1,-6.6,-8.6,-8.6,-7.3,-5.1,-1.2,2.9,2.2,8.4,8.4,8.4,8.4,8.4
Smabat X1 (H switch down)|4.5,4.5,4.5,3.9,-0.6,-3.1,-3.3,-2.7,-1.4,-1.3,-1,-3.2,-3.9,-2.5,0.6,-1.2
Smabat X1 (H switch up)|-2.5,-3.4,-4.6,-6,-7.1,-5.8,-3.6,-0.9,2.1,3.3,4.2,2.5,1.4,2.2,6.3,5.3
SMS Audio DJ Pro|-3,-2.3,-1.1,-4.4,-1.4,-2.5,-3.1,-4,-0.2,-0.3,0.3,3.2,1.4,1.9,6.2,6.2
SMS Audio Over Ear ANC|-3.2,-1.8,-0.5,0.1,0.6,1.6,3.2,4.4,3.7,-2.5,-10.9,-3.5,-2,1.6,4.1,2.4
SMS Audio Street by 50 Cent|1.6,-1.4,-4.4,-5.6,-6.3,-6.5,-3.5,-1.9,6.2,3.1,4.4,-1,-0.2,4.7,2.6,0.7
SMS Audio Sync by 50 Cent|-0.7,-2.8,-4.5,-5.4,-6.5,-5.8,-5.3,-4.3,5.2,2.2,2.9,3.1,1.3,6.2,5.9,4.2
SoftEars Cerberus|1.6,2.5,2.2,1,-1.3,-2.5,-2.6,-2.6,-2.2,-1.4,-3.2,-1.9,0.3,3.6,5.3,4.3
SoftEars RS10|0.2,0.2,0.3,-0.2,-0.4,-0.1,0,0.2,0.7,0.1,-0.8,-1.3,-1.4,1.6,2.4,2.1
SoftEars RSV|-0.1,-0.2,-0.1,-0.5,-0.8,-1.2,-1.3,-0.9,0.2,0.1,-0.4,-0.3,1.1,1.2,4.6,3.6
SoftEars Studio 4|0.4,0.8,1.1,0.6,-0.6,-1.2,-1.4,-0.7,-0.3,-0.4,-0.1,0.8,2.1,1.5,1.8,-2.2
SoftEars Turii|2.9,2.4,1.1,-0.5,-2.8,-3.3,-2.4,-1,0.8,1.1,-0.2,-1.4,1.2,-0.3,1.4,1.8
SoftEars Twilight|1.9,1.3,0.2,-1.4,-3.6,-3.7,-2.7,-1,0.9,1.2,0.3,-0.4,-0.2,0.2,2.2,1.7
SoftEars Volume|1.5,1.2,0.7,-0.6,-2.1,-2.4,-1.8,-0.9,0.3,0.3,-0.5,-0.3,-2.1,0.7,1.2,2.1
SOL REPUBLIC Master Tracks XC|-1.1,-2.2,-3.1,-3.8,-5.9,-6.2,-3.8,-3.4,-2,-1.6,2,6.1,3.8,1,6.1,6.1
SOL REPUBLIC Tracks|-3.3,-4.6,-5.3,-5,-6.3,-4.6,2.8,4.9,-1.3,-1.6,3.2,4.3,3.9,2,6,6.7
SOL REPUBLIC Tracks B|-3.5,-4.8,-5.4,-5.1,-6.4,-4.5,3.4,5.2,-1,-0.9,4.2,4.6,3.2,2.6,4.5,5.7
Somic V2|5.1,5.1,1.8,-0.4,-2.3,-2.6,-1.8,-2.4,-3.2,-4.1,-2.6,-2.4,2.9,4.6,0.1,-0.3
Sonic Sonic|5.3,5.3,5.3,5.3,3.7,-7.6,-15.7,-20,-17.2,-3.6,5.3,5.1,5.3,5.3,5.3,5.3
Sony DR-Z7|5,5,4.7,3.9,0.4,-1.5,-0.9,-1.5,-2.7,-5,-7.1,-6,-4.2,-1,5,4.8
Sony Float Run|5.6,5.6,5.6,5.6,3.8,-5.8,-3.9,-1.9,-0.8,0.7,1.7,0.2,-0.2,-2.6,-1.8,-6.1
Sony h.ear on|-1.4,-2.2,-2.7,-3.8,-4.4,-2.3,0.1,1.4,1,0.7,1.2,2.6,3.3,-0.7,0,4.3
Sony IER-M7|0.3,0.1,-0.7,-1.9,-3.5,-3.3,-2.3,-1.2,0,-0.1,-0.1,2,1.5,3.2,3.5,-0.3
Sony IER-M7 Q|0.4,0.1,-0.5,-1.9,-3.4,-3.3,-2.4,-1.3,0.1,0.1,-0.4,2.1,2.1,4.5,4,0.5
Sony IER-M9|0.6,0.4,-0.4,-1.5,-2.7,-2.3,-1.4,-0.1,1.2,0.9,1.3,4.4,0.9,3.2,1.3,-3.8
Sony IER-M9 (no filter)|1.4,1.1,0.3,-0.7,-2.3,-2,-0.9,0.3,1.9,1.6,1,0.6,-5.9,0,1.9,-1.1
Sony IER-NW510N|-5.3,-5.5,-5.8,-6.5,-6.9,-4.9,-2.8,-0.1,2.7,3.5,4,4.6,6.3,3,5.6,6.7
Sony IER-Z1R|0.3,0,-0.7,-1.7,-2.6,-1.4,-0.3,0.4,0.8,0,-0.7,5.3,-0.2,2.5,0.7,0
Sony IER-Z1R (AZLA Sedna eartips)|-0.2,-0.5,-1.2,-2.1,-2.9,-1.4,-0.2,0.7,1.2,0.5,0.7,6.4,1.4,1.5,-1,-2.4
Sony IER-Z1R (no filter)|1.7,1.3,0.6,-0.3,-1.2,0.1,1.1,1.7,1.8,0.5,-1.6,3.4,-2.1,-4.7,-1.7,0.5
Sony IER-Z1R (Symbio eartips)|0.2,-0.2,-0.9,-1.9,-2.7,-1.5,-0.2,0.6,1,0.4,0.4,6.2,0.9,1.5,-1.6,-3.2
Sony INZONE H3|3.6,3.6,1.3,-0.7,-2.6,-2.1,0.3,1.9,1.8,0.7,1.9,-1.2,4.5,4.6,-6,-8.2
Sony INZONE H7|5.6,3.8,1.2,-1.4,-3.6,-3.1,-0.4,2,3.1,3.2,0.7,0.3,3.1,1,-5.4,-7.9
Sony INZONE H9|3.5,1.3,-1.6,-3.2,-4.3,-3,-0.8,1.3,5,4.8,0.7,-0.8,3.6,2.1,-3.8,-4.8
Sony LinkBuds|4.6,4.6,4.6,4.6,3.7,0.2,-2.2,-5.7,-6.2,-5.8,-7.4,-4.4,3.2,-1.6,3.3,1.5
Sony LinkBuds S|1.4,1.1,0.5,-0.5,-1.8,-1.6,-0.8,-0.9,-1.2,-1.9,-1.4,0.4,2.2,1.8,3.2,1.7
Sony LinkBuds S (ANC Off)|0.3,0,-0.1,-2.1,-2.9,-2.7,-2.1,-1.4,-0.8,-1.1,-0.4,0.5,2.2,2.1,3.3,1.9
Sony LinkBuds S (ANC on)|0.9,0.5,0.1,-0.9,-2.2,-1.9,-1.3,-0.9,-0.8,-1.1,-0.3,0.5,2,1.4,3.2,2
Sony MDR-1000X|0.3,0.1,-1,-2.2,-3.3,-2.4,-0.2,-0.1,0,5.1,0,1.2,1.8,-0.3,-0.4,2.3
Sony MDR-1000X (wired, ANC off)|-1.9,-4.3,-5.9,-5.1,-4.9,-0.9,0.8,-0.2,0.1,4,6.2,6.3,-4.4,-3.1,0.1,4.3
Sony MDR-1000X (wired, ANC on)|0.9,0.1,-0.8,-1.5,-2.5,-2,0.2,0.8,0.9,4.7,2.8,2.4,-1.6,-2.4,-2.7,2.1
Sony MDR-1000X (wireless, ANC off)|-0.9,-1.8,-1.3,-2.8,-3.1,-0.2,0.9,0.3,1,2.8,4.7,1.9,-2,-2.4,-2.5,2.8
Sony MDR-1000X (wireless, ANC on)|1,0.9,-0.3,-1.5,-2.9,-1.6,0.4,-0.1,0.7,3.7,2.4,1.8,-2.3,-2.8,-2.8,1.9
Sony MDR-1A|-0.6,-0.7,-1.3,-2.2,-2,-0.3,3,2.8,1.1,0,0,0.8,3.4,3.1,-3.1,-1.9
Sony MDR-1AM2|-1,-2.5,-3.2,-3.1,-4.2,-1.1,2.2,2.1,1.7,1.8,2.2,3.2,3.6,1.2,-1.8,-0.2
Sony MDR-1R|3,0.3,-2.5,-3.9,-4.4,-4.8,-1.3,-0.3,-1.8,-4.5,-5.4,-0.5,3.2,5,4.6,5
Sony MDR-1RBT|-1.5,-4.2,-6.7,-7.2,-7,4,5.7,1.2,2,0.8,-3.9,-3,-2.4,5.2,2.7,4.8
Sony MDR-3|4.7,4.7,4.7,4.7,1,-4.4,-3.3,-1.7,-0.3,-1.2,-2,-2.6,-2.8,-9.9,3.1,1.1
Sony MDR-7502|4.1,4.1,4.1,3,0.5,-2.1,-1.7,-3.5,-3.5,-5.4,-5.9,-4.7,-3.9,3.2,0.9,2.2
Sony MDR-7505|4.4,4.4,4.2,1.4,-1,-2.4,-2.5,-4.1,-3.8,-4.4,-0.9,-0.1,-4.5,-0.7,3.8,4.3
Sony MDR-7506|0.9,-0.5,0,-0.3,-0.6,1.9,0.3,0.4,0.7,0.9,-0.1,-1.7,-1.1,-4.2,-3.2,1.5
Sony MDR-7509HD|4.7,4.7,3.4,1.1,-2.5,-2.5,-1.8,-4.6,-1.8,-1.3,-4.2,-3.8,0.5,1.6,-0.7,-0.2
Sony MDR-7510|3.3,1.4,0.9,-4.3,-5.2,-2,-2.1,0.1,1.4,0.7,-2.1,-3.4,0.5,1.6,-0.9,2.3
Sony MDR-7520|2,0.4,-1.8,-3.1,-3.3,1.5,4.4,1.9,2.1,2,0.1,-3.4,0.8,3,-4.6,-4.1
Sony MDR-7550|3.9,3,1.4,-0.6,-3.4,-4.7,-4.4,-3.5,-1.9,-1.7,-3.4,1.6,4.4,-2.3,-1.1,4.4
Sony MDR-AS40EX|1.6,0.8,-0.5,-2.2,-4,-3.7,-2.4,-0.6,1.5,1.8,2.6,5.8,2,-1.5,1.2,1
Sony MDR-AS50G|1.8,0.8,-0.6,-2.2,-3.9,-3.4,-2.1,-0.6,0.7,1.1,0.4,2.2,5.9,-0.9,0,-0.6
Sony MDR-AS800|0.1,-0.4,-1.2,-2.3,-3.5,-2.7,-1.5,-0.2,0.8,-0.3,-1.1,0.4,2.3,-1.2,2.3,3.6
Sony MDR-CD2000|4.6,4.6,1.8,0.6,0.3,-0.4,-0.5,-0.7,-1.8,-3.4,-2.7,-4.7,-2.1,-2,-0.6,0.5
Sony MDR-CD3000|5.3,5.3,2.4,1.7,-0.5,-1.2,-0.9,-1.8,-1.6,-1.9,0.9,2.2,-3.1,-1.6,-1.4,-2.2
Sony MDR-CD380|4.2,4.2,2.4,1.2,0.1,-1.8,-0.9,-0.4,-1.1,-3.3,-3.9,-5.5,-1.3,-5.8,-1,4.2
Sony MDR-CD900ST|4.4,3.3,-0.2,-2.4,-4.4,-4.2,-3.8,-4,-3.3,-2.4,0.4,1.1,-3.6,1.1,3.2,4.4
Sony MDR-CD900ST (Yaxi stPad microfiber earpads)|4.7,2.4,-0.9,-2.2,-4.6,-3.7,-4,-3.6,-3.2,-2,2,3.8,0.1,-1.3,-0.8,4.2
Sony MDR-CD900ST (Yaxi stPad2 earpads)|1.6,-0.5,0.5,1.2,-5.1,-1.2,-1.1,-1.6,-1.4,-0.7,3.3,2.9,0.1,-4.8,-3.1,2.6
Sony MDR-D77 Eggo|4.6,1.7,-0.9,-1.9,-2.7,-2.3,-1.4,-2,-4,-5,-3.4,-1.5,-2.2,4.5,2.5,3
Sony MDR-DS3000|4.2,4.2,4.2,3.6,1.3,0.5,1.2,-0.6,-2.4,-4.2,-4.1,-4.6,-4.9,-2.7,-1.4,0.4
Sony MDR-DS6000|4.6,4.6,1,-2.1,-3.8,-3.5,-2.3,-1.9,-3.6,-3.3,-4.1,-3,0.4,4.2,4.3,2.1
Sony MDR-EX10|-3.5,-3.3,-3.5,-4.1,-4.5,-3.4,-1.2,1.2,3.2,3,3.5,2.9,1.7,1.5,4.2,3.3
Sony MDR-EX1000|4.2,3.5,1.9,-0.1,-3,-4.4,-4.3,-3.3,-1.6,-1.6,-3.1,0.2,4.2,-2.7,-1.4,4.2
Sony MDR-EX1000 (front tube filters removed)|4.1,4.1,3.3,1.1,-1.8,-3.4,-3.6,-2.9,-1.3,-0.9,-2.8,-3.8,2.2,-2,-2,3.9
Sony MDR-EX15LP|-1.8,-1.9,-2.2,-3,-3.5,-2.7,-0.6,1.3,2.9,1.6,1.2,1.1,0.1,1.3,3.7,3.5
Sony MDR-EX15LP (BGVP W01 eartips)|4.5,2.8,0.7,-1.3,-3.4,-2.3,-1.9,0,1.5,-0.2,1.3,1.6,-2,-2.9,-1.9,0.8
Sony MDR-EX15LP (Moondrop KXXS)|5.1,3.6,1.4,-0.6,-2.6,-2.8,-1.3,0.6,2.1,0.3,1.6,1.4,-1.5,-3.5,-2,-2
Sony MDR-EX15LP (SpinFit CP100 eartips)|5.1,3.8,1.5,-0.5,-2.6,-2.7,-1.3,0.5,2,0.2,1.2,1.5,-1.7,-3.6,-3.3,-2.8
Sony MDR-EX510 (no filter)|3.5,2.7,1.1,-0.7,-3.1,-3.7,-2.9,-1.6,0.3,0.6,-0.3,-1.9,0,-3.8,0.9,4
Sony MDR-EX56LP|-1,-1.6,-2.5,-3.6,-4.6,-3.3,-1.6,-0.3,0.1,3.6,1.1,0.6,1.8,7.2,5.6,0.7
Sony MDR-EX57LP|3.3,2,-0.1,-1.9,-4.2,-4.2,-2.8,-0.8,1,1.5,0.9,0.8,-2.5,-5.3,1.8,1.1
Sony MDR-EX600|4.4,3.7,2,0,-2.5,-4,-3.8,-3.6,-2.4,-3.2,-3.4,1.8,4.3,-3.8,-1.8,4.3
Sony MDR-EX800ST|4.1,3.2,1.6,-0.5,-3.3,-4.7,-4.6,-3.9,-2.2,-1.6,-3.5,1.4,4.5,-2.6,-0.8,4.5
Sony MDR-F1|4.7,4.7,4.7,1.7,-1.4,-2.9,-2.8,-2.8,-3.4,-5.4,-5.2,0.1,1.2,-0.6,4.7,4.7
Sony MDR-G75|4.8,4.6,-0.4,-2.9,-4.8,-4.2,-2,-1.5,-1.3,-2,-2.5,3.4,-3.5,0.4,3.9,2.7
Sony MDR-M1ST|4.5,0.9,-0.9,-3,-3.9,-0.9,-3,-3.7,-3,-3.6,-3.7,-0.3,1,4.6,3.9,4.6
Sony MDR-MA900|4.7,3.2,0.5,-1.2,-2.4,-2.9,-2.1,-1.8,-2.4,-4,3.2,0.5,-1.1,-0.1,0.9,2.3
Sony MDR-MV1|-0.4,-1.2,-1,-1,-1.7,-2.5,-1,-0.1,1.1,1.2,2.3,1.3,4.6,3.3,-0.3,0.3
Sony MDR-Q68LW|4.1,4.1,3.3,0.5,-1.5,-2,-0.8,-0.4,-0.8,-2.1,-3.8,-5.3,-6.7,-0.2,3.7,4.1
Sony MDR-R10|5,5,4.5,1.2,-1.8,-2.7,-1.7,-1.5,-2.7,-3.6,-3.9,1.1,4.9,0.5,0.1,-0.5
Sony MDR-SA3000|4.9,4.9,3.9,1.9,-0.9,-1.6,-1.7,-3.2,-4.2,-1.2,3.6,-3.1,-0.9,1.6,-2.3,-2.8
Sony MDR-SA5000|4.9,4.9,2.1,-0.3,-1.3,-0.7,0.2,-0.9,-2.6,-1.9,0.4,-5.9,0.4,2.5,-1,-2.3
Sony MDR-V500|4.5,4.5,4.5,4.5,2.1,1.2,0,-0.7,-1.6,-2.5,-2.8,-3.9,-9.1,-3.4,-2.5,1.2
Sony MDR-V6|2.1,0.1,0.7,0.5,1.6,2.1,0.3,-0.1,-0.8,-1.3,-2.9,-2,-2.6,-3.9,-4.3,0.1
Sony MDR-V6 (Yaxi earpads)|1.5,-0.3,-1.2,-1.3,-1.3,-0.4,-0.4,0.1,-0.4,-1.6,-3,0.3,2,3.6,-1.9,-0.6
Sony MDR-V600|4.4,3.1,-0.4,-2.2,-5,-6.9,-5.6,-5.4,-3.9,-2,0.2,3.8,-0.5,2.7,2.1,3.4
Sony MDR-V700|4.4,4.4,2.6,-0.6,-1.2,0.1,1.1,-0.7,-2.4,-2,-3.7,-6,-5.2,1.4,-1.2,2
Sony MDR-XB1000|0.8,-0.6,-2.9,-4.1,-6,-6.5,-3.4,0.1,7.2,1.2,6.4,-2.8,-0.6,4.9,5,1.4
Sony MDR-XB300|0.1,-3.4,-4.7,-6,-6,-3.7,-0.4,2.7,5,-0.3,-1.3,-3.4,1.3,6.2,6.3,6.3
Sony MDR-XB41EX|-4.2,-3.9,-3.6,-4.1,-4.3,-2.7,-0.5,1.6,3.4,2.5,1.4,0.4,5.7,-3,1,6.5
Sony MDR-XB50|-1.5,-2.3,-3.6,-5.2,-6.6,-5.3,-1.4,3.5,2.5,-2.3,-0.4,4.5,5.5,1,1.5,4
Sony MDR-XB500|-0.3,-1.3,-2.3,-3.8,-7.1,-8.6,-5.7,-2.8,4.1,3.8,1,5.1,6.2,-0.3,3.8,5.2
Sony MDR-XB50AP|-1.5,-2.3,-3.7,-5.3,-6.7,-5.3,-1.5,3.5,2.6,-2.1,0.2,5.7,6,0.9,-0.1,4
Sony MDR-XB55AP|-1.3,-2.3,-3.8,-5.2,-7.2,-5.2,-1.9,1.2,0.6,-1.6,2.1,5.9,5.9,2.2,0.8,5.6
Sony MDR-XB700|-1.1,-0.7,-3.9,-5.4,-5.9,-3.5,1.2,4.4,7,2.9,-1,2.1,1.6,3,1.4,1.3
Sony MDR-XB75AP|-3.9,-4.1,-4.6,-5.4,-5.9,-2.7,1.2,2,0,-0.9,2.9,6,6.6,0.4,0.9,5.4
Sony MDR-XB80BS|-0.6,-1.1,-2.3,-4.3,-5.3,-2,-0.5,-0.5,-0.5,-0.6,1.2,0.3,4.6,4,1,4.3
Sony MDR-XB950B1|-5.3,-5.4,-6.4,-7.5,-7.7,-3.5,5,0.4,-0.9,0,2.1,4.1,7.7,7.3,5.5,5.3
Sony MDR-XB950N1|-5.6,-5.3,-5.7,-6.9,-6.1,-1.3,2.4,1.3,0.4,-0.4,1.2,5.2,8.1,3.7,6.3,4.1
Sony MDR-Z1000|4.4,4.4,3,1,-2.6,-1.5,-2.6,-3.5,-3.3,-3.4,-4.7,-2.1,0.7,2.6,-2,1.2
Sony MDR-Z1R|-0.6,-0.9,-2,-3.8,-4.5,-2.2,-0.6,0.4,0.5,0.8,1.3,1,2.3,5.3,2.2,1.1
Sony MDR-Z7|-1.1,-1.7,-1.7,-2.2,-4.7,-3.6,-0.9,0.2,-0.6,1.7,3.8,-3.5,0.4,1.3,1,4
Sony MDR-Z7M2|-1,-0.9,-2.5,-4.2,-3.4,-1.8,-0.9,-0.3,-0.3,1.4,1,3.5,0.2,1.6,1.6,4.4
Sony MDR-ZX1000|4.5,4.5,2.4,0.7,-2,-0.9,-2.1,-3.2,-2.5,-2.7,-4.3,-3.8,-0.1,3.8,-2.5,-0.1
Sony MDR-ZX110|5.1,5.1,2.8,0.6,-1.5,0.4,1,3.1,4.1,1.7,-4.8,-2.5,-4.6,-0.6,-2.1,-1
Sony MDR-ZX110NC|1.8,-0.6,-2.8,-3.8,-4.3,-3.4,-1.1,1.3,0.5,-1.3,-1.2,3.5,5.5,5.8,-3.1,-1.8
Sony MDR-ZX310|4.5,4.5,0.9,-1.1,-1.6,-1.3,-1.7,-2.9,-4.1,-2.2,-1.6,-1.2,-2.3,1.3,2.1,1.2
Sony MDR-ZX700|4.6,4.6,0.2,-1.6,-3.4,-3.7,-3.1,-1.8,0.3,-0.9,-3.5,-2.7,0,4.2,-2.3,0.1
Sony MDR-ZX770BN|-0.6,-1.1,-0.8,-0.3,-0.4,-1.3,1.6,5.6,4.9,3.8,3.3,-0.2,2.5,-4.2,-3.1,-4.6
Sony MH1|-4,-3.6,-3.3,-3.7,-3.9,-2.9,-0.8,1.3,3.2,2.8,3.3,4.5,2.1,3.9,4,1
Sony MH1C|-5,-4,-3.2,-3.3,-3.2,-1.9,-0.2,0.9,2.2,1.6,2.2,2.2,3,7.7,7.2,1.1
Sony MH750|-2.7,-1.5,-0.7,-0.8,-1,0.1,1.3,2.4,3.3,2.1,1.3,0,-1.2,1.3,2.1,0.3
Sony MH755|-3.2,-1.8,-0.8,-0.8,-1.2,-0.2,1,2.1,3.1,2.2,1.1,0.6,-0.8,1.8,2.4,0.7
Sony PULSE 3D|5.1,2.7,0,-1.6,-3,-1.6,-0.7,-1.3,-1.3,-2.8,-4.6,-0.2,0.4,5.1,0.6,-0.5
Sony WF-1000X|-0.2,0,-0.7,-2,-3,-1.5,-1.9,-1.9,-0.6,-0.4,1,0.4,1.7,-0.7,2.3,1.2
Sony WF-1000XM3|2.9,2.1,0.6,-1.3,-2.7,-2.1,-1,-0.9,0.5,0.5,0.7,-0.1,1.5,1.5,1.5,-2
Sony WF-1000XM3 (ANC on)|2.2,1.2,-0.2,-2.2,-3.5,-3,-1.8,-1.5,0.8,1.5,0.7,0.4,1.6,2.5,2.4,0.3
Sony WF-1000XM4|-1.5,-1.7,-2.3,-3.2,-4.5,-3.9,-3.3,-2.5,-1.5,-0.6,1,0.7,1.5,4.2,5.9,5.4
Sony WF-1000XM4 (ANC off)|-1.9,-2.2,-2.6,-3.6,-4.6,-4,-3.3,-2.3,-1.4,-0.6,0.8,2.2,3.7,5.5,6.3,4.7
Sony WF-1000XM4 (ANC on)|-1.4,-1.7,-2.3,-3.3,-4.5,-4.3,-3.8,-2.7,-1.2,-0.5,0.7,1.8,3.7,4.3,6,4.8
Sony WF-1000XM4 (AZLA Sedna Light Short eartips)|-1.1,-1.4,-2,-3,-4.1,-4.1,-3.6,-2.9,-1.4,-0.5,0.8,2,3.8,4,6,4
Sony WF-1000XM4 (Bass Boost preset)|-0.8,-1.4,-1.8,-2.3,-2.9,-2.4,-2.1,-1.8,-0.2,1,1.2,1.4,3.1,5,6.5,1.9
Sony WF-1000XM4 (Bright preset)|1.6,1.4,0.8,-0.5,-2.2,-2.3,-2,-2.1,-1.2,-0.7,0.2,-0.5,2.3,3.7,5.4,1.2
Sony WF-1000XM4 (EQ off)|0.1,-0.2,-0.8,-1.8,-2.9,-2.8,-2.4,-1.6,-0.2,0.6,1.6,2.1,2.7,2.2,1.9,-1.7
Sony WF-1000XM4 (Excited preset)|-0.5,-0.9,-1.3,-1.8,-2.3,-1.4,-0.8,-1,-0.2,0.6,1.1,1.4,2.8,3.7,5.3,0.9
Sony WF-1000XM4 (Final Audio Type E eartips)|-1.3,-1.5,-2.1,-3.1,-4.3,-4.2,-3.9,-2.9,-1.3,-0.4,0.8,2.1,2.5,5.1,5.9,2.9
Sony WF-1000XM4 (Mellow preset)|-0.4,-0.2,-1,-2.5,-4.9,-5.3,-4.3,-3.5,-1.1,0.8,1.4,3,4.7,6.6,6.6,4.9
Sony WF-1000XM4 (Relaxed preset)|3.1,4,3.3,0.6,-4.6,-6.8,-5.1,-4.1,-2.8,-1.6,-0.9,1,3.2,5.7,5.8,5.4
Sony WF-1000XM4 (Sony WF-1000XM3 silicone eartips)|-1.3,-1.6,-2.2,-3.2,-4.5,-4.2,-3.9,-2.9,-1.3,-0.4,1.1,1.4,3,4.6,6,3.7
Sony WF-1000XM4 (Speech preset)|4.3,4.3,4.3,2.4,-3.3,-6.3,-6.3,-5.5,-3.5,-2.6,-1.7,-1.2,0.8,2.8,4.3,4.3
Sony WF-1000XM4 (SpinFit eartips)|-1.1,-1.4,-2,-3.1,-4.2,-4.1,-3.8,-2.8,-1.2,-0.3,1.1,1.7,3.8,4.7,4.9,2.3
Sony WF-1000XM4 (Tanchjim APB eartips)|-1.1,-1.4,-2.1,-3.1,-4.2,-4,-3.8,-2.9,-1.3,-0.4,0.9,1.9,4,4.1,6,3.8
Sony WF-1000XM4 (Treble Boost preset)|0.6,0.4,-0.2,-1.1,-2.3,-2.2,-1.8,-1.5,0.1,0.9,0.4,-0.1,1.9,3.6,5.3,0.9
Sony WF-1000XM4 (Vocal preset)|1,0.8,0,-0.9,-2.2,-2.8,-3.7,-3.1,-1.2,-0.6,0.2,-0.1,2,3.9,5.9,2.8
Sony WF-1000XM5|-0.9,-0.9,-1.4,-2.3,-3.2,-2.4,-1.5,-1,0,1.3,1.1,2.1,2.6,0.8,0.9,0.9
Sony WF-C500|-2.5,-1.2,0.5,1.2,1.6,1.1,0.8,1.1,2.3,0.5,-0.9,-3.7,-0.3,4.6,0.2,-0.1
Sony WF-C700N|-2,-2.2,-1.4,-1.4,-1.6,-0.8,-0.5,0.1,0.2,-1.8,-1.8,0.6,1,2.1,-0.3,4.4
Sony WF-SP700N|-6.3,-6,-5,-4.5,-3.4,-0.7,1.5,3.5,5.4,3.5,3,4.2,6.2,7.4,1.4,-0.6
Sony WF-SP800N|-6.3,-6.5,-5.9,-6,-5.9,-5.7,-3.5,-2.1,-0.5,3.3,4,7.5,6.4,5.6,7.5,7.5
Sony WF-XB700|-6.4,-5.5,-3.1,-1.7,-1.6,-0.1,0.2,0.9,1.9,1.4,1.7,0.2,1.1,0.3,4.8,3
Sony WH-1000XM2|-0.5,-0.4,-1,-2,-3.3,-2.6,-0.3,1.6,3.9,3.4,-3.1,-1.2,2.1,0.8,0.5,3.1
Sony WH-1000XM3|-3.5,-3.4,-4.2,-5.3,-5.5,-1.9,-0.4,-1.3,0.3,4.1,5.5,3.9,4.9,4.4,1,1
Sony WH-1000XM3 (ANC off)|-4.5,-5.3,-4.9,-4.2,-5.4,-1.5,2.1,3.8,4.2,3.6,1,-1.4,2.2,3.4,0.2,3.5
Sony WH-1000XM3 (ANC on)|-3.5,-3.7,-4.4,-5.5,-5.8,-2.2,-0.6,-1,0.8,3.1,1.6,1.1,5.4,6.3,1,4.5
Sony WH-1000XM4|-2.7,-2.5,-2.9,-4.1,-4.4,-1.5,1.2,0.8,1.3,2.9,3.1,6.8,2.3,-0.9,1.8,1.8
Sony WH-1000XM4 (ANC Off)|-1.9,-3,-3.2,-2.2,-4,-1.3,0.9,2.1,3.7,1.8,0.3,-0.9,0.7,2.2,-2.6,0.8
Sony WH-1000XM4 (ANC on)|-3.4,-3.2,-3.4,-4.5,-4.9,-0.7,0.7,1.2,2.1,2,1.2,0.1,2.7,4.4,-2.6,3.4
Sony WH-1000XM5|-3,-2.8,-3,-4.1,-5.1,-3.5,-0.5,1,1.7,4,3.8,5.6,-0.8,0.7,2.2,4.7
Sony WH-CH400|3.2,0.6,-1,-1.1,-0.2,1.5,0.7,-1.8,-0.4,1,3.4,2,-0.8,-2.2,-2.1,-1.4
Sony WH-CH500|4.2,-1.7,-4.8,-3.8,-1.8,-0.3,0.8,1,0.3,-1,-0.5,0.5,0.9,5,-3.6,-3.1
Sony WH-CH510|0.9,-2.5,-4.8,-4,-2.2,0.1,0.3,-0.4,-0.1,-1,-1.2,1.3,1.1,6,2,0.3
Sony WH-CH520|2.1,-0.4,-1.8,-1.2,-0.7,-0.6,0,-0.3,-0.7,-0.8,0.4,-1.3,-0.1,6,-0.6,-3.4
Sony WH-CH700N|-0.7,0.4,1.4,0.9,-0.5,-0.1,1.9,4.1,3.7,4.4,1.8,-1.5,-3,-7,-2.8,0.4
Sony WH-CH710N|-0.8,-1.9,-2.2,-2.3,-2.9,-2,-0.7,0.2,1.1,2.6,1.2,-0.4,-1.9,4.9,1,1
Sony WH-CH720N|-0.1,-1.1,-2.6,-3.8,-4.6,-1.4,-0.2,1.5,1.6,4.1,3.4,0.1,4.1,4.1,-5.4,-2.1
Sony WH-H900N|-0.1,-0.7,-1.4,-2.6,-3.7,-3.2,-1.9,-0.5,0.4,0.4,0.3,6.2,3,2.5,2,1.6
Sony WH-H910N|-1.7,-1.2,0.2,-0.9,-3.5,0,1.1,1.1,4.8,1.3,0.1,1.2,3.9,-3.4,-0.8,1
Sony WH-XB700|-4.8,-4.5,-3.8,-3.1,-2.1,-0.5,4,5.7,4.7,3.4,3.2,2.8,3.3,8.2,-0.2,-3.7
Sony WH-XB900N|-5.1,-4.6,-5,-4.6,-2.1,-0.1,4.8,5.5,3.6,5,0.8,0.4,5.3,0.1,-2.2,-0.3
Sony WH-XB910N|-5.9,-6.7,-6.6,-5.2,-3.3,1.1,5.4,7.9,7.2,0.2,0.1,4.3,8.6,4.4,-2.5,-2
Sony WI-1000X|-0.9,-0.6,-0.9,-1.8,-3.7,-2.9,-1.8,0,2.4,3.4,5.7,2.6,1.6,1.3,-2,-2.7
Sony WI-1000XM2|1.1,0.2,-0.8,-1.8,-2.8,-2.2,-1.4,-0.5,1.1,0.3,-0.7,-1.3,1.5,-0.5,-0.7,1.8
Sony WI-C200|0.1,-0.7,-1.4,-2.2,-2.8,-1.2,0.4,2.2,3.4,2.1,1,1.7,0.7,-1.1,0.1,-0.5
Sony WI-C300|4,2.3,0,-2,-4,-3.1,-1.7,0.2,1.9,0,-0.6,-0.5,-0.7,-2.2,1.3,1
Sony WI-C310|1.3,0.4,-0.6,-1.6,-2.4,-1,0.4,2.1,3.2,1.6,1.3,0.6,-0.5,-2.5,-0.6,-0.8
Sony WI-C400|4.5,4.5,3.5,0.4,-3.1,-3.3,-2.3,0.1,1.9,0.3,-0.3,-0.6,-1.9,-3.6,-2.3,1
Sony WI-C600N|-0.1,-0.5,-1.3,-2.1,-2.1,-2.4,-3.1,-1.7,0.9,0.7,1,3,4.6,4.8,-2.4,-1.4
Sony WI-SP500|4.3,4.3,4.3,1.5,-2.9,-4.7,-4.9,-4.8,-2.2,-0.7,1.7,-0.1,4.2,-2.6,-5.2,0.5
Sony WI-SP600N|-6.7,-6.2,-5.1,-4.6,-3.5,-1.4,0.8,2.6,4.5,4.8,3.9,5.6,4.9,8.1,2.8,0.7
Sony WI-XB400|-5,-3.7,-2.2,-1.7,-1.2,-1,0.3,1.8,2.8,1.5,2.1,-0.1,2.6,-1.9,0.6,3.1
Sony XBA-300|2.9,2.2,0.8,-0.8,-2.8,-2.3,-0.9,1.2,3.6,3.7,2.4,-0.8,-2.8,0.3,-1.5,-3.6
Sony XBA-40|-1.6,-1.7,-2.1,-3.2,-4.5,-4.1,-2.9,-1.4,0.7,1.8,3.7,5.2,-2.4,6.2,6.9,1.7
Sony XBA-A2|-3.9,-3.6,-3.5,-3.6,-4.9,-3.2,-1.2,0.8,2.7,3.4,4.1,6.5,2.4,3.5,4.7,0.9
Sony XBA-A3|1.3,0.7,-0.6,-2.2,-4,-3.5,-2,-0.5,1.1,0.8,0,3.2,3.3,-1.7,3.2,0.7
Sony XBA-A3 (modded)|2.3,1.5,0.2,-1.5,-3.1,-2.7,-1.4,-0.7,0.8,-0.1,-1.5,4.1,4.3,-1.1,1.5,-1.6
Sony XBA-H3|2,1.6,0.4,-1.1,-2.9,-2.2,-0.9,0.4,1.1,0.1,-0.1,5.9,0.8,-3.3,-0.8,-2.2
Sony XBA-N1|-2.2,-2.3,-2.7,-3,-4.8,-2.9,-0.9,1.3,3,2.4,1.8,2.9,3.2,2.6,2.1,0.2
Sony XBA-N3|-1.5,-1.4,-1.4,-1.6,-3.1,-1.7,-0.3,1,1.7,0.6,1.6,2.6,2.7,2.7,0.7,-1
Sony XBA-N3AP|-1.3,-1.6,-2,-2.8,-3.5,-2.2,-0.7,0.8,1.7,1,0.1,2.5,3.5,3.1,2.2,0
Sony XBA-Z5|0.7,-0.2,-1.5,-3.2,-5,-4.2,-2.6,-1.2,0.2,0.5,0.3,4.1,3.8,-0.7,4.5,2.6
Sony XEA20 Xperia Ear Duo|4.6,4.6,4.6,4.6,4.6,-0.4,-2.6,-3.7,-4.5,-8.3,-3.7,-4.5,-3.3,-5.6,2.1,1.5
Soranik Bastille-R|1.2,1.3,1.3,0.4,-0.7,-0.4,0.1,0.2,0.2,0.2,-4.5,-4.9,-1.4,-6,4.1,4.9
Soranik Bastille-V|0.2,0.1,-0.3,-1.1,-1.8,-0.3,1.8,3.7,3.8,-0.2,-4.7,-0.8,0.4,-2.9,-0.1,2.3
Soranik ION-4 (Vietnamese domestic market edition)|-0.9,0.2,0.8,0.1,-1.3,-1.4,-0.6,0.7,2.1,1.6,-0.8,-3.9,1.1,-1.5,4.8,5.7
Soranik SK3|4.4,3.3,1.6,-0.3,-3,-4.2,-4,-3.1,-1.7,-1.1,-0.4,1,1.6,-4.7,2.9,4.4
Soranik SP3|2.3,2,1,-0.4,-2.5,-3.3,-2.6,-1.4,0.1,-0.4,-2.6,1.5,4.5,0.6,0.9,0.2
Soranik SP3 SE|0.5,0.3,-0.2,-1.2,-2.2,-1.8,-0.9,0.1,1,-0.1,-1.7,-0.9,3.5,-1.8,2.1,3.7
Soul by Ludacris SL100|2.1,0.6,-1.1,-3.8,-5.6,-6.4,-6.1,-4.5,1.5,-0.6,-0.9,5.4,5.4,-1,4.7,5.4
Soul by Ludacris SL150|4.5,3,0,-2.2,-5,-6.2,-5.9,-3.7,1.7,1.1,1.9,0.7,-1.7,-2.4,4.3,4.3
Soul by Ludacris SL300|2.9,-3.5,-1,-0.5,-1.2,-1.7,-1,-3.6,4.1,-1.5,0.3,2.7,-0.2,-3,0.8,0.2
Soul Combat|3,0.3,-1.6,-2.6,-4.9,-7.6,-8.6,-9.9,-0.4,0.4,2.5,6,5.6,6,4.5,0.1
Soul Jet Pro ANC Off|5.3,3.2,0.1,-0.9,-5.6,-9.5,-4.1,4,3.5,2.1,4.2,0.2,-3.2,-4.4,1.8,2.2
Soul Jet Pro ANC On|-1.2,0,0.1,-0.5,-2.8,-4.8,-4.9,-0.3,5.9,3.4,-3.1,-6.5,-2.7,-1.6,5.9,5.9
Sound Intone CX-05|0.1,-1,-1.8,-1.9,-2.6,-3.6,-5.6,-5,-3.9,-3.6,-0.4,3.2,5.4,4,3.7,5.6
Sound Linear Fitz 10 Flat|2.4,1.9,1.1,-0.4,-3.2,-4.9,-4.7,-3.7,-1.8,-1.9,-3.1,2.1,2.4,5.3,4.6,3.3
Sound Rhyme DTE900 (off-off)|1.1,1.2,0.7,-0.4,-1.8,-1,0.4,2.2,3.1,-0.8,-4.1,-0.8,-1.2,-1.1,-1.1,-0.6
Sound Rhyme DTE900 (off-on)|1.6,1.6,1.1,0,-1.2,-0.5,1,2.6,3.4,-0.3,-3.5,-0.1,-0.5,-0.6,-1.3,-2.3
Sound Rhyme DTE900 (on-off)|0,0.1,-0.4,-1.5,-2.7,-1.8,-0.3,1.6,2.9,-0.1,-2.7,0.7,0.2,0,0,0.6
Sound Rhyme DTE900 (on-on)|0.5,0.6,0.1,-1,-2.3,-1.5,0.2,2,3.4,0.3,-2.2,1.1,0.6,0.5,-0.1,-1.2
Sound Rhyme Prado (off-off)|1.5,0.9,-0.6,-2.4,-5,-6,-5.1,-3.6,-1.5,-0.8,0.2,1.3,3.5,1.1,3.7,5
Sound Rhyme Prado (off-on)|2.7,2.1,0.6,-1.2,-3.9,-4.8,-4,-2.3,-0.3,0.4,1.4,2.5,4.8,2.5,2.6,-0.9
Sound Rhyme Prado (on-off)|-0.6,-0.8,-1.8,-3.4,-5.3,-5.4,-4.2,-2.4,-0.3,0.3,1.2,2.2,3.9,1.6,4.2,5.6
Sound Rhyme Prado (on-on)|0.6,0.4,-0.7,-2.2,-4.1,-4.2,-3,-1.3,0.7,1.2,2,3,5.2,3.2,3.3,-0.1
Sound Rhyme SR5|0.9,1.2,0.8,-0.2,-1.5,-1.4,-1.1,-1.6,-2.2,-1.6,-1.7,1.2,0.9,5,-1.5,-1.1
Sound Rhyme SR7 (off-off)|2.6,1.6,0,-1.8,-4,-4.6,-3.9,-2.8,-1.7,-1.4,-0.5,1.4,1.9,3.8,2.6,2.2
Sound Rhyme SR7 (off-on)|3,2.1,0.4,-1.4,-3.6,-4.2,-3.6,-2.5,-1.2,-1.1,-0.1,1.8,2.4,4.1,1.8,-0.1
Sound Rhyme SR7 (on-off)|-0.2,-0.6,-1.7,-3.1,-4.9,-4.8,-3.5,-1.9,-0.4,0,1.3,2.8,3,4.8,3.9,3.4
Sound Rhyme SR7 (on-on)|-0.2,-0.4,-1.4,-2.8,-4.6,-4.4,-3.1,-1.6,-0.1,0.2,1.6,3.2,3.5,5.6,3.4,1.4
SoundMAGIC E10|0.7,-0.1,-1.4,-3.1,-4.8,-4.5,-3.3,-2.2,-0.6,-0.5,1.8,6,6,4.4,-0.9,2.3
SoundMAGIC E11|1.4,0.5,-0.9,-2.5,-4.1,-4,-3,-2,-0.8,-1,-1,5.1,5.4,3.2,-0.6,2.5
SoundMAGIC E11C|-3.4,-3.1,-2.8,-2.6,-2.5,-2.8,-0.7,1.2,0.6,-0.3,0,5.5,6.4,2,-2.3,3.4
SoundMAGIC E50C|0.5,0.4,-0.3,-1.4,-2.7,-2.9,-2.1,-1.3,-0.9,-1.8,-1.3,4.3,5.6,2.8,0.8,0.7
SoundMAGIC HP100|-0.6,-1.3,0,3.3,1.7,-1.8,-1.2,-1.3,-0.6,-0.5,0.2,-0.7,-2.2,3.3,-0.2,-1.7
SoundMAGIC HP200|3.1,4.8,1.8,-0.5,-2.2,-1.1,1.9,4.1,4.8,2.4,0.6,-5.2,-6,0.2,-4.2,-4.9
SoundMAGIC T60BT|-4.5,-3.7,-2.9,-3.1,-3.1,-1.4,0.3,2,3.5,2.7,1.7,-0.6,6.4,6.4,1.5,1.8
SoundPeats Air3|5,5,3.7,-0.8,-5.7,-5.6,-5.9,-4.5,-6.1,-4.6,0.8,5,5,3.9,-1.6,0.3
SoundPeats Air3 Deluxe HS|4,0.3,-3.9,-6.1,-7.1,-5.8,-4.2,-2.2,-0.4,2.4,6.2,6.3,6.3,4.3,-4.5,-4.4
SoundPeats Air4 (ANC off)|6.1,6.1,6.1,6.1,6.1,5.4,6,6.1,6.1,3,-2.1,-7.2,-10.5,-12.7,-11,-7.9
SoundPeats Air4 (ANC on)|4.9,2,-1,-0.8,-1,-0.6,-0.1,1.4,-0.7,-5.1,-3.2,1.6,1.3,-2.2,-2.6,1.1
SoundPeats Air4 Pro|1,0.5,1.7,3,3.1,-0.6,-0.6,-0.9,0.7,-1.7,-1.7,-2.3,-2.2,0.2,-0.8,-3.4
SoundPeats Air4 Pro (ANC Off)|-2.6,-1.9,0,1.5,2,-2,-1.4,-1.5,0.4,-1.7,-1.5,-0.3,-0.2,2.9,2.5,2
SoundPeats Air4 Pro (ANC on)|-0.5,-1.5,-0.7,0.5,1,-1.8,-0.1,-0.9,-0.1,-2.2,-1.9,-0.2,-1.1,1.9,1.7,1.4
SoundPeats Capsule 3 Pro|-2,-2.6,-2.6,-2.4,-1.1,1.5,1.5,0.8,0.8,0,-1.3,-1.6,-0.5,-1.8,2.3,4.8
SoundPeats Clear|-1.6,-1.4,0.1,1.4,1.3,-0.1,0.6,0.4,0.1,-0.9,-1.6,-0.3,-0.8,1.8,1.9,0.7
SoundPeats Engine 4|-3.1,-3.1,-2.1,-1.5,-0.1,0.5,0.4,0,1,0.7,-0.2,1.1,2.9,7.1,1.7,-1.5
SoundPeats H1|-6,-7.1,-4.8,-4,-1.9,-0.9,-0.3,0.9,1.9,0.7,-1,-1,5.9,7.2,2.5,3.8
SoundPeats H2|-3.5,-4.7,-2.3,-0.8,1.8,0.8,0.4,1.1,2,1.3,-0.6,-2.4,2,5.3,3,-0.7
SoundPeats Life|0.9,-2.7,-4.5,-4.1,0.5,2.6,0.2,0.5,2.2,1.2,-4.5,-2.6,-1.8,0.2,1,2.2
SoundPeats Life Classic|-3,-4.7,-4.2,-3.8,-3,-1.3,0.1,1.7,2.1,1.2,0.9,1.3,-0.3,0.3,2.5,4.8
SoundPeats Mini|-0.4,-1.8,-2.3,-1.8,2.1,3.3,3.3,3.2,2,-0.1,-1.7,-3.9,3.2,2.7,-3.2,-4.4
SoundPeats Mini Pro HS|4.7,3.1,0.4,-0.8,-0.1,-0.5,-0.8,-1.5,-2.2,-3.7,-4.4,-3.3,-1.3,3,1.5,2.1
SoundPeats Mini Pro HS (ANC off)|-1.1,-1.6,-1.5,-0.9,1.7,1.9,1.1,1.3,1.8,0.4,-1.1,-1.9,-1.9,-1.8,-0.1,3.4
SoundPeats Mini Pro HS (ANC on)|-1.4,-2.1,-2.1,-1.5,1.6,1.1,-0.4,0.3,1.8,1,-0.4,-1.1,-2.5,-0.1,0.1,4.4
SoundPeats Opera05|-0.8,-1.4,-1.2,-0.7,1,2.1,1.4,2,3.2,1.6,0,1.7,1,1.8,0.4,-4.6
SoundPeats Q9A|-3.8,-3.8,-4.3,-5.2,-6.4,-4.8,-2.4,1,3.5,3.7,0.4,3.6,6.5,3.2,1.2,5.7
SoundPeats RunFree|4.8,4.8,4.3,-2.2,-5.1,-0.4,-0.5,-1.8,-0.2,-0.3,-2.5,-2.3,1.8,0.8,-1.7,-3.2
SoundPeats RunFree Lite|4.4,4.4,4.4,-0.9,-8.8,-5.3,0,0.9,1.9,3.7,-1.9,-1.6,3.4,-6,-2.8,0.1
SoundPeats TrueFree|-5.1,-3.6,-2.5,-2.7,-3.1,-1.2,0.6,2.6,3.9,2.7,1.4,1.7,6.3,7.8,0.4,-1.6
SoundPeats TrueFree 2|-1.4,-4.1,-2.5,-2.5,-1.7,0.8,3,5.2,5.8,2.7,2.9,3.6,0.8,3.3,-5.2,-6.7
SoundPeats Wings2|4.1,4.1,4.1,4.1,4.1,3,2.5,1.5,-1.2,-5.6,-11.6,-9,-4.2,-6.3,0.4,4.1
SPC Gear Viro (breathable earpads)|2.2,0.8,-0.9,-1.7,-2.3,4.1,4.3,3.1,2.1,1,-0.2,-5.1,-1.3,3.6,-3.8,-4.4
SPC Gear Viro (leather earpads)|2.3,0.5,-1.1,-1.6,-2.7,2.8,3.7,2.1,1.1,-0.3,-2.6,-5.1,-0.1,5.2,-1.7,-1.9""",
"""SPC Gear Viro (velour earpads)|3.6,1.8,0.5,0,0,4.5,3.2,1,0.3,-1,-2.7,-5.7,-2.5,3,-2.4,-3.6
Spider Moonlight|3.6,2.2,0.7,-0.8,-3.6,-2.2,2.6,0.8,1.6,0.4,-0.6,-3.6,-6.5,2.1,6.1,-1.3
Spider PowerForce|-1.6,-3.7,-5.4,-6,-6.7,-1.8,-1,-1.6,1.5,1.6,2.8,2.1,3.1,-0.3,4.3,5.3
Spider realvoice|-3.2,-2.8,-2.8,-3.4,-4,-3.5,-2.6,-2.2,-1.3,-1.8,-1.1,3.2,5.7,5.6,5,6.3
Spiral Ear SE 5-Way Ultimate|1.5,0.3,-1.1,-3,-4.9,-4.9,-3.7,-1.7,0.3,-0.8,-6.4,-0.3,6.5,6.7,6.7,4.5
Spirit Torino Centauri|3.5,-0.9,-2.5,-2.7,-2.9,-3.7,-2.3,-3.9,-3.7,1.3,6.8,6.8,6.8,-1,1.4,-0.5
Spirit Torino Radiante|-2.8,-3.9,-6.1,-6.6,-6.9,-1.4,4.6,2.7,-3.9,-3.7,-0.1,6.6,7.1,1.6,6.2,6.4
Spirit Torino Superleggera|5.7,5.7,1.1,-2,-4,-4.5,-4.1,-3.7,-3.5,-1.2,5.6,5.7,2.4,-1,0,-0.6
Status Audio Between 3ANC|-1.3,-0.9,-1.1,-1.6,-1.2,0.4,2.9,3.5,2.5,0.9,0.3,-1.2,-0.7,-2.1,0.3,1.5
Status Audio Between 3ANC (ANC Off)|-3.9,-3.8,-3.7,-3.8,-2.8,-0.4,0.6,2,4.1,3.5,2.4,1.7,2.4,4.2,0.8,-0.9
Status Audio Between 3ANC (ANC on)|-2.2,-2.5,-2.4,-2.2,-0.5,2,2.6,2,1.3,0.7,-0.1,-0.4,0,1.9,-1.5,-2.4
Status Audio Between Pro|-2.5,-2.3,-2.1,-2.3,-2.1,-0.8,-0.1,1.8,3.8,2,2.5,2.9,1.2,4,1.1,-1.9
Status Audio BT One|-2.9,-2.1,-1.8,-1.3,-0.7,1.1,1.6,2.4,2.8,1.9,2.3,1.1,-0.8,3.6,-0.3,1.5
Status Audio BT One (wired)|1.4,0.8,-1.1,-1.6,-1.7,0,-0.4,-5,-4.1,-1.2,-0.1,2.3,-0.4,2.5,2.5,3.1
Status Audio BT One (wireless)|-1.1,-1.7,-2.8,-4.2,-5.7,-4.3,-1.9,-1.3,0.5,-1,0.9,4.5,2,5.5,5.9,6.2
Status Audio IEM-2X|0.3,-0.1,-1,-2.1,-3.1,-2.2,-0.4,1.7,3.6,3.8,2.2,3.2,-0.1,-1.9,-3.7,-4.8
Status Audio SM-CB1|3.4,0.7,-1.1,-1.5,-2.7,-3.2,-0.9,0.5,-1.2,-1.9,-0.5,2.5,-2.3,2.9,0.2,0.2
Status Audio SM-OB1|3.8,1.3,-1,-2.1,-4.3,-4.2,-3.9,-3.9,-3.5,-2.8,0.4,1.4,1.2,5.2,2.3,2.1
Stax 4070|2.6,3.4,2.9,2.3,0.9,-0.5,0.2,2,1.9,0,-1.2,-0.6,-7,-2.6,0,-2
Stax Lambda Nova Basic|5.4,0,0.3,0.8,0.4,-0.3,0.3,0.2,-0.5,-3.2,-4.3,0.3,1,3.5,1.9,-0.8
Stax Lambda Nova Signature|2.7,3,2.9,1.9,0.8,-0.4,0.1,-0.2,-0.5,-2.3,-3.5,1.1,-3.3,0.5,-2.4,-0.9
Stax SR Lambda Pro Classic (bad seal)|5.8,5.8,-5.1,-3.8,-0.5,0.4,1.2,1.4,0.7,-1.1,-3.9,-1.7,1.3,2,0.2,-1.3
Stax SR-001 Mk1|4.2,3.7,2.4,0.5,-2.3,-4.4,-4.5,-4.3,-1.7,-3.3,-3.3,1.4,2.5,5.4,5.5,2.3
Stax SR-001 Mk1 CES (CES-A1)|3,2.6,1.3,-0.5,-3.1,-4,-2.9,-1.4,1.3,0.1,-0.3,-1.1,3.2,-5,5.6,3.1
Stax SR-001 Mk2|4.4,4,2.6,0.8,-2,-3.5,-3.2,-3,-4.3,-4.1,-6.7,1.3,2.2,5.3,5.3,2.9
Stax SR-002|5.1,4.7,3.4,1.4,-1.5,-3.5,-4.1,-5.1,-5.1,0.2,-6,0.7,3.7,4.7,5.5,0.4
Stax SR-003 SA-1993|5,4.4,2.9,1,-1.6,-3.4,-3.9,-5,-2.6,-1.5,-4.5,2.4,5.1,5.8,0.9,-3.3
Stax SR-003MK2|5.3,5.3,3.5,1.4,-1.7,-3.2,-3.8,-4.7,-5,1.1,-4.7,0.7,3.5,3.9,5.1,-2.7
Stax SR-003MK2 (CES-A1)|5.2,4.4,2.7,0.7,-2,-3.2,-3.2,-2.6,-0.8,3.7,-0.8,-0.7,4.3,-4.5,2.2,-1.5
Stax SR-007|4.2,0.4,1.2,1,-0.5,-2,-1.9,-1.7,-2.9,-1.6,-1.6,5.1,1.5,-1.4,1.8,-1.7
Stax SR-007 Mk2.5|4.6,1.6,-0.1,-0.3,-1.8,-3.1,-3.3,-1.9,-3,-1.1,-0.9,3.7,-0.4,2.2,4.9,0.1
Stax SR-007A v2.9|5.3,-0.4,0.8,0.6,-0.6,-2,-1.9,-1,-2.9,-2.8,-2.1,1.9,3.7,4.6,2.9,-2.4
Stax SR-007A v2.9 (blu-tac mod)|5.7,3.4,2.5,1.4,-0.6,-2.1,-2.2,-1.2,-3.3,-3,-2.8,1.4,3.9,3.5,1.9,-2.8
Stax SR-009|5.4,4.1,3.2,2.1,0.2,-1.1,-0.8,-1.1,-1.8,-4.4,-3.6,0.4,-1.2,-2.4,2.8,-1.9
Stax SR-009 (sample 1)|5.3,5.3,2.7,2.2,0.7,-1,-0.8,-1.6,-1.8,-3.9,-2,2.2,-2.3,-4.8,1.5,-1.2
Stax SR-009 (serial number SZ9-1278)|5.1,4.7,2.7,2.1,0.5,-1,-0.7,-1.7,-2.2,-3.7,-2.3,1.4,-2.1,-3.4,0.8,-1.1
Stax SR-009S|5.3,5.3,2.8,2,0.2,-1.2,-1.1,-1.6,-2.5,-5.2,-3.3,2.2,-0.4,-4.9,2.4,-0.1
Stax SR-1|4.8,4.8,4.8,0.1,-3.6,-3.8,-2.5,-2.3,-2.1,-3.2,-1.6,-1.2,-0.8,4.8,0.9,-2.1
Stax SR-207|3.8,3.9,3.2,2.2,0.7,-0.4,-0.1,-0.5,-0.8,-2.6,-3.6,1.9,-2.9,-0.9,-1.8,-0.5
Stax SR-207 (EP-507 leather earpads)|4.6,4,2.8,1.9,0.4,-0.8,-0.2,-0.7,-1,-2.9,-4,1.3,-3.2,-2,-2.4,0.5
Stax SR-3|4.7,4.7,3.1,0.6,-2,-3.2,-2.2,-1.2,-2.4,-2.4,-1.9,-0.1,-2.4,2.7,0.2,-0.8
Stax SR-40|4.6,4.6,4.6,1.8,-2.3,-4.2,-3.6,-3.5,-1.5,-3.8,-3.2,1.8,2,2,2,2.4
Stax SR-40 Electret SR4 Adapter|4.7,4.7,4.7,3.9,-2.4,-2.9,-1.5,-1.7,-1.6,-1.8,-4.7,2,-1.8,-1.1,0.4,0.6
Stax SR-404|6,4.8,-3.6,-1.2,0,0.1,0.4,0.6,-0.1,-2.6,-4.1,-0.4,0.2,3,2.8,-0.1
Stax SR-404 (with perfect seal)|5.3,4.8,3.5,2.2,0.1,-1.2,-0.7,-0.7,-1.5,-3.7,-5.1,-0.9,0.3,2.4,1.2,-0.6
Stax SR-404 Limited|4.7,4.2,3,1.7,-0.2,-1.4,-1,-1.2,-1.9,-3.8,-5.6,1.3,-1.3,1.1,1.8,1.5
Stax SR-404 Ltd SSL-0670|5.3,5,3.5,2.7,0.9,-0.4,0.2,-0.2,-0.7,-2.4,-3.4,1,-3.3,-0.1,-1.9,-3.4
Stax SR-404 S 2742|5.2,5,3.4,2.5,0.8,-0.5,0.1,-0.5,-0.8,-2.5,-3.9,1.4,-2.9,-0.2,-3.1,-2
Stax SR-404LE|5.4,5.4,0.4,-6.4,-2,-0.8,0.6,0.9,0.3,-1.7,-4.7,-1.5,0.4,1.4,2.7,0.6
Stax SR-404LE (with perfect seal)|2.4,2.8,2.5,1.8,0.4,-0.6,0,-0.2,-0.8,-2.8,-5.3,-1.8,0.1,1.1,2.5,0.4
Stax SR-4070|4,3.5,2.3,1.2,-0.9,-2.6,0.3,0.4,0.2,-1.6,-3,1.1,-3.8,-4.2,4.5,3.7
Stax SR-5 Gold|5.5,4.2,2.6,0.3,-1.6,-2.5,-2.1,-1.7,-1.1,-1.2,3.4,-1.9,0.4,-1.3,3.7,-0.5
Stax SR-507|4.5,4,2.7,1.5,-0.1,-1.1,-0.8,-0.9,-1.8,-4.1,-5.9,1.9,0.2,0.4,1.6,1.5
Stax SR-84|4.9,4.9,2.7,-0.3,-3.1,-3.1,-2.1,-1.5,-0.5,-2.2,-3.8,-1.2,2.2,4.2,1.6,0.4
Stax SR-Gamma Pro|5.1,5.1,3.4,0.5,-1.7,-1.9,-0.3,-0.5,-0.4,-1.4,-1.7,2.5,0.3,-0.2,-3.9,-2.1
Stax SR-L300|5.4,3.9,0.6,-0.2,-0.8,-1.3,-0.7,-0.7,-1.5,-3,-1.3,1.6,1.3,-1.7,-0.4,0.3
Stax SR-L300 Limited|3.9,3.2,2,0.9,-0.5,-1.6,-1.1,-1.5,-2.8,-6.3,-0.9,4.7,3.3,0.2,0.4,0.2
Stax SR-L300 LTD|5.6,5.6,-1.1,-1.1,-1.2,-0.9,-0.1,-0.1,-1.6,-4.6,-3,3.3,3.7,-0.8,-0.4,-0.5
Stax SR-L300 LTD (L700 earpads)|5.4,3.7,1.5,0,-0.6,-1,-0.5,-0.5,-2.4,-5.6,0.5,1.7,3.9,-1.4,-0.3,0
Stax SR-L500|5.4,5.2,2.3,0.9,-0.6,-1.1,-0.5,-0.5,-1.6,-4,-3.1,2.7,2.4,-1.2,0.1,-0.9
Stax SR-L500 (blu-tac mod)|5.3,5,3.6,2.5,0.3,-1,-0.8,-1.1,-2.8,-6.4,-4.1,3.1,3.6,-2.1,1.2,-0.5
Stax SR-L500 (Vesper earpads)|5.6,5.6,0.5,-1.2,-0.8,-0.6,0.1,0.1,-2.3,-5.9,-2.4,2.4,-0.7,-0.8,2.8,-0.5
Stax SR-L700|5.5,4.1,1,0.3,-0.6,-1.3,-0.8,-1,-2.3,-5.2,-1.1,2.3,2.8,-0.8,0.6,-0.7
Stax SR-L700 (blu-tac mod)|5.3,5.3,3.5,1.8,0.1,-1.2,-0.9,-1.5,-2.9,-7,-3.2,2.9,2.4,-2.1,0.9,-0.7
Stax SR-Lambda Pro|6,6,-3.3,-1.9,-0.2,0,1,1.1,0.4,-1.7,-3.5,-1.5,-0.3,2.1,1.2,-1.9
Stax SR-Lambda Pro Classic|4.9,4.6,3.6,2.5,0.6,-0.4,0.2,-0.3,-0.8,-2.4,-4.5,-2.3,0.7,1.5,-0.3,-1.8
Stax SR-Lambda Signature|4.4,-0.7,1.1,1.3,0.9,0.3,0.8,0.8,0.2,-1.4,-3,2.8,-2.8,-0.2,-1.8,-1.4
Stax SR-Sigma 07381|5.6,5.6,2.1,-1.1,-0.6,-5.4,-3.2,-1.7,-3,-2.7,3.6,5.4,1,2.3,-0.4,-3.2
Stax SR-X Mk2|4.8,4.8,4.8,2.2,-0.9,-2.3,-1.9,-2,-2.2,-3.2,1.5,-3,-3.2,2.1,0.6,0.8
Stax SR-X Mk3 Pro|5,4.2,3,2,0.4,-0.7,-0.2,-0.9,-0.7,-1.4,1.4,-0.1,-6.9,-1.3,-1.9,-0.1
Stealthsonics U2|-2.6,-2.8,-3.5,-4.7,-5.8,-4.5,-2.3,0.3,3.3,3.7,1.9,6.2,5.7,0.4,-0.1,6.2
Stealthsonics U4|-1.4,-1.7,-2.8,-4.4,-6.4,-6.4,-4.9,-2.5,0.9,2.8,3,5.8,5.6,3.3,4.3,5.8
Stealthsonics U9|-0.7,-0.1,0.2,-0.4,-1.6,-1.6,-1.3,-1.2,-2.2,-3.9,0.2,2.9,4.9,4.5,5.6,3.8
Stealthsonics U9 JDM|0.3,0.6,0.5,-0.3,-1.2,-0.9,-0.1,0.6,1.4,0.4,-2.6,1.6,6.1,3,3.8,0
SteelSeries Arctis 1|2.7,2.1,-0.6,-2.1,-3.5,-3.6,-1.5,-0.9,0.9,3.2,3.1,4.2,3.2,-1.1,-3,-3
SteelSeries Arctis 1 Wireless|5.4,2.6,-0.7,-2.3,-4,-3.3,-1.3,0.2,-0.4,1.3,0.9,3.4,1.4,1.9,-2.6,-3.7
SteelSeries Arctis 3 2019 Edition|5,5,3.9,2,-1.2,-3.7,-2.5,-1,1.2,1.8,2.8,-0.1,1.7,-1.8,-5.4,-4.5
SteelSeries Arctis 5 2019 Edition|3.2,2.1,-1.1,-2.6,-4,-3.7,-1.4,0.6,1.2,1.9,3.5,2.4,0.1,3.4,-1,-2.8
SteelSeries Arctis 7|4.4,3.4,-0.2,-1.9,-3.4,-3.1,-1.5,0.3,2.3,2.6,0.8,-1,0.2,0.7,-1.9,-2.4
SteelSeries Arctis 7 2019 Edition|5.3,3,-0.1,-1.4,-2.9,-2.7,-0.5,-0.2,1.8,2.5,2.8,1.4,3.3,0.8,-5.8,-5.6
SteelSeries Arctis 7+|2.8,1.8,-1.3,-2.9,-4.7,-4.2,-1.1,1.3,4,3.1,2.6,3.2,2,1.4,-1.9,-2.9
SteelSeries Arctis 7P|3.2,1.8,-1.7,-3.1,-4.6,-4.2,-0.4,0.9,3.6,3,2.8,2.2,3.6,2.3,-3.1,-3.3
SteelSeries Arctis 7P+|2.4,1.2,-1.8,-3,-4.1,-3.5,-0.1,1.1,3.5,3,3.3,3.7,3,2,-4.6,-4
SteelSeries Arctis 7X|5.3,2.5,-0.5,-1.7,-3.3,-3.3,-0.8,0.7,2.3,2.7,4.1,1.7,5.8,-0.8,-6.3,-5.6
SteelSeries Arctis 9|5.8,3.3,-0.8,-2.6,-4,-3.2,-1.2,-0.1,-0.4,0.5,-0.4,2,5.8,0,-1.6,-3.3
SteelSeries Arctis 9X|5.2,4,1,-0.5,-2,-1.7,-0.8,-0.2,-0.5,0,1.4,2.3,0.5,-0.6,-3.3,-3.2
SteelSeries Arctis Nova 1|0.6,0.6,0.3,-1.7,-3.4,-3.2,0,2.6,0,0.2,-0.6,0.5,6,2.4,-4.4,-2.4
SteelSeries Arctis Nova 3|1.9,1.7,-0.2,-1.8,-3.3,-3.1,-0.8,0.5,1.9,0.5,1.1,-1.5,4.1,3.5,-3.7,-3.1
SteelSeries Arctis Nova 7|-2,-1.6,-1.5,-1,-0.8,1.4,1.7,1.9,-0.1,0.1,1,0.1,6.1,3.4,-5.4,-4.3
SteelSeries Arctis Nova Pro|1.2,3,2.4,1.1,-0.4,0.2,1.6,1.5,0.3,-0.5,-1.3,-4.3,5,5.4,-5.9,-6
SteelSeries Arctis Nova Pro Wireless|1.2,3.3,0.1,-0.5,-0.6,1.4,2.6,1.4,-1.6,-4,-2,-1,6.2,6.2,-5.5,-6
SteelSeries Arctis Prime|2.8,3.5,2.3,1.5,-0.3,-1.7,-1,-0.6,0.4,2.4,4.1,1.1,4.3,-5.2,-5.8,-2.7
SteelSeries Arctis Pro GameDAC|0.5,2.4,1.7,1,0,-1.2,-1.3,-1.3,0,0.8,2.8,0.7,5.8,-1.6,-1.4,-0.3
SteelSeries Arctis Pro Wireless|2.3,2.6,0.9,0.4,-0.3,0.2,1.5,1.1,0,1,0.6,-2.9,2.1,0.4,-1.5,-2
SteelSeries Flux In-Ear|-0.6,-0.7,-1.4,-2.3,-3.3,-2.4,-0.9,0.5,2.3,2,1.9,0.9,1.5,4.3,5.1,1.6
SteelSeries Flux InEar Pro|1.9,1.6,0.3,-1.5,-3.7,-4.6,-3.9,-3.2,-1.5,-1.9,-1.6,-0.5,0.5,1.2,4.8,4.8
SteelSeries Siberia 200|0.2,-0.5,-1,-1.2,-2,-2.8,-1.3,0.2,4.5,2,2.3,-1.6,2.3,1.9,-3.6,-0.9
SteelSeries Tusq|-3,-2.9,-3.2,-4,-4.5,-3,-0.7,2.4,6.1,6.3,7.3,7.5,3.9,1,-3.4,-2.4
StereoPravda SB7|4.2,4.2,4.2,4.1,0.8,-1.3,-1.7,-1.6,-1.4,-2.1,-3.1,-6.4,0.6,-1.3,-2.9,1.1
Sterling TE-400|4.7,4.7,-0.5,-4.7,-5.5,-5.2,-3.7,-3.6,4,-3.3,1.5,3.4,0.3,-2.6,-0.7,1.8
Steven Slate Audio VSX (passive plugin inactive))|2.2,1.4,-0.4,-3.4,-5.1,-1.8,0.5,-2,-2.7,-2.8,-1.5,-0.2,4,3.7,4.7,1.9
Stylish|-2.6,-3,-3.3,-4.3,-6,-5.6,-3.9,-1.6,1.6,3.3,3,3.6,2,-0.6,5.1,6
Superlux HD 651|4.3,4.3,4.3,3.8,0.3,-3.5,-6.1,-4.7,-3.2,-1.8,-3.1,-0.8,-0.8,-4.8,3.2,4.3
Superlux HD 660|1.6,-0.2,2.3,3.2,0.6,-0.5,-0.1,1.8,0.5,-0.4,-1,0.6,5,-2,-5.3,-4.2
Superlux HD 660 Pro 150 Ohm|-0.8,-2.3,-1,2.3,-3.2,1.8,0.6,3.6,1.1,0.9,-1.2,-1.3,1.7,3.3,0.7,-0.4
Superlux HD 662F|3.9,0.3,-0.4,0.7,-0.4,0.7,-0.6,1.1,-1.7,-3.2,-1.6,-0.8,1.5,-0.1,1.5,0.5
Superlux HD 662F (Brainwavz round XL pleather earpads)|0.9,-1.3,-2.8,-2.8,-0.9,2.7,1.9,3.5,0.6,0.3,1.5,0.5,4,1.5,-1.4,-1.2
Superlux HD 668B|5.1,3.5,-0.3,-1.4,-1.9,-1.5,-0.7,-0.2,-0.4,-0.3,0.2,-1.3,1.2,0,-2.4,-0.1
Superlux HD 668B (velour earpads)|5.5,5.2,1.4,-0.1,0.1,0.7,1.8,2.9,1.8,-0.9,-1,-3.7,-0.3,-2.7,-4.2,-3.4
Superlux HD 681|5.2,4.5,3.4,2.9,1.4,0.4,0.7,0.6,-1.1,-2.4,-1.2,-3.1,0.2,-6.3,-4,-2.8
Superlux HD 681 (velour earpads)|5.4,3.2,2.6,2.1,1.3,1,1.9,2.5,1,-1.3,-0.5,-3.4,0.5,-7.7,-3.5,-3.6
Superlux HD 681 EVO|-1.5,-2.2,-1.8,-1.5,-0.4,1.2,2.9,3.6,2.8,2.5,1.6,-1.5,3.5,2.7,-4.6,-4.6
Superlux Hd 681B (Brainwavz round hybrid earpads)|4.1,1.1,-1.2,-2.5,-1.6,-0.2,2,4.1,4.8,4.9,2.8,-3.5,-3,-4.3,-1.9,-1.2
Superlux HD 681B (pleather earpads)|2.5,-0.7,-2.8,-2.4,-1.8,0.1,0.4,0.8,1.2,1.9,2.7,-0.5,2.5,0,0,-0.2
Superlux HD 681B (velour earpads)|5.9,2.7,0.2,-0.6,0.2,0.5,2.2,4,2.4,0.2,0.6,-3.2,-0.3,-3.7,-3.1,-3.1
Swimbuds Waterproof Headphones|-4.8,-4.2,-4.2,-5.1,-6.2,-5.7,-4.5,-2.1,-1.8,-4.8,-0.4,6.7,8.4,8.4,8.4,8.4
Symphonium Audio Aurora|0.7,0.1,-1.4,-3,-5.2,-5.5,-4.5,-2.9,-1,-0.6,-0.3,2.3,4.7,3.1,2.9,5.1
Symphonium Audio Helios|0,1.2,2.3,2.3,1.7,1.5,0.9,-0.3,-0.5,-0.9,-0.8,-0.1,2,2,1.5,-1.5
Symphonium Audio Helios SE|-1.8,-0.5,0.7,0.8,0.7,1.2,1,0,0,0.2,-0.4,2.2,4.3,3.8,0.9,-3
Symphonium Audio Meteor|-2.8,-2.7,-2.7,-3.2,-3.3,-1.9,-1.2,-0.8,0.6,1.2,2.1,4,7.7,7.8,6.8,0.1
Symphonium Audio Meteor (AZLA eartips)|-1.7,-1.6,-1.7,-2.3,-2.5,-0.7,0.3,1,1.9,1.7,2.2,2.1,5.9,7.8,4.2,-2.2
Symphonium Audio Meteor (Comply eartips)|-1.5,-1.6,-1.9,-2.5,-2.8,-1.2,-0.2,0.3,1.2,0.9,1.5,1.2,4.9,7.2,4.4,-0.4
Symphonium Audio Meteor (JVC Spiral Dot eartips)|-2,-1.9,-2,-2.6,-2.7,-1.2,-0.1,0.3,1.3,1,1.5,1.7,6.1,7.6,5.4,-0.1
Symphonium Audio Meteor (SpinFit CP145 eartips)|-1.6,-1.4,-1.6,-2.4,-2.4,-1.2,0,0.7,1.6,1.3,1.9,1.5,5.4,7.7,4.4,-2.2
Symphonium Audio Mirage|2.8,2,0.4,-1.6,-4.3,-5.5,-5.3,-4.4,-2.9,-2.8,-3.7,-1,4.7,3.9,4.7,4.7
Symphonium Audio Triton|-0.1,0.6,0.9,0,-1.4,-1.9,-1.5,-1,-0.1,-0.6,-1.5,1.2,4.9,3.2,2.4,1
Symphonized NRG 3.0|-5.3,-5.3,-5.6,-6.4,-6.9,-4.7,-2.5,1.2,3.4,3.2,4.6,6.8,7.3,5.2,3,5.9
Syun ME1 Gold|4.6,3.2,0.9,-1.4,-4.4,-6,-5.5,-4.2,-1.9,-0.6,0.1,1.3,0.3,-3.5,4.1,3.2
Syun Mix1 Gold|-1.3,-2.4,-4,-5.6,-7.3,-6.8,-5,-2.8,0.2,2.4,3.3,4.7,2.8,4.1,6.5,5.7
T+A Solitaire P|2.7,2.3,1,-0.2,-2,-3.5,-2.9,-2.4,-1.6,-1.3,1.7,1.2,-0.2,1.6,3.3,1.1
T+A Solitaire PSE|4.8,3,0.4,-0.4,-2.1,-3.3,-2.5,-1.4,-1.5,-0.4,2.6,3.3,4.9,4.6,-1.5,-4.7
T-Peos Altone 200 2014|0.5,-0.5,-1.8,-3.1,-4.1,-3,-1,0.9,3.1,2.9,1.8,0.4,-1.4,5,-2.1,-3.1
T-Peos H-100|-1.1,0.3,1.3,1.1,0.6,1,1.8,2,2.2,0.2,-2,-4.7,-1.7,5.7,-0.6,-4.4
T-Peos Popular|-3.6,-2.9,-2.4,-2.6,-2.7,-1.1,0.5,1.6,2.6,1.1,-0.9,0,5,-2,-0.2,4.9
T-Peos Rich 200|-0.1,-0.4,-1.2,-2.5,-3.9,-3.2,-1.7,-0.1,1.6,1,0.1,1,3.9,-5,-1.8,5.3
T-Peos Spider|-3.6,-3.3,-3.4,-4,-4.3,-2.7,-0.8,0.8,2.6,1.9,1.1,2.3,5.8,-2.9,-0.2,6.3
T-Peos Tank|-4.1,-3.6,-3.3,-3.6,-3.7,-2,0,1.5,3.1,2.3,0.3,1.9,5.8,-3.1,-0.7,6
T-Peos U200R|-2.1,-2.1,-2.6,-3.7,-5,-4.6,-3.2,-1.7,0.5,0.4,0.6,4.2,5.8,-2.7,2.6,5.9
TAGO STUDIO T3-02|0.1,0.4,0,-1.2,-3,-3.4,-2.9,-2.1,-1.8,-3,-2.8,3.8,5.5,5.5,0.6,2.6
Takstar Pro 80|2.1,1.3,0.3,-0.4,-2.4,-2.7,-0.8,0.5,-0.6,-1.9,-3.1,0.5,4,0.6,-0.2,-1.5
Takstar Pro 82|1.6,2,1.3,-2.5,-1.3,1,1.1,1.2,0.2,-1.5,-1.5,-1.9,2.5,3.6,2.3,-1.6
Tanchjim Cora|-2,-1.4,-1.2,-1.7,-2.5,-1.9,-0.9,0.2,1.2,0.9,-0.3,-0.8,0.3,3.3,5.5,3.1
Tanchjim Darling|1.7,1.7,1,-0.2,-1.5,-1.1,-0.2,0.9,1.6,0.5,0,-1.3,-2.3,2.2,-0.3,-4.1
Tanchjim Echo|3.1,3.1,1.4,-0.3,-1.1,0.8,0.1,0,0.6,-0.4,-2.7,-3.2,-2.1,-3.5,-0.7,0.8
Tanchjim Echo (Tanchjim Echo filter)|-0.2,0.5,-0.1,-1.1,-0.9,1.7,1.5,1.7,1.9,0.7,-0.1,0.5,-0.3,-0.2,-1.3,-2.2
Tanchjim Hana|1.3,1.2,0.7,-0.2,-1.6,-1.2,-0.3,0.6,1.4,0.2,-2.6,-3.5,1.8,-2,2.8,2
Tanchjim Hana (AZLA Sedna Light eartips)|1.4,1.1,0.6,-0.5,-1.8,-1,0.1,1.2,2.1,1.1,-0.6,-1.3,2.6,-0.8,-0.8,-2.2
Tanchjim Hana (Final Audio Type E eartips)|0.9,0.6,-0.1,-1.2,-2.3,-1.8,-0.7,0.4,1.3,0.1,-1.9,-0.5,1.4,-1.1,1,-0.3
Tanchjim HANA 2021|-0.6,-0.5,-0.6,-1.3,-2.1,-1.5,-0.4,0.8,1.7,0.9,-1.6,-1.7,1.3,0,4.4,3.1
Tanchjim Kara|0.9,0.4,-0.7,-1.9,-2.6,-0.4,0.8,1,1.1,0.5,0.2,2.8,3,1.9,0.8,-0.9
Tanchjim Ola|4.7,4.7,3,0.9,-1.8,-3,-2.5,-1.7,-0.4,-1.4,-4.3,-1.8,2.2,0.3,1.4,0.9
Tanchjim Ola (no filter)|4.4,4.4,4.4,2.3,-0.9,-3.1,-3.7,-3.5,-1.8,-1.5,-3.5,-1,4.4,2.2,-2.5,0.3
Tanchjim One|-0.8,-1.1,-2,-3.1,-4.3,-3.3,-1.8,0,1.7,1.5,1.6,2.6,3.1,2.4,4.1,1.1
Tanchjim ONE (3.5 mm cable)|0.8,0.4,-0.6,-1.7,-2.8,-1.9,-0.5,1.1,2.5,2,1.7,2.6,2.1,0.4,0.9,-4.3
Tanchjim One (USB)|0.8,0.8,0.1,-0.8,-0.9,-0.2,-0.6,-0.1,0.7,0.7,0.8,1.1,1.3,2.1,2.4,-5
Tanchjim One DSP|0.8,1,0.2,-0.7,-0.9,-0.2,-0.3,-0.1,0.6,0.4,0.7,0,0.7,2.4,3.7,-0.7
Tanchjim Oxygen|1.8,1.4,0.6,-0.6,-2,-1.8,-0.8,0.2,1,0.2,-1.6,-1.3,1,0.5,2.7,0.3
Tanchjim Prism|-0.4,-0.1,-0.1,-0.7,-1.5,-1,0,1,1.7,0.7,-1.4,-1.9,-1,0.1,5.1,2.2
Tanchjim Prism (bass eartips)|-1.4,-1,-0.8,-1.1,-2.1,-1.3,-0.3,0.7,1.7,0.6,-0.4,0,-0.1,0.1,3.5,0.5
Tanchjim Prism (treble eartips)|-1.4,-0.9,-0.7,-1.2,-2.1,-1.3,-0.3,0.7,1.7,0.7,-0.1,-0.2,0.1,-0.3,3.7,1.3
Tanchjim Tanya|-0.4,-0.9,-1.9,-3,-4.1,-3.1,-1.3,0.8,3,3,2.1,1,-0.6,0.7,5.4,4.5
Tanchjim Tanya DSP|-1.8,-2.1,-2.2,-2.2,-1.1,1.3,1.5,1.6,2.1,1.5,1.4,1,1.2,3.1,0.1,-0.7
Tanchjim Zero|4.6,4.6,3.7,1.8,-1,-2.5,-2.4,-1.6,0.1,0.1,-1.7,-2.8,-3,-2.8,1.8,3.1
TANGZU Fudu|-2.4,-2,-1.9,-2.4,-2.7,-1.6,-0.3,1,1.7,0.5,-0.7,3.1,1.3,3.2,0.5,0.1
TANGZU Princess Chang Le|-3.4,-2.8,-2.3,-2.7,-3,-1.7,-0.4,1,2.1,1.1,0.6,0.2,0.2,3.1,4.2,5.2
TANGZU Shimin Li|-0.3,-0.3,-0.4,-1,-2,-0.9,-0.1,0.8,1.1,-0.3,-2.6,-3.8,-0.7,2.6,5.9,3
TANGZU Wan'er S.G|0.9,0.5,-0.1,-1.2,-2.4,-2.1,-1.1,-0.2,0.5,-0.4,-2.5,-1.8,1.1,2,4.8,2.7
TANGZU x HBB Zetian Wu Heyday Edition|-0.9,-0.3,0,-0.6,-1.4,-0.9,-0.1,0.8,1.4,0.6,-0.3,1.7,2.7,0.2,2.2,-0.3
TANGZU x SeeAudio Shimin Li Encounter Edition|0.4,0.5,0.1,-0.8,-2.2,-1.6,-0.5,0.5,1,0,-1.6,-1.5,0,4,3.4,-0.2
TANGZU Yuan Li Dark Force|-1,-0.4,-0.1,-0.6,-1.6,-1.4,-0.8,-0.1,0.5,-0.1,-0.2,1,-1.5,6,4.7,0.4
TANGZU Zetian Wu|-1.8,-1.1,-0.6,-0.9,-1.4,-0.5,0.6,1.5,2.3,1.5,-0.1,0.1,0.9,0.3,3.1,1.6
Tansio Mirai Land (off-off-off)|4.2,4.2,4.2,4.1,0.6,-1.9,-2.2,-2,-1.5,-2.1,-3.7,-3,-0.8,-4,-2.7,1.4
Tansio Mirai Land (off-off-on)|2.5,2.4,1.7,0.5,-1.1,-1.2,-0.4,0.5,1.2,0.1,-2,-2.4,-0.7,-3.8,-2.8,1.3
Tansio Mirai Land (off-on-off)|1,1.1,0.3,-0.8,-2.1,-1.6,-0.2,1.2,2.3,1.3,-1,-1.7,0.1,-3.1,-2.2,2
Tansio Mirai Land (on-off-off)|0.3,0.3,-0.4,-1.5,-2.8,-2,-0.3,1.5,3,2,-0.3,-1.1,0.5,-2.6,-2,2.4
Tansio Mirai Land (on-on-on)|-0.2,-0.1,-0.7,-1.9,-3.1,-2.2,-0.3,1.6,3.3,2.4,0,-0.9,0.7,-2.5,-1.7,2.5
Tansio Mirai Spark (off-on-off)|1.5,0.9,-0.3,-1.9,-3.7,-3.3,-2.1,-0.5,1,0.8,1.1,1.9,1.5,-3.6,-2.8,1.2
Tansio Mirai Spark (on-on-off)|0.8,0.2,-1.1,-2.6,-4.4,-3.9,-2.6,-0.8,0.9,1.1,1.7,2.7,2.1,-2.8,-1.5,2.6
Tansio Mirai Spark (on-on-on)|0.7,0.1,-1.1,-2.6,-4.5,-3.9,-2.5,-0.8,1,1.1,1.7,2.8,2.3,-2.6,-1.7,2.4
Tansio Mirai TSMR-12 (off-off-on)|2.3,1.7,0.3,-1.3,-3.3,-3.4,-2.5,-1,1.2,3.5,1.4,2.2,1.4,1.8,0,-1.9
Tansio Mirai TSMR-12 (off-on-off)|1,0.3,-1,-2.6,-4.6,-4.8,-3.7,-2.1,0.3,3,3.6,4,2.5,3.5,1.8,0
Tansio Mirai TSMR-12 (on-off-off)|0.3,-0.4,-1.7,-3.3,-5.3,-5.4,-4.3,-2.6,-0.1,2.7,5.3,5.1,3,4.3,2.6,0.9
Tansio Mirai TSMR-12 (on-off-on)|-0.2,-0.8,-1.9,-2.8,-4.2,-4.2,-2.4,-1.3,-0.1,1.7,6,5.1,1.3,0.2,-0.4,-1.1
Tansio Mirai TSMR-12 (on-on-off)|-0.4,-1,-2,-3,-3.7,-4.4,-2.4,-1.6,-0.3,1.7,6.1,5.2,1.4,0.2,-0.1,-0.6
Tansio Mirai TSMR-12 (on-on-on)|-0.5,-1.1,-2.3,-3.8,-5.7,-5.6,-4.5,-2.7,-0.1,2.9,5.3,5.5,3.6,5,3.3,1.6
Tansio Mirai TSMR-3 Pro (off-off-off)|6,6,6,6,6,6,6,4.3,0.9,-4,-9.7,-11.6,-7.6,-11.3,-8.7,-2.3
Tansio Mirai TSMR-3 Pro (off-off-on)|3.7,2.9,1.6,-0.2,-2.5,-3.3,-2.6,-1.4,0.2,-0.2,-2.7,-3.3,0.9,-3,-0.7,4.1
Tansio Mirai TSMR-3 Pro (off-on-off)|1.9,1.1,-0.2,-1.8,-4.1,-4.7,-3.8,-2.1,0.2,0.7,-0.9,-1.2,2.8,-0.8,1.4,4.8
Tansio Mirai TSMR-3 Pro (off-on-on)|1.2,0.5,-0.8,-2.5,-4.7,-5.1,-4.1,-2.3,0.2,1,-0.4,-0.5,3.5,0.5,2.1,5
Tansio Mirai TSMR-3 Pro (on-off-off)|0.9,0.2,-1.2,-2.8,-5,-5.4,-4.3,-2.4,0.3,1.3,0,-0.1,3.9,0.7,2.5,5.1
Tansio Mirai TSMR-3 Pro (on-off-on)|0.6,-0.2,-1.4,-3.1,-5.1,-5.5,-4.5,-2.4,0.3,1.4,0.2,0.1,4.2,1,2.8,5.1
Tansio Mirai TSMR-3 Pro (on-on-off)|0.4,-0.3,-1.6,-3.2,-5.4,-5.7,-4.5,-2.5,0.4,1.6,0.3,0.3,4.3,1.3,3,5.2
Tansio Mirai TSMR-3 Pro (on-on-on)|0.3,-0.4,-1.6,-3.3,-5.5,-5.7,-4.6,-2.5,0.4,1.6,0.5,0.4,4.4,1.2,3.1,5.2
Tansio Mirai TSMR-4 Pro (on-on-off)|-0.6,-0.7,-1.3,-2.4,-3.6,-3.6,-1.9,0.2,2.9,3.4,1.6,0.8,-0.1,-2.4,0.4,2.9
Tansio Mirai TSMR-5 (off-off-on)|4.3,4.3,3.3,1.6,-0.7,-1.3,-0.5,0.9,2.2,1.2,-2.6,-4.4,-4.5,-5.8,-3.5,-0.7
Tansio Mirai TSMR-5 (off-on-off)|3.3,2.5,1.2,-0.5,-2.6,-3,-2,-0.2,1.9,1.8,-1.2,-2.5,-2.6,-3.8,-1.4,1.5
Tansio Mirai TSMR-5 (on-off-off)|2,1.3,0,-1.6,-3.7,-3.9,-2.7,-0.7,1.8,2.4,0,-1.3,-1.2,-2.4,-0.1,2.9
Tansio Mirai TSMR-5 (on-on-on)|1.3,0.6,-0.6,-2.3,-4.3,-4.3,-3,-0.8,1.9,2.8,0.5,-0.6,-0.7,-1.6,0.7,3.7
Taobao 3 driver|4.1,4.1,2.8,0.5,-2.5,-3.9,-3.8,-3.2,-2.1,-2.4,-2.2,0.1,-0.9,-4.1,1.9,4.1
Taobao 6 driver|0.7,-0.2,-1.5,-3.3,-5.6,-5.7,-4.2,-1.3,2.2,1.6,1.2,2.8,4.2,4.7,2.5,3.6
TaoTronics SoundLiberty 79|-4.5,-3.1,-1.7,-1.2,0.3,1.5,2,3,4.2,2,3.7,3.8,1.2,3.6,-0.9,-2.6
TaoTronics SoundLiberty 92|4.9,4.9,0.8,-2,-4.3,-4.2,-3.8,-3.4,-3,-4.6,-4.2,-0.7,4.9,4.9,2.8,1.5
TaoTronics SoundLiberty 94|1.4,0.5,-2.1,-4.7,-5.3,-3.2,-2.7,-0.4,2.8,1.4,2,3.6,1.1,6.7,3.4,-3.4
TaoTronics SoundLiberty 95|5.2,5.2,2.6,0,-2.7,-2.5,-1.5,-3,-1.9,-4.9,-5.7,0.3,5.2,5.2,1.6,-1.9
TaoTronics SoundSurge 60|5.1,1.4,-3.1,-4.3,-5.2,-5,-3.3,-2.3,-3,-2.8,6.2,6.2,6.2,6,-1.6,-1.9
TaoTronics TT-BH041 Bluetooth Headset|4.4,4.4,4.4,3.6,-0.6,-4.1,-4.1,-5.6,-10.2,-9,-3,1.5,4.2,4.4,4.2,4.4
Tascam TH-02|3,2.3,1,-0.2,-2.8,-3.9,-3.2,-2.6,-4.7,-3.1,-3.5,4.2,5.4,5.4,-3.4,-2.5
TCL SOCL300|-0.7,-1.4,-2.4,-3.4,-4.3,-3,-1.2,0.5,1.9,1.3,0.1,0.2,4.8,3.2,0.6,1.1
TDK BA200|1.7,1.3,0.1,-1.7,-3.9,-4.7,-3.9,-2.9,-0.9,-0.9,-0.6,1.7,4,5.2,1.7,-0.9
TDK MT300|-4.4,-4.3,-4.6,-5.5,-6.1,-4.5,-2.2,0,1.9,1.5,4,7,2.4,-2.8,6.9,6.9
Teac CT-H02|2,1.7,0.8,0.3,-0.8,-3.5,-2.9,-3.1,-2.3,-2.2,-0.3,3.8,2.7,3.8,-2.4,-2.4
Technics EAH-AZ70W|-0.8,1,1.4,1,-0.2,0.8,0.5,-1.6,-2.3,-2.9,-3.4,1,4.5,3,-2.7,0.6
Technics EAH-AZ80|1.8,1.3,0.6,-0.2,-1.1,-1.2,-0.9,0.3,0.9,0.4,0.5,1,2.7,2.8,-2.5,-4.6
Tekfusion Ecoofers|-6,-5.7,-5.9,-6.4,-6.6,-4.6,-2,0.8,4.9,6.6,8.1,6,5.4,6.3,5,4
Tekfusion Twinwoofers|-5,-5.1,-5.7,-6.6,-7.2,-5.2,-2.5,0.6,2,2.8,5.7,7.7,6.8,0.9,4.8,4
Teknmotion London Underground|2.9,0.1,-2.4,-3.1,-3.4,-2.2,0.5,0.9,-4.1,-5.2,-3.2,4.1,2.7,5,-0.3,-1.4
TempoTec IM05|-1.7,-1.9,-2.5,-3.4,-4.3,-2.6,-0.5,1.7,3.2,2.6,3,-2.5,2.1,-4.8,2.6,3.1
TENHZ 3BA|2.9,2.2,0.7,-1.1,-3.5,-3.9,-2.8,-1.3,0.5,-0.5,-5.2,5.1,2.2,1.8,-2.7,0
TENHZ P4 Pro|3.2,2.6,1,-0.7,-3.2,-3.8,-3,-1.7,-0.5,-2.1,-4.4,4.5,1.5,1.7,2.5,-0.3
Teufel CAGE (active)|2.2,1.6,-0.7,-1.2,-5.1,-5.3,-1.2,-0.4,-2.6,-2.5,2.1,3.8,5.8,1.4,0.3,1.4
Teufel CAGE (passive)|1,0.9,-1,-1.6,-5.2,-4.9,-0.8,-0.3,-2.4,-2.8,2.2,4.4,6.1,2.1,1.5,2.6
Teufel Real Blue NC (ANC on)|-4.2,-2.8,-2.2,-1.3,-1,0.3,2.1,2.6,1.9,1.7,3.1,0.5,5.1,5.9,2,-0.2
Teufel Real Blue NC (ANC on, classic EQ)|-3.9,-2.9,-2.7,-1.8,-1.6,-0.1,2.1,3.2,3,2.6,3.2,2.4,4.1,5,1.2,-0.9
Teufel Real Blue NC (passive)|-3.8,-3.4,-4.7,-4.4,-6,1.4,2.9,1.9,1.3,1.6,4,3.5,4.6,5.4,-0.2,1.5
Teufel Real Z|1.5,-0.2,-1.2,-2.1,-3.8,-3.5,-1.6,0.9,4,6,3.8,-1.8,-3.1,-1.9,1.8,0.8
Teufel Zola (passive)|-1,-1.4,-4.4,-5.7,-7.7,-3.2,-0.1,1.3,0.5,-0.7,-0.9,0.5,6.1,6.1,3.5,6.1
Teufel Zola (USB)|-2.3,-2.9,-3.3,-3.8,-5.8,-2.2,0.5,1.5,0.7,-0.8,-1.4,-0.2,5.9,6.1,3.1,5.6
TForce Yuan Li|0.3,0.9,1,0.5,-0.6,-0.5,0,0.6,0.7,-0.8,-2.7,-3.1,-2.2,2.4,2.3,-0.8
TForce Yuan Li Dark Soul|0.3,1.1,1.6,1.4,1,1.9,3.2,4.2,4.5,1.7,-3.4,-6.8,-6.3,-1,5.2,-2.9
TFZ 1S|-2.7,-2.8,-3.2,-3.9,-4.7,-3.4,-1.9,-0.6,0.5,-0.9,-3.2,2.9,4.4,5.9,6.1,6.1
TFZ 2S|-2.1,-1.2,-0.6,-1,-1.5,-0.6,0.5,1.5,2.2,0.7,-2.2,-1.3,0.9,0.6,2.3,3.9
TFZ 4S|0.1,0.5,0.3,-0.5,-1.6,-1.4,-0.7,0,0.3,-1.3,-4.8,-2.8,0,0.9,1.8,4.9
TFZ 5S|-2.1,-2.2,-2.6,-3.6,-4.7,-3.3,-1.5,0.8,1.9,-0.6,-3.6,2.1,3.1,5,6.6,6.1
TFZ Balance 2M|-4.4,-3.9,-3.5,-3.8,-4.2,-2.6,-0.8,0.9,2.6,1.7,-1,0.6,6.3,0.5,6.9,6.9
TFZ Exclusive 1|-1.7,-1.7,-1.9,-2.5,-3.1,-1.7,-0.1,1.7,3,2.6,0.8,-1.1,1.2,-2.3,2.3,2.9
TFZ Exclusive 3|-3.5,-3.1,-2.8,-3.2,-3.4,-1.8,0.1,2.1,3.9,2.8,0.4,-1.6,3.9,-5.3,3.7,6.5
TFZ Exclusive 5|2.1,1.1,-0.3,-1.8,-3.4,-2.7,-1.1,0.6,2.1,1.2,-1.3,-3.5,2.6,-2.8,0.9,3.1
TFZ Force King|-1.1,-0.4,0,-0.4,-0.9,0.2,1.4,2.4,3,1.6,-0.9,-3.8,0.3,-5.1,-1.3,-0.2
TFZ Force1|-3.9,-3.1,-2.5,-2.7,-2.7,-0.5,1.7,4.1,6.4,3.7,1.3,-1.2,3.8,-2.7,-0.7,0.3
TFZ King Pro|-2.2,-1.5,-0.9,-1.2,-1.8,-0.9,0.1,1,1.8,0,-3.1,-1.7,3.7,0.3,1.2,5.6
TFZ Live 1|-2.2,-1.3,-0.5,-0.7,-1,0.3,1.6,2.8,3.5,2.1,-0.4,-2.8,1.3,-5,-0.3,1.9
TFZ Live 3|-3.8,-3.3,-3.1,-3.6,-3.9,-2.2,-0.2,1.7,3.3,2.5,1.1,1.9,2.9,2,2.6,2.3
TFZ Live X|-3.6,-3.3,-3.3,-4,-4.5,-2.7,-0.6,1.8,4.1,4.1,2.8,1.7,2.6,2.1,1.8,0.3
TFZ MY LOVE 4|5.1,4.3,2.7,0.7,-2.1,-3.2,-2.8,-1.5,0.7,1.3,0.9,0.6,-0.3,-1.6,-2.7,-4.1
TFZ My Love Edition|-4.9,-3.8,-2.7,-2.6,-2.3,-0.5,1.2,2.6,3.1,1.6,-0.6,1,1.1,3.5,0.8,1.5
TFZ MY LOVE III|-2.8,-2.6,-2.7,-3.2,-3.6,-2,-0.1,1.7,3.4,1.2,-0.8,-1.3,0.3,3.2,7.1,4.8
TFZ Mylove 2|-2.8,-1.8,-1.2,-1.5,-1.8,-0.8,0.4,1.6,2.3,1.2,-1.4,-1.7,1.7,-2.8,1.2,5.6
TFZ No.3|-2.2,-2.2,-2.6,-3.3,-4,-2.6,-0.7,1.2,2.7,1.5,0,-0.2,2.5,-1.2,1.3,5.9
TFZ S2 Pro|-0.6,-0.7,-1.1,-1.9,-2.8,-1.6,-0.3,1.1,2,0.6,-2.2,-2.5,1.4,-0.8,2.5,4.2
TFZ Secret Garden|0.8,0.6,0,-1.1,-2.3,-1.5,-0.4,0.9,2,0.6,-2.9,-4.6,0.8,-6.4,0.6,5
TFZ T2 Galaxy|-3.2,-2.4,-1.8,-1.9,-2.3,-0.9,0.7,2.1,3,1.5,-0.3,0.9,1.8,-0.8,0.6,3
TFZ T2 Pro|4.8,4.2,2.4,0.5,-2.2,-3.3,-2.6,-1.1,-0.1,0.1,0.5,0.6,2.2,-4.4,-4.6,-1.4
TFZ Tequila 1|-0.7,-1.4,-2.8,-4.4,-5.9,-5.1,-3.3,-1.1,1.3,2.1,1.6,1.7,3.6,-1.5,3.4,5.5
TFZ Tequila Pro|-2.5,-2.3,-2.3,-3,-3.5,-1.9,0,1.8,3.5,2.7,0.3,-1.2,1.9,-3.2,1.4,2.2
TFZ Ti Galaxy|-2.5,-1.8,-1.5,-2,-2.7,-1.6,-0.4,0.8,1.3,-0.1,-2.9,0.3,0.7,5.1,4.9,4.4
TFZ Warrior|-2.1,-2.6,-3.2,-3.7,-2.9,-3.4,-0.7,1.5,2,1.7,0.2,1.8,4.5,-0.2,3.1,3.3
TGXear Serratus|4.9,4.9,4.8,1.6,-1,-2.3,-1.3,-0.7,-0.1,0.9,-0.1,-5.5,-3.2,-2.1,-0.2,-3.7
TGXear Sunniva|4.2,4.2,4.2,0.9,-2.8,-1.9,-2.8,-1.6,-0.7,0.5,-0.7,-6.4,-2.5,-3.8,0.6,3
TGXear Totem|4.8,4.8,4.8,4.8,0.6,-0.6,0.4,-0.9,-0.4,0.4,-0.3,-4.4,-5.5,-4.4,-0.8,-2.5
Thermaltake Isurus|3.3,2.7,1.4,-0.5,-3,-4.4,-4.1,-2.4,-0.1,1.6,2.6,2.8,0.5,0.6,-0.3,0.2
ThieAudio Clairvoyance|-1.1,-0.5,-0.1,-0.4,-0.8,-0.6,-0.7,-0.4,0.6,0.7,-0.3,-0.9,1.8,3.9,3.8,1.6
ThieAudio Clairvoyance (100 ohm resistor)|-6,-4.7,-3.6,-3.3,-2.6,-1.1,-0.4,0.5,1.8,2,1.2,0.8,4.1,7.2,7.7,5.6
ThieAudio Elixir|0.4,0.5,0.3,-0.5,-1.7,-1.6,-1,-0.3,0.1,-0.9,-3,-1.7,1.6,1.4,3.5,1.7
ThieAudio Excalibur|0.2,0.2,0.1,-0.9,-1.9,-1.2,0.1,1.4,2.2,1.2,-0.2,0.7,3,-0.1,0.9,-1.5
ThieAudio Hype 2|-1.9,-1.6,-1.4,-1.5,-1.2,-0.6,-0.6,-0.4,0.6,0.9,2,3.1,2.3,1,0.7,-1.4
ThieAudio Legacy 2|0.9,1.2,0.9,-0.2,-1.9,-2.6,-2.3,-1.7,-0.6,-1.3,-3.1,-2.3,0.7,0,2,4.2
ThieAudio Legacy 3 (CIEM, off-off)|-2.4,-1.7,-1.2,-1.8,-2.7,-2.5,-1.7,-1,-0.4,-1.4,-4.5,1.3,2,4.3,4,5.7
ThieAudio Legacy 3 (CIEM, off-on)|-1.5,-1,-0.9,-1.6,-3,-3.3,-2.7,-2,-1,-1.5,-4.4,1.2,1.9,4.7,5,5.5
ThieAudio Legacy 3 (CIEM, on-off)|-2.3,-1.7,-1.3,-1.8,-2.7,-2.4,-1.7,-1,-0.2,-1.3,-4.5,1.3,2,4.7,4,5.6
ThieAudio Legacy 3 (CIEM, on-on)|-1.4,-1,-0.8,-1.6,-3,-3.2,-2.8,-2,-1,-1.6,-4.4,1.1,1.9,4.6,5,5.5
ThieAudio Legacy 3 (off-off)|-1.4,-0.9,-0.9,-1.5,-2.4,-1.9,-1.1,-0.1,0.5,-0.2,-2.8,0.6,3,3,4.5,3.9
ThieAudio Legacy 3 (off-on)|-0.7,-0.5,-0.5,-1.4,-2.7,-2.8,-2.1,-1.1,-0.5,-0.6,-2.7,0.4,2.8,3.1,5.3,4.9
ThieAudio Legacy 3 (on-off)|-1.5,-0.9,-0.8,-1.5,-2.4,-1.9,-1.1,-0.1,0.5,-0.2,-2.8,0.6,3.1,3,4.4,3.8
ThieAudio Legacy 3 (on-on)|-0.8,-0.4,-0.5,-1.4,-2.7,-2.7,-2,-1.2,-0.4,-0.6,-2.7,0.5,2.8,3.1,5.2,4.8
ThieAudio Legacy 4 (off-off)|-1.2,-0.3,0.4,0,-0.9,-0.6,0.1,0.9,1.4,0.4,-1.9,-1.7,2.8,-1.4,2.4,3.5
ThieAudio Legacy 4 (off-on)|-0.1,0.6,0.9,0.2,-1.2,-1.5,-1.1,-0.3,0.4,-0.2,-1.9,-1.9,2.5,-1.1,3,4.3
ThieAudio Legacy 4 (on-off)|-1.1,-0.2,0.4,0,-0.8,-0.5,0.2,0.9,1.4,0.5,-1.9,-1.7,2.9,-1.3,2.4,3.5
ThieAudio Legacy 4 (on-on)|-0.1,0.6,0.9,0.2,-1.2,-1.5,-1.1,-0.3,0.3,-0.2,-1.9,-2,2.6,-1.1,3,4.3
ThieAudio Legacy 5|0.3,0.6,0.6,-0.3,-1.7,-2.3,-2.4,-2.6,-2.3,-2.6,-3.5,0.6,2.9,5,2.5,3.7
ThieAudio Legacy 9|-2.4,-1.9,-1.7,-2.1,-2.6,-1.4,0.1,1.5,2.8,1.9,-0.6,1.1,3.8,7.3,4.1,1
ThieAudio Monarch|-2.8,-1.4,0.4,1.2,1.4,1,0.9,1.2,1.5,0.3,-0.4,0.5,2,1.3,-1.5,-0.7
ThieAudio Monarch (100 Ohm resistor)|-7.5,-5,-1.6,0,0.7,0.1,0.4,1,1.9,1.1,-0.7,1.3,5.8,5.7,5,3.4
ThieAudio Monarch (20 Ohm resistor)|-5.1,-3.3,-0.6,0.7,1,0,0,0.5,1.4,0.5,-1.4,0.4,4.6,4.4,3.8,2.4
ThieAudio Monarch MKII|-0.1,0.1,0.6,0.4,-0.1,-0.5,-0.7,-0.5,0.3,-0.1,-1,-1.1,-0.3,0.2,3.3,1.3
ThieAudio Monarch MKII (JVC Spring eartips)|-1.5,-0.7,0,-0.2,-0.6,-0.7,-0.4,0,0.9,0.6,0.5,1,1.9,1.3,3,-0.1
ThieAudio Monarch MKIII|0.6,0.7,0.5,0,-0.1,0.7,0.9,1.4,1.7,0.9,0.5,0.8,1.4,0.8,0.6,-3
ThieAudio Oracle|0.1,0.2,0,-0.8,-1.7,-1.1,-0.5,-0.1,0.6,0.4,0.1,1.6,0.1,3.2,3.2,0
ThieAudio Oracle MKII|-1.5,-1.2,-0.9,-0.9,-0.6,0.4,0.8,1.4,2.5,2.6,2.7,2.8,-0.2,2.1,-1,-3.4
ThieAudio Prestige|-0.9,-0.8,-0.7,-0.4,1.1,2.3,2.9,2.4,1.4,0.9,0.9,1.1,1.6,0.2,-0.7,-3.8
ThieAudio Prestige LTD|-0.6,-0.3,-0.3,-0.5,-0.3,0.3,0,0.1,0.9,0.2,0.4,1.4,2.9,4,2.7,-2.6
ThieAudio V16 Divinity|-0.3,-0.5,-0.9,-1.6,-1.8,-0.2,0.1,0.1,0.6,0.1,-1.4,-0.4,1.3,4,3.5,1
ThieAudio Voyager 14 (off-off)|0,0.1,-0.2,-0.9,-1.5,0.1,1.8,2.8,1.3,-2.5,-4.9,-1.3,1.3,3.9,3,1.1
ThieAudio Voyager 14 (off-on)|0.9,0.7,0.1,-1,-2.5,-2.2,-1,0.6,1.1,-1.9,-4.4,-0.9,1.8,4.6,4,2.1
ThieAudio Voyager 14 (on-off)|-2.4,-2.4,-2.5,-3,-3.3,-0.8,2,4.7,3.5,-0.8,-3.5,0,2.7,5.1,4.3,2.4
ThieAudio Voyager 14 (on-on)|-1.5,-1.6,-1.9,-2.7,-3.5,-2.3,-0.4,1.8,2.7,-0.5,-3.2,0.2,2.9,5.7,5.1,3.1
ThieAudio Voyager 3 (on-off)|-1.5,-1.7,-2.3,-3.4,-5.7,-5.2,-3.9,-1.8,0.6,1.8,4,6.6,6.7,6.7,-1.6,-2.6
ThieAudio Wraith|6,3.5,-0.6,-2.2,-3.3,-3.3,-1,-1.3,-1.2,-4.6,-0.4,3.1,4.5,4.5,3,0
Thinksoud RAIN|-3.7,-4,-4.9,-6.1,-7.5,-6.3,-4.3,-1.7,1.3,3.9,6.5,6.3,5.2,-1.9,7,7
Thinksound On1|-1.4,-2.4,-3.6,-3.3,-1.7,2.4,1.7,0.2,-0.3,-0.8,1.3,4.7,0.6,4.2,-0.2,-2
Thinksound Rain2|-2.1,-2.4,-3.4,-4.7,-5.9,-4.9,-2.9,-0.6,2.9,4.9,6.1,7.1,4.7,1.4,1.8,3.4
Thinksound Rain3|-2,-2.4,-2.8,-3.7,-4.5,-2.8,-0.8,1.9,5,6.4,6,5.7,-0.4,-3.5,-0.3,0.5
Thinksound ts01|-1.6,-1.9,-2.9,-4.3,-5.9,-5.5,-3.7,-1.6,1.7,3.1,5.6,5.9,3.9,3.8,2.7,4.6
Thinksound ts02|-1.3,-1.6,-2.7,-4.2,-6,-5.4,-3.6,-1.3,1.7,3.3,6.1,6.3,3.9,3.2,0.6,4.5
Tiandirenhe TD1|-5.4,-4.3,-3.4,-3.3,-3.3,-1.2,1,3.6,5.6,2.5,0.3,-3.1,0.6,1.7,4.9,4.6
Tiandirenhe TD15|-6.2,-5,-4,-3.9,-3.6,-1.2,1.3,3.9,7,3.5,2.5,0.1,3.3,-0.2,1.8,2
Tiandirenhe TD19|-3,-2.4,-2.3,-2.8,-3.2,-1.7,-0.1,1.7,2.8,1.5,-0.4,1,1.4,4.4,0.7,-0.5
Timsok TS-1024|2.8,1.8,0,-1.2,-3.3,-4.9,-3.2,-0.3,-1.7,-0.1,2.9,2,4.1,6,-0.4,-0.3
Timsok TS-316|-1.3,-0.8,-0.7,-1.3,-1.8,-0.8,0.4,1.6,2.2,1,0.6,1.7,2.2,-2.4,-0.8,-1.6
Tin HiFi C0|2.1,1.7,0.8,-0.5,-2,-2,-1.2,-0.3,0.5,-0.2,-0.9,0.4,0.9,1.2,2,-1.4
Tin HiFi C2|0.4,0.2,-0.4,-1.4,-2.4,-1.7,-0.5,0.8,1.9,1.4,1,1.6,1.5,0.6,1.1,-1.7
Tin HiFi C3|-1.7,-1.4,-1.3,-1.7,-2.2,-1.2,0.2,1.4,1.1,0.3,-0.2,0.8,0.3,1.3,3.1,0.8
Tin HiFi C5|-1.8,-1.3,-1.1,-1.6,-2.2,-1.4,-0.4,0.7,1.3,-0.3,-2.1,-1,0.4,5,2.5,3.3
Tin HiFi P1|4.6,3.1,1,-0.9,-3,-3.6,-3.1,-2.4,-1.6,-1.9,-1.7,2.8,2.7,3.2,6,2.6
Tin HiFi P1 (front vent sealed)|1.6,1.2,0.2,-1.1,-2.7,-3,-2.3,-1.5,-0.7,-1.1,-0.7,3.5,3,3.7,6.6,3.8
Tin HiFi P1 Max|2.5,1.8,0.8,-0.6,-2.3,-1.9,-0.5,0.1,-0.7,-1.6,-1.8,1.2,3.9,4.2,-0.2,-2.3
Tin HiFi P1 Plus|3,2.5,1.6,0.3,-1.7,-2.6,-2.4,-2,-1.4,-1.8,-1.5,3.7,4.7,2.8,5.8,-0.6
Tin HiFi P2|1.3,0.9,-0.1,-1.3,-2.8,-2.4,-1.3,0,1.3,0.8,0,4,6.9,7,4.2,-4
Tin HiFi P2 Plus Commemorative Edition|0.6,-0.1,-1.2,-2.6,-4.1,-3.4,-1.9,-0.2,1.8,2.2,1.9,6.2,7.7,7.7,4.2,-3.6
Tin HiFi T1|-0.3,-0.3,-0.8,-1.7,-3.2,-3.2,-2.6,-1.9,-1.4,-1.8,-2.5,1.7,5.3,1.1,5.5,5.7
Tin HiFi T1 Plus|-0.1,-0.9,-2.1,-3.5,-4.9,-3.9,-2.3,-0.3,1.6,1.2,-0.6,-0.6,1.8,0.8,5.9,5.3
Tin HiFi T2|5.1,4.1,2.1,0,-2.7,-4,-3.7,-2.9,-1.4,-0.8,1.1,2.5,1.4,-1.7,0.1,-1.4
Tin HiFi T2 (foam eartips)|5,4.3,2.5,0.4,-2.4,-3.7,-3.4,-2.6,-1.3,-0.8,0.6,1.2,2.7,-0.7,-2.8,-4.6
Tin HiFi T2 (front vent mod)|-4.1,-3.4,-3,-3.3,-3.7,-2.6,-1,0.6,2.1,2.5,4,4.3,4.5,1.4,2.4,1.8
Tin HiFi T2 (galaxy mod)|-2.2,-2,-2.1,-2.8,-3.4,-2,-0.2,1.6,2.9,1.6,-1,-1.1,2.1,-0.9,1.7,2.5
Tin HiFi T2 DLC|1.6,1.2,0.5,-0.6,-1.9,-1.6,-0.7,0.3,1.2,0.5,-0.4,0.4,0.1,-1.3,3.1,0.2
Tin HiFi T2 DLC 2022|1.6,1.2,0.4,-0.7,-2,-1.6,-0.7,0.3,1,0.3,-0.8,-0.2,-0.7,-1,2.6,0.7
Tin HiFi T2 Evo|5.1,4.7,3.3,1.5,-0.9,-1.3,-0.5,0.6,1.9,0.7,-0.5,0,-2.6,-5.6,-1.1,-0.7
Tin HiFi T2 Plus|2.3,1.9,0.8,-0.8,-2.8,-2.7,-1.6,-0.2,0.9,-0.5,-2,1.9,2.6,0.5,-1.3,-2.6""",
"""Tin HiFi T2 Plus (AZLA Sedna Light Short eartips)|1.8,1.3,0.1,-1.5,-3.5,-3.4,-2.2,-0.7,0.5,-0.7,-1.6,2.8,3.8,1.4,-1.6,-1.8
Tin HiFi T2 Pro|4.7,4.7,4.5,2.1,-1.4,-3.5,-3.4,-2.3,-0.3,0.5,0.4,0.3,2.1,-1.9,-5.1,-3.4
Tin HiFi T2 Pro (Comply eartips with Waxguard)|4.9,4.9,3,0.9,-2,-3.4,-3.1,-2.1,-0.3,0.2,0.5,0.3,4.2,-1.5,-6.8,-5.4
Tin HiFi T2 Pro (white grill, Comply eartips with Waxguard)|4.4,3.6,2.2,0.3,-2.3,-3.2,-2.5,-1.1,0.7,1.2,1.4,1.7,3.6,-3.3,-8.1,-6.3
Tin HiFi T3|3.7,2.6,0.7,-1.2,-3.9,-4.7,-3.9,-2.6,-0.5,0,-0.6,-0.2,4.4,1,-1.6,-0.4
Tin HiFi T3 Plus|-0.1,-0.1,-0.4,-1.1,-1.9,-0.8,0.3,1.7,1.9,0.8,-0.9,-1,-0.1,1.4,3.7,1
Tin HiFi T4|1.9,1.5,0.7,-0.4,-1.9,-1.9,-1.1,-0.1,0.6,-0.4,-2.8,-0.6,3.1,-2.5,2,2.9
Tin HiFi T4 Plus|-0.3,-0.2,-0.7,-1.7,-2.8,-2,-0.6,0.8,2,1.5,0.9,1.5,0.6,-0.6,0.1,-2.4
Tin HiFi T5|0.7,0.2,-0.8,-2,-3.4,-2.7,-1.2,0.9,1.8,1.4,-0.3,-1.6,1.1,-4.2,0.7,2.9
Tin HiFi T5 (Comply eartips with Waxguard)|0.3,-0.1,-1,-2.3,-3.5,-2.6,-0.9,1.3,2.3,1.9,1.3,1.4,2.7,-4.2,-2.9,-0.5
Tin HiFi Tin Buds 3|1.5,0.8,-0.3,-1.2,-4.1,-3.2,-1.8,0.4,2.6,3.1,3.9,2.5,-1.9,-5,-3.8,-0.4
Tin HiFi TWS2000|2.5,1.7,0.5,-1.2,-3.3,-3.3,-2,0,2.2,2.5,1,0.2,2.6,-2.4,-1.7,-0.7
Tingker H6|2.5,2,0.6,-1,-3,-3.2,-2.3,-0.7,1.2,1.8,3.2,1.6,2,-3.1,0.2,-0.1
Tingker TK200|-4.7,-4.2,-3.7,-3.9,-4.2,-2.6,-0.4,1.7,3.5,3.4,3.3,6,8.2,8.3,1.9,-4.1
Tipsy Dunmer Pro|3.4,2.5,1,-0.8,-3,-3.3,-2,0.1,2.5,3.9,3.1,3.4,1.7,-3.6,-4,-4.2
Tipsy TTROMSO|-0.6,-1.1,-2.1,-3.1,-4,-3.1,-1.2,0.8,2.6,2.1,0.7,2.8,4.1,0,3.1,0.2
TKZK Ouranos|0.7,0.8,0.5,-0.4,-1.4,-0.5,0.3,1.2,1.1,0.2,-0.4,-0.2,0.3,0.4,1.2,-1.5
Toneking TK12s|-2.5,-2.8,-3.5,-4.5,-5.3,-3.6,-1.4,1.4,4.5,5.4,4.3,2.3,5.3,5.1,-4.6,-3.9
Toneking TO200|4.6,4.6,2,-1.4,-5.4,-6.6,-7.5,-7,-6,-5.1,-1.8,3.6,4.6,2.3,4.6,4.6
Torque t096z (bass boost filter)|-6.3,-5.6,-5.1,-5.3,-5.2,-3.3,-1.2,0.6,2.4,1.6,-0.4,1.7,4.4,7.3,7.8,7.8
Torque t096z (mid centric filter)|4.7,4.2,2.3,0.5,-2.2,-3.6,-3.2,-2.6,-1.4,-1.9,-1.3,1,-2.8,-1.2,0.3,-2
Torque t096z (neutral filter)|2.2,1.2,-0.4,-2.4,-4.9,-6,-5,-3.3,-0.9,-1.4,-3.3,-0.3,3.1,3.2,5.3,5.3
Torque t096z (treble boost filter)|1.7,1,-0.5,-2.1,-3.9,-3.7,-2.1,-0.2,2.1,1.9,1.8,-1.1,-3.9,-6.3,2.4,1.9
Torque t096z (U-shaped filter)|-5.2,-4.6,-4.1,-4.3,-4.2,-2.2,-0.1,1.8,4,3.7,3.2,-0.6,-0.6,-3,5.8,5.6
Torque t096z (warm tilt filter)|-1.4,-1.8,-2.8,-4,-5.1,-4,-2.1,-0.3,1.9,1.5,0.4,-2.1,1.7,0.1,5.5,6
Torque t103z (black filter)|-5.6,-5.7,-6.3,-7.1,-7.7,-6.2,-4,-1.8,1.3,1.5,1.6,4.2,7.9,8.7,8.7,8.7
Torque t103z (red filter)|5.2,5.1,1.9,-1.2,-5.7,-10,-10,-7.5,-3.3,-2.6,-2.1,1,4.6,5.2,5.2,5.2
Torque t103z (yellow filter)|-1.1,-2.3,-4,-5.6,-7,-6.2,-4.1,-1.9,1.5,2.3,3.9,4.1,1.3,0,6.5,5.8
Torque t402v (around-ear earpads, black vent)|1.8,-0.4,-2.3,-4.3,-6.7,-8.8,-5.6,2,5.8,2.3,-1,2.2,3.1,5.2,0.8,1.4
Torque t402v (around-ear earpads, blue vent)|-0.6,-2.5,-4.3,-5.3,-8.5,-11,-6.9,1,6.3,2.2,2.7,4.5,5,6.3,3,3.5
Torque t402v (around-ear earpads, red vent)|4.2,0.9,-1.8,-3.8,-6.5,-8.9,-8,-2,4.8,0.1,-1.1,0.7,0.9,4.2,2.1,3.4
Torque t402v (around-ear earpads, yellow vent)|-4.3,-5.1,-5.5,-8,-11,-10.5,-3.6,3.7,7.5,4.5,7.3,5.5,6.8,7.5,3.6,3.8
Torque t402v (on-ear earpads, black vent)|0.4,-1.5,-3.3,-4.8,-6.5,-6.3,-4,-1.1,5.7,0.7,-1.1,-0.2,-1.5,2.4,6.6,6.6
Torque t402v (on-ear earpads, blue vent)|-0.6,-2.3,-4.2,-5.5,-7.6,-7.7,-5,-2.2,5.4,1.2,1.6,1,-0.1,3.9,6.8,6.8
Torque t402v (on-ear earpads, red vent)|-0.2,-2,-4.1,-5.3,-7.3,-6.9,-4.5,-1.9,5.6,0.8,0.5,0.6,-0.5,3.7,6.9,6.9
Torque t402v (on-ear earpads, yellow vent)|-2.9,-3.8,-5.3,-6.4,-8.3,-7.3,-4.4,-1.8,6,1.5,3.7,1.9,1.3,5.3,7.7,7.7
Toshiba HR-810 Low Gain|5.2,5.2,5.2,3.5,-1.4,-3.6,-2.8,-2.5,-2.1,-2.3,-4.7,-4.8,-4.2,2.9,5.2,4.1
TOZO NC2|-2,-2,-2,-1.6,-0.9,0,1.8,3.9,5.3,2.3,2.4,4.1,3.2,1.5,-3,-5
TOZO NC7|-5.3,-2.7,0.5,1.1,0.9,1.2,1.8,2.7,5.8,4.7,5.1,3.8,3.4,2,-8.4,-7.7
TOZO NC9|-3.8,-2.6,-1.3,-1.1,-0.8,0.5,2.2,5,8.1,5,4.5,1.5,2,5.7,-3.2,-8.6
TOZO T10|-6,-6,-6.4,-6.4,-3.6,0.8,3.7,6.8,8.8,8.4,5.6,4.3,4.5,2,-3.7,-2.6
TOZO T12|-4.5,-2.8,-1.6,-1.4,-0.4,2.1,4.3,6,7.2,4.6,4.6,2.3,-1.2,-2.9,2,-2
TOZO T6|-3.6,-2.9,-2.3,-2.3,-1.6,0,1.9,4,5.8,3.2,2,-0.9,2.5,7.7,0.3,-4.2
Tralucent Audio 1+X plus|-0.3,0.9,1.5,0.7,-0.9,-1.6,-1.4,-0.7,-0.1,-0.2,-0.4,-3.8,-3.4,4.8,2.2,2.2
Tralucent Audio 1Plus1.2|0.2,-0.1,-1.2,-2.9,-4.9,-4.8,-3.3,-1,1.4,1,-1.4,-3.2,2.4,3.1,5.4,6
Tralucent Audio 1Plus2.3|1.3,0.6,-0.7,-2.3,-4.3,-3.9,-2.2,0.6,4.8,6.2,2.4,-0.3,1,2,5.1,-2.7
Tralucent Audio 1Plus5.2|2.6,1.9,0.6,-1.2,-3.5,-4.5,-3.8,-2.8,-1.4,-1.4,-0.5,0.5,-0.2,6.1,5.8,0.3
Tralucent Audio Audio 1Plus 2.2|4.6,4.6,4.6,4.4,-0.2,-3.9,-4.3,-3,-0.4,-1.1,-2.7,-1.5,4.3,1.5,-5,-4.7
Tranya T10|-5.5,-5,-4.6,-4.9,-4.3,0.1,2.7,4.4,5.5,2.2,0.4,-1.3,-1.1,3,7.4,4.9
TREBLAB X5|-3,-2.1,-1.5,-1.7,-1.8,-0.6,1.5,3.7,5.3,3.8,3.4,2.6,3.5,4.6,-1.4,-3.1
TREBLAB Z2|3.3,3.4,2.5,1.6,-0.2,-1,-0.6,1.1,-0.3,-0.9,-0.7,-2.2,0.5,2.3,-3,-4.5
TREBLAB Z7 Pro|-1.4,-1.8,-2.4,-3,-4.6,-1.4,1.3,5.5,4.1,1.8,-1.7,0.6,-0.7,2.7,-1.3,1.2
TRI i ONE|-2.6,-2.1,-1.8,-2.3,-2.6,-1.1,0.5,2,3.3,2.2,0.4,-3.4,1.5,-5.5,4,5
TRI i3|-2.9,-1.9,-1.1,-1.4,-2,-1.5,-0.8,0,2,4.5,-0.4,1.1,1.6,6.7,2.3,1.6
TRI i3 Pro|-2.9,-2.2,-1.7,-2,-2.7,-2.1,-0.9,0.2,1.3,1.6,2.9,2.4,5.2,7.3,0.7,-1.5
TRI i4|-1.6,-1.4,-1.3,-1.1,-2.3,-0.1,2.3,2.9,2.9,1.4,-1.5,-2.7,0.6,0,-1.6,0.3
TRI Kai|-1.9,-1.7,-1.7,-2.4,-3.1,-2.5,-1.5,-0.6,0,-1.1,-3.5,-1,2.3,5.2,4.8,5.7
TRI Meteor|-1.1,-0.2,0.4,0.1,-1.3,-0.9,-0.7,-0.1,0.6,-0.5,-1.6,-1,2.1,-1.1,0.3,1.4
TRI Starlight|2.2,1.9,1.3,0.3,-1,-0.4,0.5,1.3,1.8,0.9,-1.2,0.3,3.5,6.4,0.1,-7.3
TRI Starsea (off-off)|-1.1,-0.6,-0.3,-0.9,-2,-1.9,-1.2,-0.4,0.4,-0.4,-2.6,1.8,3.8,5,0.7,0.8
TRI Starsea (off-on)|-0.6,-0.2,-0.1,-0.9,-2.2,-2.4,-1.8,-0.9,0,-0.7,-2.6,1.7,3.5,5.5,1.4,1.3
TRI Starsea (on-off)|-1.1,-0.5,-0.3,-0.9,-2,-1.9,-1.2,-0.4,0.4,-0.4,-2.5,1.7,3.8,4.8,0.7,0.8
TRI Starsea (on-on)|-0.7,-0.2,-0.1,-0.9,-2.2,-2.3,-1.8,-1,0,-0.7,-2.4,1.7,3.6,5.2,1.4,1.5
TRI Starshine|3.2,2.4,1.2,-0.4,-2.6,-3.1,-2.2,-0.6,1.6,2.1,1.8,4.2,2.2,3.4,0.2,-5.8
Triple Driver|0.4,-0.4,-1.6,-2.9,-4.4,-3.3,-1.6,0.6,2.9,3,1.5,-0.2,1.2,0.4,6.2,3.5
Triple Driver LTNG|1.6,0.9,-0.3,-1.8,-3.4,-2.7,-1.1,0.7,2.3,1.9,1,-1.4,-0.6,-0.4,4.2,-1.8
Triple Driver Over Ear|-2.9,-3.4,-3,-3.3,-2,1.5,3.6,1.2,-1,-0.1,2.2,3.1,1.8,1.2,3.5,2.7
Tripowin Cencibel|-0.7,-0.6,-0.9,-1.6,-2.3,-1.2,0.3,1.9,3.2,2.8,0.8,-2.4,0.6,-2.8,2.9,0.7
Tripowin H1|2.7,3.6,1.8,-1.8,-3.8,-4.6,-3.7,-3.2,-1.4,-0.8,1.5,-0.8,-0.4,-4.4,4.2,4.7
Tripowin Kailua|-0.7,-0.9,-1.3,-2.1,-2.8,-1.5,-0.1,1.3,2.2,1.2,-0.3,2.5,4.3,4.7,0.5,-2.2
Tripowin Leá|2.2,2,1.3,0.1,-1.5,-1.7,-1.2,-0.5,0,-1.1,-2.9,-2.9,-1.6,0.1,5.3,3.9
Tripowin Olina|0.9,0.9,0.4,-0.5,-1.8,-1.1,0,1.1,1.9,0.5,-0.8,-1.3,0.1,-1.5,0.1,-0.9
Tripowin Olina (Tanchjim Tanya filter)|0.5,0.5,0.1,-0.8,-1.9,-1.1,0,1,1.9,0.8,-0.4,-0.6,0.9,-0.7,1.3,-0.2
Tripowin Olina SE|1.1,0.8,0.3,-0.8,-2.1,-1.8,-0.9,0,0.8,0.1,-1.8,-2,0.7,1,3.5,3.1
Tripowin Piccolo|0.4,0.4,-0.3,-1.6,-2.7,-2,-0.8,0.3,0.7,-0.9,-2,0.1,2.2,2.7,3.3,0.3
Tripowin Rhombus|-1.8,-1.5,-1.5,-2.2,-2.8,-1.6,-0.1,1.4,2.4,1.6,-0.2,-1.1,-0.9,1.1,-0.1,3.3
Tripowin TC-01|-1.2,-1.2,-1.5,-2.1,-2.8,-1.5,0.2,2.1,3.6,2.9,1,-2.4,0.7,-2.2,1.8,1.6
Tripowin TP10|2.6,2.1,1.1,-0.1,-1.5,-0.7,0.8,2,1.4,-2.8,-4.8,-6.6,-1.7,-6.4,0.8,4.2
Tripowin x HBB Mele|-2.3,-2.1,-2.2,-2.8,-3.5,-2.3,-0.9,0.5,1.7,1.3,-1.3,-1.6,1.5,0.8,4.2,5.8
TRN BA5|4.6,3.4,1.2,-0.9,-3.7,-4.2,-3.1,-1,1.3,1.7,-0.1,0.5,0.7,-2.1,-2.5,-0.8
TRN BAX|1.7,1,0.1,-1.4,-2.8,-2.2,-1.1,0.3,1.5,1.2,-0.9,0.5,1.6,-2.3,0.2,0.3
TRN BAX Pro (off-off-off)|-0.1,-0.6,-1.6,-2.8,-4,-3.1,-1.5,0.3,2,1.7,0.9,-0.4,0.9,0.8,2,-0.2
TRN BAX Pro (off-off-on)|2.6,1.9,0.7,-0.8,-2.5,-2.3,-1.1,0.5,1.8,1,-1.8,-1.9,-2.6,-2,0.7,-0.7
TRN BAX Pro (off-on-on)|1.9,1.3,0.1,-1.4,-3.1,-2.8,-1.5,0.1,1.3,0.6,-2.2,-2.5,-2.8,-2.3,2.2,3.2
TRN BAX Pro (on-off-off)|0.8,0.4,-0.6,-1.8,-3,-2.2,-0.8,1,2.4,1.5,-1.3,-1,-1.6,-0.9,2,0.1
TRN BAX Pro (on-on-off)|0.3,-0.2,-1.1,-2.3,-3.5,-2.7,-1.2,0.5,1.9,1,-1.8,-1.6,-2,-1.3,3.2,4.3
TRN BAX Pro (on-on-on)|0.3,-0.2,-1.1,-2.3,-3.5,-2.7,-1.2,0.5,1.9,1,-1.8,-1.6,-2,-1.4,3.2,4.3
TRN Conch (black nozzle)|1.1,1.2,0.8,-0.2,-1.3,-0.7,0.3,1.4,2,0.8,-0.3,-1.8,1.6,-1,-0.6,-3.9
TRN Conch (blue nozzle)|1.4,1.4,0.9,-0.1,-1.1,-0.5,0.5,1.5,2.1,0.8,-0.6,-3,1.6,-2.4,-0.4,-3.2
TRN Conch (red nozzle)|-0.2,0,-0.2,-1,-2,-1.3,-0.2,1,1.7,0.9,0.6,1.6,2,2.6,-0.2,-4.4
TRN IM1|-3.9,-3,-2.1,-2.1,-2.1,0,2.1,4.1,5.8,4.8,1.3,6.2,4.4,-3.5,-1.4,0.6
TRN Kirin (Atmospheric Immersion nozzle)|0.6,0.2,-0.5,-1.4,-2.4,-1.7,-0.6,0.4,0.9,-0.7,-4.2,2.6,6.6,1.2,6.4,-0.8
TRN Kirin (reference nozzle)|1.1,0.7,0.1,-0.8,-1.8,-1,0.2,1.3,2,1,-1,-1.3,3.6,-1.2,3.4,-2.3
TRN Kirin (transparency nozzle)|1,0.7,0,-0.8,-1.8,-1,0.1,1.3,2.1,1,-0.9,-1.6,3.4,-1.4,3.2,-2.2
TRN MT1|0,-0.1,-0.4,-1.2,-1.9,-0.6,0.8,2.2,3.2,2.1,-0.1,-4.6,-0.8,-1.8,4.6,1.1
TRN MT3|1.3,0.8,-0.1,-0.9,-1.9,-1.8,-0.8,0.2,1,0,-0.3,0.3,0,-1.5,-2.1,0.7
TRN MT4|0.8,0.7,0.2,-0.8,-1.9,-1.2,0.1,1.5,2.7,2.5,1.6,-0.8,1.2,0,-0.4,-3.8
TRN MT4 Pro|-0.8,0,0.5,0,-0.7,-0.4,0.1,0.6,0.5,-1,-2.1,-0.8,1.6,1.6,2.3,-0.8
TRN ST5|-0.2,-0.5,-1.3,-2.3,-3.5,-2.3,-0.7,1.1,2.2,0.8,-1.8,2.5,3.1,6.1,0,-3.6
TRN TA2|-1.2,-1.8,-2.9,-3.9,-5,-3.7,-1.8,0.4,2.4,2.2,0.1,-0.2,-0.1,6.3,7.2,2.9
TRN TA3|0.8,0,-1.3,-2.7,-4.1,-3.4,-1.7,0.7,2,-0.2,-1.3,3.4,5,3.8,-0.7,-2.6
TRN TA4|1.6,0.9,-0.2,-1.6,-3,-2.5,-1.3,0.2,1.2,0.4,-0.8,2.1,4.2,3.1,0.4,-3.4
TRN V20|-1.1,-1.5,-2.1,-2.9,-3.6,-1.9,-0.2,1.8,3.4,2.4,0.2,2.2,5,5.7,0.8,-4.5
TRN V30|-1.8,-1.8,-2.2,-3,-3.7,-2.1,-0.3,1.7,3.4,2.8,0.4,2.5,2.4,6.9,-1.2,-5.6
TRN V80|-2.2,-1.7,-1.5,-1.8,-2.4,-0.7,1.1,2.7,3.5,1.1,-1.3,7.6,6.7,7,-4.6,-8.4
TRN V90|-1.1,-1,-1.2,-1.9,-2.5,-0.7,1.1,2.8,3.6,2.2,-0.2,1.7,0.8,1.5,-0.8,-4.4
TRN X7|0.5,-0.1,-1.1,-2.6,-4.5,-4.4,-3.1,-1.2,0.6,-0.9,-6.9,1.9,3.5,4.7,5.4,5.3
Tronsmart Apollo Air|-3.6,-4,-4,-4.5,-4.3,-1.2,1.2,2.8,3.3,2.2,2.1,2.2,0.8,0.1,-0.7,5.8
Tronsmart Apollo Bold (ANC off)|-3.6,-4,-4.7,-4.2,-2.6,1.4,3.9,4.7,5.3,4.3,3.2,2,2.9,0.7,-0.7,-1.9
Tronsmart Apollo Bold (ANC off, classic)|-3.6,-4.1,-4.6,-5.2,-5.1,-2.2,1.1,4.7,7.8,8,7.9,4.4,1.5,-1.5,0.1,-2.2
Tronsmart Apollo Bold (ANC off, jazz)|0.3,-0.6,-1.8,-3,-4.5,-3.7,-1.7,1,4,4.8,5.6,3.6,1.4,-1.2,0.6,-1.9
Tronsmart Apollo Bold (ANC off, pop)|1.8,0.9,-0.7,-2.4,-4.5,-5,-3.8,-2.1,0,0.5,1.8,1.1,0.9,0.2,4.2,3.2
Tronsmart Apollo Bold (ANC off, rock)|-3.4,-4,-4.5,-4.9,-4.7,-1.5,1.7,5.2,7.9,7.7,7.3,3.7,0.9,-1.9,-0.1,-2.2
Tronsmart Apollo Bold (ANC on)|-5.9,-5.6,-5.4,-4.5,-2.6,1.8,3.8,3.7,5.9,6.5,5.2,4.1,4.6,2.7,-1.5,-1.8
Tronsmart Apollo Bold (ANC on, classic)|-5.8,-5.5,-5.2,-5.3,-5,-1.7,1.1,3.8,8.5,8.9,8.9,6.6,3.4,0.7,-0.4,-2
Tronsmart Apollo Bold (ANC on, jazz)|-2.2,-2.1,-2.5,-3.4,-4.4,-3.4,-1.8,0,4.7,7,7.4,5.6,3.2,0.9,-0.2,-1.8
Tronsmart Apollo Bold (ANC on, pop)|-0.6,-0.7,-1.4,-2.7,-4.5,-4.6,-4,-3,0.7,2.7,3.7,3.2,2.8,2.3,3.5,3.2
Tronsmart Apollo Bold (ANC on, rock)|-5.9,-5.4,-5,-5.1,-4.4,-1.1,1.7,4.2,8.5,8.8,8.8,5.9,2.8,0.2,-0.7,-2.1
Tronsmart Apollo Bold (classic)|-3.3,-3.7,-4,-4.4,-4.2,-1,2.2,5.5,7.8,7.8,6.9,3.5,-1,-5.3,-1.2,-1.8
Tronsmart Apollo Bold (jazz)|0.4,-0.2,-1.1,-2.3,-3.6,-2.6,-0.7,1.8,4.4,4.8,4.6,2.7,-1.1,-5.1,-0.9,-1.5
Tronsmart Apollo Bold (normal)|-5.4,-5,-4.8,-3.9,-1.9,2.3,4.4,4.4,6.3,7.2,4.6,4.2,2.5,-1.7,-3.4,-1.6
Tronsmart Apollo Bold (pop)|2,1.2,-0.1,-1.7,-3.7,-3.9,-2.8,-1.3,0.3,0.4,0.6,0.2,-1.7,-3.8,2.8,3.5
Tronsmart Apollo Bold (rock)|-3.3,-3.6,-3.8,-4.1,-3.8,-0.4,2.8,5.9,7.7,7.7,6.2,2.8,-1.7,-5.9,-1.4,-1.8
Tronsmart Onyx Free|-0.6,-1.7,-2.1,-2.6,-3.3,-2.4,-1.3,0.4,1.3,0.5,-1.1,-5,2.7,5.5,-1.8,1.6
Tronsmart Onyx Neo|-3.9,-2.7,-2.2,-1.3,0.6,0,-0.8,2.1,2.3,2,2.4,2.4,0.6,4.5,-3.9,-5.6
Tronsmart Onyx Prime|-5.5,-4.9,-4.2,-4.3,-4.2,-2.2,-0.1,2,3.7,3,1.1,6.6,7.7,1.8,1.2,-0.5
Tronsmart Onyx Pure|3.3,0.9,-1.4,-2.6,-2.6,-2,-1.8,-1.4,-1.3,-1.8,-0.1,3.8,5.5,-1.8,-1.2,-1.2
TrueFree 01|4.5,4.5,4.5,4.5,-1.4,-2.2,0,-1.5,-1.1,-0.1,-0.7,-7.1,-1.8,-5.9,1.4,4.4
TrueFree T3|4.8,4.8,4.8,4.8,4.8,4.8,1.8,-0.5,-3.8,-7.4,-9.1,-7.3,-5.3,-2.7,0.2,2.1
Truthear Hexa|0.2,1.1,1.4,0.6,-0.8,-1.1,-0.7,-0.1,0.6,-0.4,-0.1,0.5,0.9,4.6,1.6,-2.4
Truthear Hola|-1.2,-1,-1.1,-1.8,-2.8,-2.3,-1.4,-0.5,0.5,0,-1.2,0.2,0.8,2.4,6.1,4.5
Truthear Nova|-3.3,-1.9,-0.7,-0.3,-0.2,1.4,2,2.2,2.1,1,1.4,1.4,-0.8,-0.1,0.5,0.7
Truthear Nova (foam eartips)|-0.4,0.3,0.6,0.2,-0.1,0.5,0.9,1,1.2,0.3,0.2,0.1,-0.1,0,0.1,-0.8
Truthear Nova (silicone eartips)|-1.1,-0.2,0.4,0.2,-0.1,0.5,0.8,1,1.3,0.5,0.6,0.8,0.6,0.7,0.4,-0.8
Truthear x Crinacle Zero|1.4,0.4,-0.5,-0.8,0.1,0,-0.3,0.3,0.8,-0.1,-1.5,-1.9,-0.9,-1.7,1.5,1
Truthear x Crinacle Zero RED|1,1.1,1,0.4,-0.2,-1.1,-1.4,-1,-0.2,-0.8,-1.9,-0.5,-0.5,1.2,3,1.5
Truthear x Crinacle Zero RED (Bass+)|-1,-0.8,-0.7,-1.1,-1.3,-1.4,-1.2,-0.5,0.6,0.2,-0.8,0.6,0.5,2.4,4.3,2.7
Turtle Beach Battle Buds|-4.3,-4.4,-5,-6.1,-7.2,-5.9,-4,-1,3.5,6.2,7,6.7,4.6,-1,7.5,5.8
Turtle Beach Elite 800|1.2,0.3,-1.4,-1.8,-1.9,-0.6,3.6,5.7,2.3,-0.1,-2.6,-3.1,-6.3,3.7,-1.2,-0.5
Turtle Beach Elite Atlas|0.9,0.8,0.1,-0.1,-4,-2.3,3.6,3,2.9,-0.8,0.1,0.5,0.6,3.8,-2.9,-3.3
Turtle Beach Elite Atlas Aero|1.6,1.5,0.7,-1.1,-2,2.2,4.5,-0.3,-2.6,-2.6,-1.3,-0.2,2,3.3,-3.1,-2.5
Turtle Beach Elite Pro 2 SuperAmp|-0.3,-0.3,-1.1,-1.5,-2.4,0.3,5.1,0.9,1.3,0.3,1,1.5,1.5,5.9,-2.9,-4.6
Turtle Beach Recon 200|-1.4,-0.2,-2.5,-5.7,-5.5,-0.2,4.6,4.1,3.8,3.2,3.4,1.6,0.4,2.8,-2.9,-2.6
Turtle Beach Recon 200 Gen 2|2.9,2.1,-1.1,-3.8,-4.6,-0.7,3.7,2.2,2.3,1.9,1.3,-0.5,-2.8,1.5,-3.9,-2.5
Turtle Beach Recon 500|4.1,1.9,-0.3,-2.2,-3.6,-2.2,1.1,0.5,-3.7,-2.9,-0.8,-2,3.8,1.1,-3.3,1.8
Turtle Beach Recon 50X|2.9,2.6,1,-0.6,-3.9,-5.9,-5.7,-4.3,-3.1,-0.9,2.3,2.9,2,4.9,-0.2,0.1
Turtle Beach Recon 70|5,5,3.9,0.6,-2.7,-2.9,-0.8,0.5,-0.6,-0.4,0.7,0.7,-2.4,1.3,-3.7,-3.7
Turtle Beach Stealth 300|-2.2,-3.1,-3.7,-3.7,-3.7,-1.8,1.7,0.8,0.3,-0.5,4,5.8,4.4,-0.8,-1.1,2.9
Turtle Beach Stealth 600|-0.1,-0.8,-1.2,-1.3,-1.7,-0.5,1.1,1.3,0.1,-2.7,0.2,1.9,2.8,0.9,-1.7,-0.7
Turtle Beach Stealth 600 Gen 2|3.8,3.7,2.7,-0.2,-2.8,-1.2,3.9,2.7,-1.1,-1.1,-0.7,-2.7,-2.4,1.6,-5.1,-2.8
Turtle Beach Stealth 700|-2,-2.4,-3.1,-3.5,-5.2,-5.4,-2.1,1.8,0.1,2.1,5.5,3,6.4,1.9,1.5,2.5
Turtle Beach Stealth 700 Gen 2|0.9,0.4,0.1,-0.6,-0.9,2.5,4.6,6.3,1.8,0.3,2,0.2,3.1,0.3,-6.3,-7.2
Turtle Beach Stealth Pro|1.4,-0.4,-4.5,-5.2,-4.1,1.8,6,4.2,1.9,0.9,-0.4,0.4,5.7,2.9,-7.7,-3.3
TXN Audio Dest Ti|2.1,1.3,-0.1,-1.5,-3.7,-3,-2.2,-0.5,1.1,0.7,-0.2,-0.1,2.1,6.1,2.7,-2.1
TY Hi-Z HM150|-0.1,-3,-5.8,-7.3,-7.6,-3.6,-1.9,0.9,5.3,1.4,-5.6,1.1,5.8,2.3,2.8,5.8
TY Hi-Z HP32|4.7,1.4,-1.9,-2.9,-3.6,-0.9,-0.5,1.4,3.8,-1.8,-8.8,-2.1,4.7,-2.7,-1.7,4.4
UBSound Fighter|-3.5,-4,-5.1,-6.1,-6.8,-5.3,-3.2,-1.1,2.7,1.3,1.7,7.4,7.6,6.2,4.4,4.4
Ucotech RE-2|4.7,4.7,4.4,2.5,-0.2,-1.7,-1.7,-1.5,-1.3,-2.6,-3.8,-2.3,-1.7,-0.3,0.3,-2.9
UFOEar UFO-111|-2.7,-2.1,-1.4,-1.3,-0.4,1.5,3.8,3.5,1.9,-1.3,-5.6,3,3.9,-0.2,-2.8,-0.3
UiiSii BA-T6|-2.3,-2.2,-2.6,-3.4,-3.9,-3.1,-1.1,0.9,2.6,1.8,0.5,-1.4,0.8,-0.1,4.3,5.3
Ultimate Ears FITS|4.4,4.4,3.9,1.8,-1.1,-3.6,-3.7,-1.8,0.8,-0.9,-0.7,1.5,0.9,1.4,-7.5,-3.9
Ultimate Ears Live|0.8,0.4,-1,-2.7,-4.9,-4.9,-3.3,-0.2,3.1,0.4,-3.1,5,6.1,6.6,5.3,1.1
Ultimate Ears Reference Monitor|4.6,3.9,2.4,0.5,-2.2,-3.7,-3.7,-3.4,-2.3,-1.6,-1.2,3.2,3.6,-0.7,-3,-2.5
Ultimate Ears Reference Monitors|3.5,2.8,1.2,-0.8,-3.6,-5.1,-5,-4.4,-2.9,-2,-2.4,1.3,2.5,4.2,1.4,1.5
Ultimate Ears Reference Remastered|4.2,3.1,1.2,-0.8,-3.5,-4.4,-4,-3.2,-1.5,-0.6,0,3.1,3.8,0.8,4,2.1
Ultimate Ears Reference Remastered (CIEM)|4.7,4,2.3,0.4,-2.3,-3.5,-3.4,-3,-2.5,-3.4,-3.3,1.7,1.8,-1.2,4.6,0.6
Ultimate Ears Super.fi 5 Extra Bass|-5.6,-5.4,-5.5,-6.2,-6.8,-4.9,-2.5,0.5,3.9,5.9,6.1,5.1,6.8,0.2,0.3,4.9
Ultimate Ears TF10|0.5,0.2,-1.2,-2.8,-4.7,-5.1,-4.3,-3.4,-2,-1.6,0.6,4.8,6,6,2.1,-0.9
Ultimate Ears TripleFi 10|1,0.4,-0.8,-2.5,-4.7,-4.9,-4,-2.6,-0.9,-0.6,0.4,4,5.7,5.7,1.4,-0.2
Ultimate Ears UE11|-1.5,-2,-2.9,-4.3,-5.9,-5.1,-3.5,-1.3,1.4,2.3,2,3.5,3.6,1.7,2.5,3.4
Ultimate Ears UE18+ (2nd Gen)|2.8,1.9,0.2,-1.7,-4.2,-5.1,-4.7,-3.9,-1.9,-1,-1.2,2.8,5.3,5.6,5.6,3.1
Ultimate Ears UE18+ (3rd Gen)|3.3,2.4,0.6,-1.4,-4.2,-5.4,-5.5,-5.4,-4.3,-0.6,0,3.7,4.2,3.3,5.1,4.8
Ultimate Ears UE18+ (CIEM)|1.9,1.1,-0.4,-2.3,-4.8,-5.8,-5.2,-4.2,-2.1,-0.9,-1,4.1,5.9,5.8,5.9,2.5
Ultimate Ears UE18+ Pro|4.9,4.2,2.7,0.8,-2,-3.3,-3.4,-3.1,-2.7,-3.3,-2.9,2.5,4.1,-0.3,1.5,0.5
Ultimate Ears UE350|-3.7,-3.6,-3.9,-4.6,-5.1,-3.9,-2.1,-0.5,1.6,1.9,2.3,4.6,6.2,7.4,4.8,2.2
Ultimate Ears UE4 Pro|1.5,0.7,-0.4,-2.2,-4.5,-5.1,-4.1,-2.6,-0.4,0,-0.1,2.5,2.1,0.1,1.9,2.7
Ultimate Ears UE5|0.9,0.4,-0.9,-2.7,-5.1,-5.6,-4.7,-3.1,-1,-0.6,-0.9,1.7,4.8,4.9,2.6,3.7
Ultimate Ears UE500|-4,-3.3,-3,-3.5,-3.9,-2.8,-1.2,0.1,1.7,1.1,2.1,1,7.1,7.4,3.7,1.6
Ultimate Ears UE6|-1.7,-1.4,-1.2,-1.8,-2.7,-1.9,-0.8,0.6,2,1.8,2.2,5.2,3.6,1.7,3.2,0.9
Ultimate Ears UE600|2.7,2.2,0.7,-1.2,-3.5,-4.8,-4.5,-4.3,-2.9,-2.7,-0.7,3.6,2.4,3.4,0.3,2.5
Ultimate Ears UE7|2,1.3,-0.1,-1.9,-4.5,-5.4,-4.9,-3.7,-1.8,-1,0,2.7,3.1,0.6,1.7,4.4
Ultimate Ears UE700|3.3,3,1.5,-0.2,-2.6,-3.8,-3.5,-3.1,-1.8,-1.8,-0.6,3.1,5.7,5.5,-1.7,-2.7
Ultimate Ears UE900S|2.3,1.7,0.4,-1.5,-4.2,-5.9,-6.4,-6.2,-1.2,1.9,1.6,2.5,6,6,3.4,0.2
Ultrasone Edition 10|6.1,3.9,1.3,0.3,-0.6,-0.3,1,1.2,1,-1,2.2,6.7,-5,-2.6,-5.2,-2.8
Ultrasone Edition 8|5.7,4.5,2.6,2,-0.4,-2.7,-1.9,-1.9,-1,-1.7,2.1,0.9,-1.9,0.2,-1.8,-2.8
Ultrasone Edition 8 Palladium|5.6,5.6,3.1,-0.1,-2.7,-3.6,-0.7,1.4,0.5,-0.1,2.1,1.4,-3.8,-1.5,-1.3,-2.5
Ultrasone HFI-2400|3.4,0.5,-1.8,-2.4,-2.3,-2.4,0,1,1.8,1.1,2.7,5.9,-3,0.4,-2.4,0.1
Ultrasone HFI-450|5.2,5.2,3.4,1.2,-0.5,-1.8,-4.1,-4.1,-2.3,-2.8,-1.3,1.6,-0.2,-1.6,-1.9,-3.2
Ultrasone HFI-580|4,1.4,2.4,1.7,2.4,-0.2,-1,0.1,0.4,0.6,0.6,-2.3,-9.3,0.6,-1.7,-4.3
Ultrasone HFI-780|5.3,5,3.8,3.1,2.8,0.3,-3.7,-3.7,-2.6,-3.1,-4.2,1.9,-4.5,-1.6,0.1,-4.5
Ultrasone PRO 2900|1.5,-0.7,-1,-0.8,-0.2,-1.2,1.6,2.4,3.2,1,1.7,1.8,-7,-0.1,0.6,-0.5
Ultrasone PRO 550|2.9,1.1,0.1,0,0.6,4.4,0.3,-2.8,-1.9,-2.8,-1.9,-0.4,-3.7,2,0.6,-2.8
Ultrasone PRO 650|5.1,3.4,1.1,0.2,-0.3,0.7,0.1,-0.3,-0.2,-2.4,-2,2,-4.9,0.7,2.6,-0.4
Ultrasone PRO 900|-1.1,-2.6,-1.8,-3.1,-3.3,4.5,4.2,2.9,3.5,4,3.9,1.9,-3.6,1.2,-0.8,-2
Ultrasone PROline 550|5.7,5.7,4.5,4.3,4.9,5.7,1.8,-5.3,-3.7,-4.2,-1.8,-3.6,-5.3,-3.5,-1.3,-3.3
Ultrasone PROline 650|0.7,2.7,-1.6,-1.3,-1,3.3,4.1,3.3,1.4,-0.5,-0.5,0.9,-6.9,-2.1,0.9,2.3
Ultrasone Signature Pro|0.8,-0.8,-0.5,-2.7,-4.4,-4.8,-2.7,-3.4,-3.2,-1.3,1.3,6.2,1.8,4.4,2.4,-2.1
Ultrasone Tribute 7|6,2.7,-0.4,-3.4,-5.7,-5.2,-2.4,-1.6,-2.7,-2,0.1,3.7,5.6,6,0.3,-3.6
Ultrasone Zino|4.2,1,-1.4,-1.4,-0.8,1.6,4.5,3.9,5.9,-3.1,-6.6,-1.8,-0.2,0.4,-2.9,-1.6
Umi Voix|-3.4,-3.4,-4.2,-5.2,-6.2,-5.1,-2.5,-0.2,-0.1,-2.8,-2.2,6.6,5.1,6.9,7.1,7.1
Under Armour True Wireless Flash|-2.1,-1.7,-1.4,-1.5,-0.5,0.8,0.9,1.6,2.5,0.9,0.1,0,4.4,6.7,-0.4,-2.9
Unique Melody 2HT Terminator|0.2,0.5,0.4,-0.4,-1.1,-0.1,1.3,2.4,2.8,0.6,-2.4,-1.1,0.4,-0.2,-2.8,-1.8
Unique Melody 3D Terminator|2.7,2.7,1.9,0.5,-1.5,-1.9,-1.2,-0.1,1.1,0.5,-1.2,-2.3,0.1,-4.3,1.7,1.3
Unique Melody 3DD-Ti|1.1,1.9,2.2,1.3,-0.2,-0.6,0,1.1,0.6,-4,-4,-1.2,-4.9,-0.4,1.2,4.5
Unique Melody 3DD-Ti Open|1,1.5,1.6,0.8,-0.5,-0.6,0.1,1.1,0.6,-4,-3.9,-1.2,-4.7,-0.4,1.3,4.7
Unique Melody 3X|4.5,3.3,1.4,-0.7,-3.4,-4.9,-5,-4.7,-4.3,-4.9,-3,4.5,4.7,1.9,1.1,4.7
Unique Melody Legacy|0.7,0.1,-1,-2.5,-4.2,-3.8,-2.6,-1.4,-0.4,-1.4,-2.6,1.9,1.7,5.6,2.9,4
Unique Melody Macbeth Ti|-2.5,-2.1,-1.9,-2.4,-2.9,-1.9,-0.9,0.2,1.3,0.7,-1,0.6,4,4.1,0.9,1.2
Unique Melody Maestro V2|2.1,1.4,0,-1.6,-3.4,-3.4,-2.6,-1.6,-0.7,-1.1,-1.1,2.9,5.3,1,-0.2,3.3
Unique Melody Martian|4.8,4.8,3.5,1.5,-1.2,-2.5,-2.5,-2.2,-2.2,-3.5,-4.6,-0.4,3.5,3.3,0,-2.7
Unique Melody Mason V3|0.9,0.6,0.1,-0.8,-1.8,-1.3,-0.4,0.2,0.4,-0.7,-2,1.4,4.4,-1.4,0,2.8
Unique Melody Mason V3 Ported|5,3.9,1.5,-0.6,-2.7,-2.7,-1.9,-1,-0.6,-1.8,-2.6,0.8,3.7,-2.1,-0.7,2.2
Unique Melody Mason V3+|1.3,0.8,0,-1.1,-2.3,-2,-1.3,-0.4,0,-1,-2,1.3,4.2,-0.8,0.4,3
Unique Melody Maven|2.7,1.6,0.2,-1.4,-3,-2.7,-1.3,0.3,2.1,1.9,0.4,1.2,5.7,1.8,-1.5,-2.6
Unique Melody Maverick II|4.5,3.1,0.8,-1.3,-3.9,-4.7,-4,-2.7,-1.1,-1,-1.8,-1.2,3.7,-2.4,0.2,1.8
Unique Melody Mavis 2|4.2,4.2,3,1.1,-1.6,-2.7,-2.6,-2.2,-1.9,-3,-4.4,-1.4,2.6,-0.6,-1.5,1
Unique Melody ME1|4.4,4.4,2.3,0,-3.2,-5.2,-5.7,-6.2,-7.2,-6.1,-3.4,4.4,4.2,4.1,4.4,4.4
Unique Melody Merlin V2|1.8,1.2,-0.1,-1.9,-4.3,-4.9,-4.2,-2.9,-1,-0.4,-0.7,3.2,4.4,4.2,1.8,-0.8
Unique Melody MEST|-0.2,0.5,1,0.6,-0.3,-0.2,-0.2,-0.4,-0.7,-0.9,-0.5,2.4,4,1.5,-0.8,-2.1
Unique Melody MEST (CIEM)|0.3,0.4,0.1,-0.7,-1.9,-2,-2,-2.3,-2.5,-2.3,-1.9,1.6,5.5,4.1,1.4,-0.7
Unique Melody MEST Mk2|-1.2,-0.4,0.2,-0.2,-1.3,-1.3,-1.2,-1.3,-1.1,-0.7,-0.1,3.3,5.9,2.8,0.5,-1.3
Unique Melody MEST Mk2 (AZLA eartips)|-1,0.1,0.9,0.8,0.3,0.4,0.7,0,-0.7,-0.6,1.1,3.5,4.5,1.6,-2.3,-4.2
Unique Melody MEST Mk2 (AZLA SoundFit Xelastec eartips)|-1.1,-0.1,0.7,0.5,-0.1,0,0.2,-0.3,-0.9,-0.9,0.8,3.4,5,2.7,-1.8,-3.9
Unique Melody MEST Mk2 (Moondrop Spring eartips)|-1.4,-0.2,0.5,0.3,-0.2,-0.2,0,-0.6,-1.1,-1.1,0.6,3.3,5.4,3,-1.7,-3.4
Unique Melody MEST Mk2 (SpinFit CP100 eartips)|-1.2,-0.1,0.8,0.6,-0.4,0.1,0.5,-0.1,-0.6,-0.7,1.2,3,4.1,2,-2.7,-3.6
Unique Melody MEXT|-1.1,-0.3,0.5,0.1,-0.8,-0.8,-0.4,-0.2,-0.2,-0.1,0.2,3.6,6,-0.1,-0.2,0.4
Unique Melody Mini MEST|-1.3,-1.6,-1.9,-2.8,-3.6,-3.1,-2,-0.6,1.1,1.3,0.1,3,5.8,0,-0.7,2.1
Unique Melody Miracle V2|0.8,0.7,0,-1.3,-3.2,-3.3,-2.6,-1.6,-0.8,-1.4,-2.1,4.3,5.8,1.4,2.3,3.6
Unique Melody Mirage|-0.4,-0.8,-1.2,-2.1,-3.1,-2.9,-2.2,-1,0.5,0.8,-0.6,2.5,4.5,1,-1,1.5
Unknown Custom Juno|1.9,0.8,-0.9,-2.8,-5.4,-6.3,-5.6,-4,-1.7,-0.4,0.7,6.1,6.1,6.1,4.6,2.7
Unknown Custom Juno (min bass)|4.3,3.2,1.4,-0.6,-3.2,-4.4,-4,-3,-1.6,-1.5,-1.7,4.1,5,3.5,-0.9,0.5
Unknown Custom Quad 2.0|0.8,-0.1,-1.2,-2.9,-5,-5.3,-4.3,-2.5,-0.1,0.7,0.9,5.7,5.9,2.6,0.2,2.4
Unknown Custom UCD-2|0.1,-0.8,-1.9,-3.5,-5.5,-5.7,-4.6,-2.5,0.2,1.5,2.4,3.4,5.3,3.1,-1.2,4.9
Unknown Custom UCD-3|1,0.3,-1,-2.7,-4.9,-5.6,-4.8,-3.2,-0.8,0.4,1.4,5.8,5.8,3.2,-0.5,1.9
Urbanears Plattan|0.8,-0.2,-1.3,-2,-3.4,-4.8,-3.1,-5.1,-8,-7.4,-0.2,6,6,1.9,5.5,6
Urbanfun YBF-ISS014|-3.6,-2.9,-2.1,-2.8,-2.8,-0.9,0.8,2.5,3.7,3.1,2.2,0.3,2.4,-0.3,4,1.4
V-MODA BassFit Wireless|-3.3,-3.6,-2.9,-2.6,-2.4,0.4,1.1,2,2.4,2.3,3.9,4.2,1.8,-2.4,1.6,2.9
V-MODA Crossfade 2 Wireless|-0.8,-1.1,-4.6,-5.3,-5.7,-2,2.3,3,0.4,-1.9,-0.5,-0.3,-3.7,3.3,4.1,3.9
V-MODA Crossfade LP|0.1,-2,-3.5,-4.2,-5.8,-4.1,1.4,3.1,-0.3,-1.3,-1.5,-2.7,-3.9,5.8,5.3,4
V-MODA Crossfade LP2|-1.1,-2.2,-4.4,-5.8,-9.1,-8.6,-2.8,5.6,3.3,0.6,1.1,2,-0.6,6.3,5.7,3.8
V-MODA Crossfade M-100|-0.7,-1.1,-1.9,-2.9,-4.6,-1.9,4.2,4.5,1.5,-2,-1.9,-1.9,0.5,6,0.2,0.3
V-MODA Crossfade M-80|1.8,0.6,-1.2,-2.3,-3.4,-5.2,-1.5,0.4,1.7,-1.9,-1.6,0.7,-4,2.8,2.4,3.7
V-MODA Crossfade Wireless (wired)|0.2,-1.7,-2.2,-3.9,-6.3,-2.6,4.1,5.9,3,-1.3,-1.5,-1.2,-6.9,6,1.3,-0.7
V-MODA Crossfade Wireless (wireless)|-1.1,-2.7,-3.4,-4.6,-6,-2.6,4.1,5,2.8,-1.1,-0.3,-0.2,-6.6,3.6,2.4,-0.8
V-MODA LP2|-2.1,-2.9,-3.5,-5.4,-6.9,-4.3,1.5,4.5,0.3,-1,1.2,-0.1,-2.6,6.5,6.5,4.3
V-MODA M-200|1.5,1.6,2.4,0.9,-2.3,-3.3,-4.4,-4.8,-4.7,-4.6,-1.8,1.1,5.5,1.2,4.1,3.6
V-MODA V-80 True Blood|0.5,-0.3,-1.5,-2.4,-3.5,-3.6,0.1,2.1,2.1,-2.1,-1.6,2.2,-2.6,2,2.3,3.6
V-MODA XS|-1.4,-2.3,-2.9,-3.1,-3.8,-3.4,1.1,2.4,2.8,-0.2,1.5,0.7,-1.7,2.3,2.5,2.3
V-MODA Zn|1.4,-0.3,-2.4,-4.1,-5.8,-4.7,-3.2,-0.6,2.1,3.1,4.5,3.6,1.6,-1.5,0.7,3.3
Valco VMK20|0.8,-0.2,-0.8,-1.2,-2,-2.4,-2.8,-2,-1.3,-2.2,2.8,4.1,5,3.5,-0.2,0.5
Valco VMK20 (wired, ANC on)|-4,-3.4,-3.7,-4.5,-6.3,-6.5,-4.6,-1.8,1.5,4,7.1,6.2,7.1,6.2,0.5,1.9
Valco VMK20 (wireled, power off)|3.7,1.2,-0.3,-1.3,-3.9,-4.3,-1.6,0.3,1.1,-0.5,2.4,-1.1,1.8,1.5,-1.3,-0.8
Valco VMK20 (wireless, ANC off)|1.8,0.1,0.5,0.2,-1.1,0.3,1.5,1.8,0,-4.6,0.2,-1.5,2,0.1,-2.5,-2
Valco VMK20 (wireless, ANC on)|-0.3,-0.8,-1.2,-1.6,-2.4,-2.7,-3,-1.9,-1.2,-1.3,6,4.5,6.4,4.7,-1.7,-0.6
Valco VMK25 (wired, ANC off)|-2.5,-2.4,-2.6,-3,-5.1,-5,-2.4,-0.3,0.7,-0.9,2.6,3.4,6.8,2.9,1.7,4.5
Valco VMK25 (wired, ANC on)|-1.7,-0.8,-0.3,-0.6,-1.8,-2.7,-2.1,-1.6,-0.1,-1,2.6,4,6.8,2.1,-2,1.7
Valco VMK25 (wired, power off)|-1.5,-1.5,-1.6,-2.9,-5.8,-5.2,-2.6,-0.5,0.4,-1.8,1.8,3.3,6.4,2.7,1.5,4.9
Valco VMK25 (wireless, ANC off)|-1,-0.6,-0.3,-1.4,-4,-3.6,-1.2,-0.4,-1,-2.1,2.4,3.9,1.6,-2.7,1,4.3
Valco VMK25 (wireless, ANC on)|-1.2,0,1,1,0.2,-0.8,-0.7,-1.2,-1.2,-1.3,2.1,4.7,1.1,-4,-2.3,1.8
Veedix Diamond String|-0.5,0,0.2,-0.5,-1.6,-1.5,-0.7,0.3,1.7,1.1,1.2,0.3,3,-0.1,3.9,-0.1
Velodyne vPulse|-5.4,-3.7,-2.2,-2.1,-2,-0.6,0.7,1.6,2.5,2,2.1,1.4,-2.1,4.3,7.4,3.6
Vento Conductor T-500 Pro|-4.9,-3.9,-2.9,-2.6,-2.7,-0.6,1.4,3.3,4.5,3.9,2.9,-0.2,-0.4,0.8,0.9,0.5
Venture Electronics Asura 2.0|5,5,1.1,-4.1,-10,-8.4,-7.8,-6.3,-4.5,-3.3,0.7,5,5,3.4,5,5
Venture Electronics Bonus In-Ears (BIE)|-4.2,-3.6,-3,-3.3,-3.5,-1.6,0.1,2.1,3.7,3,0.8,-0.5,4.6,-2.1,5.7,5.8
Venture Electronics Duke|3.3,2.7,1.2,-0.5,-2.8,-3.4,-2.5,-1.3,0.9,1.4,2.4,2.5,0.9,3.6,-2.9,-3.1
Venture Electronics Duke (CIEM)|-0.2,-1.1,-2.5,-4.2,-6.5,-6.9,-5.7,-3.7,-1,0.3,0.4,4.1,3.8,4.7,5.7,5.7
Venture Electronics Grand Duke|-2.6,-2.6,-3.3,-4.5,-5.9,-5,-3.1,-0.6,2.5,3.6,5,6.1,5.4,-0.6,3,2
Venture Electronics Monk Blood Orange|4.7,4.7,0.8,-4.3,-8.6,-6.3,-5.8,-4.7,-3.1,-1.9,-2.2,2.1,4.7,3.7,4.7,4.7
Venture Electronics Monk IE Biggie|0,-0.8,-2.4,-4.4,-7.3,-9.2,-9.3,-7.8,-2.2,3.1,2.5,6.5,6.4,5.5,6.4,6.4
Venture Electronics Monk IE Smalls|-0.1,-0.6,-1.9,-3.7,-6.4,-7.6,-7.3,-6,-2.9,0.2,2.4,6.1,6,2.4,6,6
Venture Electronics Monk Plus|4.1,4.1,4.1,0.2,-6.5,-4.9,-3.6,-2.4,-1.1,-1.5,-4.7,-2.4,4.1,-0.9,1.6,3.9
Venture Electronics Zen|5.4,5.3,-0.6,-5.5,-10.2,-8.4,-8.8,-7.1,-5,-2.8,2.1,5.4,5.4,5.4,5.4,5.4
Verum One|4.1,3.3,1.8,0.7,-1.3,-2.6,-1.9,-2.1,-2.5,-3.1,-0.1,-0.7,-0.2,-1.4,1.5,1.3
Verum One (leather earpads)|4.7,3.9,2,0.7,-1.2,-2.4,-1.8,-2.1,-2.2,-3.8,0.2,-1.7,-2,-3,2.8,1.2
Verum One (ZMF Ori earpads)|3.7,3.1,1.5,0.3,-1.7,-2.7,-2.2,-2.6,-2.7,-3.4,0.6,-0.1,0.6,-0.5,2.2,1.5
ViSang VS-K1|1.6,0.7,-0.7,-2.4,-4.1,-4.2,-3.3,-2.8,-2,-3.2,-0.9,3.2,5.8,5.8,4.3,2.3
Vision Ears Elysium|2.5,2.2,1.1,-0.5,-2.3,-3.2,-2.1,-0.7,0.9,0.6,0.8,3.3,1.4,1.3,2.6,-2.6
Vision Ears Erlkönig|-0.1,-0.7,-2,-3.5,-5.2,-5,-3.7,-1.8,0.6,1.2,1.1,1.2,1.8,2.4,3.6,3.9
Vision Ears Erlkönig 1|-2.3,-2.7,-3.7,-4.9,-6.2,-5.3,-3.4,-0.9,1.9,2.5,2.2,2.3,2.7,3.4,4.5,4.6
Vision Ears Erlkönig 2|-0.1,-0.7,-2,-3.4,-5.2,-5,-3.8,-1.8,0.7,1.2,1.1,1.1,1.7,2.3,3.5,3.9
Vision Ears Erlkönig 3|4,2.7,0.6,-1.5,-4.1,-4.9,-4.3,-3,-1,-0.6,-0.7,-0.5,-0.1,0.7,1.7,2.2
Vision Ears Erlkönig 4|3.7,2.3,0.3,-1.7,-4.4,-5.2,-4.6,-3.1,-1.2,-0.8,-0.9,-0.7,-0.1,0.8,2.8,3.2
Vision Ears EVE 20|1.1,0.7,-0.6,-2.2,-4.3,-4.4,-3.3,-1.5,0.5,0.7,0.2,5.6,4.4,1,-1.4,0.1
Vision Ears EXT|-0.5,-0.3,-0.5,-1.3,-2,-0.8,0.7,2.1,3.3,2.3,-0.2,4.2,3.8,6.2,2,-4.2
Vision Ears PHöNIX|0.7,0.4,-0.7,-2.2,-3.9,-3.4,-2,0,2.1,2.2,2.7,1.8,1.5,2.8,1.8,-1.3
Vision Ears VE2|0.4,-0.3,-1.4,-3,-5.1,-5.5,-4.5,-2.7,-0.4,0.1,-0.6,2.2,1.5,3,3.6,5
Vision Ears VE3.2|1.2,0.2,-1,-2.8,-4.9,-5.4,-4.4,-2.8,-0.7,-0.4,-1.3,2.2,2.1,4.5,2.6,4.9
Vision Ears VE4.2|-1.2,-1.8,-2.8,-4.1,-5.6,-4.8,-2.9,-0.2,3.3,4.5,3.4,1.1,4.6,-0.7,0.5,3.7
Vision Ears VE5|3.6,2.7,1.1,-0.9,-3.5,-4.7,-4.5,-3.7,-2.7,-2.6,-1.7,-0.6,0.8,0.7,4.4,4.5
Vision Ears VE6X1|1.3,0.7,-0.7,-2.5,-5,-5.8,-5.1,-3.9,-2.1,-1,0.6,1.4,2.3,2.9,5,5
Vision Ears VE6X2|2.2,2.1,0.8,-1.1,-3.8,-4.9,-4.6,-3.7,-1.7,0.1,0.5,0,0.5,-0.1,3.6,4.7
Vision Ears VE7|1.6,1.4,0.8,-0.5,-2.5,-3.2,-2.7,-1.6,-0.2,-0.3,-0.9,3.7,3.5,2.9,0.1,-0.7
Vision Ears VE8|1.6,1.2,0.2,-1.3,-3.2,-3.3,-2.4,-1.2,0,-0.8,-0.3,3.8,3.7,2.4,-1.7,0.5
Vision Ears VE8 (CIEM)|0.9,0.1,-1.2,-2.8,-4.9,-5.1,-4.2,-2.6,-0.6,-0.6,-1.5,3,3,4.4,2.7,3.3
Vivo XE800|2.9,3,2.3,1,-0.9,-1.9,-1.7,-1.3,-1.2,-2.6,-2.9,-1.1,2.8,1.6,-2.5,-0.9
VJJB N1|-4.1,-4,-4.4,-5.2,-5.6,-4.5,-2.2,0.8,3.9,2.3,2.6,2.9,4.1,-2.5,4.7,6.5
Voice of China|1.6,0.2,-1.6,-3.3,-5,-4.5,-2.9,-1.2,1.1,1.4,2.1,0,-2.3,-4.5,3.8,3.6
VSonic Ares|-3.7,-3.1,-2.6,-3,-3.5,-2.3,-1.1,-0.1,1.2,1.7,1.7,1.8,6.7,6,2.3,4.8
VSonic GR01|3.2,2.4,0.8,-1,-3.6,-4.8,-4.4,-3.5,-1.6,-0.8,0.1,2.5,5.1,2.9,-0.4,0
VSonic GR02 Bass Edition|-3.3,-3,-3.2,-4,-4.6,-3.7,-2.4,-1.5,-0.5,-0.9,0.5,6.1,6,4.3,2.3,6.3
VSonic GR07|0.9,0.2,-0.9,-2.2,-3.8,-3.6,-2.9,-1.7,-0.3,0.2,1.2,2.7,5.5,2.3,-0.9,0.7
VSonic GR07 Bass Edition|2.3,1.9,1.1,-0.4,-2.7,-3.7,-3.6,-3.1,-2.2,-1.7,-1.1,1.6,5.3,4.9,1.6,2.4
VSonic GR07 Classic|3.8,3,1.6,-0.4,-3,-4.2,-3.8,-2.9,-1.1,-0.3,-0.2,0,4.1,3.1,1.5,2.2
VSonic GR07 Mk2|4.1,3,1.3,-0.7,-3.2,-4.2,-3.9,-3.3,-2.3,-1.7,-1.2,1.6,4.7,3.8,0.4,1.6
VSonic VC1000|3.3,2.9,1.5,-0.4,-2.7,-4.1,-3.9,-3.6,-2.2,-1.9,0.1,1.9,5.6,3.3,-0.1,-3.4
VSonic VCO2|1,0.4,-1,-2.7,-5,-5.3,-4.4,-2.9,-0.9,-0.4,-0.5,4.8,6.1,6.1,2.2,-1.5
VSonic VS05|3.5,2.5,0.7,-1.2,-3.5,-4.2,-3.4,-2.5,-0.4,0.4,3,5,2.4,0.9,-3.5,-2.2
VSonic VS7|3.4,2.2,0.4,-1.5,-4.2,-5.5,-5.3,-4.4,-2.7,-1.4,-0.4,2.2,5,5,0.1,1.5
VSonic VSD1|0.3,-0.3,-1.3,-2.5,-3.8,-3.4,-2.3,-1.6,-0.4,-0.5,-0.2,1.4,4.5,0,1.6,1.7
VSonic VSD1S|2.3,0.8,-1.2,-3.2,-5.8,-6.1,-4.7,-2.6,0.3,1.1,0.4,1.9,4.4,2.3,2.6,4.6
VSonic VSD2S|3.9,2.7,0.8,-1.3,-4.3,-6,-5.9,-5,-2.5,1.1,4.2,5.3,4.7,3.8,-2.3,-0.3
VSonic VSD3|1.1,0.5,-0.9,-2.4,-4.1,-4.2,-3.2,-2.3,-0.4,0.6,3.1,5.2,3.9,2.3,-1.6,-1.2
VSonic VSD3S|-2.7,-2.7,-3.2,-4,-5,-3.8,-2.3,-0.8,1,2.7,5.3,7,6.1,6,0.1,1.5
W+G T2|3,2.2,0.7,-1,-3.1,-3.1,-1.9,-0.2,2,2.4,-0.2,0.4,2.2,-2.8,-4.2,-1.1
Walmart Three DOllar Buds|5.2,5.2,5.2,5.2,5.2,-3.3,-8,-7.9,-5.1,-3.9,-1.3,3.4,-1.4,0.7,0.2,-0.8
Warbler Prelude|4.6,2.4,-0.1,-2.2,-5,-6.4,-6.2,-5,-3.1,-2.2,-1.5,0.7,0.8,4.8,4.8,4.8
Warbler Prelude (CIEM)|4.1,2.7,0.4,-1.7,-4.6,-5.9,-5.7,-4.9,-3.4,-2.9,-3.3,-0.7,0.2,5.1,5.1,5.1
Warwick Acoustics Aperio|1.9,2.4,-0.6,0.2,-1,-1.6,-1,0.5,-2.4,-2.9,1.2,1,1.5,1.6,0.8,0.5
Warwick Acoustics Sonoma Model One|3.3,2.6,-0.3,0.6,0,-0.6,-0.3,-0.4,-2.8,-3.2,0.1,0.6,-0.6,0.1,1.1,0.5
Westone 4R|2.3,0.7,-1.4,-3.3,-6,-6.8,-6,-4.1,-0.9,0.1,1.7,5.9,5.9,5.9,2.2,0.1
Westone ADV Alpha|-3.2,-3.3,-4.2,-5.4,-6.6,-5.7,-3.8,-1.8,0.9,1.7,3.9,6.7,5.7,0.5,5.9,6.3
Westone ES60 (CIEM)|2,1.1,-0.6,-2.3,-4.7,-5.2,-4.1,-2.4,-0.3,-0.3,-1.8,0.8,2.8,0.8,2.3,2.2
Westone MACH 80|2.7,2.2,1,-0.8,-3.4,-4.4,-4,-3,-1.5,-1.1,-1.2,-0.5,2.5,0.4,2.2,4.7
Westone Pro X50|-0.1,-0.9,-2.2,-3.9,-6,-6.5,-5.5,-3.5,-0.4,1.3,-0.8,7.1,6.8,7.1,7.1,3.3
Westone UM Pro 10|1.9,1.4,0.2,-1.7,-4.4,-5.7,-5.3,-4.6,-3.3,-3.1,-2.4,2.4,4,5.1,5.1,5.1
Westone UM Pro 20|-1.5,-1.8,-3,-4.7,-6.8,-7,-5.9,-3.9,-1.2,0.3,1.1,6.1,6.1,6.1,5.7,6.1
Westone UM Pro 30|-0.1,-0.4,-1.7,-3.4,-5.7,-6.2,-5.2,-3.6,-1.4,-1.1,-1.7,5.8,6,6,2.7,4.8""",
"""Westone UM Pro 50|-0.8,-1.1,-2.4,-4.1,-6.4,-6.8,-5.6,-3.6,-0.5,0.4,-1.7,6.8,6.8,6.9,6.9,5.7
Westone UM1|4.6,3.5,1.4,-0.8,-3.5,-5.1,-5.1,-5.1,-4.4,-4.8,-3.8,0.2,0,4.6,4.6,4.6
Westone UM3X|2.3,1.9,0.3,-1.4,-4.1,-5,-4.7,-3.8,-2.6,-3.1,-1.9,4.6,5.6,5.6,1.8,0.8
Westone UM3X RC|1.5,0.7,-0.9,-2.7,-5.1,-6,-5.4,-4.4,-2.7,-2.4,-1.5,5.7,6.1,6.1,3.3,1.5
Westone W10|2.1,1.7,0.3,-1.5,-4.1,-5.3,-5.1,-4.4,-3.6,-3.6,-1.6,2.6,2.7,4.8,2.6,4.8
Westone W2|3,2.4,1,-0.8,-3.3,-4.4,-4.2,-3.6,-2.1,-2.1,-1.5,1,5.8,4.8,3.9,0.3
Westone W20|1.6,1.1,-0.2,-2,-4.7,-5.6,-4.9,-3.6,-1.5,-0.6,-1.2,1.3,5.9,5.8,5.9,4.5
Westone W30|1.4,0.7,-0.6,-2.6,-4.9,-5.9,-5.2,-3.8,-1.5,-0.1,1.2,6.3,6.3,6.3,1.3,-0.6
Westone W40|2.9,1.5,-0.7,-2.9,-5.7,-6.7,-5.9,-4.3,-1.4,-0.3,0.6,5.7,5.7,5.7,2.6,-0.8
Westone W50|-0.5,-1.3,-2.8,-4.6,-6.8,-7.1,-5.8,-3.6,-0.4,1,0.8,7.3,7.3,7.3,7.3,2.9
Westone W60|0.4,0,-1.3,-3.1,-5.4,-5.8,-4.7,-2.7,0,1,0.6,6.6,6.1,6.6,3.5,0.1
Westone W80|-0.5,-0.8,-2,-3.6,-5.8,-6.2,-5.1,-3.6,-1.7,-1.5,-0.7,6.2,6.2,4.5,5.8,6.2
Whizzer A-HE03 Kylin|-1,-1.3,-2.3,-3.8,-5.7,-5.3,-3.6,-0.8,2.8,4.2,2.8,3.9,3,2.9,5.2,1.8
Whizzer A15|2.1,1.4,0,-1.8,-4.2,-4.4,-3.2,-0.9,2.4,3,3,5.1,3.1,-1,-1.4,-1.7
Whizzer A15 Pro|4.7,4.7,4.7,3.7,0.5,-1.4,-1.7,-1.3,-1.1,-1.6,-3.2,-2.9,-2.7,-2.5,1.8,0.6
Whizzer BS1|1.9,1.6,0.4,-1.1,-2.9,-3.1,-2.2,-1.1,-0.4,-1.7,-3.9,-4.3,1.6,1.4,2.3,4.2
Whizzer HE01|0.9,0.4,-0.6,-1.9,-3.3,-2.7,-1.2,0.5,2.1,1.6,-0.6,-1.6,-1.1,2.9,5.4,1.3
Whizzer Kylin HE01B|1.8,1.4,0.4,-0.9,-2.5,-1.7,-0.6,0.9,1.9,1.3,-0.4,-1.9,-1.5,0.7,3.1,-0.8
Whizzer Kylin HE10|-0.6,-0.3,-0.1,-0.7,-1.2,-0.1,1.1,2.3,3.2,2.1,1.1,0.7,1.3,0,1.4,-2.4
Wicked Audio Deuce|-5.4,-4.6,-4,-4.2,-4.1,-2,0.3,2.2,4.5,4.9,4.8,1.7,3.4,1,-0.7,4.1
Willsound MK2|4.3,4.3,4.3,1.3,-1.4,-2.3,-1.4,-1.9,-2.3,-2.4,-3.9,-2.7,1.1,-0.9,-1.4,0.3
Wiss Audio On Ear Force 1 HP P244|-1.2,-1.6,-2.7,-3.7,-4.6,-4,-4,-2.2,0.4,0.2,2,3.3,-0.6,6.5,6.5,5.7
Woodees iESW100B|-1.7,-1.6,-1.9,-2.6,-3.3,-1.7,0.4,2.7,4,2.9,3.4,-0.1,2.1,0.4,-0.5,2.7
Woodees iESW100L 24K Blues|-3,-2.8,-3.2,-4,-4.8,-3.2,-1.1,1.3,3.5,4,3.8,1.9,2.6,-5,2.9,5.2
Woodees iESW101B|-2.1,-1.7,-1.5,-2.1,-2.4,-0.7,1.3,3.4,3.9,3,2.8,-0.9,1.7,-0.1,0,2.8
Woodees iESW101V Vintage|-1.9,-1.4,-1.3,-2.2,-3.3,-3.1,-2.1,-0.7,0.9,2.8,2.7,1.9,3.6,1.1,4.1,5.5
Wyze Buds|1.1,-0.3,-4.1,-6.4,-7.1,-4.4,-1.7,-0.1,0.3,0.6,1.7,3.8,3.1,3.5,3.5,5.2
Wyze Buds Pro|1.5,0.7,-0.7,-1.3,0.1,1.6,1.1,1.6,4.3,1.9,1,2.9,0,-1,-2.4,-3.6
Wyze Noise-Cancelling Headphones|1.3,1.3,-2.1,-2.6,-1.9,2.5,3.8,-0.4,-0.5,-0.3,2.6,6,5.6,-8.3,-6.5,0.1
Wyze Wireless Gaming Headset|1.7,-3.8,-7.3,-6.1,-1.6,2.3,2.4,1.1,1.6,6.7,6.8,-1.8,-0.4,-1.8,-4,-4.1
Xbox Stereo Headset|0.3,-0.9,-3.1,-5.4,-7.2,-5.1,-1.6,1,0.6,2.9,6.5,7.1,7.1,7.2,-1.1,-1.3
Xbox Wireless Headset|-2.8,-5.3,-7.2,-8.3,-8.6,-5.2,2.2,4.3,5.9,6.1,6.8,4.1,7.7,5,0.2,-0.3
Xenns Mangird Tea|-0.8,0.1,0.6,0.2,-0.7,-0.3,-0.2,-0.3,-1,-1.3,-0.3,1.1,1.4,5.7,0.3,-1.7
Xenns Mangird Tea2|-0.7,-0.4,-0.3,-0.9,-1.7,-1.4,-1,-0.7,-0.2,-1,-2.2,1.2,1.8,5.3,5.4,2.4
Xenns Mangird Top|-1.6,-1.6,-1.8,-2.3,-2.4,-0.9,0.2,1,1.5,1.4,1.3,1.8,3.7,2.9,3.3,-0.9
Xenns UP|-1.2,-1,-1.2,-1.9,-2.8,-2.1,-1.1,0,0.9,0,-1.6,-0.2,2,4.1,3.8,1.7
XFYRO xS2|2.6,1.5,-0.2,-2,-4.1,-4.5,-3.1,-0.6,2,2.1,4.6,6.1,3.2,-2.5,-0.3,-0.1
Xiaomi Crystal|-1.8,-2.1,-2.8,-3.7,-4.6,-3.3,-1.3,0.7,3.4,3.9,5.6,3.9,1.6,0.5,-1.5,0.4
Xiaomi Hybrid|-2,-2.3,-2.9,-3.7,-4.2,-2.9,-1,0.7,2.5,1.9,0.7,0.8,0.6,6.2,7.8,2.4
Xiaomi Mi ANC|5.2,5,4.1,2.8,1.4,-2,-4.1,-4.8,-6.1,-5.7,-2.9,2.7,-5.3,2.5,5.3,1
Xiaomi Mi True Wireless Earbuds Basic 2|-6.2,-5,-3.6,-3.3,-2.3,1.2,2.3,3.4,4.3,4.3,5,5.6,2,7.3,-2.4,-4.3
Xiaomi Piston 2|-3.4,-3.5,-3.7,-4.3,-4.8,-3.1,-1,1.1,3.7,4.1,4.8,4.7,2.9,1.3,0.1,1.6
Xiaomi Piston 3|-0.3,-1.3,-2.8,-4.3,-5.4,-4.4,-2.5,-0.5,2.3,2,0.9,0.2,-0.4,-0.5,5.2,3.7
Xiaomi Piston 3 Youth Edition|0.2,-0.8,-2.3,-3.6,-5,-4.3,-2.6,-0.9,1.4,1.6,1.7,-0.3,-2.1,-4.4,5.4,4.5
XINHS HS3 (off-off)|-0.4,0.3,0.5,-0.2,-1.5,-1.7,-1.3,-0.7,-0.3,-0.9,-1.9,1.7,4.1,5.5,-0.7,0.3
XINHS HS3 (on-on)|0.2,0.7,0.5,-0.5,-2.4,-2.9,-2.7,-1.8,-1.1,-1.1,-1.4,1.9,3.5,5.7,0.8,1.5
XINHS x Akros Erasmus (off-off)|-0.5,-0.1,-0.1,-0.8,-2,-1.9,-1.4,-0.5,0,-1,-2.2,1.5,3.7,5.1,0.7,-0.5
XINHS x Akros Erasmus (off-on)|0,0.4,0.2,-0.8,-2.5,-2.8,-2.5,-1.6,-0.9,-1.4,-2,1.4,3.1,5.5,1.8,0.6
XINHS x Akros Erasmus (on-off)|-0.5,-0.2,-0.1,-0.9,-2,-2,-1.3,-0.5,0,-1,-2.2,1.5,3.8,5,0.7,-0.6
XINHS x Akros Erasmus (on-on)|0.2,0.5,0.2,-0.8,-2.5,-2.8,-2.5,-1.6,-0.9,-1.4,-2,1.4,3,5.5,1.8,0.5
XPG PRECOG S|0.5,-0.4,-3.2,-5.1,-7,-8,-6.3,-1.2,-3.6,-1.5,6.1,6.3,6.3,3.6,-0.4,4
XSL X8|-3.1,-3.1,-3.5,-4.4,-5.2,-3.7,-1.8,0.5,2.6,2.9,3,2,0.4,6.9,3.9,0.2
XTZ EarPhone-12|0.8,-0.4,-2.1,-3.8,-5.5,-5.1,-3.5,-1.7,0.7,0.8,1.3,0.6,2.4,3.3,4.4,4.6
Yamaha EPH-100|-1.3,-1.4,-1.8,-2.7,-3.6,-2.5,-1.1,0.5,2,1.2,-0.7,-1.1,7.2,7.2,6.2,0.5
Yamaha HP-300|5.2,5.2,1.3,-2.5,-7.4,-8.5,-8.3,-9.8,-8.2,0.8,0.4,5.2,5.2,5.2,5.2,5.2
Yamaha HP-50S|4.9,3.6,0,-1.7,-3.7,-4.8,-4.5,-4.4,-4.2,-2.6,3.2,0.6,1.8,4.4,3.3,3.1
Yamaha HP1|2.7,1.5,0.2,-0.9,-2.7,-4,-3.3,-3.6,-3.3,-1.9,2.3,-0.2,0.3,3.1,5.5,3.3
Yamaha HP1 (sample 1)|2.7,1.7,0.2,-0.8,-2.5,-3.8,-2.9,-3.2,-2.7,-1.9,2,0.1,1.1,3.3,4.8,1.9
Yamaha HP1 (serial number 051712)|2.8,1.4,0.3,-0.9,-2.8,-4.1,-3.5,-4,-3.8,-1.8,2.8,-0.4,-0.6,3,5.5,4.7
Yamaha HP3|4.6,3.7,0.8,-1,-3.4,-4.9,-4,-4.1,-3.8,-3.9,0.3,2.5,0.6,3.4,3.5,2.7
Yamaha HPH-MT220|-1,-1.4,-1.7,-1.6,-1.8,-1,0.4,-1,-3.2,-3.5,-0.2,6.1,3.5,3.8,1.9,0
Yamaha PRO 300|5,5,5,4.7,2.6,0.3,-0.5,-3.3,-3,-5.5,-7.6,-1.8,1.3,0.4,-2,-0.5
Yamaha PRO 400|-1.6,-2.5,-0.7,-3.8,-7.4,0.2,3.8,0.2,-3.3,-2.8,-0.9,3,-0.8,5.5,5,2.9
Yamaha PRO 500|2.7,1,0.4,-1.3,-4.8,-5.2,-1,0.4,-2.1,-4,-3.6,1.6,-2.7,4.9,3.3,1.7
Yamaha RH5Ma|4.7,1.4,-0.9,-1.7,-2.6,-1.7,-1.2,-1.6,2.5,1.5,-0.8,0.8,-1.6,-4.4,-5.3,-1.6
Yamaha YH-100|2.6,1.8,0.9,-0.2,-2,-3.4,-3,-3.9,-5.2,-4,5,-0.1,3.5,3.8,5.1,2.4
Yamaha YH-3|2.5,1.7,0.3,-0.8,-2.7,-4.3,-3.2,-3.5,-2.8,-0.1,4.8,0.5,0.6,4.3,3.1,0.5
Yamaha YH-5M|4.7,4.1,2.1,0.2,-2.8,-4.7,-5.4,-6.7,-8,-4.4,4.7,4.7,4.3,-0.8,3.5,4.6
Yamaha YH-E700A (ANC on)|-3,-1.2,-4.5,-2.9,1.4,2.1,-0.3,-1.2,1.6,1.3,1.1,2.5,4,-0.5,6.8,1.4
Yanyin Aladdin|-0.1,0,0,-0.8,-1.9,-1.9,-1.6,-1.5,-1.7,-2.4,-2,0.6,2.4,3.4,3.4,1.8
Yanyin Canon|-1.3,-0.9,-0.7,-0.8,0.1,-0.6,1.5,1.7,1.2,0,-1.3,0.9,2.2,2.1,-1.9,-1
Yanyin Canon (off-off-off)|-1.1,-0.9,-1,-1.7,-2.6,-1.5,-0.2,1.2,2.2,1.4,-1.1,1.1,2,4.4,3.7,0.8
Yanyin Canon (off-off-on)|-0.6,-0.3,-0.5,-1.2,-2.1,-1.1,0.4,1.7,2.8,1.9,-0.6,1.6,2.4,4.1,-0.5,-1.8
Yanyin Canon (off-on-off)|0.5,0.5,0.3,-0.7,-2,-1.8,-1,-0.2,0.4,-0.6,-2.9,-0.2,1.2,4,4.4,1.7
Yanyin Canon (off-on-on)|1.3,1.2,0.6,-0.4,-1.8,-1.8,-1.2,-0.6,-0.3,-1.4,-1.7,1.8,2,2.7,-1.5,-1.6
Yanyin Canon (on-off-off)|-2.1,-1.9,-2,-2.8,-3.4,-2.2,-0.6,1.1,2.5,1.8,-0.2,2,2.7,5.1,4.6,1.9
Yanyin Canon (on-on-on)|-0.2,0,-0.3,-1.1,-2.1,-1.6,-0.6,0.5,1.4,0.5,-1.6,0.9,2.4,3.9,1.2,-0.1
Yanyin Canon II (off-off)|-0.2,-0.4,-1,-1.8,-2.3,-0.6,0.2,0.6,1.2,0.6,0.6,2.9,4.4,2.4,0.5,-2.6
Yanyin Canon II (off-on)|-0.7,-0.9,-1.4,-2.3,-2.8,-1,0,0.8,1.4,1,0.9,3.3,4.8,2.8,0.8,-2.2
Yanyin Canon II (on-off)|-0.7,-0.9,-1.5,-2.4,-3,-1,0,0.8,1.4,1,0.9,3.3,4.8,2.8,0.8,-2.1
Yanyin Canon II (on-on)|-1,-1.1,-1.8,-2.7,-3.2,-1.2,0,0.9,1.7,1.2,1.2,3.6,5,3,1,-2
Yanyin Mahina|-1.6,-1.1,-0.9,-1.5,-2.4,-1.9,-1.1,-0.2,0.6,-0.4,-2.1,1.5,4,4.9,4.7,1.9
Yanyin Moonlight|0.4,0.8,0.9,0.1,-1.2,-1.5,-1.2,-0.6,-0.2,-1.4,-3,1,3.2,4.6,4.3,0.9
Yincrow RW-9|4.3,4.3,4.3,3.8,0.5,-1.2,-1.9,-2.1,-3.3,-3.3,-4.3,-3.2,-0.7,-2.1,-1,0.5
Yincrow X6|4.8,1.8,-1.8,-3.8,-5.6,-4.1,-5.4,-4.5,-4,-4.5,-2.2,3.9,4.8,2.2,3.9,4.8
Yinfu MD51|2.6,2.3,0.8,-1,-3.3,-3.5,-2.3,-0.4,1.9,2.1,0.2,1.2,0,1.4,-4.4,-0.9
Yinyoo Ash|-3.3,-2.9,-2.9,-3.3,-3.8,-1.9,0,2.2,4.1,3.4,-0.1,3.8,7,2.9,4,1.9
Yinyoo Topaz (no filter)|4.4,3.9,2.7,0.9,-1.3,-1.9,-1.2,-0.2,0.8,-0.1,-3.8,-3.3,-3.7,-2.1,1.4,1.8
Yinyoo V2|-1.8,-1.9,-2.1,-2.7,-3.1,-1.3,0.6,2.7,4.7,4.6,3.8,-0.8,1.9,-2.4,-4.7,-1.2
Ylife True Wireless Earbuds|-3.7,-2.5,-1.5,-1.5,-1.6,0,1.9,3.9,5.5,2.6,0.5,-0.7,4.2,6.7,-2.5,-2.5
YU9 Audio U-556|3.4,3.1,2,0.4,-1.8,-2.6,-2.4,-2,-1.7,-3.2,-3.9,-2.5,0.3,1.6,1.3,0.1
Yuin G1A|4.8,4.8,3.3,0.3,-2.6,-2.8,-1.7,-0.6,-0.3,-1.2,-0.9,-2.7,1.3,-8.3,-2.2,1.9
Yuin G2A|4.7,4.7,3.3,1,-1.7,-2.3,-1.5,-0.6,-0.2,-0.8,-0.9,-1.9,0.5,-6.7,-1.4,1
Yuin PK1|4.6,4.6,4.6,4,0.7,-1,-1.6,-2.4,-2.8,-3.2,-3.3,-0.2,-0.3,-1.9,-1.9,-0.7
Yuin PK2|5.8,5.8,5.8,5.8,5.8,4.2,3.8,2.1,0.2,-1.3,-3.8,-6.3,-3.4,-3.2,-6.9,-8.1
Yutai BAS02|0.2,-0.2,-1.2,-2.6,-4.5,-5,-4.1,-3.3,-1.3,-0.9,0.6,1.3,3.5,5.8,3.7,2.1
Zero Audio Carbo Basso ZH-DX210-CB|-5.4,-4.6,-3,-2.8,-2.8,-0.8,1.1,3,5,4.8,3.2,-0.1,2.7,0.8,4,6.4
Zero Audio Carbo Doppio ZH-BX700|3.9,3.3,1.8,-0.2,-2.8,-4,-3.6,-2.6,-1.4,-1.9,-3.1,-3,3.8,-3.6,0.7,4.3
Zero Audio Carbo Singolo ZH-BS150-CS|2.2,1.7,0.2,-1.7,-4.2,-5.1,-4.3,-3.4,-1,-0.1,-0.5,-4.5,4.1,-2.8,4.9,4.9
Zero Audio Carbo Tenore|-0.3,0,0.1,-0.5,-1.2,-0.2,0.7,2,2.9,1.4,-0.1,-3,4.3,4.4,0.9,-0.4
Zero Audio Duoza ZH-DWX10|-0.4,-0.7,-1.8,-3.3,-4.9,-4.1,-2.1,0.4,3.5,4.9,3.9,-1,4.7,3.8,4.1,-0.4
ZiiGaat Cinno|-0.3,-0.3,-0.7,-1.4,-2.5,-2.2,-1.5,-0.9,-0.4,-0.9,1.2,2.5,2.8,2.9,3.1,-0.1
ZiiGaat Nuo|1.4,1.2,0.5,-0.5,-1.7,-1.4,-0.6,0.3,0.8,-0.4,-0.3,1,2.1,0.8,-1.6,-4.5
Zipbuds Pro Mic|-1.4,-2,-2.6,-3.6,-4.3,-3.1,-1.1,0.9,3.7,4.9,4.9,3.1,0.9,-6.3,-1.5,-0.1
ZMF Aeolus|0.8,1.4,-0.1,-1.5,-2.9,-3.6,-3.4,-2.6,-1.8,-0.8,1.9,2.7,6.1,-1.9,2.2,3.2
ZMF Aeolus (universal perforated earpads)|3.2,3.6,2.1,0.6,-1,-1.3,-1.1,-1.5,-2.7,-3.9,2.6,0.4,5.2,0.3,-2.2,-1.2
ZMF Aeolus (universal perforated suede earpads)|4.7,3.7,2.4,0.8,-1.1,-2.3,-1.5,-1.8,-3.4,-4.9,1.8,-0.1,2.3,-3,-0.5,1.1
ZMF Aeolus (ZMF BE2 perforated suede earpads)|1.4,0.1,-0.8,-1.7,-3.4,-4,-3.5,-3,-0.2,0.1,2.6,3.1,2.7,-0.2,0.9,1.1
ZMF Aeolus (ZMF Universe perforated leather earpads)|2.2,0.5,-0.9,-1.7,-3.3,-4.2,-3.8,-2.8,-1.7,-0.9,1.2,1.8,3.5,-0.3,3.6,3.2
ZMF Aeolus (ZMF Universe perforated suede earpads)|3.2,1.5,0,-0.9,-2.6,-3.7,-3.5,-3.2,-2.4,-1.4,1.6,0.5,2.4,-0.3,2.1,2.3
ZMF Atrium (perforated lambskin BE2 earpads)|3.6,2.1,0.4,-1.1,-2.8,-3.1,-1.4,-0.3,-0.5,0.3,1.4,-1,1.6,-2.4,2.4,2.7
ZMF Atrium (solid mesh, perforated lambskin earpads)|5,3,0.8,-0.7,-3.1,-3.8,-2.6,-2.2,-1.5,-0.1,-0.4,-1.6,3.4,-0.6,2.4,3.3
ZMF Atrium (solid mesh, perforated suede earpads)|5,2.8,0.5,-1.1,-3.3,-4.1,-3.2,-3.1,-1.7,-0.7,1.1,-1.6,2.9,0,4.2,5.1
ZMF Atrium (ZMF Auteur perforated leather earpads)|3.2,1.3,-0.5,-2,-3.5,-3.9,-2.9,-1.9,-0.6,1.7,0.8,-0.6,3.5,-3.4,5.9,5.3
ZMF Atrium (ZMF BE2 perforated suede earpads)|3.1,1.1,-0.5,-1.8,-3.3,-3.6,-2.7,-1.3,1.3,1.7,1.9,-0.2,0.8,-2.7,3,2.1
ZMF Atrium (ZMF Universe perforated leather earpads)|3.5,1.6,0,-1.2,-2.9,-3.1,-2.1,-1,0.7,1.6,0.6,-1.1,1.4,-3.6,6,1.5
ZMF Atrium (ZMF Universe perforated suede earpads)|5.5,3.4,1.2,-0.1,-1.8,-2.3,-1.7,-1.5,-1.2,-0.4,0.4,-3,-0.3,-4,4.1,3
ZMF Atrium Closed (ZMF Caldera thin lambskin earpads)|-0.9,-1.1,-0.9,-1,-2.3,-3.6,-3.2,-0.5,2.3,2.9,5.9,3,0.9,-3.6,2.9,3.6
ZMF Atticus|2.3,1.3,-1.8,-4.1,-6.3,-4.1,-1.6,-1.1,-2,-0.5,2.3,2.9,5.3,-1,0.7,3.3
ZMF Atticus (Eikon suede earpads)|3.3,2.7,-0.8,-3.1,-5.6,-4.4,-1.7,-1.5,-2.2,-0.3,2.6,2.7,4.9,-2,1.3,2.8
ZMF Atticus (ZMF Eikon suede earpads)|2.5,1,0.4,-2.1,-4.8,-4.6,-2.4,-2.4,-3.5,-2.7,1.5,0.9,4.7,0.9,1.6,4.1
ZMF Atticus (ZMF Ori leather earpads)|0.8,-0.1,-1.2,-4.3,-7,-4.8,-2.8,-2.5,-2.6,-1.1,3.3,3.4,5.9,1.9,2.9,5.2
ZMF Auteur (perforated lambskin Auteur earpads)|4,2.8,1.6,0.4,-1.1,-1.8,-1.2,-1.2,-1.3,-2.5,-0.9,-0.5,1.9,-3.6,1.6,2
ZMF Auteur (ZMF Auteur perforated hybrid earpads)|2.8,1.4,-0.3,-2.2,-4.1,-5,-4.3,-2.7,-1.9,-0.8,4,2.4,3.3,-2.2,5.4,5.1
ZMF Auteur (ZMF Auteur perforated leather earpads)|3.8,2.6,0.8,-1.1,-3,-4,-3.9,-3.3,-2.7,-0.8,4.1,2.2,3.8,-1.4,2.7,3.1
ZMF Auteur (ZMF Auteur perforated suede earpads)|3.5,2.3,0.9,-1,-2.9,-3.7,-3.4,-3,-1.4,-1.1,4,1.9,3,-3,2.3,4.2
ZMF Auteur (ZMF Eikon perforated leather earpads)|4.9,3.6,1.7,0.1,-2.1,-2.9,-2.8,-2.3,-1.6,-1.4,3.3,0.4,1.2,-3.3,2.9,1.9
ZMF Auteur (ZMF Eikon perforated suede earpads)|2.9,2.2,0.2,-1.1,-3.2,-3.8,-3,-1.4,-0.8,0,4.6,2.5,3.1,-3.7,1.9,3.2
ZMF Auteur (ZMF Universe perforated leather earpads)|3.5,2.4,0.8,-0.9,-3,-3.7,-3.4,-2.3,-2,-1.3,3.8,1.4,2.7,-1.3,3.1,2.3
ZMF Auteur (ZMF Universe perforated suede earpads)|5.4,4.6,2.3,0.4,-1.6,-2.7,-2.8,-2.7,-3.1,-3,3.4,-0.3,1,-1.7,2.9,1.8
ZMF Auteur Blackwood|3.1,2.2,1,-0.4,-1.8,-2.4,-1.9,-1,-1.7,-1.8,2.5,0.4,2.9,1.2,0.4,-0.4
ZMF Auteur Blackwood (universal perforated earpads)|2.6,1.9,1,-0.2,-1.6,-2.2,-1.2,-0.9,-2.2,-3,3,-0.2,3,-0.6,1.3,0.3
ZMF Auteur Classic (ZMF Auteur perforated hybrid earpads)|2.1,1.1,-0.1,-1.5,-3.4,-3.8,-2.6,-0.9,-0.1,-0.1,1.6,1.5,0,-4.3,5.9,4.7
ZMF Auteur Classic (ZMF Auteur perforated lambskin earpads)|1.5,0.1,-1.2,-1.4,-3.7,-3.8,-2.9,-1.6,-0.6,0.6,1,4.2,2.2,-0.4,6.1,4.9
ZMF Auteur Classic (ZMF Auteur perforated leather earpads)|3.1,2,0.4,-1.1,-3,-3.9,-3.2,-2,-2,-0.1,1.4,1.8,0.9,-2.8,4.6,3.6
ZMF Auteur Classic (ZMF Auteur perforated suede earpads)|3.4,2.4,1.1,-0.5,-2.4,-3.3,-2.7,-1.8,0.5,-0.5,1.6,1,-0.3,-4.3,3.6,3.4
ZMF Auteur Classic (ZMF BE2 perforated suede earpads)|2.7,1.7,0.7,-0.7,-2.7,-3.8,-3.2,-2.2,0.7,0.4,1,2.5,-0.1,-3.3,4.3,2.7
ZMF Auteur Classic (ZMF Eikon perforated leather earpads)|4.1,3,1.4,0.2,-1.6,-2.7,-2.4,-2,-0.7,-0.9,-0.6,1,-1.2,-4.6,4.3,3.4
ZMF Auteur Classic (ZMF Eikon perforated suede earpads)|2.5,1.9,0,-1.1,-2.8,-3.7,-2.9,-0.6,0.9,1.1,2.3,2.2,-0.2,-5.2,3.2,4.5
ZMF Auteur Classic (ZMF Universe perforated lambskin earpads)|3.8,1.9,0.3,-0.2,-2.2,-2.4,-1.6,-1.3,-1.3,-0.1,1.7,2.2,-1.1,-3,3.2,2.3
ZMF Auteur Classic (ZMF Universe perforated leather earpads)|3.3,2.4,0.9,-0.3,-2.2,-3.4,-2.9,-1.4,-0.9,-0.8,1,1.2,-0.7,-3.5,4.8,3.7
ZMF Auteur Classic (ZMF Universe perforated suede earpads)|5.2,4.4,2.4,1.1,-1,-2.7,-2.6,-2.1,-2.8,-2.7,1.9,-0.9,-2.3,-4.2,4,2.6
ZMF Auteur Cocobolo|2.8,2.4,1.5,0.1,-1.2,-2.4,-2.2,-1.4,-0.5,0.6,2.2,1.7,3.5,-0.1,2.4,-0.1
ZMF Auteur Teak|2.9,2,1.7,0.8,-0.4,-0.3,0.2,0.2,0.4,-1.3,1.6,0.4,2.5,-2.7,0.9,-2.6
ZMF Bokeh|-2.4,-2.4,-1.6,-0.9,-1.3,-4.1,-4.6,-2.3,1.8,2,4.8,4.2,2,1,3.4,2.8
ZMF Caldera (cowhide earpads)|2.6,2.1,1,-0.1,-2.3,-4,-3.3,-2.8,-1.1,3.1,5.5,0.9,5,-3.2,0.6,-0.9
ZMF Caldera (lambskin earpads)|3.1,2.5,1.2,-0.1,-2.1,-3.4,-2,-1,0.1,1.9,3.9,-1.4,2,-1.9,0.7,-1
ZMF Caldera (suede earpads)|2.7,2.1,0.8,-0.4,-2.4,-3.2,-1.7,-1.1,0.2,2.6,4,-1,2.1,-3.1,0.6,-0.3
ZMF Caldera (thick earpads)|2.2,1.7,0.7,-0.5,-2.6,-4.3,-2.6,-0.7,0.5,2.9,4.8,0.3,2.3,-3.1,1.1,-0.3
ZMF Caldera (thin lambskin earpads)|2.8,2.5,1.7,0.7,-1,-2.4,-1.9,-1.3,-1.8,0.3,4,-0.8,5.4,-1.1,1.7,0.4
ZMF Eikon|0.1,-0.3,-2,-3.4,-3.6,-2.8,-1.9,-0.5,1.3,1.7,6.2,3.7,0.2,1.7,3.5,3.2
ZMF Eikon (Eikon lambskin earpads)|2,2.5,-1.5,-3.4,-4,-3.7,-2.5,-1.6,-2.4,-1.2,3.2,3.1,3.1,-3,5.8,4.8
ZMF Eikon (Protein earpads)|0.9,0.9,0.7,-1.5,-3.5,-3.2,-2.8,-2.9,-1.5,-1.9,6,2.5,2.4,-1.6,5.1,2.7
ZMF Eikon (ZMF Auteur leather earpads)|2.9,2.4,1.7,-1.5,-4.2,-4,-2.9,-1.5,-1.2,-1.3,4.8,1.3,3.7,-1,0.9,4.8
ZMF Eikon (ZMF Eikon leather earpads)|3,2.1,1.1,-1.9,-4.8,-3.8,-2.6,-0.8,-0.4,-1.4,4.9,1.1,0.7,-0.6,2.6,3.3
ZMF Eikon (ZMF Eikon suede earpads)|4.1,3.3,2.2,0.1,-1.9,-2.9,-2.5,-2.6,-3,-2.3,3.8,-0.5,-0.8,-3.1,2.9,3.6
ZMF Eikon (ZMF Universe leather earpads)|4.1,3,2.5,-0.5,-3.6,-3.2,-1.9,-1.2,-3.2,-2.9,3.9,0.2,-0.2,-2.5,2.3,2.9
ZMF Verite|2.6,2,0.5,-0.9,-2.6,-3.8,-3.5,-3.2,-2.5,-1,1,1.7,5.4,-2.5,3.7,4.4
ZMF Verite (perforated hybid BE2 earpads)|2.7,1.4,-0.1,-1.7,-3.7,-5.3,-5.2,-4.3,-1.5,0.2,1.8,3.2,5.4,-3,5.4,5.9
ZMF Verite (perforated Verite lambskin earpads)|4,3,0.9,-0.5,-2.3,-3.7,-3.5,-3.4,-2.9,-2.1,1.4,1.8,4.9,-3.2,2.8,3.4
ZMF Verite (ZMF Auteur perforated hybrid earpads)|3.6,2,0.4,-1.2,-3.4,-4.9,-4.1,-3,-2.7,-1.1,0.6,-0.9,4.9,0.3,4.4,5
ZMF Verite (ZMF BE2 perforated hybrid earpads)|2.9,1.7,0.3,-1.4,-3.4,-4.8,-4.6,-3.6,-0.7,0.2,1.4,1.2,5.9,1.3,3.6,3.3
ZMF Verite (ZMF BE2 perforated leather earpads)|4.7,3.4,1.9,0.3,-1.9,-3.3,-3.3,-3.4,-1.8,-1.3,1.1,-0.2,4.2,-0.2,2.1,1.5
ZMF Verite (ZMF Universe perforated leather earpads)|4.7,2.9,0.8,-0.8,-3.1,-4.8,-4.7,-4.2,-4.2,-0.9,0.6,-0.9,5.2,1.2,5.5,5.5
ZMF Verite (ZMF Universe perforated suede earpads)|5.1,5.1,2.7,1,-1.6,-3.7,-3.9,-3.6,-3.2,-2.9,-0.1,-2.8,3.2,0,3.4,3.9
ZMF Verite Closed|-0.1,0.3,-0.8,-2.3,-2,-1.9,-2.4,-0.7,0,0.2,2.5,-0.4,5.2,1.8,0.7,1.4
ZMF Verite Closed (Auteur lambskin earpads)|1.7,1.4,-1.1,-3.3,-3.4,-2.6,-2.6,-2.1,-1.5,-0.9,2.1,0.7,5,-2.4,3.8,5.7
ZMF Verite Closed (hybrid Universe earpads)|0.7,0,-1,-3.2,-2.7,-2,-1.9,-1.9,-1.6,0.4,3.2,0.5,6.2,-0.8,0.5,2.9
ZMF Verite Closed (ZMF Auteur hybrid earpads)|0.5,-0.8,-0.9,-2.4,-3.7,-2,-3.9,-4.2,-1.6,0.2,1.8,1,6.2,0.4,6,4.9
ZMF Verite Closed (ZMF Auteur leather earpads)|-0.5,-1.4,-1,-3.5,-4.9,-3.4,-3.7,-1.6,-0.6,-0.2,3.1,1.3,6.4,2,5.8,5.6
ZMF Verite Closed (ZMF Eikon leather earpads)|-1,-1.4,-1.3,-4.6,-6.7,-2.2,-3.5,-1.9,0.6,1.2,3.8,2,6.8,2.6,5.2,4.1
ZMF Verite Closed (ZMF Eikon suede earpads)|0.2,-0.5,-0.6,-2.2,-2,-3.1,-3.5,-2.7,-1.6,0,2.5,0.3,5.3,-0.9,3.4,4.7
ZMF Verite Closed (ZMF Universe hybrid earpads)|-0.7,-1.8,-1.7,-3.3,-4.8,-2.1,-2.7,-1.3,-1.9,0.3,3.7,1.4,6.3,2.3,2.7,4.2
ZMF Verite Closed (ZMF Universe leather earpads)|0.7,-0.6,-1,-3,-5,-2.2,-3.1,-1.1,-2.5,-0.4,3.1,0.8,5.9,0.5,2.8,4.3
Zoukbox ZDY10 Bass|-1.4,-1.6,-2.4,-3.5,-4.6,-3.4,-1.4,0.6,3.4,4.1,5.1,3.9,-0.1,-1.3,0.3,0.7
Zoukbox ZLX30|-3.4,-2.9,-2.6,-2.9,-2.9,-1.2,0.7,2.2,4,3.1,1.9,-1.2,-1.9,-0.8,7.5,2.7"""
    )

    val HP: List<HpModel> by lazy {
        HP_RAW.joinToString("\n").lines().filter { it.contains("|") }.map { line ->
            val p = line.split("|")
            HpModel(p[0], p[1].split(",").map { it.toFloat() }.toFloatArray())
        }
    }

    private fun hpAt(v: FloatArray, hz: Double): Float {
        if (hz <= HP_F.first()) return v.first()
        if (hz >= HP_F.last()) return v.last()
        var i = 0
        while (i < HP_F.size - 2 && HP_F[i + 1] <= hz) i++
        val t = ((ln(hz) - ln(HP_F[i])) / (ln(HP_F[i + 1]) - ln(HP_F[i]))).toFloat()
        return v[i] + (v[i + 1] - v[i]) * t
    }

    /** Applies a headphone correction. Uses the 31-band Pro engine when the phone supports it, else the normal equalizer. */
    fun applyHeadphone(m: HpModel) {
        var maxPos = 0f
        if (proOk) {
            for (i in PRO_C.indices) {
                val g = hpAt(m.v, PRO_C[i]).coerceIn(-12f, 12f)
                g31[i] = g
                maxPos = maxOf(maxPos, g)
            }
            for (b in levels.indices) levels[b] = 0
            proOn = true; headroom = true; pre = 0f
        } else {
            val info = eqInfo
            for (b in info.freqs.indices) {
                val g = hpAt(m.v, info.freqs[b].toDouble())
                levels[b] = (g * 100).toInt().coerceIn(info.min, info.max)
                maxPos = maxOf(maxPos, g)
            }
            for (i in g31.indices) g31[i] = 0f
            pre = -maxPos.coerceIn(0f, 12f)
        }
        clar = 0f
        preset = m.name
        save(); applyAll()
    }

    fun applyProfile(p: Profile) {
        val info = eqInfo
        for (b in info.freqs.indices) {
            levels[b] = (curveAt(p.curve, info.freqs[b]) * 100).toInt().coerceIn(info.min, info.max)
        }
        clar = p.clar; pre = p.pre
        bass = (p.bass / 12f * 1000).toInt().coerceIn(0, 1000); bassOn = p.bass > 0
        virt = (p.wide * 10).toInt().coerceIn(0, 1000); virtOn = p.wide > 0
        preset = p.name
        save(); applyAll()
    }

    fun applyPreset(p: Preset) {
        val info = eqInfo
        for (b in info.freqs.indices) {
            levels[b] = (curveAt(p.curve, info.freqs[b]) * 100).toInt().coerceIn(info.min, info.max)
        }
        if (p.bass >= 0) { bass = p.bass; bassOn = p.bass > 0 }
        if (p.surround >= 0) { virt = p.surround; virtOn = p.surround > 0 }
        if (p.loud >= 0) { loud = p.loud; loudOn = p.loud > 0 }
        clar = 0f; pre = 0f
        preset = p.name
        save(); applyAll()
    }

    fun resetFlat() {
        for (b in levels.indices) levels[b] = 0
        clar = 0f; pre = 0f
        for (i in g31.indices) g31[i] = 0f
        for (i in pf.indices) pf[i] = pf[i].copy(gain = 0f)
        preset = ""
        save(); applyAll()
    }

    private val chains = HashMap<Int, Chain>()

    class Chain(val session: Int) {
        private val eq = runCatching { Equalizer(1000, session) }.getOrNull()
        private val bb = runCatching { BassBoost(1000, session) }.getOrNull()
        private val vz = runCatching { Virtualizer(1000, session) }.getOrNull()
        private val ld = runCatching { LoudnessEnhancer(session) }.getOrNull()
        private val rv = runCatching { EnvironmentalReverb(1000, session) }.getOrNull()
        private val dp = runCatching {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, PRO_C.size, true, 5, false, 0, true
            ).build()
            DynamicsProcessing(0, session, cfg)
        }.getOrNull()

        init { if (dp != null) proOk = true }

        fun apply() {
            runCatching {
                eq?.let { e ->
                    for (b in 0 until e.numberOfBands) {
                        val v = eff(b)
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
            runCatching {
                rv?.let {
                    it.decayTime = (200 + rvTime * 30).toInt()
                    it.reverbLevel = (-6000 + rvMix * 60).toInt().toShort()
                    it.enabled = rvOn && !hifi
                }
            }
            runCatching {
                dp?.let { d ->
                    for (i in PRO_C.indices) {
                        val g = (g31.getOrElse(i) { 0f } + proDb(PRO_C[i]).toFloat()).coerceIn(-15f, 15f)
                        d.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, PRO_EDGE[i], g))
                    }
                    val cut = when (mbcBands) {
                        3 -> doubleArrayOf(250.0, 2500.0, 20000.0)
                        4 -> doubleArrayOf(150.0, 800.0, 4000.0, 20000.0)
                        else -> doubleArrayOf(100.0, 400.0, 1600.0, 6000.0, 20000.0)
                    }
                    val ci = mbcChar.coerceIn(0, 2)
                    val att = floatArrayOf(30f, 10f, 40f)[ci]
                    val rel = floatArrayOf(300f, 150f, 100f)[ci]
                    val ratio = 1f + mbcAmt / 100f * 3f
                    val makeup = mbcAmt / 100f * 3f
                    for (b in 0 until 5) {
                        val used = b < cut.size
                        val cf = if (used) cut[b].toFloat() else 20000f + (b - cut.size + 1) * 500f
                        d.setMbcBandAllChannelsTo(
                            b, DynamicsProcessing.MbcBand(used && mbcOn && !hifi, cf, att, rel, ratio, -20f, 6f, -90f, 1f, 0f, makeup)
                        )
                    }
                    var maxPos = 0.0
                    if (headroom) {
                        for (i in PRO_C.indices) maxPos = maxOf(maxPos, g31.getOrElse(i) { 0f } + proDb(PRO_C[i]))
                    }
                    val head = -maxPos.toFloat()
                    val gl = head - (if (balance > 0) balance / 100f * 30f else 0f)
                    val gr = head - (if (balance < 0) -balance / 100f * 30f else 0f)
                    d.setInputGainbyChannel(0, gl)
                    d.setInputGainbyChannel(1, gr)
                    val (la, lr, lratio) = when (limMode) {
                        1 -> Triple(0.5f, 120f, 20f)
                        2 -> Triple(3f, 30f, 6f)
                        else -> Triple(1f, 60f, 10f)
                    }
                    d.setLimiterAllChannelsTo(
                        DynamicsProcessing.Limiter(true, limOn, 0, la, lr, lratio, limCeil, if (hifi) 0f else boost)
                    )
                    d.enabled = proOn
                }
            }
        }

        fun release() {
            eq?.release(); bb?.release(); vz?.release(); ld?.release(); dp?.release(); rv?.release()
        }
    }

    fun init(c: Context) {
        if (sp != null) return
        val p = c.getSharedPreferences("fx", Context.MODE_PRIVATE)
        sp = p
        eqOn = p.getBoolean("eqOn", true)
        hifi = p.getBoolean("hifi", false)
        preset = p.getString("preset", "").orEmpty()
        clar = p.getFloat("clar", 0f); pre = p.getFloat("pre", 0f); theme = p.getInt("theme", 0)
        proOn = p.getBoolean("proOn", false); limOn = p.getBoolean("limOn", true)
        limCeil = p.getFloat("limCeil", -1f); boost = p.getFloat("boost", 0f)
        headroom = p.getBoolean("headroom", false); balance = p.getFloat("balance", 0f)
        mbcOn = p.getBoolean("mbcOn", false); mbcBands = p.getInt("mbcBands", 3); mbcChar = p.getInt("mbcChar", 0)
        mbcAmt = p.getFloat("mbcAmt", 40f); limMode = p.getInt("limMode", 0)
        rvOn = p.getBoolean("rvOn", false); rvTime = p.getFloat("rvTime", 40f); rvMix = p.getFloat("rvMix", 30f)
        pf.clear()
        val pfs = p.getString("pf", "").orEmpty().split(";").mapNotNull { s ->
            runCatching {
                val t = s.split(",")
                PF(t[0].toInt(), t[1].toFloat(), t[2].toFloat(), t[3].toFloat(), t[4] == "1")
            }.getOrNull()
        }
        if (pfs.size == 6) pf.addAll(pfs) else pf.addAll(defaultPf())
        g31.clear()
        val gs = p.getString("g31", "").orEmpty().split(",").mapNotNull { it.toFloatOrNull() }
        g31.addAll(if (gs.size == PRO_C.size) gs else List(PRO_C.size) { 0f })
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
            ?.putFloat("clar", clar)?.putFloat("pre", pre)?.putInt("theme", theme)
            ?.putBoolean("proOn", proOn)?.putBoolean("limOn", limOn)?.putFloat("limCeil", limCeil)?.putFloat("boost", boost)
            ?.putBoolean("headroom", headroom)?.putFloat("balance", balance)
            ?.putBoolean("mbcOn", mbcOn)?.putInt("mbcBands", mbcBands)?.putInt("mbcChar", mbcChar)
            ?.putFloat("mbcAmt", mbcAmt)?.putInt("limMode", limMode)
            ?.putBoolean("rvOn", rvOn)?.putFloat("rvTime", rvTime)?.putFloat("rvMix", rvMix)
            ?.putString("pf", pf.joinToString(";") { "${it.type},${it.freq},${it.gain},${it.q},${if (it.on) 1 else 0}" })
            ?.putString("g31", g31.joinToString(","))
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

/** Built-in 6 second test sweep, so you can hear the effects without a music player. */
object Sweep {
    private var track: AudioTrack? = null
    private var session = 0
    var playing by mutableStateOf(false)
    private val handler = Handler(Looper.getMainLooper())

    fun start() {
        stop()
        val sr = 48000
        val sec = 6
        val n = sr * sec
        val data = FloatArray(n * 2)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val f = 35.0 * Math.pow(18000.0 / 35.0, t / sec)
            ph += 2 * Math.PI * f / sr
            val env = minOf(1.0, t * 15) * minOf(1.0, (sec - t) * 15)
            val s = Math.sin(ph) * 0.2 * env
            val p = 0.5 + 0.5 * Math.sin(1.2 * t)
            data[2 * i] = (s * Math.sqrt(1 - p)).toFloat()
            data[2 * i + 1] = (s * Math.sqrt(p)).toFloat()
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sr)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val tr = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(data.size * 4)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        tr.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)
        session = tr.audioSessionId
        Fx.attach(session)
        tr.play()
        track = tr
        playing = true
        handler.postDelayed({ stop() }, (sec * 1000 + 300).toLong())
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
        if (session != 0) { Fx.release(session); session = 0 }
        playing = false
    }
}

/** Optional root helpers. They only read system info or whitelist this app. They never edit system files. */
object Root {
    var out by mutableStateOf("")
    var busy by mutableStateOf(false)

    fun run(label: String, cmd: String, filter: Regex? = null, maxLines: Int = 20) {
        if (busy) return
        busy = true
        out = "Running..."
        Thread {
            val text = try {
                val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
                val s = p.inputStream.bufferedReader().readText()
                p.waitFor()
                s
            } catch (e: Exception) {
                "No root access (su not found or denied)."
            }
            val shown = text.lines()
                .filter { it.isNotBlank() && (filter == null || filter.containsMatchIn(it)) }
                .take(maxLines)
                .joinToString("\n") { it.trim() }
            Handler(Looper.getMainLooper()).post {
                out = label + "\n" + shown.ifBlank { "Nothing found, or root was denied." }
                busy = false
            }
        }.start()
    }
}
