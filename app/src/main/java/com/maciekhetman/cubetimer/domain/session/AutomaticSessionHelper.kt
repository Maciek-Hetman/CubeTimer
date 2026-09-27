package com.maciekhetman.cubetimer.domain.session

import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.data.local.entity.SessionEntity
import com.maciekhetman.cubetimer.data.local.entity.SolveEntity
import com.maciekhetman.cubetimer.model.DayPart
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Session
import com.maciekhetman.cubetimer.model.SessionKind
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Domain helper for automatic speedcubing session grouping, naming,
 * duplicate disambiguation, and 60-minute inactivity gap evaluation.
 *
 * Conforms directly to the CubeTimer-web reference specification.
 */
object AutomaticSessionHelper {

    const val DEFAULT_INACTIVITY_GAP_MILLIS = 60 * 60 * 1000L // 60 minutes = 3,600,000 ms

    // Formatter matching 3-letter lowercase English abbreviation
    private val MONTH_FORMATTER = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)

    /**
     * Determines the DayPart for a given Instant and ZoneId.
     */
    fun dayPartFromInstant(
        instant: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): DayPart {
        val zdt = ZonedDateTime.ofInstant(instant, zoneId)
        return DayPart.fromHour(zdt.hour)
    }

    /**
     * Determines the DayPart from a 24-hour integer (0..23).
     */
    fun dayPartFromHour(hour: Int): DayPart = DayPart.fromHour(hour)

    /**
     * Generates base automatic session name: "${day} ${month} ${year} ${dayPart}".
     * Example: "30 aug 2026 morning", "1 sep 2026 night".
     */
    fun automaticSessionName(
        instant: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val zdt = ZonedDateTime.ofInstant(instant, zoneId)
        val day = zdt.dayOfMonth
        val month = zdt.format(MONTH_FORMATTER).lowercase(Locale.ENGLISH)
        val year = zdt.year
        val dayPart = DayPart.fromHour(zdt.hour).value
        return "$day $month $year $dayPart"
    }

    /**
     * Backward-compatible alias for generateBaseSessionName.
     */
    fun generateBaseSessionName(
        instant: Instant = Instant.now(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String = automaticSessionName(instant, zoneId)

    /**
     * Disambiguates duplicate automatic session names by appending " 2", " 3", etc.
     */
    fun uniqueAutomaticSessionName(
        instant: Instant = Instant.now(),
        existingNames: Iterable<String>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val base = automaticSessionName(instant, zoneId)
        return disambiguateSessionName(base, existingNames)
    }

    /**
     * Disambiguates a base name given existing session names.
     */
    fun disambiguateSessionName(
        baseName: String,
        existingNames: Iterable<String>
    ): String {
        val taken = existingNames.map { it.lowercase().trim() }.toSet()
        if (!taken.contains(baseName.lowercase().trim())) {
            return baseName
        }

        var n = 2
        while (taken.contains("${baseName.lowercase().trim()} $n")) {
            n += 1
        }
        return "$baseName $n"
    }

    /**
     * Evaluates whether an open automatic session (Domain model) should be reused.
     */
    fun shouldReuseAutomaticSession(
        session: Session?,
        lastSolveTimestampMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
        gapMs: Long = DEFAULT_INACTIVITY_GAP_MILLIS,
        mode: Mode = Mode.CUBE_3x3
    ): Boolean {
        if (session == null || session.deletedAt != null || session.archived || session.endedAt != null) {
            return false
        }
        if (session.kind != SessionKind.AUTOMATIC || session.event != mode) {
            return false
        }

        val lastActivityMs = lastSolveTimestampMs ?: CubeTypeConverters.isoToEpochMillis(session.startedAt)
        val elapsed = nowMs - lastActivityMs
        return elapsed in 0..gapMs
    }

    /**
     * Evaluates whether an open automatic session (Entity model) should be reused.
     */
    fun shouldReuseAutomaticSession(
        session: SessionEntity?,
        lastSolve: SolveEntity?,
        nowMs: Long = System.currentTimeMillis(),
        gapMs: Long = DEFAULT_INACTIVITY_GAP_MILLIS,
        event: String = "3x3"
    ): Boolean {
        if (session == null || session.deletedAt != null || session.archived || session.endedAt != null) {
            return false
        }
        if (session.kind != "automatic" || session.event != event) {
            return false
        }

        val lastActivityMs = if (lastSolve != null) {
            CubeTypeConverters.isoToEpochMillis(lastSolve.solvedAt)
        } else {
            CubeTypeConverters.isoToEpochMillis(session.startedAt)
        }

        val elapsed = nowMs - lastActivityMs
        return elapsed in 0..gapMs
    }
}
