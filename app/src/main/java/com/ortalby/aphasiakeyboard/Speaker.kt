package com.ortalby.aphasiakeyboard

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.widget.TextView
import java.util.Locale
import java.util.UUID

/**
 * Hebrew text-to-speech on the phone. While reading, the word being spoken is highlighted
 * in the TextView that shows the text, to support reading along.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {

    enum class State { INITIALIZING, READY, NO_HEBREW_VOICE, FAILED }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val tts = TextToSpeech(appContext, this)
    private var pending: (() -> Unit)? = null
    private var currentUtterance: String? = null
    private var currentView: TextView? = null
    private var currentText: String = ""

    var state: State = State.INITIALIZING
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            state = State.FAILED
            return
        }
        val result = tts.setLanguage(HEBREW)
        state = if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            State.NO_HEBREW_VOICE
        } else {
            State.READY
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}

            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                main.post { if (utteranceId == currentUtterance) highlight(start, end) }
            }

            override fun onDone(utteranceId: String) {
                main.post { if (utteranceId == currentUtterance) clearHighlight() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                main.post { if (utteranceId == currentUtterance) clearHighlight() }
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                main.post { if (utteranceId == currentUtterance) clearHighlight() }
            }
        })
        main.post {
            pending?.invoke()
            pending = null
        }
    }

    /** Reads [text] aloud. If [view] is given, it shows [text] and highlights the spoken word. */
    fun speak(text: String, rate: Float, view: TextView? = null) {
        if (state == State.INITIALIZING) {
            pending = { speak(text, rate, view) }
            return
        }
        if (state != State.READY) return
        clearHighlight()
        currentView = view
        currentText = text
        val id = UUID.randomUUID().toString()
        currentUtterance = id
        tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() {
        pending = null
        currentUtterance = null
        clearHighlight()
        tts.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun highlight(start: Int, end: Int) {
        val view = currentView ?: return
        if (start < 0 || end > currentText.length || start >= end) return
        val spannable = SpannableString(currentText)
        spannable.setSpan(BackgroundColorSpan(HIGHLIGHT), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        view.text = spannable
    }

    private fun clearHighlight() {
        currentView?.text = currentText
        currentView = null
    }

    companion object {
        val HEBREW: Locale = Locale.forLanguageTag("he-IL")
        private val HIGHLIGHT = Color.parseColor("#FFE082")
    }
}
