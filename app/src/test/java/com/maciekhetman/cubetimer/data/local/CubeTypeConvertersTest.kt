package com.maciekhetman.cubetimer.data.local

import com.maciekhetman.cubetimer.data.local.converter.CubeTypeConverters
import com.maciekhetman.cubetimer.model.Mode
import com.maciekhetman.cubetimer.model.Penalty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

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
    fun testInstantConverters() {
        val now = Instant.now()
        val str = CubeTypeConverters.instantToString(now)
        assertNotNull(str)
        val parsed = CubeTypeConverters.stringToInstant(str)
        assertEquals(now, parsed)

        assertNull(CubeTypeConverters.instantToString(null))
        assertNull(CubeTypeConverters.stringToInstant(null))
        assertNull(CubeTypeConverters.stringToInstant("invalid-timestamp"))
    }
}
