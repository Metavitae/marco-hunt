package com.metavitae.marcohunt

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.metavitae.marcohunt.engine.Types.Band

/**
 * How the private temperature signal reaches the player (founder's rule):
 * colour and sound while the camera board is showing, vibration only when the camera is off
 * (Google advises against vibration during AR). Quiet mode (earphones or nobody overhearing): no sound.
 * The sounds are real instrument samples (CC0), see assets/sound/LICENSES.md.
 */
object Signal {
    // Real instruments (CC0, from The Sound Portrait's set): harp for HOT, nylon guitar (lute-like) for WARM, flute for COLD.
    private var pool: android.media.SoundPool? = null
    private val ids = HashMap<String, Int>()

    fun init(ctx: Context) {
        if (pool != null) return
        val p = android.media.SoundPool.Builder().setMaxStreams(6)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).build()
        for (n in listOf("harp_G5", "nylon_E4", "flute_A4")) {
            try { ctx.assets.openFd("sound/$n.ogg").use { ids[n] = p.load(it, 1) } } catch (e: Exception) { }
        }
        pool = p
    }

    // (sample, playback rate, start time s): D major pentatonic figures. Rates stay within SoundPool's 0.5..2.0.
    private fun notes(b: Band): List<Triple<String, Float, Long>> = when (b) {
        Band.HOT -> listOf(Triple("harp_G5", 587.33f / 784f, 0L), Triple("harp_G5", 739.99f / 784f, 110L), Triple("harp_G5", 880f / 784f, 220L))
        Band.WARM -> listOf(Triple("nylon_E4", 440f / 329.63f, 0L), Triple("nylon_E4", 493.88f / 329.63f, 170L))
        Band.COLD -> listOf(Triple("flute_A4", 293.66f / 440f, 0L))
    }

    private val h = android.os.Handler(android.os.Looper.getMainLooper())

    fun play(b: Band) {
        val p = pool ?: return
        for ((n, rate, t) in notes(b)) {
            val id = ids[n] ?: continue
            h.postDelayed({ p.play(id, 0.9f, 0.9f, 1, 0, rate) }, t)
        }
    }

    /** HOT: three quick buzzes · WARM: two · COLD: one long soft one. */
    fun buzz(ctx: Context, b: Band) {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        val pattern = when (b) {
            Band.HOT -> longArrayOf(0, 70, 90, 70, 90, 70)
            Band.WARM -> longArrayOf(0, 90, 140, 90)
            Band.COLD -> longArrayOf(0, 260)
        }
        try { if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1)) } catch (e: Exception) { }
    }

    fun quiet(ctx: Context) = ctx.getSharedPreferences("marcohunt", Context.MODE_PRIVATE).getBoolean("quiet", false)
    fun setQuiet(ctx: Context, q: Boolean) = ctx.getSharedPreferences("marcohunt", Context.MODE_PRIVATE).edit().putBoolean("quiet", q).apply()
}
