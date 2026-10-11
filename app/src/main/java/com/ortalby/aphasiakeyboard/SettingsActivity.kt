package com.ortalby.aphasiakeyboard

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** Setup screen: model file, enabling the keyboard and the WhatsApp reader, voice, profile and phrases. */
class SettingsActivity : ComponentActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var speaker: Speaker

    private lateinit var modelStatus: TextView
    private lateinit var keyboardStatus: TextView
    private lateinit var accessibilityStatus: TextView
    private lateinit var voiceStatus: TextView
    private lateinit var testOutput: TextView

    private fun dp(v: Int) = UiKit.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        speaker = Speaker(this)

        val scroll = ScrollView(this).apply { setBackgroundColor(UiKit.BACKGROUND) }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(20), dp(16), dp(40))
        }
        scroll.addView(page)
        setContentView(scroll)

        page.addView(UiKit.text(this, "מקלדת אפאזיה", 30f, UiKit.PRIMARY, bold = true))
        page.addView(
            UiKit.text(this, "הכול עובד על הטלפון בלבד. לאפליקציה אין גישה לאינטרנט.", 17f, UiKit.TEXT_SECONDARY),
            UiKit.matchWrap(this, 4),
        )

        buildModelSection(page)
        buildKeyboardSection(page)
        buildAccessibilitySection(page)
        buildVoiceSection(page)
        buildProfileSection(page)
        buildPhrasesSection(page)
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    override fun onDestroy() {
        speaker.shutdown()
        super.onDestroy()
    }

    private fun section(page: LinearLayout, title: String): LinearLayout {
        val card = UiKit.card(this)
        card.addView(UiKit.text(this, title, 22f, UiKit.PRIMARY, bold = true))
        page.addView(card, UiKit.matchWrap(this, 18))
        return card
    }

    private fun addButton(parent: LinearLayout, label: String, color: Int, filled: Boolean = true, onClick: () -> Unit): TextView {
        val b = UiKit.button(this, label, color, filled, 19f, onClick)
        parent.addView(b, UiKit.matchWrap(this, 10))
        return b
    }

    private fun field(parent: LinearLayout, label: String, value: String, multiline: Boolean): EditText {
        parent.addView(UiKit.text(this, label, 17f, UiKit.TEXT_SECONDARY), UiKit.matchWrap(this, 12))
        val edit = EditText(this).apply {
            setText(value)
            textSize = 19f
            textDirection = View.TEXT_DIRECTION_RTL
            inputType = if (multiline) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            if (multiline) minLines = 3
        }
        parent.addView(edit, UiKit.matchWrap(this))
        return edit
    }

    // ---------------------------------------------------------------- model

    private lateinit var modelList: LinearLayout
    private lateinit var storageAccessButton: TextView

    private fun buildModelSection(page: LinearLayout) {
        val s = section(page, "1. המודל")
        s.addView(
            UiKit.text(
                this,
                "הורידי בדפדפן את הקובץ gemma-4-E2B-it.litertlm (כ-2.6GB) לתיקיית ההורדות. " +
                    "האפליקציה משתמשת בקובץ ישירות מההורדות – אל תמחקי אותו.",
                17f,
            ),
            UiKit.matchWrap(this, 6),
        )
        addButton(s, "⬇️ הורדת המודל בדפדפן", UiKit.GREY, filled = false) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MODEL_URL)))
        }
        modelStatus = UiKit.text(this, "", 18f, bold = true)
        s.addView(modelStatus, UiKit.matchWrap(this, 10))

        storageAccessButton = addButton(s, "🔓 מתן גישה לקובץ המודל", UiKit.PRIMARY) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        addButton(s, "🔎 חיפוש המודל בהורדות", UiKit.PRIMARY, filled = false) { scanForModels(autoSelect = false) }
        modelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        s.addView(modelList, UiKit.matchWrap(this, 4))

        val gpu = CheckBox(this).apply {
            text = "להריץ על המעבד הגרפי (GPU) – מהיר יותר"
            textSize = 18f
            isChecked = prefs.useGpu
            setOnCheckedChangeListener { _, checked ->
                prefs.useGpu = checked
                lifecycleScope.launch { LlmManager.release() }
            }
        }
        s.addView(gpu, UiKit.matchWrap(this, 10))

        addButton(s, "🧪 בדיקת המודל", UiKit.ORANGE, filled = false) { testModel() }
        testOutput = UiKit.text(this, "", 18f)
        s.addView(testOutput, UiKit.matchWrap(this, 8))
    }

    private fun hasStorageAccess(): Boolean = Environment.isExternalStorageManager()

    /** Finds .litertlm files in Downloads (and its sub-folders) and lists them to choose from. */
    private fun scanForModels(autoSelect: Boolean) {
        modelList.removeAllViews()
        if (!hasStorageAccess()) {
            modelList.addView(
                UiKit.text(this, "קודם צריך ללחוץ על \"מתן גישה לקובץ המודל\" ולהדליק את המתג.", 17f, UiKit.ORANGE),
                UiKit.matchWrap(this, 6),
            )
            return
        }
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                downloads.walkTopDown().maxDepth(3)
                    .filter { it.isFile && it.name.lowercase(Locale.ROOT).endsWith(".litertlm") }
                    .sortedByDescending { it.lastModified() }
                    .toList()
            }
            modelList.removeAllViews()
            if (found.isEmpty()) {
                modelList.addView(
                    UiKit.text(
                        this@SettingsActivity,
                        "לא נמצא קובץ .litertlm בהורדות. אם ההורדה עדיין רצה, חכי שתסתיים ונסי שוב.",
                        17f,
                        UiKit.ORANGE,
                    ),
                    UiKit.matchWrap(this@SettingsActivity, 6),
                )
                return@launch
            }
            if (autoSelect && found.size == 1) {
                selectModel(found.first())
                return@launch
            }
            found.forEach { file ->
                addButton(modelList, "${file.name}\n${gb(file.length())}", UiKit.GREEN, filled = false) {
                    selectModel(file)
                }
            }
        }
    }

    private fun selectModel(file: File) {
        lifecycleScope.launch {
            LlmManager.release()
            prefs.modelPath = file.absolutePath
            // A copy made by an earlier version is no longer needed.
            withContext(Dispatchers.IO) {
                File(filesDir, "model.litertlm").takeIf { it.absolutePath != file.absolutePath }?.delete()
                File(filesDir, "model.litertlm.part").delete()
            }
            modelList.removeAllViews()
            refreshStatuses()
        }
    }

    private fun testModel() {
        testOutput.setTextColor(UiKit.TEXT)
        testOutput.text = if (LlmManager.isLoaded) "⏳ חושבת…" else "⏳ טוענת את המודל… עד חצי דקה"
        val started = System.currentTimeMillis()
        lifecycleScope.launch {
            val result = runCatching {
                LlmManager.generate(
                    this@SettingsActivity,
                    Prompts.writerSystem(prefs.profile),
                    Prompts.completeFragments("מחר רופא 10", null),
                )
            }
            val seconds = (System.currentTimeMillis() - started) / 1000.0
            result.onSuccess { raw ->
                val options = Prompts.parseOptions(raw)
                testOutput.text = "בדיקה: «מחר רופא 10»\n\n" + options.joinToString("\n") { "• $it" } +
                    "\n\n(${String.format(Locale.ROOT, "%.1f", seconds)} שניות)"
            }.onFailure { e ->
                testOutput.setTextColor(UiKit.RED)
                testOutput.text = when (e) {
                    is ModelMissingException -> "❌ קובץ המודל לא נמצא – ראי את ההוראות בסעיף 1."
                    else -> "❌ ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    // ---------------------------------------------------------------- keyboard

    private fun buildKeyboardSection(page: LinearLayout) {
        val s = section(page, "2. המקלדת")
        keyboardStatus = UiKit.text(this, "", 18f, bold = true)
        s.addView(keyboardStatus, UiKit.matchWrap(this, 8))
        addButton(s, "הפעלת המקלדת בהגדרות", UiKit.PRIMARY) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        addButton(s, "בחירת המקלדת עכשיו", UiKit.PRIMARY, filled = false) {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
    }

    private fun isKeyboardEnabled(): Boolean =
        getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }

    // ---------------------------------------------------------------- accessibility

    private fun buildAccessibilitySection(page: LinearLayout) {
        val s = section(page, "3. קריאת הודעות בוואטסאפ")
        s.addView(
            UiKit.text(
                this,
                "שירות הנגישות מאפשר למקלדת לקרוא את ההודעות בשיחה הפתוחה וללחוץ על \"שליחה\" אחרי שאישרת. " +
                    "הוא פועל רק בוואטסאפ ורק על הטלפון.",
                17f,
            ),
            UiKit.matchWrap(this, 6),
        )
        accessibilityStatus = UiKit.text(this, "", 18f, bold = true)
        s.addView(accessibilityStatus, UiKit.matchWrap(this, 8))
        addButton(s, "פתיחת הגדרות נגישות", UiKit.PRIMARY) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        s.addView(
            UiKit.text(
                this,
                "אם המתג אפור ולא נלחץ: פתחי את פרטי האפליקציה ⟵ ⋮ (למעלה) ⟵ \"אפשר הגדרות מוגבלות\", ואז נסי שוב.",
                16f,
                UiKit.TEXT_SECONDARY,
            ),
            UiKit.matchWrap(this, 10),
        )
        addButton(s, "פרטי האפליקציה", UiKit.GREY, filled = false) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, WhatsAppAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    // ---------------------------------------------------------------- voice

    private fun buildVoiceSection(page: LinearLayout) {
        val s = section(page, "4. קול")
        voiceStatus = UiKit.text(this, "", 18f, bold = true)
        s.addView(voiceStatus, UiKit.matchWrap(this, 8))

        val rateLabel = UiKit.text(this, "", 17f, UiKit.TEXT_SECONDARY)
        s.addView(rateLabel, UiKit.matchWrap(this, 10))
        // 0.50 … 1.20
        val seek = SeekBar(this).apply {
            max = 70
            progress = ((prefs.speechRate - 0.5f) * 100).toInt().coerceIn(0, 70)
        }
        fun showRate() {
            rateLabel.text = "מהירות הקראה: ${String.format(Locale.ROOT, "%.2f", prefs.speechRate)}"
        }
        showRate()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    prefs.speechRate = 0.5f + value / 100f
                    showRate()
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        s.addView(seek, UiKit.matchWrap(this, 4))

        addButton(s, "🔊 ניסיון הקראה", UiKit.PRIMARY, filled = false) {
            speaker.speak("שלום, זו המהירות שבה אקריא את ההודעות.", prefs.speechRate)
            voiceStatus.postDelayed({ refreshStatuses() }, 500)
        }
        addButton(s, "התקנת קול עברי", UiKit.GREY, filled = false) {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        }
    }

    // ---------------------------------------------------------------- profile

    private fun buildProfileSection(page: LinearLayout) {
        val s = section(page, "5. פרטים אישיים")
        s.addView(
            UiKit.text(this, "עוזר למודל לכתוב בסגנון שלך. נשמר רק בטלפון.", 17f, UiKit.TEXT_SECONDARY),
            UiKit.matchWrap(this, 4),
        )
        val p = prefs.profile
        val name = field(s, "שם", p.name, multiline = false)

        s.addView(UiKit.text(this, "לשון", 17f, UiKit.TEXT_SECONDARY), UiKit.matchWrap(this, 12))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val female = RadioButton(this).apply { text = "נקבה"; textSize = 19f; id = View.generateViewId() }
        val male = RadioButton(this).apply { text = "זכר"; textSize = 19f; id = View.generateViewId() }
        group.addView(female)
        group.addView(male)
        group.check(if (p.gender == Gender.MALE) male.id else female.id)
        s.addView(group, UiKit.matchWrap(this))

        val people = field(s, "אנשים קרובים – שורה לכל אחד (לדוגמה: דנה – הבת)", p.people, multiline = true)
        val style = field(s, "איך את כותבת בדרך כלל (ביטויים, כינויים, אימוג'י)", p.styleNotes, multiline = true)

        val saved = UiKit.text(this, "", 17f, UiKit.GREEN, bold = true)
        addButton(s, "💾 שמירה", UiKit.GREEN) {
            prefs.profile = Profile(
                name = name.text.toString(),
                gender = if (group.checkedRadioButtonId == male.id) Gender.MALE else Gender.FEMALE,
                people = people.text.toString(),
                styleNotes = style.text.toString(),
            )
            saved.text = "✓ נשמר"
        }
        s.addView(saved, UiKit.matchWrap(this, 6))
    }

    // ---------------------------------------------------------------- phrases

    private fun buildPhrasesSection(page: LinearLayout) {
        val s = section(page, "6. ביטויים שמורים")
        val edit = field(s, "ביטוי בכל שורה", prefs.phrases.joinToString("\n"), multiline = true)
        val saved = UiKit.text(this, "", 17f, UiKit.GREEN, bold = true)
        addButton(s, "💾 שמירת ביטויים", UiKit.GREEN) {
            prefs.phrases = edit.text.toString().lines()
            saved.text = "✓ נשמרו ${prefs.phrases.size} ביטויים"
        }
        s.addView(saved, UiKit.matchWrap(this, 6))
    }

    // ---------------------------------------------------------------- status

    private fun refreshStatuses() {
        val access = hasStorageAccess()
        storageAccessButton.visibility = if (access) View.GONE else View.VISIBLE
        val file = prefs.modelPath?.let { File(it) }
        when {
            file != null && file.isFile -> {
                modelStatus.text = "✅ מודל: ${file.name} (${gb(file.length())})"
                modelStatus.setTextColor(UiKit.GREEN)
            }
            !access -> {
                modelStatus.text = "⚠️ אין לאפליקציה גישה לקובץ המודל. לחצי על \"מתן גישה לקובץ המודל\" והדליקי את המתג."
                modelStatus.setTextColor(UiKit.ORANGE)
            }
            else -> {
                modelStatus.text = "⚠️ עדיין לא נבחר קובץ מודל"
                modelStatus.setTextColor(UiKit.ORANGE)
                scanForModels(autoSelect = true)
            }
        }
        setStatus(keyboardStatus, isKeyboardEnabled(), "✅ המקלדת מופעלת", "⚠️ המקלדת עוד לא מופעלת")
        setStatus(accessibilityStatus, isAccessibilityEnabled(), "✅ שירות הנגישות פועל", "⚠️ שירות הנגישות כבוי")
        when (speaker.state) {
            Speaker.State.READY -> setStatus(voiceStatus, true, "✅ יש קול עברי", "")
            Speaker.State.NO_HEBREW_VOICE -> setStatus(voiceStatus, false, "", "⚠️ חסר קול עברי – לחצי על \"התקנת קול עברי\"")
            Speaker.State.FAILED -> setStatus(voiceStatus, false, "", "⚠️ מנוע ההקראה לא זמין")
            Speaker.State.INITIALIZING -> {
                voiceStatus.text = "בודקת קול…"
                voiceStatus.setTextColor(UiKit.TEXT_SECONDARY)
                voiceStatus.postDelayed({ refreshStatuses() }, 700)
            }
        }
    }

    private fun setStatus(view: TextView, ok: Boolean, okText: String, badText: String) {
        view.text = if (ok) okText else badText
        view.setTextColor(if (ok) UiKit.GREEN else UiKit.ORANGE)
    }

    private fun gb(bytes: Long): String = String.format(Locale.ROOT, "%.2fGB", bytes / (1024.0 * 1024.0 * 1024.0))

    companion object {
        private const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    }
}
