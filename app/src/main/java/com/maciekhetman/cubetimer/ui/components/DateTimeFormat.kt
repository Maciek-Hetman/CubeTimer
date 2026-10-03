package com.maciekhetman.cubetimer.ui.components

import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * [epochMillis] as a short date and time (year, abbreviated month, day, hours and minutes) in the
 * app's language and in [timeZone] (the device's by default). Built on every call, never cached,
 * because the language can change while the app runs (system per-app language setting).
 */
fun formatDateTime(epochMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
    val locale = Locale.getDefault()
    val format = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "yMMMdjmm"), locale)
    format.timeZone = timeZone
    return format.format(Date(epochMillis))
}
