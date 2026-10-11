package com.ortalby.aphasiakeyboard

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The keyboard. Layout is built for the left thumb: the mode buttons are a column on the left,
 * backspace is on the left, and every target is large.
 *
 * Modes:
 *  READ    – the messages on screen, read aloud with the spoken word highlighted, and simplified on request
 *  REPLIES – three suggested replies to the open chat
 *  BUILD   – type a few words (or a full message) on a large Hebrew keyboard
 *  OPTIONS – three full sentences built from the typed words
 *  PHRASES – her saved phrases
 *  CONFIRM – the message is read aloud; it is sent only when she presses "send"
 */
class AphasiaKeyboardService : InputMethodService() {

    private enum class Mode { READ, REPLIES, BUILD, OPTIONS, PHRASES, CONFIRM }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: AppPrefs
    private lateinit var speaker: Speaker

    private lateinit var rightArea: LinearLayout
    private val navButtons = mutableMapOf<Mode, TextView>()

    private var mode = Mode.READ
    private var renderToken = 0
    private val draft = StringBuilder()
    private var confirmText = ""
    private var confirmReturnMode = Mode.BUILD

    /** Simplified versions already computed, by original message text. */
    private val simplifiedCache = mutableMapOf<String, String>()
    private var repliesCacheKey: String? = null
    private var repliesCache: List<String> = emptyList()
    private var optionsCacheKey: String? = null
    private var optionsCache: List<String> = emptyList()

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        speaker = Speaker(this)
    }

    override fun onDestroy() {
        scope.cancel()
        speaker.shutdown()
        super.onDestroy()
    }

    private fun dp(v: Int) = UiKit.dp(this, v)

    // ---------------------------------------------------------------- layout

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundColor(UiKit.BACKGROUND)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(KEYBOARD_HEIGHT_DP))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val nav = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(nav, LinearLayout.LayoutParams(dp(92), LinearLayout.LayoutParams.MATCH_PARENT))

        fun addNav(m: Mode?, label: String, action: () -> Unit) {
            val b = UiKit.button(this, label, UiKit.PRIMARY, filled = false, textSizeSp = 17f) { action() }
            nav.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            })
            if (m != null) navButtons[m] = b
        }
        addNav(Mode.READ, "📖\nקריאה") { setMode(Mode.READ) }
        addNav(Mode.REPLIES, "💬\nתשובות") { setMode(Mode.REPLIES) }
        addNav(Mode.BUILD, "✏️\nכתיבה") { setMode(Mode.BUILD) }
        addNav(Mode.PHRASES, "⭐\nביטויים") { setMode(Mode.PHRASES) }
        addNav(null, "🌐\nמקלדת") { switchToNextInputMethod(false) }

        rightArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(4), 0, dp(2), 0)
        }
        root.addView(rightArea, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        render()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (!restarting) {
            mode = if (isWhatsAppEditor()) Mode.READ else Mode.BUILD
        }
        render()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        speaker.stop()
        super.onFinishInputView(finishingInput)
    }

    private fun isWhatsAppEditor(): Boolean =
        currentInputEditorInfo?.packageName in WhatsAppAccessibilityService.PACKAGES

    private fun setMode(m: Mode) {
        mode = m
        render()
    }

    private fun render() {
        if (!::rightArea.isInitialized) return
        speaker.stop()
        renderToken++
        val navMode = when (mode) {
            Mode.OPTIONS -> Mode.BUILD
            Mode.CONFIRM -> null
            else -> mode
        }
        navButtons.forEach { (m, b) -> styleNav(b, m == navMode) }
        rightArea.removeAllViews()
        when (mode) {
            Mode.READ -> renderRead()
            Mode.REPLIES -> renderReplies()
            Mode.BUILD -> renderBuild()
            Mode.OPTIONS -> renderOptions()
            Mode.PHRASES -> renderPhrases()
            Mode.CONFIRM -> renderConfirm()
        }
    }

    private fun styleNav(b: TextView, active: Boolean) {
        b.background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#33000000")),
            UiKit.rounded(this, if (active) UiKit.PRIMARY else UiKit.CARD, if (active) null else UiKit.PRIMARY),
            null,
        )
        b.setTextColor(if (active) android.graphics.Color.WHITE else UiKit.PRIMARY)
    }

    /** A scrolling list that fills the right area. */
    private fun scrollingList(): LinearLayout {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        scroll.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        rightArea.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return list
    }

    private fun scrollToBottom(list: LinearLayout) {
        val scroll = list.parent as? ScrollView ?: return
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addInfo(list: LinearLayout, message: String, color: Int = UiKit.TEXT_SECONDARY): TextView {
        val t = UiKit.text(this, message, 19f, color)
        list.addView(t, UiKit.matchWrap(this, 8))
        return t
    }

    private fun openSettingsButton(list: LinearLayout) {
        val b = UiKit.button(this, "⚙️ פתיחת הגדרות האפליקציה", UiKit.PRIMARY) {
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        list.addView(b, UiKit.matchWrap(this, 10))
    }

    // ---------------------------------------------------------------- chat access

    private sealed class ChatResult {
        data class Ok(val snapshot: ChatSnapshot) : ChatResult()
        data object NoService : ChatResult()
        data object NoChat : ChatResult()
    }

    private fun readChat(): ChatResult {
        val service = WhatsAppAccessibilityService.instance ?: return ChatResult.NoService
        val snap = service.readOpenChat() ?: return ChatResult.NoChat
        return ChatResult.Ok(snap)
    }

    /** Shows why the chat can't be read. Returns the snapshot when it can. */
    private fun chatOrExplain(list: LinearLayout): ChatSnapshot? = when (val r = readChat()) {
        is ChatResult.Ok -> r.snapshot
        ChatResult.NoService -> {
            addInfo(list, "כדי לקרוא הודעות צריך להפעיל את שירות הנגישות של מקלדת אפאזיה.", UiKit.ORANGE)
            openSettingsButton(list)
            null
        }
        ChatResult.NoChat -> {
            addInfo(list, "לא רואה שיחה פתוחה בוואטסאפ. פתחי שיחה ולחצי שוב.", UiKit.ORANGE)
            null
        }
    }

    // ---------------------------------------------------------------- READ

    private fun renderRead() {
        val list = scrollingList()
        val snap = chatOrExplain(list) ?: return
        snap.chatTitle?.let {
            list.addView(UiKit.text(this, it, 22f, UiKit.PRIMARY, bold = true), UiKit.matchWrap(this))
        }
        if (snap.messages.isEmpty()) {
            addInfo(list, "אין הודעות על המסך.")
            return
        }
        snap.messages.takeLast(6).forEach { msg -> list.addView(messageCard(msg), UiKit.matchWrap(this, 8)) }
        scrollToBottom(list)
    }

    private fun messageCard(msg: ChatMessage): View {
        val card = UiKit.card(this, if (msg.outgoing) UiKit.CARD_OUTGOING else UiKit.CARD)
        val label = if (msg.outgoing) "אני" else (msg.sender ?: "")
        if (label.isNotEmpty()) card.addView(UiKit.text(this, label, 16f, UiKit.TEXT_SECONDARY, bold = true))
        val body = UiKit.text(this, msg.text, if (msg.outgoing) 19f else 23f)
        card.addView(body)

        val actions = UiKit.row(this)
        actions.addView(UiKit.button(this, "🔊 הקראה", UiKit.PRIMARY, filled = false, textSizeSp = 18f) {
            speaker.speak(msg.text, prefs.speechRate, body)
        }, UiKit.weighted(this))

        if (!msg.outgoing) {
            val simpleBox = UiKit.card(this, UiKit.SIMPLE_BOX, UiKit.ORANGE).apply { visibility = View.GONE }
            val simpleText = UiKit.text(this, "", 24f)
            simpleBox.addView(simpleText)
            val simplifyButton = UiKit.button(this, "✨ פשט", UiKit.ORANGE, filled = false, textSizeSp = 18f) {}
            simplifyButton.setOnClickListener { simplify(msg, simplifyButton, simpleBox, simpleText) }
            actions.addView(simplifyButton, UiKit.weighted(this))
            card.addView(actions, UiKit.matchWrap(this, 4))
            card.addView(simpleBox, UiKit.matchWrap(this, 6))
            simplifiedCache[msg.text]?.let { cached ->
                simpleText.text = cached
                simpleBox.visibility = View.VISIBLE
            }
        } else {
            card.addView(actions, UiKit.matchWrap(this, 4))
        }
        return card
    }

    private fun simplify(msg: ChatMessage, button: TextView, box: LinearLayout, textView: TextView) {
        simplifiedCache[msg.text]?.let { cached ->
            textView.text = cached
            box.visibility = View.VISIBLE
            speaker.speak(cached, prefs.speechRate, textView)
            return
        }
        val token = renderToken
        button.isEnabled = false
        box.visibility = View.VISIBLE
        textView.text = thinkingMessage()
        scope.launch {
            val result = runModel(Prompts.simplify(msg))
            if (token != renderToken) return@launch
            button.isEnabled = true
            result.onSuccess { simple ->
                simplifiedCache[msg.text] = simple
                textView.text = simple
                speaker.speak(simple, prefs.speechRate, textView)
            }.onFailure { e -> textView.text = errorMessage(e) }
        }
    }

    // ---------------------------------------------------------------- REPLIES

    private fun renderReplies() {
        val list = scrollingList()
        val snap = chatOrExplain(list) ?: return
        if (snap.messages.isEmpty()) {
            addInfo(list, "אין הודעות על המסך שאפשר לענות עליהן.")
            return
        }
        val last = snap.lastIncoming
        if (last != null) {
            val card = UiKit.card(this)
            card.addView(UiKit.text(this, last.sender ?: snap.chatTitle ?: "", 16f, UiKit.TEXT_SECONDARY, bold = true))
            val body = UiKit.text(this, last.text, 20f)
            card.addView(body)
            card.setOnClickListener { speaker.speak(last.text, prefs.speechRate, body) }
            list.addView(card, UiKit.matchWrap(this))
        }

        val key = snap.transcript()
        if (key == repliesCacheKey && repliesCache.isNotEmpty()) {
            showReplyOptions(list, repliesCache)
            return
        }
        val status = addInfo(list, thinkingMessage())
        val token = renderToken
        scope.launch {
            val result = runModel(Prompts.suggestReplies(prefs.profile, snap))
            if (token != renderToken) return@launch
            result.onSuccess { raw ->
                val options = Prompts.parseOptions(raw)
                if (options.isEmpty()) {
                    status.text = "המודל לא החזיר תשובות. לחצי על 🔄."
                    addRegenerateReplies(list)
                    return@onSuccess
                }
                repliesCacheKey = key
                repliesCache = options
                list.removeView(status)
                showReplyOptions(list, options)
            }.onFailure { e ->
                status.text = errorMessage(e)
                status.setTextColor(UiKit.RED)
            }
        }
    }

    private fun showReplyOptions(list: LinearLayout, options: List<String>) {
        options.forEach { option ->
            list.addView(UiKit.button(this, option, UiKit.GREEN, filled = false, textSizeSp = 21f) {
                openConfirm(option, returnTo = Mode.REPLIES)
            }, UiKit.matchWrap(this, 8))
        }
        addRegenerateReplies(list)
    }

    private fun addRegenerateReplies(list: LinearLayout) {
        list.addView(UiKit.button(this, "🔄 אפשרויות אחרות", UiKit.GREY, filled = false, textSizeSp = 18f) {
            repliesCacheKey = null
            setMode(Mode.REPLIES)
        }, UiKit.matchWrap(this, 12))
    }

    // ---------------------------------------------------------------- BUILD

    private lateinit var draftView: TextView

    private fun renderBuild() {
        val draftCard = UiKit.card(this)
        draftView = UiKit.text(this, "", 24f).apply { minHeight = dp(44) }
        draftCard.addView(draftView)
        rightArea.addView(draftCard, UiKit.matchWrap(this, 2))
        updateDraftView()

        val actions = UiKit.row(this)
        actions.addView(UiKit.button(this, "🔊", UiKit.PRIMARY, filled = false, textSizeSp = 20f) {
            if (draft.isNotBlank()) speaker.speak(draft.toString(), prefs.speechRate, draftView)
        }, UiKit.weighted(this, 0.8f, 2))
        actions.addView(UiKit.button(this, "🧩 השלם", UiKit.ORANGE, textSizeSp = 18f) {
            if (draft.isNotBlank()) setMode(Mode.OPTIONS)
        }, UiKit.weighted(this, 1.3f, 2))
        actions.addView(UiKit.button(this, "✅ אישור", UiKit.GREEN, textSizeSp = 18f) {
            if (draft.isNotBlank()) openConfirm(draft.toString().trim(), returnTo = Mode.BUILD)
        }, UiKit.weighted(this, 1.3f, 2))
        actions.addView(UiKit.button(this, "🗑", UiKit.RED, filled = false, textSizeSp = 20f) {
            draft.clear()
            updateDraftView()
        }, UiKit.weighted(this, 0.8f, 2))
        rightArea.addView(actions, UiKit.matchWrap(this))

        rightArea.addView(
            HebrewKeyboard.build(this, ::onKey),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
    }

    private fun onKey(key: HebrewKeyboard.Key) {
        when (key) {
            is HebrewKeyboard.Key.Char -> draft.append(key.value)
            HebrewKeyboard.Key.Space -> if (draft.isNotEmpty() && !draft.endsWith(" ")) draft.append(' ')
            HebrewKeyboard.Key.Backspace -> if (draft.isNotEmpty()) draft.deleteCharAt(draft.length - 1)
        }
        updateDraftView()
    }

    private fun updateDraftView() {
        if (!::draftView.isInitialized) return
        if (draft.isEmpty()) {
            draftView.text = "כתבי כמה מילים…"
            draftView.setTextColor(UiKit.TEXT_SECONDARY)
        } else {
            draftView.text = draft.toString()
            draftView.setTextColor(UiKit.TEXT)
        }
    }

    // ---------------------------------------------------------------- OPTIONS

    private fun renderOptions() {
        val list = scrollingList()
        val words = draft.toString().trim()
        list.addView(UiKit.text(this, "המילים שלך:", 16f, UiKit.TEXT_SECONDARY, bold = true), UiKit.matchWrap(this))
        list.addView(UiKit.text(this, words, 22f), UiKit.matchWrap(this))

        val snap = (readChat() as? ChatResult.Ok)?.snapshot?.takeIf { isWhatsAppEditor() }
        val key = words + "\u0000" + (snap?.transcript() ?: "")
        if (key == optionsCacheKey && optionsCache.isNotEmpty()) {
            showSentenceOptions(list, optionsCache)
            return
        }
        val status = addInfo(list, thinkingMessage())
        val token = renderToken
        scope.launch {
            val result = runModel(Prompts.completeFragments(prefs.profile, words, snap))
            if (token != renderToken) return@launch
            result.onSuccess { raw ->
                val options = Prompts.parseOptions(raw)
                if (options.isEmpty()) {
                    status.text = "המודל לא החזיר משפטים. לחצי על 🔄."
                    addOptionsFooter(list)
                    return@onSuccess
                }
                optionsCacheKey = key
                optionsCache = options
                list.removeView(status)
                showSentenceOptions(list, options)
            }.onFailure { e ->
                status.text = errorMessage(e)
                status.setTextColor(UiKit.RED)
                addOptionsFooter(list)
            }
        }
    }

    private fun showSentenceOptions(list: LinearLayout, options: List<String>) {
        list.addView(UiKit.text(this, "מה רצית להגיד?", 18f, UiKit.PRIMARY, bold = true), UiKit.matchWrap(this, 10))
        options.forEach { option ->
            list.addView(UiKit.button(this, option, UiKit.GREEN, filled = false, textSizeSp = 21f) {
                openConfirm(option, returnTo = Mode.OPTIONS)
            }, UiKit.matchWrap(this, 8))
        }
        addOptionsFooter(list)
    }

    private fun addOptionsFooter(list: LinearLayout) {
        val row = UiKit.row(this)
        row.addView(UiKit.button(this, "🔄 עוד", UiKit.GREY, filled = false, textSizeSp = 18f) {
            optionsCacheKey = null
            setMode(Mode.OPTIONS)
        }, UiKit.weighted(this))
        row.addView(UiKit.button(this, "✏️ חזרה לכתיבה", UiKit.PRIMARY, filled = false, textSizeSp = 18f) {
            setMode(Mode.BUILD)
        }, UiKit.weighted(this))
        list.addView(row, UiKit.matchWrap(this, 12))
    }

    // ---------------------------------------------------------------- PHRASES

    private fun renderPhrases() {
        val list = scrollingList()
        val phrases = prefs.phrases
        if (phrases.isEmpty()) {
            addInfo(list, "אין ביטויים שמורים. אפשר להוסיף בהגדרות האפליקציה.")
            openSettingsButton(list)
            return
        }
        phrases.chunked(2).forEach { pair ->
            val row = UiKit.row(this)
            pair.forEach { phrase ->
                row.addView(UiKit.button(this, phrase, UiKit.PRIMARY, filled = false, textSizeSp = 20f) {
                    openConfirm(phrase, returnTo = Mode.PHRASES)
                }, UiKit.weighted(this, 1f, 3))
            }
            if (pair.size == 1) row.addView(View(this), UiKit.weighted(this, 1f, 3))
            list.addView(row, UiKit.matchWrap(this))
        }
    }

    // ---------------------------------------------------------------- CONFIRM

    private fun openConfirm(text: String, returnTo: Mode) {
        confirmText = text
        confirmReturnMode = returnTo
        setMode(Mode.CONFIRM)
    }

    private fun renderConfirm() {
        val list = scrollingList()
        val whatsApp = isWhatsAppEditor()
        list.addView(
            UiKit.text(this, if (whatsApp) "לשלוח את ההודעה הזו?" else "להכניס את הטקסט הזה?", 18f, UiKit.PRIMARY, bold = true),
            UiKit.matchWrap(this),
        )
        val card = UiKit.card(this, UiKit.CARD, UiKit.GREEN)
        val body = UiKit.text(this, confirmText, 26f)
        card.addView(body)
        card.setOnClickListener { speaker.speak(confirmText, prefs.speechRate, body) }
        list.addView(card, UiKit.matchWrap(this, 6))

        list.addView(UiKit.button(this, if (whatsApp) "✅ שלחי" else "✅ הכניסי", UiKit.GREEN, textSizeSp = 24f) {
            commitConfirmed(whatsApp)
        }, UiKit.matchWrap(this, 10).apply { height = dp(70) })

        val row = UiKit.row(this)
        row.addView(UiKit.button(this, "✏️ שני", UiKit.PRIMARY, filled = false) {
            draft.clear()
            draft.append(confirmText)
            setMode(Mode.BUILD)
        }, UiKit.weighted(this))
        row.addView(UiKit.button(this, "🔊 שוב", UiKit.PRIMARY, filled = false) {
            speaker.speak(confirmText, prefs.speechRate, body)
        }, UiKit.weighted(this))
        row.addView(UiKit.button(this, "❌ בטלי", UiKit.RED, filled = false) {
            setMode(confirmReturnMode)
        }, UiKit.weighted(this))
        list.addView(row, UiKit.matchWrap(this, 4))

        speaker.speak(confirmText, prefs.speechRate, body)
    }

    private fun commitConfirmed(whatsApp: Boolean) {
        val ic = currentInputConnection
        if (ic == null) {
            showResult("אין שדה כתיבה פעיל.", UiKit.RED)
            return
        }
        ic.beginBatchEdit()
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText(confirmText, 1)
        ic.endBatchEdit()
        draft.clear()
        optionsCacheKey = null

        if (!whatsApp) {
            setMode(Mode.BUILD)
            return
        }
        val service = WhatsAppAccessibilityService.instance
        if (service == null) {
            showResult("ההודעה נכתבה בשדה, אבל לא נשלחה: שירות הנגישות כבוי. לחצי על כפתור השליחה של וואטסאפ, או הפעילי את השירות בהגדרות.", UiKit.ORANGE)
            return
        }
        val token = renderToken
        scope.launch {
            var sent = false
            // The send button appears only after WhatsApp sees text in the field.
            for (attempt in 0 until 15) {
                delay(150)
                if (service.clickSend()) {
                    sent = true
                    break
                }
            }
            if (token != renderToken) return@launch
            if (sent) {
                repliesCacheKey = null
                speaker.speak("נשלח", prefs.speechRate)
                delay(600)
                if (token == renderToken) setMode(Mode.READ)
            } else {
                showResult("לא מצאתי את כפתור השליחה. ההודעה נמצאת בשדה – לחצי על שליחה בוואטסאפ.", UiKit.ORANGE)
            }
        }
    }

    private fun showResult(message: String, color: Int) {
        renderToken++
        speaker.stop()
        rightArea.removeAllViews()
        val list = scrollingList()
        addInfo(list, message, color)
        list.addView(UiKit.button(this, "📖 חזרה לקריאה", UiKit.PRIMARY, filled = false) { setMode(Mode.READ) }, UiKit.matchWrap(this, 12))
    }

    // ---------------------------------------------------------------- model helpers

    private suspend fun runModel(task: PromptTask): Result<String> =
        runCatching { LlmManager.generate(this, task) }

    private fun thinkingMessage(): String =
        if (LlmManager.isLoaded) "⏳ חושבת…" else "⏳ טוענת את המודל… בפעם הראשונה זה יכול לקחת עד חצי דקה"

    private fun errorMessage(e: Throwable): String = when (e) {
        is ModelMissingException -> "קובץ המודל לא נמצא. פתחי את אפליקציית מקלדת אפאזיה ובדקי את סעיף המודל."
        is ModelIncompleteException -> "קובץ המודל לא הורד עד הסוף. צריך להוריד אותו שוב (פרטים באפליקציה)."
        is ModelCorruptException -> "קובץ המודל פגום. צריך להוריד אותו שוב (פרטים באפליקציה)."
        else -> "שגיאה במודל: ${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        private const val KEYBOARD_HEIGHT_DP = 440
    }
}

/** Large Hebrew keyboard (standard Israeli layout) with backspace on the left for the left hand. */
object HebrewKeyboard {
    sealed class Key {
        data class Char(val value: String) : Key()
        data object Space : Key()
        data object Backspace : Key()
    }

    private val ROWS = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("ק", "ר", "א", "ט", "ו", "ן", "ם", "פ"),
        listOf("ש", "ד", "ג", "כ", "ע", "י", "ח", "ל", "ך", "ף"),
        listOf("ז", "ס", "ב", "ה", "נ", "מ", "צ", "ת", "ץ"),
    )

    fun build(service: InputMethodService, onKey: (Key) -> Unit): LinearLayout {
        val ctx = service
        val keyboard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        fun rowView() = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            weightSum = 10f
        }
        fun keyView(label: String, sizeSp: Float, fill: Int, onTap: () -> Unit) = TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(UiKit.TEXT)
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#33000000")),
                UiKit.rounded(ctx, fill, UiKit.BORDER, radiusDp = 8),
                null,
            )
            isClickable = true
            setOnClickListener { onTap() }
        }
        fun keyParams(weight: Float) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
            val m = UiKit.dp(ctx, 2)
            setMargins(m, m, m, m)
        }
        val rowParams = { LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f) }

        ROWS.forEach { keys ->
            val row = rowView()
            keys.forEach { k ->
                row.addView(keyView(k, 24f, UiKit.CARD) { onKey(Key.Char(k)) }, keyParams(1f))
            }
            keyboard.addView(row, rowParams())
        }

        val bottom = rowView()
        bottom.addView(keyView("⌫", 24f, android.graphics.Color.parseColor("#FFE0E0")) { onKey(Key.Backspace) }, keyParams(2f))
        bottom.addView(keyView(",", 24f, UiKit.CARD) { onKey(Key.Char(",")) }, keyParams(1f))
        bottom.addView(keyView("רווח", 20f, UiKit.CARD) { onKey(Key.Space) }, keyParams(5f))
        bottom.addView(keyView(".", 24f, UiKit.CARD) { onKey(Key.Char(".")) }, keyParams(1f))
        bottom.addView(keyView("?", 24f, UiKit.CARD) { onKey(Key.Char("?")) }, keyParams(1f))
        keyboard.addView(bottom, rowParams())
        return keyboard
    }
}
