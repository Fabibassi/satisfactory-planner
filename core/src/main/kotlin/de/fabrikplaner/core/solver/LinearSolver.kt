package de.fabrikplaner.core.solver

import kotlin.math.abs

object LinearSolver {
    /**
     * Solves A x = b with Gauss-Jordan elimination and partial pivoting.
     * Returns null when the matrix is singular within [epsilon].
     */
    fun solve(a: Array<DoubleArray>, b: DoubleArray, epsilon: Double = 1e-9): DoubleArray? {
        val n = b.size
        require(a.size == n) { "row count" }
        if (n == 0) return DoubleArray(0)
        val m = Array(n) { r ->
            require(a[r].size == n) { "column count" }
            DoubleArray(n + 1) { c -> if (c < n) a[r][c] else b[r] }
        }
        for (col in 0 until n) {
            var pivot = col
            var best = abs(m[col][col])
            for (row in col + 1 until n) {
                val value = abs(m[row][col])
                if (value > best) {
                    best = value
                    pivot = row
                }
            }
            if (best < epsilon) return null
            if (pivot != col) {
                val swap = m[col]
                m[col] = m[pivot]
                m[pivot] = swap
            }
            val divisor = m[col][col]
            for (c in col..n) m[col][c] /= divisor
            for (row in 0 until n) {
                if (row == col) continue
                val factor = m[row][col]
                if (factor == 0.0) continue
                for (c in col..n) m[row][c] -= factor * m[col][c]
            }
        }
        return DoubleArray(n) { m[it][n] }
    }
}
