package de.fabrikplaner.core.graph

import de.fabrikplaner.core.data.GameData
import de.fabrikplaner.core.model.GraphEdge
import de.fabrikplaner.core.model.GraphNode
import de.fabrikplaner.core.model.NodeKind

object GraphPorts {
    fun inputs(node: GraphNode, game: GameData): List<String> = when (node.kind) {
        NodeKind.RECIPE -> node.recipeId?.let { game.recipeOrNull(it) }?.inputs?.map { it.itemId }.orEmpty()
        NodeKind.SINK, NodeKind.SPLITTER, NodeKind.MERGER -> listOfNotNull(node.itemId)
        NodeKind.SOURCE -> emptyList()
    }

    fun outputs(node: GraphNode, game: GameData): List<String> = when (node.kind) {
        NodeKind.RECIPE -> node.recipeId?.let { game.recipeOrNull(it) }?.outputs?.map { it.itemId }.orEmpty()
        NodeKind.SOURCE, NodeKind.SPLITTER, NodeKind.MERGER -> listOfNotNull(node.itemId)
        NodeKind.SINK -> emptyList()
    }

    fun orderedInputs(
        node: GraphNode,
        game: GameData,
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
    ): List<String> = orderTowardNeighbors(node, inputs(node, game), nodes, edges, output = false)

    fun orderedOutputs(
        node: GraphNode,
        game: GameData,
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
    ): List<String> = orderTowardNeighbors(node, outputs(node, game), nodes, edges, output = true)

    fun inputCenter(
        node: GraphNode,
        itemId: String,
        game: GameData,
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
    ): Pair<Float, Float> {
        val items = orderedInputs(node, game, nodes, edges)
        val index = items.indexOf(itemId).let { if (it < 0) 0 else it }
        return GraphMetrics.inputPort(node, index, items.size.coerceAtLeast(1))
    }

    fun outputCenter(
        node: GraphNode,
        itemId: String,
        game: GameData,
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
    ): Pair<Float, Float> {
        val items = orderedOutputs(node, game, nodes, edges)
        val index = items.indexOf(itemId).let { if (it < 0) 0 else it }
        return GraphMetrics.outputPort(node, index, items.size.coerceAtLeast(1))
    }

    private fun orderTowardNeighbors(
        node: GraphNode,
        items: List<String>,
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        output: Boolean,
    ): List<String> {
        if (items.size <= 1) return items
        val byId = nodes.associateBy { it.id }
        fun anchor(itemId: String): Double {
            val links = edges.filter { edge ->
                edge.itemId == itemId && if (output) edge.fromNodeId == node.id else edge.toNodeId == node.id
            }
            val samples = links.mapNotNull { edge ->
                val otherId = if (output) edge.toNodeId else edge.fromNodeId
                byId[otherId]?.let { it.y + GraphMetrics.CARD_H / 2f }
            }
            if (samples.isEmpty()) return items.indexOf(itemId).toDouble()
            return samples.average()
        }
        return items.sortedWith(compareBy({ anchor(it) }, { items.indexOf(it) }))
    }
}
