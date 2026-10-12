package de.fabrikplaner.core.graph

import de.fabrikplaner.core.data.GameData
import de.fabrikplaner.core.model.GraphEdge
import de.fabrikplaner.core.model.GraphNode
import de.fabrikplaner.core.model.NodeKind
import kotlin.math.abs

enum class FlowStatus {
    BALANCED,
    UNDER,
    OVER,
    UNCONNECTED,
    UNUSED,
}

data class PortBalance(
    val itemId: String,
    val expectedPerMinute: Double,
    val actualPerMinute: Double,
    val status: FlowStatus,
)

data class NodeBalance(
    val nodeId: String,
    val runRatio: Double,
    val inputs: List<PortBalance>,
    val outputs: List<PortBalance>,
) {
    val worst: FlowStatus
        get() {
            val statuses = inputs.map { it.status } + outputs.map { it.status }
            return when {
                statuses.any { it == FlowStatus.UNDER } -> FlowStatus.UNDER
                statuses.any { it == FlowStatus.OVER || it == FlowStatus.UNCONNECTED } -> FlowStatus.OVER
                statuses.any { it == FlowStatus.BALANCED } -> FlowStatus.BALANCED
                else -> FlowStatus.UNUSED
            }
        }
}

data class BalanceReport(
    val nodes: Map<String, NodeBalance>,
    val edgeRates: Map<String, Double>,
)

object GraphBalancer {
    private const val EPS = 1e-4

    fun evaluate(nodes: List<GraphNode>, edges: List<GraphEdge>, game: GameData): BalanceReport {
        val nodeById = nodes.associateBy { it.id }
        val outgoing = edges.groupBy { it.fromNodeId }
        val incoming = edges.groupBy { it.toNodeId }
        var flow = edges.associate { it.id to 0.0 }

        fun supplied(nodeId: String, itemId: String, rates: Map<String, Double>): Double =
            incoming[nodeId].orEmpty().filter { it.itemId == itemId }.sumOf { rates[it.id] ?: 0.0 }

        fun configuredDemand(node: GraphNode, itemId: String): Double = when (node.kind) {
            NodeKind.SINK -> if (node.itemId == itemId) node.ratePerMinute.coerceAtLeast(0.0) else 0.0
            NodeKind.RECIPE -> {
                val recipe = node.recipeId?.let { game.recipeOrNull(it) } ?: return 0.0
                val potential = potential(node)
                recipe.inputs.filter { it.itemId == itemId }.sumOf { game.perMinute(it, recipe) * potential }
            }
            else -> 0.0
        }

        fun downstreamWeight(edge: GraphEdge, seen: Set<String>): Double {
            val node = nodeById[edge.toNodeId] ?: return 0.0
            if (node.id in seen) return 0.0
            val direct = configuredDemand(node, edge.itemId)
            if (node.kind == NodeKind.SPLITTER || node.kind == NodeKind.MERGER) {
                val next = outgoing[node.id].orEmpty().filter { it.itemId == edge.itemId }
                return direct + next.sumOf { downstreamWeight(it, seen + node.id) }
            }
            return direct
        }

        repeat(60) {
            val previous = flow
            val next = HashMap<String, Double>(edges.size)
            edges.forEach { next[it.id] = 0.0 }
            for (node in nodes) {
                val produced = production(node, game, previous, incoming)
                for ((itemId, amount) in produced) {
                    val outs = outgoing[node.id].orEmpty().filter { it.itemId == itemId }
                    if (outs.isEmpty() || amount <= 0.0) continue
                    val weights = outs.map { downstreamWeight(it, setOf(node.id)).coerceAtLeast(0.0) }
                    val sum = weights.sum()
                    if (sum <= EPS) {
                        val share = amount / outs.size
                        outs.forEach { next[it.id] = share }
                    } else {
                        outs.forEachIndexed { index, edge ->
                            next[edge.id] = amount * weights[index] / sum
                        }
                    }
                }
            }
            flow = next
            val delta = edges.maxOfOrNull { edge -> abs((next[edge.id] ?: 0.0) - (previous[edge.id] ?: 0.0)) } ?: 0.0
            if (delta < 1e-4) return report(nodes, edges, game, next, incoming, outgoing)
        }
        return report(nodes, edges, game, flow, incoming, outgoing)
    }

    private fun potential(node: GraphNode): Double =
        (node.machineCount.coerceAtLeast(0.0) * node.clockPercent.coerceAtLeast(0.0) / 100.0)

    private fun production(
        node: GraphNode,
        game: GameData,
        flow: Map<String, Double>,
        incoming: Map<String, List<GraphEdge>>,
    ): Map<String, Double> {
        fun supplied(itemId: String): Double =
            incoming[node.id].orEmpty().filter { it.itemId == itemId }.sumOf { flow[it.id] ?: 0.0 }
        return when (node.kind) {
            NodeKind.SOURCE -> {
                val itemId = node.itemId ?: return emptyMap()
                mapOf(itemId to node.ratePerMinute.coerceAtLeast(0.0))
            }
            NodeKind.SINK -> emptyMap()
            NodeKind.SPLITTER, NodeKind.MERGER -> {
                val itemId = node.itemId ?: return emptyMap()
                mapOf(itemId to supplied(itemId))
            }
            NodeKind.RECIPE -> {
                val recipe = node.recipeId?.let { game.recipeOrNull(it) } ?: return emptyMap()
                val machines = potential(node)
                if (machines <= 1e-12) {
                    return recipe.outputs.associate { it.itemId to 0.0 }
                }
                val ratio = if (recipe.inputs.isEmpty()) {
                    1.0
                } else {
                    recipe.inputs.minOf { input ->
                        val required = game.perMinute(input, recipe) * machines
                        if (required <= 1e-9) 1.0 else (supplied(input.itemId) / required).coerceIn(0.0, 1.0)
                    }
                }
                buildMap {
                    for (output in recipe.outputs) {
                        val rate = game.perMinute(output, recipe) * machines * ratio
                        put(output.itemId, (get(output.itemId) ?: 0.0) + rate)
                    }
                }
            }
        }
    }

    private fun report(
        nodes: List<GraphNode>,
        edges: List<GraphEdge>,
        game: GameData,
        flow: Map<String, Double>,
        incoming: Map<String, List<GraphEdge>>,
        outgoing: Map<String, List<GraphEdge>>,
    ): BalanceReport {
        val balances = nodes.associate { node ->
            val produced = production(node, game, flow, incoming)
            val machines = potential(node)
            val inputs = when (node.kind) {
                NodeKind.RECIPE -> {
                    val recipe = node.recipeId?.let { game.recipeOrNull(it) }
                    recipe?.inputs?.map { input ->
                        val expected = game.perMinute(input, recipe) * machines
                        val actual = incoming[node.id].orEmpty()
                            .filter { it.itemId == input.itemId }
                            .sumOf { flow[it.id] ?: 0.0 }
                        PortBalance(input.itemId, expected, actual, status(actual, expected))
                    }.orEmpty()
                }
                NodeKind.SINK -> {
                    val itemId = node.itemId
                    if (itemId == null) emptyList()
                    else {
                        val expected = node.ratePerMinute.coerceAtLeast(0.0)
                        val actual = incoming[node.id].orEmpty()
                            .filter { it.itemId == itemId }
                            .sumOf { flow[it.id] ?: 0.0 }
                        listOf(PortBalance(itemId, expected, actual, status(actual, expected)))
                    }
                }
                NodeKind.MERGER, NodeKind.SPLITTER -> {
                    val itemId = node.itemId
                    if (itemId == null) emptyList()
                    else {
                        val actual = incoming[node.id].orEmpty()
                            .filter { it.itemId == itemId }
                            .sumOf { flow[it.id] ?: 0.0 }
                        listOf(PortBalance(itemId, actual, actual, if (actual > EPS) FlowStatus.BALANCED else FlowStatus.UNUSED))
                    }
                }
                NodeKind.SOURCE -> emptyList()
            }
            val outputs = when (node.kind) {
                NodeKind.RECIPE -> {
                    val recipe = node.recipeId?.let { game.recipeOrNull(it) }
                    recipe?.outputs?.map { output ->
                        val expected = game.perMinute(output, recipe) * machines
                        val actual = produced[output.itemId] ?: 0.0
                        val connected = outgoing[node.id].orEmpty().any { it.itemId == output.itemId }
                        val flowStatus = when {
                            !connected && actual > EPS -> FlowStatus.UNCONNECTED
                            else -> status(actual, expected)
                        }
                        PortBalance(output.itemId, expected, actual, flowStatus)
                    }.orEmpty()
                }
                NodeKind.SOURCE, NodeKind.SPLITTER, NodeKind.MERGER -> {
                    val itemId = node.itemId
                    if (itemId == null) emptyList()
                    else {
                        val actual = produced[itemId] ?: 0.0
                        val connected = outgoing[node.id].orEmpty().any { it.itemId == itemId }
                        val flowStatus = when {
                            actual <= EPS -> FlowStatus.UNUSED
                            !connected -> FlowStatus.UNCONNECTED
                            else -> FlowStatus.BALANCED
                        }
                        listOf(PortBalance(itemId, actual, actual, flowStatus))
                    }
                }
                NodeKind.SINK -> emptyList()
            }
            val ratio = if (node.kind == NodeKind.RECIPE && machines > 1e-9) {
                val recipe = game.recipeOrNull(node.recipeId ?: "")
                val primary = recipe?.outputs?.firstOrNull()?.itemId
                if (recipe == null || primary == null) 0.0
                else {
                    val full = game.outputRate(recipe, primary) * machines
                    if (full <= 1e-9) 0.0 else ((produced[primary] ?: 0.0) / full).coerceIn(0.0, 1.0)
                }
            } else {
                1.0
            }
            node.id to NodeBalance(node.id, ratio, inputs, outputs)
        }
        return BalanceReport(balances, flow)
    }

    private fun status(actual: Double, expected: Double): FlowStatus {
        if (expected <= EPS && actual <= EPS) return FlowStatus.UNUSED
        if (expected <= EPS && actual > EPS) return FlowStatus.OVER
        val ratio = actual / expected
        return when {
            ratio < 0.99 -> FlowStatus.UNDER
            ratio > 1.01 -> FlowStatus.OVER
            else -> FlowStatus.BALANCED
        }
    }
}
