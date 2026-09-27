package com.maciekhetman.cubetimer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.maciekhetman.cubetimer.model.TimingDevice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repository = SettingsRepository(context)
    }

    @After
    fun tearDown() = runTest {
        context.settingsDataStore.edit { it.clear() }
        context.solvesDataStore.edit { it.clear() }
    }

    @Test
    fun hideStartHint_defaultsToFalse() = runTest {
        assertFalse(repository.hideStartHintFlow.first())
    }

    @Test
    fun setHideStartHint_persistsValue() = runTest {
        repository.setHideStartHint(true)
        assertTrue(repository.hideStartHintFlow.first())

        repository.setHideStartHint(false)
        assertFalse(repository.hideStartHintFlow.first())
    }

    @Test
    fun migrateFromLegacy_copiesHideStartHint() = runTest {
        val legacyKey = booleanPreferencesKey("hide_start_hint")
        context.solvesDataStore.edit { prefs ->
            prefs[legacyKey] = true
        }

        repository.migrateFromLegacyIfNeeded()

        assertTrue(repository.hideStartHintFlow.first())
    }

    @Test
    fun timingDevice_defaultsToTouch() = runTest {
        assertEquals(TimingDevice.KEYBOARD, repository.timingDeviceFlow.first())
    }

    @Test
    fun setTimingDevice_persistsBluetoothAndBack() = runTest {
        repository.setTimingDevice(TimingDevice.EXTERNAL_TIMER)
        assertEquals(TimingDevice.EXTERNAL_TIMER, repository.timingDeviceFlow.first())

        repository.setTimingDevice(TimingDevice.KEYBOARD)
        assertEquals(TimingDevice.KEYBOARD, repository.timingDeviceFlow.first())
    }

    @Test
    fun timingDevice_smartCubeOrGarbageFallsBackToTouch() = runTest {
        // A smart cube is not a timer input on Android; neither is an unknown stored value.
        repository.setTimingDevice(TimingDevice.SMART_CUBE)
        assertEquals(TimingDevice.KEYBOARD, repository.timingDeviceFlow.first())

        context.settingsDataStore.edit { it[stringPreferencesKey("timing_device")] = "stackmat" }
        assertEquals(TimingDevice.KEYBOARD, repository.timingDeviceFlow.first())
    }

    @Test
    fun scrambleScalePercent_defaultsTo100() = runTest {
        org.junit.Assert.assertEquals(100, repository.scrambleScalePercentFlow.first())
    }

    @Test
    fun setScrambleScalePercent_persistsValidValuesAcrossRange() = runTest {
        val testScales = listOf(70, 75, 80, 100, 125, 140)
        for (scale in testScales) {
            repository.setScrambleScalePercent(scale)
            org.junit.Assert.assertEquals(scale, repository.scrambleScalePercentFlow.first())
        }
    }

    @Test
    fun setScrambleScalePercent_coercesValuesOutside70To140() = runTest {
        repository.setScrambleScalePercent(50)
        org.junit.Assert.assertEquals(70, repository.scrambleScalePercentFlow.first())

        repository.setScrambleScalePercent(180)
        org.junit.Assert.assertEquals(140, repository.scrambleScalePercentFlow.first())
    }

    @Test
    fun scrambleScalePercentFlow_coercesLegacyOrCorruptedValuesOnRead() = runTest {
        val scrambleKey = androidx.datastore.preferences.core.intPreferencesKey("scramble_scale_percent")
        context.settingsDataStore.edit { prefs ->
            prefs[scrambleKey] = 50
        }
        org.junit.Assert.assertEquals(70, repository.scrambleScalePercentFlow.first())

        context.settingsDataStore.edit { prefs ->
            prefs[scrambleKey] = 200
        }
        org.junit.Assert.assertEquals(140, repository.scrambleScalePercentFlow.first())
    }
}

