package com.example.hififx

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.BassBoost
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
        clar = p.getFloat("clar", 0f); pre = p.getFloat("pre", 0f); theme = p.getInt("theme", 0)
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
