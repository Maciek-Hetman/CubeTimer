package com.maciekhetman.cubetimer.data.auth

import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArraySet

/**
 * A [SharedPreferences] that lives only in memory - nothing is ever written to disk.
 *
 * [EncryptedTokenStorage] falls back to this when encrypted storage can't be built, so the refresh
 * token is held for the lifetime of the process but never persisted in the clear. Semantics follow
 * the platform implementation: [SharedPreferences.Editor.clear] is applied before the edit's own
 * puts and removes regardless of call order, `putString(key, null)` removes the key, and both
 * `apply()` and `commit()` take effect immediately.
 */
class InMemorySharedPreferences : SharedPreferences {

    private val lock = Any()
    private val values = HashMap<String, Any>()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = synchronized(lock) {
        values.mapValuesTo(HashMap()) { (_, value) -> if (value is Set<*>) HashSet(value) else value }
    }

    private fun lookup(key: String?): Any? =
        if (key == null) null else synchronized(lock) { values[key] }

    override fun getString(key: String?, defValue: String?): String? =
        (lookup(key) as String?) ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (lookup(key) as Set<String>?)?.let { HashSet(it) } ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = (lookup(key) as Int?) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = (lookup(key) as Long?) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = (lookup(key) as Float?) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (lookup(key) as Boolean?) ?: defValue

    override fun contains(key: String?): Boolean = lookup(key) != null

    override fun edit(): SharedPreferences.Editor = EditorImpl()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        listeners.remove(listener)
    }

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = HashMap<String, Any>()
        private val removals = HashSet<String>()
        private var clearRequested = false

        private fun stage(key: String?, value: Any?): SharedPreferences.Editor {
            requireNotNull(key) { "key must not be null" }
            synchronized(this) {
                if (value == null) {
                    pending.remove(key)
                    removals += key
                } else {
                    removals.remove(key)
                    pending[key] = value
                }
            }
            return this
        }

        override fun putString(key: String?, value: String?) = stage(key, value)

        override fun putStringSet(key: String?, values: MutableSet<String>?) =
            stage(key, values?.let { HashSet(it) })

        override fun putInt(key: String?, value: Int) = stage(key, value)

        override fun putLong(key: String?, value: Long) = stage(key, value)

        override fun putFloat(key: String?, value: Float) = stage(key, value)

        override fun putBoolean(key: String?, value: Boolean) = stage(key, value)

        override fun remove(key: String?) = stage(key, null)

        override fun clear(): SharedPreferences.Editor {
            synchronized(this) { clearRequested = true }
            return this
        }

        override fun commit(): Boolean {
            commitToMemory()
            return true
        }

        override fun apply() {
            commitToMemory()
        }

        private fun commitToMemory() {
            val changedKeys = ArrayList<String>()
            synchronized(lock) {
                synchronized(this) {
                    if (clearRequested) {
                        changedKeys += values.keys
                        values.clear()
                        clearRequested = false
                    }
                    for (key in removals) {
                        if (values.remove(key) != null) changedKeys += key
                    }
                    for ((key, value) in pending) {
                        if (values.put(key, value) != value) changedKeys += key
                    }
                    removals.clear()
                    pending.clear()
                }
            }
            if (changedKeys.isNotEmpty()) {
                for (listener in listeners) {
                    for (key in changedKeys.distinct()) {
                        listener.onSharedPreferenceChanged(this@InMemorySharedPreferences, key)
                    }
                }
            }
        }
    }
}
