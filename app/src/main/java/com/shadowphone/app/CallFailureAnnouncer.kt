package com.shadowphone.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/** Plays a system-generated busy cadence, then a language-matched live TTS announcement. */
internal class CallFailureAnnouncer(context: Context) : AutoCloseable {
    companion object {
        private const val TONE_DURATION_MS = 2_400
        private const val UTTERANCE_ID = "shadow-phone-call-failure"
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var ttsReady = false
    private var tts: TextToSpeech? = null
    private var tone: ToneGenerator? = null

    init {
        try {
            tts = TextToSpeech(appContext) { result ->
                handler.post { ttsReady = result == TextToSpeech.SUCCESS && !closed }
            }
        } catch (_: RuntimeException) {
            ttsReady = false
        }
    }

    fun announce() {
        handler.post {
            if (closed) return@post
            val locales = appContext.resources.configuration.locales
            val configuredLocale = if (locales.size() > 0) locales.get(0) else Locale.getDefault()
            val locale = if (configuredLocale.language.equals("ar", ignoreCase = true)) Locale("ar") else Locale.ENGLISH
            val arabic = locale.language == "ar"
            val announcement = if (arabic) {
                "الرقم المطلوب قد يكون مغلقاً أو قد يكون خارج نطاق الخدمة، يرجى المحاولة لاحقاً وشكراً."
            } else {
                "The requested number may be switched off or out of service, please try again later. Thank you."
            }

            try {
                tone?.release()
                tone = ToneGenerator(AudioManager.STREAM_MUSIC, 85).also {
                    it.startTone(ToneGenerator.TONE_SUP_BUSY, TONE_DURATION_MS)
                }
            } catch (_: RuntimeException) {
                // Continue with TTS if the device cannot create a ToneGenerator.
            }
            handler.postDelayed({
                if (closed) return@postDelayed
                try { tone?.stopTone() } catch (_: RuntimeException) { }
                speakWhenReady(locale, announcement, arabic, 0)
            }, TONE_DURATION_MS.toLong() + 150L)
        }
    }

    private fun speakWhenReady(locale: Locale, text: String, arabic: Boolean, attempt: Int) {
        if (closed) return
        val engine = tts
        if (!ttsReady || engine == null) {
            if (attempt < 50) handler.postDelayed({ speakWhenReady(locale, text, arabic, attempt + 1) }, 100L)
            return
        }
        try {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            val languageResult = engine.setLanguage(locale)
            val voiceLocale = if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Locale.getDefault().also { engine.setLanguage(it) }
            } else locale
            selectVoice(engine, voiceLocale, preferFemale = arabic)
            engine.setSpeechRate(0.96f)
            engine.setPitch(if (arabic) 1.04f else 0.96f)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "$UTTERANCE_ID-${System.nanoTime()}")
        } catch (_: RuntimeException) {
            // TTS availability and voice support vary by installed system engine.
        }
    }

    private fun selectVoice(engine: TextToSpeech, locale: Locale, preferFemale: Boolean) {
        try {
            val matching = engine.voices.orEmpty().filter { it.locale.language.equals(locale.language, ignoreCase = true) }
            val genderWords = if (preferFemale) listOf("female", "fem", "woman", "girl") else listOf("male", "masc", "man", "boy")
            val namedGender = matching.firstOrNull { voice -> genderWords.any { word -> voice.name.contains(word, ignoreCase = true) } }
            val chosen: Voice? = namedGender ?: matching.maxByOrNull { it.quality }
            if (chosen != null) engine.voice = chosen
        } catch (_: RuntimeException) {
            // Keep the language's default voice when the engine exposes no selectable voices.
        }
    }

    override fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        try { tone?.stopTone(); tone?.release() } catch (_: RuntimeException) { }
        tone = null
        try { tts?.stop(); tts?.shutdown() } catch (_: RuntimeException) { }
        tts = null
    }
}
