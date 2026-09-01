package com.gabrielpc.enginesoundsimulator

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences

/** Routes app preference access to test-only files while preserving assets and system services. */
internal class IsolatedPreferenceContext(
    base: Context,
    private val namespace: String,
) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        return baseContext.getSharedPreferences(testPreferenceName(name), mode)
    }

    fun clear() {
        AppPreferenceStores.all.forEach { name ->
            baseContext.deleteSharedPreferences(testPreferenceName(name))
        }
    }

    private fun testPreferenceName(name: String): String = "test.$namespace.$name"
}
