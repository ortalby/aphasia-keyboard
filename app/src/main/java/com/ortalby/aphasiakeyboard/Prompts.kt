package com.ortalby.aphasiakeyboard

object Prompts {

    fun writerSystem(profile: Profile): String {
        val female = profile.gender == Gender.FEMALE
        val who = profile.name.ifBlank { if (female) "המשתמשת" else "המשתמש" }
        val base = if (female) {
            """
            אתה עוזר כתיבה בוואטסאפ עבור $who, אישה עם אפאזיה מסוג ברוקה אחרי פגיעה מוחית.
            היא מבינה טוב, אבל מתקשה למצוא מילים ולבנות משפטים, ולכן לפעמים כותבת רק כמה מילים, או מילים עם שגיאות כתיב.
            כללים:
            - כתוב תמיד בשמה, בגוף ראשון, בלשון נקבה.
            """.trimIndent()
        } else {
            """
            אתה עוזר כתיבה בוואטסאפ עבור $who, גבר עם אפאזיה מסוג ברוקה אחרי פגיעה מוחית.
            הוא מבין טוב, אבל מתקשה למצוא מילים ולבנות משפטים, ולכן לפעמים כותב רק כמה מילים, או מילים עם שגיאות כתיב.
            כללים:
            - כתוב תמיד בשמו, בגוף ראשון, בלשון זכר.
            """.trimIndent()
        }
        val rules = """
            - עברית פשוטה וטבעית, כמו הודעת וואטסאפ אמיתית. משפטים קצרים.
            - אל תוסיף עובדות, שמות, שעות או מקומות שלא הופיעו במילים או בשיחה.
            - כתוב רק את מה שהתבקש, בלי הקדמות ובלי הסברים.
        """.trimIndent()
        val sb = StringBuilder(base).append('\n').append(rules)
        if (profile.people.isNotBlank()) {
            sb.append("\n\nאנשים קרובים (שם – מי זה):\n").append(profile.people.trim())
        }
        if (profile.styleNotes.isNotBlank()) {
            sb.append("\n\nסגנון הכתיבה ").append(if (female) "שלה" else "שלו").append(":\n").append(profile.styleNotes.trim())
        }
        return sb.toString()
    }

    fun completeFragments(words: String, chat: ChatSnapshot?): String {
        val sb = StringBuilder()
        if (chat != null && chat.messages.isNotEmpty()) {
            sb.append("ההודעות האחרונות בשיחה")
            chat.chatTitle?.let { sb.append(" עם ").append(it) }
            sb.append(":\n").append(chat.transcript()).append("\n\n")
        }
        sb.append("המילים שנכתבו עכשיו: «").append(words.trim()).append("»\n\n")
        sb.append("כתוב 3 נוסחים שונים להודעה שרוצים לשלוח, על בסיס המילים האלה")
        if (chat != null && chat.messages.isNotEmpty()) sb.append(" ובהתאם לשיחה")
        sb.append(". כל נוסח בשורה נפרדת, בלי מספור.")
        return sb.toString()
    }

    fun suggestReplies(chat: ChatSnapshot): String {
        val sb = StringBuilder("השיחה בוואטסאפ")
        chat.chatTitle?.let { sb.append(" עם ").append(it) }
        sb.append(":\n").append(chat.transcript()).append("\n\n")
        sb.append(
            "כתוב 3 תשובות קצרות ושונות שאפשר לשלוח עכשיו: אחת שמסכימה או חיובית, " +
                "אחת שמסרבת או דוחה, ואחת אחרת (למשל שאלה או בקשה להתקשר). " +
                "כל תשובה בשורה נפרדת, בלי מספור."
        )
        return sb.toString()
    }

    const val SIMPLIFY_SYSTEM = """אתה מפשט הודעות וואטסאפ עבור אדם עם אפאזיה וקושי בקריאה.
כתוב מחדש את ההודעה במילים פשוטות ובמשפטים קצרים מאוד, לכל היותר 3 משפטים.
אל תשנה את המשמעות ואל תוסיף מידע. השאר שמות, שעות, תאריכים ומספרים בדיוק כמו שהם.
אם יש בהודעה שאלה, כתוב אותה בשורה האחרונה כך: שאלה: ...
כתוב רק את ההודעה המפושטת."""

    fun simplify(message: ChatMessage): String =
        "ההודעה מ${message.sender ?: "הצד השני"}:\n${message.text}"

    // Bullets or list numbering such as "1." / "2)" — not numbers that belong to the message.
    private val leadingMarks = Regex("^\\s*(?:[-*•·]|\\d{1,2}[.)])\\s*")
    private val quoteChars = charArrayOf('"', '\'', '«', '»', '“', '”', '„', '״', ' ')

    /** Splits the model's answer into separate options, one per line. */
    fun parseOptions(raw: String, max: Int = 3): List<String> =
        raw.lines()
            .map { it.replace(leadingMarks, "").trim(*quoteChars) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(max)
}
