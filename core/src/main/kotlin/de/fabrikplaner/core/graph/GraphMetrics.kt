package de.fabrikplaner.core.graph

import de.fabrikplaner.core.model.GraphNode

object GraphMetrics {
    const val CARD_W = 210f
    const val CARD_H = 132f
    const val GAP_X = 80f
    const val GAP_Y = 40f
    const val ORIGIN = 48f

    fun portY(index: Int, count: Int): Float {
        val safeCount = count.coerceAtLeast(1)
        val top = 40f
        val bottom = CARD_H - 16f
        return top + (bottom - top) * (index + 0.5f) / safeCount
    }

    fun inputPort(node: GraphNode, index: Int, count: Int): Pair<Float, Float> =
        node.x to (node.y + portY(index, count))

    fun outputPort(node: GraphNode, index: Int, count: Int): Pair<Float, Float> =
        (node.x + CARD_W) to (node.y + portY(index, count))
}
