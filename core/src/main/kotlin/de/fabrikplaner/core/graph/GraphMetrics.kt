package de.fabrikplaner.core.graph

import de.fabrikplaner.core.model.GraphNode

/**
 * Card and port geometry in density-independent units (the same units as [GraphNode.x]).
 * The port point is the center of the dot. Input dots sit on the left edge, output dots on the right edge.
 * The canvas and the cards must both use these points; a second offset formula drifts at every zoom level.
 */
object GraphMetrics {
    const val CARD_W = 228f
    const val CARD_H = 168f
    const val GAP_X = 132f
    const val GAP_Y = 40f
    const val ORIGIN = 48f
    const val PORT_RADIUS = 7f

    /**
     * Ports sit on the card edge and are spread across the inner height.
     * One port is centered. Several ports use the full span so neighboring lines stay apart.
     */
    fun portY(index: Int, count: Int): Float {
        val safeCount = count.coerceAtLeast(1)
        val top = 42f
        val bottom = CARD_H - 16f
        if (safeCount == 1) return (top + bottom) / 2f
        return top + (bottom - top) * index / (safeCount - 1)
    }

    fun inputPort(node: GraphNode, index: Int, count: Int): Pair<Float, Float> =
        node.x to (node.y + portY(index, count))

    fun outputPort(node: GraphNode, index: Int, count: Int): Pair<Float, Float> =
        (node.x + CARD_W) to (node.y + portY(index, count))
}
