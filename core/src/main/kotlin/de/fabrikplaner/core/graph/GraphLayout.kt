package de.fabrikplaner.core.graph

import de.fabrikplaner.core.data.GameData
import de.fabrikplaner.core.model.GraphEdge
import de.fabrikplaner.core.model.GraphNode
import de.fabrikplaner.core.model.NodeKind
import de.fabrikplaner.core.model.TargetRate
import de.fabrikplaner.core.solver.SolverResult
import kotlin.math.max

data class FactoryGraph(
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
)

object GraphLayout {
    fun fromSolution(result: SolverResult, targets: List<TargetRate>, game: GameData): FactoryGraph {
        val nodes = mutableListOf<GraphNode>()
        val recipeNodes = result.machines.mapNotNull { machine ->
            val recipeId = machine.recipeId ?: return@mapNotNull null
            recipeId to machine
        }
        val depthByRecipe = recipeNodes.associate { it.first to it.second.depth }
        val maxDepth = depthByRecipe.values.maxOrNull() ?: 0

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
        val recipes = recipeNodes.map { (recipeId, machine) ->
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
        val placedSources = place(sources, layer = 0)
        val placedRecipes = mutableListOf<GraphNode>()
        for (depth in 1..max(maxDepth, 1)) {
            val layer = recipes.filter { (depthByRecipe[it.recipeId] ?: 1) == depth }.sortedBy { it.id }
            placedRecipes += place(layer, depth)
        }
        val placedSinks = place(sinks.sortedBy { it.id }, layer = maxDepth + 1)
        nodes += placedSources
        nodes += placedRecipes
        nodes += placedSinks

        val edges = connect(nodes, game)
        return FactoryGraph(nodes, edges)
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
}
