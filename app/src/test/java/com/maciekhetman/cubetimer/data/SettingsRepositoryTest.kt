package com.maciekhetman.cubetimer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
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
}
