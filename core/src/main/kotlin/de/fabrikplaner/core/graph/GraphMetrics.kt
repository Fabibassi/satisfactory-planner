package de.fabrikplaner.core.graph

import de.fabrikplaner.core.model.GraphNode

/**
 * Card and port geometry in density-independent units (the same units as [GraphNode.x]).
 * The port point is the center of the dot. Input dots sit on the left edge, output dots on the right edge.
 * The canvas and the cards must both use these points; a second offset formula drifts at every zoom level.
 */
object GraphMetrics {
    const val CARD_W = 228f
    const val CARD_H = 124f
    const val GAP_X = 132f
    const val GAP_Y = 36f
    const val ORIGIN = 48f
    const val PORT_RADIUS = 7f

    fun portY(index: Int, count: Int): Float {
        val safeCount = count.coerceAtLeast(1)
        val top = 36f
        val bottom = CARD_H - PORT_RADIUS - 8f
        return top + (bottom - top) * (index + 0.5f) / safeCount
    }

    fun inputPort(node: GraphNode, index: Int, count: Int): Pair<Float, Float> =
        node.x to (node.y + portY(index, count))

    fun outputPort(node: GraphNode, index: Int, count: Int): Pair<Float, Float> =
        (node.x + CARD_W) to (node.y + portY(index, count))
}
