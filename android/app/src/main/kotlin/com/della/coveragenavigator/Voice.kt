package com.della.coveragenavigator

import android.content.Context
import android.content.res.Configuration
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Spoken turn instructions. Uses Persian when the device has a Persian voice and the app is in
 * Persian; otherwise English (never Persian text read by an English voice).
 * Each maneuver is announced at most once per distance band: ~300 m, ~100 m, and "now" (~25 m).
 */
class Voice(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var ready = false
    private var speakContext: Context = context
    private val spoken = HashSet<String>()
    private var lastKey = ""

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        val appLocale = androidx.core.os.ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.getDefault()
        val fa = Locale.forLanguageTag("fa")
        val wantFa = appLocale.language == "fa"
        val loc = if (wantFa && t.isLanguageAvailable(fa) >= TextToSpeech.LANG_AVAILABLE) fa else Locale.ENGLISH
        t.language = loc
        val cfg = Configuration(context.resources.configuration)
        cfg.setLocale(loc)
        speakContext = context.createConfigurationContext(cfg)
        ready = true
    }

    fun stop() {
        tts?.stop()
    }

    fun announce(nav: NavInstruction?, enabled: Boolean) {
        if (!enabled || !ready || nav == null) return
        if (nav.key != lastKey) {
            lastKey = nav.key
            spoken.removeAll { !it.startsWith(nav.key + "|") }
        }
        val d = nav.distanceMeters
        val band = when {
            nav.mode == "to_road" -> "road"
            d <= 30 -> "now"
            d in 70.0..140.0 -> "100"
            d in 220.0..400.0 -> "300"
            else -> return
        }
        val id = nav.key + "|" + band
        if (!spoken.add(id)) return
        val text = phrase(nav, band) ?: return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun s(res: Int, vararg args: Any) = speakContext.getString(res, *args)

    private fun action(type: String, verified: Boolean?): String = when (type) {
        "slight_right" -> s(R.string.mv_slight_right)
        "right" -> s(R.string.mv_right)
        "sharp_right" -> s(R.string.mv_sharp_right)
        "slight_left" -> s(R.string.mv_slight_left)
        "left" -> s(R.string.mv_left)
        "sharp_left" -> s(R.string.mv_sharp_left)
        "turnaround" -> if (verified == true) s(R.string.mv_turnaround) else s(R.string.voice_turnaround_if_safe)
        "arrive" -> s(R.string.mv_arrive)
        "join" -> s(R.string.nav_join_route)
        else -> s(R.string.mv_continue)
    }

    private fun phrase(n: NavInstruction, band: String): String? {
        if (n.mode == "to_road") return s(R.string.voice_head_to_road)
        val act = action(n.type, n.turnaroundVerified)
        val road = n.roadName?.let { " " + s(R.string.voice_onto, it) } ?: ""
        return when (band) {
            "now" -> act + road
            "100" -> s(R.string.voice_in_meters, 100, act + road)
            else -> s(R.string.voice_in_meters, 300, act + road)
        }
    }
}
