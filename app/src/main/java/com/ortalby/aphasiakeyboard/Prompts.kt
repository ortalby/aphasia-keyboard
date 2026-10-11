package com.ortalby.aphasiakeyboard

/**
 * What is sent to the model: instructions, worked examples (shown to the model as earlier
 * turns of the conversation, so it copies their form), and the actual request.
 */
data class PromptTask(
    val system: String,
    val examples: List<Pair<String, String>>,
    val prompt: String,
    val maxTokens: Int = 200,
)

object Prompts {

    // ---------------------------------------------------------------- shared

    private fun writerSystem(profile: Profile): String {
        val female = profile.gender == Gender.FEMALE
        val who = profile.name.ifBlank { if (female) "המשתמשת" else "המשתמש" }
        val sb = StringBuilder()
        if (female) {
            sb.append("אתה עוזר כתיבה בוואטסאפ עבור $who, אישה עם אפאזיה מסוג ברוקה אחרי פגיעה מוחית.\n")
            sb.append("היא מבינה טוב, אבל מתקשה למצוא מילים. לכן היא כותבת רק כמה מילות מפתח, לפעמים עם שגיאות כתיב או עם מילה לא נכונה.\n")
            sb.append("התפקיד שלך: להבין מה היא רוצה להגיד, ולכתוב את זה בשבילה כהודעה מוכנה לשליחה.\n\n")
            sb.append("כללים:\n")
            sb.append("- כתוב בשמה, בגוף ראשון, בלשון נקבה (אני הולכת, אני רוצה, אני יכולה).\n")
        } else {
            sb.append("אתה עוזר כתיבה בוואטסאפ עבור $who, גבר עם אפאזיה מסוג ברוקה אחרי פגיעה מוחית.\n")
            sb.append("הוא מבין טוב, אבל מתקשה למצוא מילים. לכן הוא כותב רק כמה מילות מפתח, לפעמים עם שגיאות כתיב או עם מילה לא נכונה.\n")
            sb.append("התפקיד שלך: להבין מה הוא רוצה להגיד, ולכתוב את זה בשבילו כהודעה מוכנה לשליחה.\n\n")
            sb.append("כללים:\n")
            sb.append("- כתוב בשמו, בגוף ראשון, בלשון זכר (אני הולך, אני רוצה, אני יכול).\n")
        }
        sb.append("- עברית יומיומית ופשוטה, כמו הודעת וואטסאפ אמיתית. משפט אחד או שניים קצרים.\n")
        sb.append("- השתמש בכל מילות המפתח. תקן שגיאות כתיב.\n")
        sb.append("- אל תמציא פרטים: שמות, שעות, מקומות וסיבות – רק מה שמופיע במילים או בשיחה.\n")
        sb.append("- כשאדם בשיחה פונה אליה/אליו, ההודעה היא תשובה אליו.\n")
        sb.append("- הפלט: בדיוק 3 שורות, נוסח אחד בכל שורה. בלי מספרים, בלי כותרות, בלי הסברים.")
        if (profile.people.isNotBlank()) {
            sb.append("\n\nאנשים קרובים (שם – מי זה):\n").append(profile.people.trim())
        }
        if (profile.styleNotes.isNotBlank()) {
            sb.append("\n\nסגנון הכתיבה ").append(if (female) "שלה" else "שלו").append(":\n").append(profile.styleNotes.trim())
        }
        return sb.toString()
    }

    /** Picks the feminine or masculine form for the user's own first-person verbs. */
    private fun g(profile: Profile, female: String, male: String) =
        if (profile.gender == Gender.FEMALE) female else male

    // ---------------------------------------------------------------- words → sentences

    private fun completeRequest(words: String, chatTitle: String?, transcript: String?): String {
        val sb = StringBuilder()
        if (!transcript.isNullOrBlank()) {
            sb.append("השיחה")
            chatTitle?.let { sb.append(" עם ").append(it) }
            sb.append(":\n").append(transcript).append("\n\n")
        }
        sb.append("מילות המפתח: ").append(words.trim()).append('\n')
        sb.append("כתוב 3 נוסחים להודעה.")
        return sb.toString()
    }

    fun completeFragments(profile: Profile, words: String, chat: ChatSnapshot?): PromptTask {
        val examples = listOf(
            completeRequest("מחר רופא 10", null, null) to listOf(
                "יש לי תור לרופא מחר בעשר.",
                "מחר ב-10 אני ${g(profile, "הולכת", "הולך")} לרופא.",
                "מחר בעשר בבוקר אני אצל הרופא.",
            ).joinToString("\n"),
            completeRequest(
                "כן אחרי 5 עוגה",
                "דנה",
                "דנה: אמא, אפשר לבוא אלייך היום?",
            ) to listOf(
                "כן, בואי אחרי חמש. תביאי עוגה.",
                "כן, אחרי 5. אני אכין עוגה.",
                "בטח, תבואי אחרי חמש ונאכל עוגה ביחד.",
            ).joinToString("\n"),
            completeRequest("רוצה שתות קפה איתך", null, null) to listOf(
                "אני רוצה לשתות איתך קפה.",
                "בא לי לשבת איתך על קפה.",
                "נשתה ביחד קפה?",
            ).joinToString("\n"),
            completeRequest("עייפה לא היום", "יוסי", "יוסי: נצא לטייל היום?") to listOf(
                "אני ${g(profile, "עייפה", "עייף")}, לא היום.",
                "היום לא, אני ${g(profile, "עייפה", "עייף")}. אולי מחר?",
                "תודה, אבל היום אני ${g(profile, "עייפה", "עייף")} מדי.",
            ).joinToString("\n"),
        )
        val hasChat = chat != null && chat.messages.isNotEmpty()
        return PromptTask(
            system = writerSystem(profile),
            examples = examples,
            prompt = completeRequest(words, chat?.chatTitle, if (hasChat) chat!!.transcript() else null),
        )
    }

    // ---------------------------------------------------------------- suggested replies

    private fun repliesRequest(chatTitle: String?, transcript: String): String {
        val sb = StringBuilder("השיחה")
        chatTitle?.let { sb.append(" עם ").append(it) }
        sb.append(":\n").append(transcript).append("\n\n")
        sb.append("כתוב 3 תשובות קצרות להודעה האחרונה: כן/חיובית, לא/דחייה, ושאלה או בקשה.")
        return sb.toString()
    }

    fun suggestReplies(profile: Profile, chat: ChatSnapshot): PromptTask {
        val examples = listOf(
            repliesRequest("דנה", "דנה: היי אמא, את באה מחר לארוחת שישי?") to listOf(
                "כן, אני ${g(profile, "באה", "בא")}. מחכה לזה!",
                "מחר אני לא ${g(profile, "יכולה", "יכול")}. אולי בשבוע הבא.",
                "באיזו שעה?",
            ).joinToString("\n"),
            repliesRequest("מרפאה", "מרפאה: תזכורת: תור לפיזיותרפיה ביום שני ב-9:00. נא לאשר הגעה.") to listOf(
                "מאשר/ת, אגיע ביום שני ב-9:00.",
                "לא אוכל להגיע. אפשר לקבוע תור אחר?",
                "אפשר תור יותר מאוחר?",
            ).joinToString("\n").let {
                if (profile.gender == Gender.FEMALE) it.replace("מאשר/ת", "מאשרת") else it.replace("מאשר/ת", "מאשר")
            },
        )
        return PromptTask(
            system = writerSystem(profile),
            examples = examples,
            prompt = repliesRequest(chat.chatTitle, chat.transcript()),
        )
    }

    // ---------------------------------------------------------------- simplify an incoming message

    private const val SIMPLIFY_SYSTEM = """אתה מפשט הודעות וואטסאפ עבור אדם עם אפאזיה שמתקשה לקרוא טקסט ארוך.
כתוב את ההודעה מחדש במילים פשוטות ובמשפטים קצרים מאוד: עד 3 משפטים, משפט בכל שורה.
אל תשנה את המשמעות ואל תוסיף מידע. השאר שמות, שעות, תאריכים ומספרים בדיוק כמו שהם.
אם בהודעה יש שאלה, כתוב אותה בשורה האחרונה כך: שאלה: ...
כתוב רק את ההודעה המפושטת."""

    private fun simplifyRequest(sender: String, text: String) = "ההודעה מ$sender:\n$text"

    fun simplify(message: ChatMessage): PromptTask = PromptTask(
        system = SIMPLIFY_SYSTEM,
        examples = listOf(
            simplifyRequest(
                "דנה",
                "היי אמא, רציתי להגיד לך שהתור לרופא השיניים הוזז ליום שלישי ב-16:30 כי לרופא היה משהו דחוף, " +
                    "אז אני אאסוף אותך ב-16:00 מהבית, תהיי מוכנה בבקשה ותביאי את כרטיס הקופה. זה בסדר מבחינתך?",
            ) to "התור לרופא השיניים זז ליום שלישי ב-16:30.\nדנה תאסוף אותך מהבית ב-16:00, עם כרטיס הקופה.\nשאלה: זה בסדר?",
            simplifyRequest(
                "יוסי",
                "בוקר טוב! אני בדרך לסופר, צריכה משהו? חלב, לחם, פירות? תגידי לי עד עשר כי אחר כך אני כבר יוצא משם.",
            ) to "יוסי הולך לסופר.\nהוא יוצא משם בעשר.\nשאלה: צריך משהו מהסופר?",
        ),
        prompt = simplifyRequest(message.sender ?: "הצד השני", message.text),
    )

    // ---------------------------------------------------------------- parsing

    // List numbering or bullets ("1.", "2)", "-", "•", "נוסח 1:") — not numbers that belong to the message.
    private val leadingMarks = Regex("^\\s*(?:[-*•·]|\\d{1,2}[.)]|(?:נוסח|תשובה|אפשרות)\\s*\\d*\\s*[:.-])\\s*")
    private val quoteChars = charArrayOf('"', '\'', '«', '»', '“', '”', '„', '״', ' ')

    /** Splits the model's answer into separate options, one per line, dropping headings. */
    fun parseOptions(raw: String, max: Int = 3): List<String> =
        raw.lines()
            .map { it.replace(leadingMarks, "").replace("**", "").trim(*quoteChars) }
            .filter { it.isNotBlank() && !it.endsWith(":") }
            .distinct()
            .take(max)
}
