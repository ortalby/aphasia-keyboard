package com.ortalby.aphasiakeyboard

import android.content.Context
import org.json.JSONArray

enum class Gender { FEMALE, MALE }

data class Profile(
    val name: String,
    val gender: Gender,
    val people: String,
    val styleNotes: String,
)

/** All settings live only on the phone, in private app storage. */
class AppPrefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("aphasia_prefs", Context.MODE_PRIVATE)

    var modelPath: String?
        get() = sp.getString(KEY_MODEL_PATH, null)
        set(value) = sp.edit().putString(KEY_MODEL_PATH, value).apply()

    var useGpu: Boolean
        get() = sp.getBoolean(KEY_USE_GPU, true)
        set(value) = sp.edit().putBoolean(KEY_USE_GPU, value).apply()

    var speechRate: Float
        get() = sp.getFloat(KEY_SPEECH_RATE, 0.85f)
        set(value) = sp.edit().putFloat(KEY_SPEECH_RATE, value).apply()

    var profile: Profile
        get() = Profile(
            name = sp.getString(KEY_NAME, "") ?: "",
            gender = if (sp.getString(KEY_GENDER, Gender.FEMALE.name) == Gender.MALE.name) Gender.MALE else Gender.FEMALE,
            people = sp.getString(KEY_PEOPLE, "") ?: "",
            styleNotes = sp.getString(KEY_STYLE, "") ?: "",
        )
        set(value) {
            sp.edit()
                .putString(KEY_NAME, value.name.trim())
                .putString(KEY_GENDER, value.gender.name)
                .putString(KEY_PEOPLE, value.people.trim())
                .putString(KEY_STYLE, value.styleNotes.trim())
                .apply()
        }

    var phrases: List<String>
        get() {
            val raw = sp.getString(KEY_PHRASES, null) ?: return DEFAULT_PHRASES
            val arr = JSONArray(raw)
            return (0 until arr.length()).map { arr.getString(it) }
        }
        set(value) {
            val arr = JSONArray()
            value.map { it.trim() }.filter { it.isNotEmpty() }.forEach { arr.put(it) }
            sp.edit().putString(KEY_PHRASES, arr.toString()).apply()
        }

    companion object {
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_USE_GPU = "use_gpu"
        private const val KEY_SPEECH_RATE = "speech_rate"
        private const val KEY_NAME = "profile_name"
        private const val KEY_GENDER = "profile_gender"
        private const val KEY_PEOPLE = "profile_people"
        private const val KEY_STYLE = "profile_style"
        private const val KEY_PHRASES = "phrases"

        val DEFAULT_PHRASES = listOf(
            "כן",
            "לא",
            "תודה ❤️",
            "אני בסדר",
            "תתקשרי אליי",
            "רגע, אני כותבת לאט",
            "אני לא מבינה, תסבירי שוב בפשטות",
            "אוהבת אותך",
            "אני עסוקה, אחזור אלייך",
            "מתי?",
            "איפה?",
            "בוא/י אליי",
        )
    }
}
