package de.fabrikplaner.core.graph

import de.fabrikplaner.core.data.GameData
import de.fabrikplaner.core.model.GraphEdge
import de.fabrikplaner.core.model.GraphNode
import de.fabrikplaner.core.model.NodeKind

data class ConnectResult(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val connected: Boolean,
)

object GraphOps {
    fun connect(
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        fromId: String,
        toId: String,
        itemId: String,
        game: GameData,
        edgeId: String,
    ): ConnectResult {
        if (fromId == toId) return ConnectResult(nodes, edges, false)
        val from = nodes.find { it.id == fromId } ?: return ConnectResult(nodes, edges, false)
        val to = nodes.find { it.id == toId } ?: return ConnectResult(nodes, edges, false)
        if (!produces(from, itemId, game) || !accepts(to, itemId, game)) {
            return ConnectResult(nodes, edges, false)
        }
        if (edges.any { it.fromNodeId == fromId && it.toNodeId == toId && it.itemId == itemId }) {
            return ConnectResult(nodes, edges, false)
        }
        val updatedNodes = nodes.map { node ->
            when {
                node.id == fromId && node.itemId == null &&
                    (node.kind == NodeKind.SPLITTER || node.kind == NodeKind.MERGER) -> node.copy(itemId = itemId)
                node.id == toId && node.itemId == null &&
                    (node.kind == NodeKind.SPLITTER || node.kind == NodeKind.MERGER) -> node.copy(itemId = itemId)
                else -> node
            }
        }
        val edge = GraphEdge(id = edgeId, fromNodeId = fromId, toNodeId = toId, itemId = itemId)
        return ConnectResult(updatedNodes, edges + edge, true)
    }

    fun produces(node: GraphNode, itemId: String, game: GameData): Boolean = when (node.kind) {
        NodeKind.SINK -> false
        NodeKind.SOURCE -> node.itemId == itemId
        NodeKind.SPLITTER, NodeKind.MERGER -> node.itemId == null || node.itemId == itemId
        NodeKind.RECIPE -> {
            val recipe = node.recipeId?.let { game.recipeOrNull(it) } ?: return false
            recipe.outputs.any { it.itemId == itemId }
        }
    }

    fun accepts(node: GraphNode, itemId: String, game: GameData): Boolean = when (node.kind) {
        NodeKind.SOURCE -> false
        NodeKind.SINK -> node.itemId == itemId
        NodeKind.SPLITTER, NodeKind.MERGER -> node.itemId == null || node.itemId == itemId
        NodeKind.RECIPE -> {
            val recipe = node.recipeId?.let { game.recipeOrNull(it) } ?: return false
            recipe.inputs.any { it.itemId == itemId }
        }
    }
}
