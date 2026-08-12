package com.maciekhetman.cubetimer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.solvesDataStore: DataStore<Preferences> by preferencesDataStore(name = "solves")
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
