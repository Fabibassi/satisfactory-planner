package de.fabrikplaner.core.solver

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LinearSolverTest {
    @Test
    fun solvesTwoByTwoSystem() {
        val a = arrayOf(
            doubleArrayOf(2.0, 1.0),
            doubleArrayOf(1.0, 3.0),
        )
        val x = assertNotNull(LinearSolver.solve(a, doubleArrayOf(5.0, 10.0)))
        assertClose(1.0, x[0])
        assertClose(3.0, x[1])
    }

    @Test
    fun returnsNullWhenSingular() {
        val a = arrayOf(
            doubleArrayOf(1.0, 1.0),
            doubleArrayOf(2.0, 2.0),
        )
        assertNull(LinearSolver.solve(a, doubleArrayOf(1.0, 3.0)))
    }

    @Test
    fun emptySystem() {
        val x = assertNotNull(LinearSolver.solve(emptyArray(), doubleArrayOf()))
        assertEquals(0, x.size)
    }
}

internal fun assertClose(expected: Double, actual: Double, eps: Double = 1e-4) {
    val tolerance = maxOf(eps, abs(expected) * 1e-6)
    kotlin.test.assertTrue(
        abs(expected - actual) <= tolerance,
        "expected $expected but was $actual (tol $tolerance)",
    )
}
