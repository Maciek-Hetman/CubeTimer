package com.maciekhetman.cubetimer.data.auth

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InMemorySharedPreferencesTest {

    @Test
    fun `stores and reads every type, falling back to the default for missing keys`() {
        val prefs = InMemorySharedPreferences()
        prefs.edit()
            .putString("s", "text")
            .putInt("i", 7)
            .putLong("l", 8L)
            .putFloat("f", 1.5f)
            .putBoolean("b", true)
            .putStringSet("set", setOf("x", "y"))
            .commit()

        assertEquals("text", prefs.getString("s", "default"))
        assertEquals(7, prefs.getInt("i", -1))
        assertEquals(8L, prefs.getLong("l", -1L))
        assertEquals(1.5f, prefs.getFloat("f", -1f), 0f)
        assertTrue(prefs.getBoolean("b", false))
        assertEquals(setOf("x", "y"), prefs.getStringSet("set", null))
        assertTrue(prefs.contains("s"))

        assertEquals("default", prefs.getString("missing", "default"))
        assertEquals(-1, prefs.getInt("missing", -1))
        assertFalse(prefs.getBoolean("missing", false))
        assertNull(prefs.getStringSet("missing", null))
        assertFalse(prefs.contains("missing"))
    }

    @Test
    fun `apply and commit take effect immediately`() {
        val prefs = InMemorySharedPreferences()

        prefs.edit().putString("a", "1").apply()
        assertEquals("1", prefs.getString("a", null))

        assertTrue(prefs.edit().putString("b", "2").commit())
        assertEquals("2", prefs.getString("b", null))
    }

    @Test
    fun `clear is applied before the edit's own puts and removes like the platform`() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("a", "1").putString("b", "2").commit()

        prefs.edit().putString("c", "3").clear().remove("b").commit()

        assertEquals(mapOf("c" to "3"), prefs.all)
    }

    @Test
    fun `null value removes the key and the last call per key wins`() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("a", "1").putString("b", "2").commit()

        prefs.edit().putString("a", null).remove("b").putString("b", "again").commit()

        assertFalse(prefs.contains("a"))
        assertEquals("again", prefs.getString("b", null))
    }

    @Test
    fun `an editor that was never committed changes nothing`() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("a", "1").commit()

        prefs.edit().putString("a", "changed").remove("a").clear()

        assertEquals("1", prefs.getString("a", null))
    }

    @Test
    fun `getAll and string sets are defensive copies`() {
        val prefs = InMemorySharedPreferences()
        val original = mutableSetOf("x")
        prefs.edit().putString("s", "text").putStringSet("set", original).commit()
        original += "mutated-after-put"

        val snapshot = prefs.all
        val set = prefs.getStringSet("set", null)!!
        set += "mutated-after-get"

        assertEquals(setOf("x"), prefs.getStringSet("set", null))
        assertEquals("text", snapshot["s"])
        assertNotSame(snapshot, prefs.all)
    }

    @Test
    fun `listeners hear about changed keys until unregistered`() {
        val prefs = InMemorySharedPreferences()
        val heard = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> heard += key }
        prefs.registerOnSharedPreferenceChangeListener(listener)

        prefs.edit().putString("a", "1").putString("b", "2").commit()
        prefs.edit().putString("a", "1").commit() // unchanged value: nothing to report
        prefs.edit().remove("b").commit()
        assertEquals(listOf<String?>("a", "b", "b"), heard.sortedBy { it })

        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        prefs.edit().putString("c", "3").commit()
        assertEquals(3, heard.size)
    }
}
