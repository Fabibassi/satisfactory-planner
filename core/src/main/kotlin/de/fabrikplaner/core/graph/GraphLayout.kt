package de.fabrikplaner.core.graph

import de.fabrikplaner.core.data.GameData
import de.fabrikplaner.core.model.GraphEdge
import de.fabrikplaner.core.model.GraphNode
import de.fabrikplaner.core.model.NodeKind
import de.fabrikplaner.core.model.TargetRate
import de.fabrikplaner.core.solver.SolverResult

data class FactoryGraph(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
)

object GraphLayout {
    fun fromSolution(result: SolverResult, targets: List<TargetRate>, game: GameData): FactoryGraph {
        val recipeNodes = result.machines.mapNotNull { machine ->
            val recipeId = machine.recipeId ?: return@mapNotNull null
            val recipe = game.recipe(recipeId)
            GraphNode(
                id = "recipe-$recipeId",
                kind = NodeKind.RECIPE,
                x = 0f,
                y = 0f,
                recipeId = recipeId,
                buildingId = recipe.buildingId,
                machineCount = machine.machineCount,
                clockPercent = 100.0,
            )
        }
        val sources = result.extractors.map { extractor ->
            val itemId = extractor.outputs.first().itemId
            GraphNode(
                id = "src-$itemId",
                kind = NodeKind.SOURCE,
                x = 0f,
                y = 0f,
                buildingId = extractor.buildingId,
                itemId = itemId,
                machineCount = extractor.machineCount,
                ratePerMinute = extractor.outputs.first().perMinute,
            )
        }
        val sinks = targets.filter { it.perMinute > 0.0 }.map { target ->
            GraphNode(
                id = "sink-${target.itemId}",
                kind = NodeKind.SINK,
                x = 0f,
                y = 0f,
                itemId = target.itemId,
                ratePerMinute = target.perMinute,
            )
        }
        val nodes = sources + recipeNodes + sinks
        val edges = connect(nodes, game)
        val arranged = arrange(nodes, edges)
        return FactoryGraph(EdgeRouter.openChannels(arranged, edges), edges)
    }

    /**
     * Sugiyama-style placement: longest-path layers left to right, then barycenter sweeps
     * to cut crossings. Ports are ordered separately from the finished coordinates.
     */
    fun arrange(nodes: List<GraphNode>, edges: List<GraphEdge>): List<GraphNode> {
        if (nodes.isEmpty()) return nodes
        val compacted = compactLayers(assignLayers(nodes, edges))
        val layers = nodes.groupBy { compacted[it.id] ?: 0 }
            .mapValues { (_, column) -> column.sortedBy { it.id }.toMutableList() }
            .toMutableMap()
        repeat(8) { pass ->
            sweep(layers, edges, leftToRight = pass % 2 == 0)
        }
        val maxLayer = layers.keys.maxOrNull() ?: 0
        val placed = mutableListOf<GraphNode>()
        for (layer in 0..maxLayer) {
            placed += place(layers[layer].orEmpty(), layer)
        }
        return placed
    }

    fun straightCrossings(nodes: List<GraphNode>, edges: List<GraphEdge>): Int {
        val byId = nodes.associateBy { it.id }
        val segments = edges.mapNotNull { edge ->
            val from = byId[edge.fromNodeId] ?: return@mapNotNull null
            val to = byId[edge.toNodeId] ?: return@mapNotNull null
            Segment(
                edge.fromNodeId,
                edge.toNodeId,
                from.x + GraphMetrics.CARD_W / 2f,
                from.y + GraphMetrics.CARD_H / 2f,
                to.x + GraphMetrics.CARD_W / 2f,
                to.y + GraphMetrics.CARD_H / 2f,
            )
        }
        var count = 0
        for (i in segments.indices) {
            for (j in i + 1 until segments.size) {
                val a = segments[i]
                val b = segments[j]
                if (a.from == b.from || a.from == b.to || a.to == b.from || a.to == b.to) continue
                if (properCross(a, b)) count++
            }
        }
        return count
    }

    private fun assignLayers(nodes: List<GraphNode>, edges: List<GraphEdge>): Map<String, Int> {
        val incoming = edges.groupBy { it.toNodeId }
        val memo = HashMap<String, Int>()
        fun depth(id: String, stack: Set<String>): Int {
            memo[id]?.let { return it }
            if (id in stack) return 0
            val preds = incoming[id].orEmpty()
            val value = if (preds.isEmpty()) 0 else preds.maxOf { 1 + depth(it.fromNodeId, stack + id) }
            memo[id] = value
            return value
        }
        nodes.forEach { depth(it.id, emptySet()) }
        return memo
    }

    private fun compactLayers(layers: Map<String, Int>): Map<String, Int> {
        val used = layers.values.distinct().sorted()
        val remap = used.withIndex().associate { it.value to it.index }
        return layers.mapValues { remap.getValue(it.value) }
    }

    private fun sweep(
        layers: MutableMap<Int, MutableList<GraphNode>>,
        edges: List<GraphEdge>,
        leftToRight: Boolean,
    ) {
        val max = layers.keys.maxOrNull() ?: return
        val sequence = if (leftToRight) (1..max).toList() else (max - 1 downTo 0).toList()
        val outgoing = edges.groupBy { it.fromNodeId }
        val incoming = edges.groupBy { it.toNodeId }
        for (layer in sequence) {
            val neighbor = if (leftToRight) layer - 1 else layer + 1
            val indexOf = layers[neighbor].orEmpty().mapIndexed { index, node -> node.id to index.toDouble() }.toMap()
            val current = layers[layer] ?: continue
            val ranked = current.mapIndexed { fallback, node ->
                val links = if (leftToRight) incoming[node.id].orEmpty() else outgoing[node.id].orEmpty()
                val samples = links.mapNotNull { edge ->
                    val other = if (leftToRight) edge.fromNodeId else edge.toNodeId
                    indexOf[other]
                }
                val score = if (samples.isEmpty()) fallback.toDouble() else samples.average()
                Rank(score, fallback, node)
            }
            layers[layer] = ranked.sortedWith(compareBy({ it.score }, { it.fallback })).map { it.node }.toMutableList()
        }
    }

    private fun place(layerNodes: List<GraphNode>, layer: Int): List<GraphNode> =
        layerNodes.mapIndexed { index, node ->
            val x = GraphMetrics.ORIGIN + layer * (GraphMetrics.CARD_W + GraphMetrics.GAP_X)
            val y = GraphMetrics.ORIGIN + index * (GraphMetrics.CARD_H + GraphMetrics.GAP_Y)
            node.copy(x = x, y = y)
        }

    private fun connect(nodes: List<GraphNode>, game: GameData): List<GraphEdge> {
        val edges = mutableListOf<GraphEdge>()
        val itemIds = LinkedHashSet<String>()
        for (node in nodes) {
            when (node.kind) {
                NodeKind.SOURCE, NodeKind.SINK -> node.itemId?.let { itemIds += it }
                NodeKind.RECIPE -> {
                    val recipe = node.recipeId?.let { game.recipeOrNull(it) } ?: continue
                    recipe.inputs.forEach { itemIds += it.itemId }
                    recipe.outputs.forEach { itemIds += it.itemId }
                }
                else -> node.itemId?.let { itemIds += it }
            }
        }
        var serial = 0
        for (itemId in itemIds) {
            val producers = producers(nodes, game, itemId)
            val consumers = consumers(nodes, game, itemId)
            for (producer in producers) {
                for (consumer in consumers) {
                    if (producer == consumer) continue
                    if (producer.second <= 1e-6 || consumer.second <= 1e-6) continue
                    edges += GraphEdge(
                        id = "e-${serial++}",
                        fromNodeId = producer.first,
                        toNodeId = consumer.first,
                        itemId = itemId,
                    )
                }
            }
        }
        return edges
    }

    private fun producers(nodes: List<GraphNode>, game: GameData, itemId: String): List<Pair<String, Double>> =
        nodes.mapNotNull { node ->
            val amount = when (node.kind) {
                NodeKind.SOURCE -> if (node.itemId == itemId) node.ratePerMinute else 0.0
                NodeKind.RECIPE -> {
                    val recipe = node.recipeId?.let { game.recipeOrNull(it) } ?: return@mapNotNull null
                    game.outputRate(recipe, itemId) * node.machineCount * node.clockPercent / 100.0
                }
                else -> 0.0
            }
            if (amount > 1e-6) node.id to amount else null
        }

    private fun consumers(nodes: List<GraphNode>, game: GameData, itemId: String): List<Pair<String, Double>> =
        nodes.mapNotNull { node ->
            val amount = when (node.kind) {
                NodeKind.SINK -> if (node.itemId == itemId) node.ratePerMinute else 0.0
                NodeKind.RECIPE -> {
                    val recipe = node.recipeId?.let { game.recipeOrNull(it) } ?: return@mapNotNull null
                    game.inputRate(recipe, itemId) * node.machineCount * node.clockPercent / 100.0
                }
                else -> 0.0
            }
            if (amount > 1e-6) node.id to amount else null
        }

    private fun properCross(a: Segment, b: Segment): Boolean {
        val o1 = orient(a.x1, a.y1, a.x2, a.y2, b.x1, b.y1)
        val o2 = orient(a.x1, a.y1, a.x2, a.y2, b.x2, b.y2)
        val o3 = orient(b.x1, b.y1, b.x2, b.y2, a.x1, a.y1)
        val o4 = orient(b.x1, b.y1, b.x2, b.y2, a.x2, a.y2)
        return o1 != o2 && o3 != o4 && o1 != 0 && o2 != 0 && o3 != 0 && o4 != 0
    }

    private fun orient(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Int {
        val value = (by - ay).toDouble() * (cx - bx) - (bx - ax).toDouble() * (cy - by)
        return when {
            value > 1e-3 -> 1
            value < -1e-3 -> -1
            else -> 0
        }
    }

    private data class Rank(val score: Double, val fallback: Int, val node: GraphNode)

    private data class Segment(
        val from: String,
        val to: String,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
    )
}
