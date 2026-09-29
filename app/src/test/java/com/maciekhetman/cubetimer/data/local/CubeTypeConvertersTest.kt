package com.maciekhetman.cubetimer.data.local

import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import org.junit.Assert.assertEquals
import org.junit.Test

class CubeTypeConvertersTest {

    @Test
    fun testModeConverters() {
        assertEquals("2x2", CubeTypeConverters.fromMode(Mode.CUBE_2x2))
        assertEquals("3x3", CubeTypeConverters.fromMode(Mode.CUBE_3x3))
        assertEquals("4x4", CubeTypeConverters.fromMode(Mode.CUBE_4x4))
        assertEquals("5x5", CubeTypeConverters.fromMode(Mode.CUBE_5x5))
        assertEquals("megaminx", CubeTypeConverters.fromMode(Mode.MEGAMINX))
        assertEquals("pyraminx", CubeTypeConverters.fromMode(Mode.PYRAMINX))
        assertEquals("3x3", CubeTypeConverters.fromMode(null))

        assertEquals(Mode.CUBE_2x2, CubeTypeConverters.toMode("2x2"))
        assertEquals(Mode.CUBE_3x3, CubeTypeConverters.toMode("3x3"))
        assertEquals(Mode.CUBE_4x4, CubeTypeConverters.toMode("4x4"))
        assertEquals(Mode.CUBE_5x5, CubeTypeConverters.toMode("5x5"))
        assertEquals(Mode.MEGAMINX, CubeTypeConverters.toMode("megaminx"))
        assertEquals(Mode.PYRAMINX, CubeTypeConverters.toMode("pyraminx"))
        assertEquals(Mode.CUBE_3x3, CubeTypeConverters.toMode("unknown"))
        assertEquals(Mode.CUBE_3x3, CubeTypeConverters.toMode(null))
    }

    @Test
    fun testPenaltyConverters() {
        assertEquals("none", CubeTypeConverters.fromPenalty(Penalty.NONE))
        assertEquals("plus_two", CubeTypeConverters.fromPenalty(Penalty.PLUS_TWO))
        assertEquals("dnf", CubeTypeConverters.fromPenalty(Penalty.DNF))
        assertEquals("none", CubeTypeConverters.fromPenalty(null))

        assertEquals(Penalty.NONE, CubeTypeConverters.toPenalty("none"))
        assertEquals(Penalty.PLUS_TWO, CubeTypeConverters.toPenalty("plus_two"))
        assertEquals(Penalty.PLUS_TWO, CubeTypeConverters.toPenalty("+2"))
        assertEquals(Penalty.DNF, CubeTypeConverters.toPenalty("dnf"))
        assertEquals(Penalty.NONE, CubeTypeConverters.toPenalty("unknown"))
        assertEquals(Penalty.NONE, CubeTypeConverters.toPenalty(null))
    }

    @Test
    fun eventForRewrite_keepsAnEventThisAppHasNoModeFor() {
        // toMode("skewb") is the 3x3 fallback, so a domain model round-tripped through it must not
        // overwrite the stored string.
        assertEquals(Mode.CUBE_3x3, CubeTypeConverters.toMode("skewb"))
        assertEquals("skewb", CubeTypeConverters.eventForRewrite("skewb", Mode.CUBE_3x3))
        assertEquals("clock", CubeTypeConverters.eventForRewrite("clock", Mode.CUBE_3x3))
    }

    @Test
    fun eventForRewrite_usesTheModeForKnownEventsAndExplicitChanges() {
        assertEquals("3x3", CubeTypeConverters.eventForRewrite("3x3", Mode.CUBE_3x3))
        assertEquals("3x3", CubeTypeConverters.eventForRewrite("cube_3x3", Mode.CUBE_3x3))
        assertEquals("pyraminx", CubeTypeConverters.eventForRewrite("pyraminx", Mode.PYRAMINX))
        // The caller picked another mode on purpose: that wins over whatever was stored.
        assertEquals("4x4", CubeTypeConverters.eventForRewrite("skewb", Mode.CUBE_4x4))
    }
}
