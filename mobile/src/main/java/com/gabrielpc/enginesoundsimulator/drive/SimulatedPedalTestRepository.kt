package com.gabrielpc.enginesoundsimulator.drive

import android.content.Context
import com.gabrielpc.enginesoundsimulator.AppPreferenceStores

/** Keeps virtual-pedal experiment controls separate from the driver's normal preferences. */
internal class SimulatedPedalTestRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        AppPreferenceStores.SIMULATED_PEDAL_TEST,
        Context.MODE_PRIVATE,
    )

    fun uphillDragGrade(): Double {
        return preferences.getFloat(KEY_UPHILL_DRAG_GRADE, DEFAULT_UPHILL_DRAG_GRADE.toFloat())
            .toDouble()
            .coerceIn(MIN_UPHILL_DRAG_GRADE, MAX_UPHILL_DRAG_GRADE)
    }

    fun saveUphillDragGrade(grade: Double): Double {
        val clean = grade.coerceIn(MIN_UPHILL_DRAG_GRADE, MAX_UPHILL_DRAG_GRADE)
        val saved = preferences.edit()
            .putFloat(KEY_UPHILL_DRAG_GRADE, clean.toFloat())
            .commit()

        return if (saved) clean else uphillDragGrade()
    }

    fun coastRegenStrength(): Double {
        return preferences.getFloat(KEY_COAST_REGEN_STRENGTH, DEFAULT_COAST_REGEN_STRENGTH.toFloat())
            .toDouble()
            .coerceIn(MIN_COAST_REGEN_STRENGTH, MAX_COAST_REGEN_STRENGTH)
    }

    fun saveCoastRegenStrength(strength: Double): Double {
        val clean = strength.coerceIn(MIN_COAST_REGEN_STRENGTH, MAX_COAST_REGEN_STRENGTH)
        val saved = preferences.edit()
            .putFloat(KEY_COAST_REGEN_STRENGTH, clean.toFloat())
            .commit()

        return if (saved) clean else coastRegenStrength()
    }

    companion object {
        const val MIN_UPHILL_DRAG_GRADE = 0.0
        const val MAX_UPHILL_DRAG_GRADE = 0.30
        const val DEFAULT_UPHILL_DRAG_GRADE = 0.0
        const val MIN_COAST_REGEN_STRENGTH = 0.0
        const val MAX_COAST_REGEN_STRENGTH = 1.0
        const val DEFAULT_COAST_REGEN_STRENGTH = 1.0

        private const val KEY_UPHILL_DRAG_GRADE = "uphill_drag_grade"
        private const val KEY_COAST_REGEN_STRENGTH = "coast_regen_strength"
    }
}
