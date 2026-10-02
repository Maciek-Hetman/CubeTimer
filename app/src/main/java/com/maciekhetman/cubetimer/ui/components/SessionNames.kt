package com.maciekhetman.cubetimer.ui.components

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.maciekhetman.cubetimer.R
import com.maciekhetman.cubetimer.model.DayPart

/** "30 aug 2026 morning", optionally followed by a de-duplicating " 2", " 3", … (see AutomaticSessionHelper). */
private val AutomaticSessionNamePattern =
    Regex("""^(\d{1,2}) ([a-z]{3}) (\d{4}) (morning|afternoon|evening|night)((?: \d+)?)$""")

private val EnglishMonthAbbreviations =
    listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

/**
 * A session name as shown in the app's language. Automatic session names are stored and synced in
 * English ("30 aug 2026 morning 2") and must stay that way, so only their display is translated
 * ("30 sie 2026 rano 2" in Polish); in English the result equals the stored name. Any other name,
 * such as a manual session's, is returned unchanged.
 */
fun displaySessionName(resources: Resources, name: String): String {
    val match = AutomaticSessionNamePattern.matchEntire(name) ?: return name
    val (day, month, year, dayPart, suffix) = match.destructured
    val monthIndex = EnglishMonthAbbreviations.indexOf(month)
    if (monthIndex < 0) return name
    val monthName = resources.getStringArray(R.array.session_name_months)[monthIndex]
    val dayPartName = resources.getString(
        when (DayPart.fromString(dayPart)) {
            DayPart.MORNING -> R.string.session_name_morning
            DayPart.AFTERNOON -> R.string.session_name_afternoon
            DayPart.EVENING -> R.string.session_name_evening
            DayPart.NIGHT -> R.string.session_name_night
        }
    )
    return "$day $monthName $year $dayPartName$suffix"
}

@Composable
fun displaySessionName(name: String): String = displaySessionName(LocalResources.current, name)
