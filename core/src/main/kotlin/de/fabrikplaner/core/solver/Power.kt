package de.fabrikplaner.core.solver

import kotlin.math.pow

/** Satisfactory clock exponent: power scales with (clock/100)^log2(2.5). */
const val POWER_EXPONENT = 1.321928

fun craftingPowerMw(baseMw: Double, machines: Double, clockPercent: Double = 100.0): Double {
    val clock = (clockPercent / 100.0).coerceAtLeast(0.0)
    return baseMw * machines.coerceAtLeast(0.0) * clock.pow(POWER_EXPONENT)
}
