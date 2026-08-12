package com.buswaze.app

import kotlin.math.roundToInt

/**
 * Turns Valhalla maneuver type codes into human instructions.
 * The routing server only speaks English, so Hebrew is generated here
 * from the maneuver type + street name.
 */
object Instructions {

    /** Text shown in the banner / directions list. */
    fun localize(m: Maneuver, hebrew: Boolean): String {
        if (!hebrew) return m.instruction
        val base = when (m.type) {
            1, 2, 3 -> "התחילו בנסיעה"
            4, 5, 6 -> "הגעתם ליעד"
            8, 17, 22 -> "המשיכו ישר"
            9 -> "פנו קלות ימינה"
            10 -> "פנו ימינה"
            11 -> "פנו ימינה בחדות"
            12 -> "בצעו פניית פרסה ימינה"
            13 -> "בצעו פניית פרסה שמאלה"
            14 -> "פנו שמאלה בחדות"
            15 -> "פנו שמאלה"
            16 -> "פנו קלות שמאלה"
            18, 20 -> "צאו ביציאה ימינה"
            19, 21 -> "צאו ביציאה שמאלה"
            23 -> "היצמדו לימין"
            24 -> "היצמדו לשמאל"
            25, 37, 38 -> "השתלבו בכביש"
            26 -> if (m.roundaboutExit > 0)
                "היכנסו לכיכר וצאו ביציאה ה־${m.roundaboutExit}"
            else "היכנסו לכיכר"
            27 -> "צאו מהכיכר"
            28 -> "עלו על המעבורת"
            29 -> "רדו מהמעבורת"
            else -> return m.instruction // unknown type — English fallback
        }
        val withStreet = when {
            m.street == null -> base
            m.type in intArrayOf(8, 17, 22) -> "$base בכביש ${m.street}"
            m.type in 9..21 || m.type in intArrayOf(23, 24, 25, 37, 38) ->
                "$base אל ${m.street}"
            else -> base
        }
        return withStreet
    }

    /** Text spoken out loud, with an optional distance prefix. */
    fun spoken(m: Maneuver, hebrew: Boolean, meters: Double?): String {
        val text = localize(m, hebrew)
        if (meters == null || meters < 160) return text
        val rounded = (meters / 50).roundToInt() * 50
        return if (hebrew) "בעוד $rounded מטר, $text"
        else "In $rounded meters, $text"
    }
}
